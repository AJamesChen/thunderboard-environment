/* Thunderboard BRD4184A environment monitor. SPDX-License-Identifier: MIT */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "sl_bt_api.h"
#include "sl_sensor_rht.h"
#include "app_timer.h"
#include "app_assert.h"
#include "app_log.h"
#include "gatt_db.h"
#include "app.h"
#include "environment_values.h"
#include "sl_bt_app_ota_dfu.h"
#include "app_properties_config.h"

#define NO_CONNECTION 0xff
#define SAMPLE_PERIOD_MS 2000
#define ATT_UNLIKELY_ERROR 0x0e
#define ATT_ATTRIBUTE_NOT_FOUND 0x0a
#define ATT_INVALID_OFFSET 0x07

static uint8_t advertising_set = 0xff;
static uint8_t connection = NO_CONNECTION;
static bool temperature_notify;
static bool humidity_notify;
static bool sensor_ready;
static bool sample_valid;
static uint8_t temperature_value[2];
static uint8_t humidity_value[2];
static app_timer_t sample_timer;
static bool ota_restart_pending;
static bool ota_active;

static void notify_value(uint16_t characteristic, const uint8_t value[2])
{
  sl_status_t status = sl_bt_gatt_server_send_notification(
    connection, characteristic, 2, value);
  if (status != SL_STATUS_OK) {
    /* A missed update is retried with a fresh sample at the next interval. */
    app_log_warning("Notification 0x%04x failed: 0x%lx\r\n",
                    characteristic, (unsigned long)status);
  }
}

/* app_timer invokes this in main context, never in the timer interrupt. */
static void sample_environment(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  uint32_t humidity;
  int32_t temperature;
  sl_status_t status;

  /* Sensor conversion is blocking. Keep the Bluetooth/flash update path free
   * of I2C conversion delays while a firmware image is being transferred. */
  if (ota_active) {
    return;
  }

  if (!sensor_ready) {
    status = sl_sensor_rht_init();
    if (status != SL_STATUS_OK) {
      sample_valid = false;
      app_log_warning("Si7021 init failed: 0x%lx\r\n", (unsigned long)status);
      return;
    }
    sensor_ready = true;
  }

  status = sl_sensor_rht_get(&humidity, &temperature);
  sample_valid = (status == SL_STATUS_OK);
  if (!sample_valid) {
    app_log_warning("Si7021 read failed: 0x%lx\r\n", (unsigned long)status);
    sl_sensor_rht_deinit();
    sensor_ready = false;
    return;
  }

  environment_encode((uint16_t)environment_temperature(temperature), temperature_value);
  environment_encode(environment_humidity(humidity), humidity_value);
  app_log_info("Temperature=%ld mC Humidity=%lu milli-%%RH\r\n",
               (long)temperature, (unsigned long)humidity);

  if (connection != NO_CONNECTION) {
    if (temperature_notify) {
      notify_value(gattdb_temperature, temperature_value);
    }
    if (humidity_notify) {
      notify_value(gattdb_humidity, humidity_value);
    }
  }
}

static void start_advertising(void)
{
  sl_status_t status = sl_bt_legacy_advertiser_generate_data(
    advertising_set, sl_bt_advertiser_general_discoverable);
  app_assert_status(status);
  status = sl_bt_legacy_advertiser_start(
    advertising_set, sl_bt_legacy_advertiser_connectable);
  app_assert_status(status);
  app_log_info("Advertising as BG22 Environment\r\n");
}

void app_init(void)
{
  /* Sensor initialization is deferred until the Bluetooth boot event. */
}

void app_process_action(void)
{
  if (ota_restart_pending) {
    ota_restart_pending = false;
    (void)sl_bt_app_ota_dfu_restart_progress();
  }
}

/* The SDK stages and verifies the entire GBL before selecting it for boot.
 * Defer restart out of this callback: it is called while the OTA guard is held. */
void sl_bt_app_ota_dfu_on_status_event(sl_bt_app_ota_dfu_status_evt_t *evt)
{
  if (evt->event_id == SL_BT_APP_OTA_DFU_EVT_STATE_CHANGE_ID) {
    ota_active = evt->evt_info.sts.status >= SL_BT_APP_OTA_DFU_DOWNLOAD_BEGIN
                 && evt->evt_info.sts.status <= SL_BT_APP_OTA_DFU_WAIT_FOR_REBOOT;
    app_log_info("OTA state=%u error=%u bootloader=%ld\r\n",
                 (unsigned)evt->evt_info.sts.status,
                 (unsigned)evt->ota_error_code, (long)evt->btl_api_retval);
    if (evt->evt_info.sts.status == SL_BT_APP_OTA_DFU_WAIT_FOR_REBOOT) {
      sl_bt_app_ota_dfu_reboot();
    } else if (evt->evt_info.sts.status == SL_BT_APP_OTA_DFU_ERROR) {
      ota_restart_pending = true;
    }
  }
}

void sl_bt_on_event(sl_bt_msg_t *evt)
{
  sl_status_t status;

  switch (SL_BT_MSG_ID(evt->header)) {
    case sl_bt_evt_system_boot_id:
      app_log_info("Thunderboard environment monitor started\r\n");
      sample_environment(NULL, NULL);
      status = app_timer_start(&sample_timer, SAMPLE_PERIOD_MS,
                               sample_environment, NULL, true);
      app_assert_status(status);
      status = sl_bt_advertiser_create_set(&advertising_set);
      app_assert_status(status);
      status = sl_bt_advertiser_set_timing(advertising_set, 160, 160, 0, 0);
      app_assert_status(status);
      start_advertising();
      break;

    case sl_bt_evt_connection_opened_id:
      connection = evt->data.evt_connection_opened.connection;
      temperature_notify = false;
      humidity_notify = false;
      app_log_info("Phone connected\r\n");
      break;

    case sl_bt_evt_connection_closed_id:
      connection = NO_CONNECTION;
      temperature_notify = false;
      humidity_notify = false;
      app_log_info("Phone disconnected\r\n");
      start_advertising();
      break;

    case sl_bt_evt_gatt_server_characteristic_status_id: {
      const sl_bt_evt_gatt_server_characteristic_status_t *event =
        &evt->data.evt_gatt_server_characteristic_status;
      if (event->status_flags == sl_bt_gatt_server_client_config) {
        bool enabled = (event->client_config_flags & sl_bt_gatt_notification) != 0;
        if (event->characteristic == gattdb_temperature) {
          temperature_notify = enabled;
          if (enabled && sample_valid) {
            notify_value(gattdb_temperature, temperature_value);
          }
        } else if (event->characteristic == gattdb_humidity) {
          humidity_notify = enabled;
          if (enabled && sample_valid) {
            notify_value(gattdb_humidity, humidity_value);
          }
        }
      }
      break;
    }

    case sl_bt_evt_gatt_server_user_read_request_id: {
      const sl_bt_evt_gatt_server_user_read_request_t *event =
        &evt->data.evt_gatt_server_user_read_request;
      const uint8_t *value = NULL;
      uint16_t length = 2;
      char version[16];
      uint8_t error = 0;
      if (event->characteristic == gattdb_temperature) {
        value = temperature_value;
      } else if (event->characteristic == gattdb_humidity) {
        value = humidity_value;
      } else if (event->characteristic == gattdb_firmware_version) {
        length = (uint16_t)snprintf(version, sizeof(version), "%lu",
                                    (unsigned long)SL_APPLICATION_VERSION);
        value = (const uint8_t *)version;
      }
      if (value == NULL) {
        error = ATT_ATTRIBUTE_NOT_FOUND;
      } else if (!sample_valid && event->characteristic != gattdb_firmware_version) {
        /* Do not present an old sample as a successful current reading. */
        error = ATT_UNLIKELY_ERROR;
      } else if (event->offset > length) {
        error = ATT_INVALID_OFFSET;
      }
      uint16_t sent;
      status = sl_bt_gatt_server_send_user_read_response(
        event->connection, event->characteristic, error,
        error ? 0 : length - event->offset,
        error ? NULL : value + event->offset, &sent);
      app_assert_status(status);
      break;
    }

    default:
      break;
  }
}
