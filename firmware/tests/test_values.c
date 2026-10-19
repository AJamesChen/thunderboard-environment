#include <assert.h>
#include <stdint.h>
#include "environment_values.h"

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
  return 0;
}
