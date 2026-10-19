#ifndef ENVIRONMENT_VALUES_H
#define ENVIRONMENT_VALUES_H

#include <stdint.h>

/* Si7021 driver units are 0.001 C / 0.001 %RH; GATT uses 0.01 units.
 * Round to nearest, with ties away from zero. Clamp humidity to 0..100%.
 * The Si7021 temperature range fits in a signed 16-bit GATT value. */
static inline int16_t environment_temperature(int32_t milli_celsius)
{
  return (int16_t)((milli_celsius >= 0)
                   ? (milli_celsius + 5) / 10
                   : (milli_celsius - 5) / 10);
}

static inline uint16_t environment_humidity(uint32_t milli_percent)
{
  return (uint16_t)((milli_percent > 100000u)
                    ? 10000u : (milli_percent + 5u) / 10u);
}

static inline void environment_encode(uint16_t value, uint8_t bytes[2])
{
  bytes[0] = (uint8_t)value;
  bytes[1] = (uint8_t)(value >> 8);
}

#endif
