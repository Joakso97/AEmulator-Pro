 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#if defined(HOST_SENSORS) == defined(HOST_AUDIO)
#error "define exactly one of HOST_SENSORS or HOST_AUDIO"
#endif

 
 
typedef void *(*fetch_fn)(const char *name);
typedef int (*register_fn)(void *self, const void *name);
typedef void (*configure_fn)(unsigned int maxThreads, int callerWillJoin);
typedef void (*join_fn)(void);

typedef struct {
    const char *iface_lib;      
    const char *impl_lib;       
    const char *fetch_sym;
    const char *register_sym;   
    const char *instance;       
    int required;               
} hal_t;

#if defined(HOST_SENSORS)

#define TAG "aemu-sensors-host"
#define THREADS 1
static const hal_t hals[] = {
    { "android.hardware.sensors@1.0.so", "android.hardware.sensors@1.0-impl-mediatek.so",
      "HIDL_FETCH_ISensors",
      "_ZN7android8hardware7sensors4V1_08ISensors17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE",
      "default", 1 },
};

#else  

#define TAG "aemu-audio-host"
#define THREADS 16
static const hal_t hals[] = {
    { "android.hardware.audio@2.0.so", "android.hardware.audio@2.0-impl-mediatek.so",
      "HIDL_FETCH_IDevicesFactory",
      "_ZN7android8hardware5audio4V2_015IDevicesFactory17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE",
      "default", 1 },
    { "android.hardware.audio.effect@2.0.so", "android.hardware.audio.effect@2.0-impl.so",
      "HIDL_FETCH_IEffectsFactory",
      "_ZN7android8hardware5audio6effect4V2_015IEffectsFactory17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEE",
      "default", 0 },
    { "android.hardware.soundtrigger@2.0.so", "android.hardware.soundtrigger@2.0-impl.so",
      "HIDL_FETCH_ISoundTriggerHw",
      "_ZN7android8hardware12soundtrigger4V2_015ISoundTriggerHw17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE",
      "default", 0 },
};

#endif

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define NHALS (sizeof hals / sizeof hals[0])

 
static void make_name(unsigned char out[12], const char *s) {
    size_t n = strlen(s);
    memset(out, 0, 12);
    if (n > 10) n = 10;
    out[0] = (unsigned char)(n << 1);
    memcpy(out + 1, s, n);
}

 
 
static void *lookup(const char *sym) {
    static const char *libs[] = { "libhidltransport.so", "libhidlbase.so", "libhwbinder.so" };
    void *p = dlsym(RTLD_DEFAULT, sym);
    for (unsigned i = 0; !p && i < sizeof libs / sizeof libs[0]; i++) {
        void *h = dlopen(libs[i], RTLD_NOW);
        if (h) p = dlsym(h, sym);
    }
    return p;
}

typedef struct { fetch_fn fetch; register_fn reg; } loaded_t;

 
static int load_one(const hal_t *h, loaded_t *l) {
    void *iface = dlopen(h->iface_lib, RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { LOG("dlopen %s: %s", h->iface_lib, dlerror()); return 0; }
    void *impl = dlopen(h->impl_lib, RTLD_NOW);
    if (!impl) { LOG("dlopen %s: %s", h->impl_lib, dlerror()); return 0; }
    l->fetch = (fetch_fn)dlsym(impl, h->fetch_sym);
    l->reg = (register_fn)dlsym(iface, h->register_sym);
    if (!l->fetch) { LOG("%s: %s not found: %s", h->impl_lib, h->fetch_sym, dlerror()); return 0; }
    if (!l->reg)   { LOG("%s: registerAsService not found: %s", h->iface_lib, dlerror()); return 0; }
    return 1;
}


#if defined(HOST_SENSORS)
#include <stdio.h>
#include <unistd.h>
 
 
static void diag_sensors(void) {
    static const char *confs[] = { "/vendor/etc/sensors/hals.conf", "/system/etc/sensors/hals.conf" };
    for (unsigned i = 0; i < 2; i++) {
        FILE *f = fopen(confs[i], "r");
        if (!f) { LOG("diag: %s: not present", confs[i]); continue; }
        char line[256];
        int n = 0;
        while (fgets(line, sizeof line, f) && n++ < 16) {
            size_t l = strlen(line);
            while (l && (line[l - 1] == '\n' || line[l - 1] == '\r')) line[--l] = 0;
            if (!l || line[0] == '#') continue;
            LOG("diag: %s: \"%s\"%s", confs[i], line,
                (line[0] == '/' && access(line, R_OK) != 0) ? " (file missing)" : "");
        }
        fclose(f);
    }
}
#define DIAG_BEFORE_FETCH() diag_sensors()
#else
#define DIAG_BEFORE_FETCH() ((void)0)
#endif

 
static int register_one(const hal_t *h, const loaded_t *l) {
    DIAG_BEFORE_FETCH();
    void *dev = l->fetch(h->instance);
    if (!dev) { LOG("%s(\"%s\") returned null", h->fetch_sym, h->instance); return 0; }

    unsigned char name[12];
    make_name(name, h->instance);
    int rc = l->reg(dev, name);
    if (rc != 0) { LOG("registerAsService(%s) failed: %d", h->instance, rc); return 0; }
    LOG("registered %s/%s", h->iface_lib, h->instance);
    return 1;
}

int main(void) {
     
    loaded_t loaded[NHALS];
    int ok[NHALS];
    for (unsigned i = 0; i < NHALS; i++) {
        ok[i] = load_one(&hals[i], &loaded[i]);
        if (!ok[i] && hals[i].required) { LOG("required HAL %u failed to load, giving up", i); return 1; }
    }

     
    configure_fn configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEjb");
    if (!configure) configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEmb");
    join_fn join = (join_fn)lookup("_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!configure || !join) { LOG("configureRpcThreadpool/joinRpcThreadpool not found"); return 1; }

    configure(THREADS, 1);

    int registered = 0;
    for (unsigned i = 0; i < NHALS; i++) {
        if (!ok[i]) continue;
        if (register_one(&hals[i], &loaded[i])) registered++;
        else if (hals[i].required) { LOG("required HAL %u failed, giving up", i); return 1; }
    }
    if (!registered) return 1;

    join();
    return 0;
}
