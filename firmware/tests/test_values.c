#include <assert.h>
#include <stdint.h>
#include "environment_values.h"
#include "reporting_period.h"

int main(void)
{
  assert(environment_temperature(23456) == 2346);
  assert(environment_temperature(-12345) == -1235);
  assert(environment_temperature(-4) == 0);
  assert(environment_temperature(-5) == -1);
  assert(environment_temperature(-40000) == -4000);
  assert(environment_temperature(125000) == 12500);
  assert(environment_humidity(0) == 0);
  assert(environment_humidity(45678) == 4568);
  assert(environment_humidity(100000) == 10000);
  assert(environment_humidity(UINT32_MAX) == 10000);
  uint8_t encoded[2];
  environment_encode((uint16_t)environment_temperature(-12345), encoded);
  assert(encoded[0] == 0x2d && encoded[1] == 0xfb);
  environment_encode(environment_humidity(45678), encoded);
  assert(encoded[0] == 0xd8 && encoded[1] == 0x11);
  uint8_t period_bytes[4];
  uint32_t period = REPORTING_PERIOD_DEFAULT_MS;
  reporting_period_encode(5000, period_bytes);
  assert(period_bytes[0] == 0x88 && period_bytes[1] == 0x13
         && period_bytes[2] == 0 && period_bytes[3] == 0);
  assert(reporting_period_decode(period_bytes, 4, &period) && period == 5000);
  for (unsigned i = 0; i < 2; ++i) {
    uint32_t boundary = i ? REPORTING_PERIOD_MAX_MS : REPORTING_PERIOD_MIN_MS;
    reporting_period_encode(boundary, period_bytes);
    assert(reporting_period_decode(period_bytes, 4, &period) && period == boundary);
  }
  uint32_t invalid[] = { 0, 999, 1001, 3600001, UINT32_MAX };
  for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
    reporting_period_encode(invalid[i], period_bytes);
    period = 2000;
    assert(!reporting_period_decode(period_bytes, 4, &period) && period == 2000);
  }
  assert(!reporting_period_decode(period_bytes, 3, &period));
  assert(!reporting_period_decode(period_bytes, 5, &period));
  assert(!reporting_period_decode(NULL, 4, &period));
  assert(!reporting_period_decode(period_bytes, 4, NULL));
  return 0;
}
