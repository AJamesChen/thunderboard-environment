#ifndef REPORTING_PERIOD_H
#define REPORTING_PERIOD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define REPORTING_PERIOD_DEFAULT_MS 2000u
#define REPORTING_PERIOD_MIN_MS 1000u
#define REPORTING_PERIOD_MAX_MS 3600000u

static inline bool reporting_period_decode(const uint8_t *bytes, size_t length,
                                           uint32_t *period_ms)
{
  if (bytes == NULL || period_ms == NULL || length != 4) {
    return false;
  }
  uint32_t value = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8)
                   | ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
  if (value < REPORTING_PERIOD_MIN_MS || value > REPORTING_PERIOD_MAX_MS
      || value % 1000u != 0) {
    return false;
  }
  *period_ms = value;
  return true;
}

static inline void reporting_period_encode(uint32_t period_ms, uint8_t bytes[4])
{
  for (unsigned i = 0; i < 4; ++i) {
    bytes[i] = (uint8_t)(period_ms >> (8 * i));
  }
}

#endif
