#ifndef ENVIRONMENT_APP_H
#define ENVIRONMENT_APP_H

#include <stdbool.h>

void app_init(void);
void app_process_action(void);
/* Implemented by the SDK's app_os_helper bare-metal compatibility layer. */
void app_init_bt(void);
void app_proceed(void);
bool app_is_process_required(void);
bool app_mutex_acquire(void);
void app_mutex_release(void);

#endif
