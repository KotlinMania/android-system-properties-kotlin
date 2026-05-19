#pragma once

#include <stdint.h>
#include <sys/system_properties.h>

static inline const prop_info* kotlinmaniaSystemPropertyFind(const char* name) {
    return __system_property_find(name);
}

static inline void kotlinmaniaSystemPropertyReadCallback(
    const prop_info* pi,
    void (*callback)(void* cookie, const char* name, const char* value, uint32_t serial),
    void* cookie
) {
#if __ANDROID_API__ >= 26
    __system_property_read_callback(pi, callback, cookie);
#else
    (void)pi;
    (void)callback;
    (void)cookie;
#endif
}

static inline int kotlinmaniaSystemPropertyGet(const char* name, char* value) {
    return __system_property_get(name, value);
}
