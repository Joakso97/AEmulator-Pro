 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "aemu-keymaster-host"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define IMPL_LIB "android.hardware.keymaster@3.0-impl.so"
#define IFACE_LIB "android.hardware.keymaster@3.0.so"
#define FETCH_SYM "HIDL_FETCH_IKeymasterDevice"
 
#define REGISTER_SYM "_ZN7android8hardware9keymaster4V3_016IKeymasterDevice17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE"

 
typedef void *(*fetch_fn)(const char *name);
typedef int (*register_fn)(void *self, const void *name);
typedef void (*configure_fn)(unsigned int maxThreads, int callerWillJoin);
typedef void (*join_fn)(void);

static void *need(void *lib, const char *what, const char *sym) {
    void *p = dlsym(lib, sym);
    if (!p) { LOG("%s: %s not found: %s", what, sym, dlerror()); exit(1); }
    return p;
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

int main(void) {
    void *iface = dlopen(IFACE_LIB, RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { LOG("dlopen %s: %s", IFACE_LIB, dlerror()); return 1; }
    void *impl = dlopen(IMPL_LIB, RTLD_NOW);
    if (!impl) { LOG("dlopen %s: %s", IMPL_LIB, dlerror()); return 1; }

    fetch_fn fetch = (fetch_fn)need(impl, "impl", FETCH_SYM);
    register_fn reg = (register_fn)need(iface, "iface", REGISTER_SYM);
     
    configure_fn configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEjb");
    if (!configure) configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEmb");
    join_fn join = (join_fn)lookup("_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!configure || !join) { LOG("configureRpcThreadpool/joinRpcThreadpool not found"); return 1; }

    configure(1, 1);

    void *dev = fetch("default");
    if (!dev) { LOG("%s(\"default\") returned null", FETCH_SYM); return 1; }

     
    unsigned char name[12];
    memset(name, 0, sizeof name);
    name[0] = 7 << 1;
    memcpy(name + 1, "default", 7);

    int rc = reg(dev, name);
    if (rc != 0) { LOG("registerAsService failed: %d", rc); return 1; }
    LOG("registered android.hardware.keymaster@3.0::IKeymasterDevice/default");

    join();
    return 0;
}
