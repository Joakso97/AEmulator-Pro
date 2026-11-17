 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <android/log.h>

#define TAG "allocator@2.0-service"
#define ERR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

 
struct Sp {
    void *p;
    Sp() : p(0) {}
    ~Sp() {}
};
struct CxxString { uintptr_t w[3]; };        

typedef Sp (*get_service_fn)(const CxxString *name, bool get_stub);
typedef int (*register_fn)(void *self, const CxxString *name);
typedef void *(*assign_fn)(CxxString *self, const char *s);
typedef void (*configure_fn)(size_t max_threads, bool caller_will_join);
typedef void (*join_fn)(void);

static void *need(void *lib, const char *sym) {
    void *f = lib ? dlsym(lib, sym) : 0;
    if (!f) ERR("symbol %s not found: %s", sym, dlerror());
    return f;
}

int main() {
    void *iface = dlopen("android.hardware.graphics.allocator@2.0.so", RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { ERR("android.hardware.graphics.allocator@2.0.so: %s", dlerror()); return 1; }
    void *cxx = dlopen("libc++.so", RTLD_NOW | RTLD_GLOBAL);
    void *ht = dlopen("libhidltransport.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cxx || !ht) { ERR("libc++.so / libhidltransport.so: %s", dlerror()); return 1; }

    get_service_fn get_service = (get_service_fn)need(iface,
        "_ZN7android8hardware8graphics9allocator4V2_010IAllocator10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");
    register_fn register_as_service = (register_fn)need(iface,
        "_ZN7android8hardware8graphics9allocator4V2_010IAllocator17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEE");
    assign_fn assign = (assign_fn)need(cxx, "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc");
    configure_fn configure = (configure_fn)need(ht, "_ZN7android8hardware20configureRpcThreadpoolEjb");
    join_fn join = (join_fn)need(ht, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!get_service || !register_as_service || !assign || !configure || !join) return 1;

    CxxString name = {{0, 0, 0}};
    assign(&name, "default");

    configure(1, true);
    Sp svc = get_service(&name, true);       
    if (!svc.p) {
        ERR("Could not get passthrough implementation for android.hardware.graphics.allocator@2.0::IAllocator/default.");
        return 1;
    }
    int st = register_as_service(svc.p, &name);
    if (st != 0) { ERR("Could not register android.hardware.graphics.allocator@2.0::IAllocator/default (status %d)", st); return 1; }
    INFO("Registration complete for android.hardware.graphics.allocator@2.0::IAllocator/default.");
    join();
    return 1;        
}
