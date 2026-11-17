 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <stddef.h>
#include <stdint.h>
#ifdef AEMU_NO_BIONIC_HEADERS
 
extern "C" {
void *dlopen(const char *file, int flags);
void *dlsym(void *handle, const char *name);
char *dlerror(void);
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
__attribute__((noreturn)) void abort(void);
__attribute__((noreturn)) void _exit(int status);
__attribute__((noreturn)) void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **), const void *structors);
}
 
#if defined(__LP64__)
#define RTLD_NOW 0x2
#define RTLD_GLOBAL 0x100
#else
#define RTLD_NOW 0x0
#define RTLD_GLOBAL 0x2
#endif
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#else
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>
#endif

#define TAG "composer@2.1-service"
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

 
 
 
 
 
struct TypeInfo { const void *vptr; const char *name; const void *base; };

#define EXPORT_TI(var, sym, text, base_ti) \
    __attribute__((visibility("default"), used)) TypeInfo var __asm__(sym) = { 0, text, base_ti }

EXPORT_TI(ti_ibase, "_ZTIN7android4hidl4base4V1_05IBaseE",
          "N7android4hidl4base4V1_05IBaseE", 0);
EXPORT_TI(ti_icomposer, "_ZTIN7android8hardware8graphics8composer4V2_19IComposerE",
          "N7android8hardware8graphics8composer4V2_19IComposerE", &ti_ibase);
EXPORT_TI(ti_icomposerclient, "_ZTIN7android8hardware8graphics8composer4V2_115IComposerClientE",
          "N7android8hardware8graphics8composer4V2_115IComposerClientE", &ti_ibase);

static void fill_type_infos(void *cxx) {
    const char *cti = "_ZTVN10__cxxabiv117__class_type_infoE";
    const char *sti = "_ZTVN10__cxxabiv120__si_class_type_infoE";
    char *v_cti = (char *)dlsym(cxx, cti);
    char *v_sti = (char *)dlsym(cxx, sti);
    if (!v_cti || !v_sti) { ERR("type_info vtables not found in libc++.so: %s", dlerror()); return; }
    ti_ibase.vptr = v_cti + 2 * sizeof(void *);
    ti_icomposer.vptr = v_sti + 2 * sizeof(void *);
    ti_icomposerclient.vptr = v_sti + 2 * sizeof(void *);
}

 
 
 
 
 
 
 
 
static void *g_hidlbase;
static void *real_fn(void **cache, const char *sym) {
    if (!*cache && g_hidlbase) *cache = dlsym(g_hidlbase, sym);
    return *cache;
}
#define SYM_CHECK "_ZN7android8hardware7details5checkEbPKc"
#define SYM_LOGERR "_ZN7android8hardware7details8logErrorERKNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE"
#define SYM_ERRLOG "_ZN7android8hardware7details13errorWriteLogEiPKc"

__attribute__((visibility("default"), used)) void hidl_check(bool cond, const char *msg) __asm__(SYM_CHECK);
void hidl_check(bool cond, const char *msg) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_CHECK));
    if (f) { ((void (*)(bool, const char *))f)(cond, msg); return; }
    if (!cond) { ERR("hidl check failed: %s", msg ? msg : "(null)"); abort(); }
}

__attribute__((visibility("default"), used)) void hidl_log_error(const void *message) __asm__(SYM_LOGERR);
void hidl_log_error(const void *message) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_LOGERR));
    if (f) { ((void (*)(const void *))f)(message); return; }
    ERR("hidl error (std::string message not decoded)");      
}

__attribute__((visibility("default"), used)) int hidl_error_write_log(int tag, const char *info) __asm__(SYM_ERRLOG);
int hidl_error_write_log(int tag, const char *info) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_ERRLOG));
    if (f) return ((int (*)(int, const char *))f)(tag, info);
    ERR("errorWriteLog %d: %s", tag, info ? info : "");
    return 0;
}

 
 
 
 
 
 
 
 
 
#define SYM_EF_CREATE "_ZN7android8hardware9EventFlag15createEventFlagEPNSt3__16atomicIjEEPPS1_"
#define SYM_EF_DELETE "_ZN7android8hardware9EventFlag15deleteEventFlagEPPS1_"
#define ENOSYS_ 38
static void *g_fmq;
static void *fmq_fn(void **cache, const char *sym) {
    if (!*cache) {
        if (!g_fmq) g_fmq = dlopen("libfmq.so", RTLD_NOW | RTLD_GLOBAL);
        if (!g_fmq) { ERR("libfmq.so: %s", dlerror()); return 0; }
        *cache = dlsym(g_fmq, sym);
        if (!*cache) ERR("%s not found in libfmq.so: %s", sym, dlerror());
    }
    return *cache;
}

__attribute__((visibility("default"), used)) int ef_create(void *fw_addr, void **flag) __asm__(SYM_EF_CREATE);
int ef_create(void *fw_addr, void **flag) {
    static void *cache;
    void *f = fmq_fn(&cache, SYM_EF_CREATE);
    return f ? ((int (*)(void *, void **))f)(fw_addr, flag) : -ENOSYS_;
}

__attribute__((visibility("default"), used)) int ef_delete(void **flag) __asm__(SYM_EF_DELETE);
int ef_delete(void **flag) {
    static void *cache;
    void *f = fmq_fn(&cache, SYM_EF_DELETE);
    return f ? ((int (*)(void **))f)(flag) : -ENOSYS_;
}

static void *need(void *lib, const char *sym) {
    void *f = lib ? dlsym(lib, sym) : 0;
    if (!f) ERR("symbol %s not found: %s", sym, dlerror());
    return f;
}

 
 
 
 
static void *need_sized(void *lib, const char *sym_ulong, const char *sym_uint) {
    const char *first = sizeof(size_t) == 8 ? sym_ulong : sym_uint;
    const char *second = sizeof(size_t) == 8 ? sym_uint : sym_ulong;
    void *f = lib ? dlsym(lib, first) : 0;
    if (!f && lib) f = dlsym(lib, second);
    if (!f) ERR("symbols %s / %s not found: %s", first, second, dlerror());
    return f;
}

int main() {
    void *iface = dlopen("android.hardware.graphics.composer@2.1.so", RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { ERR("android.hardware.graphics.composer@2.1.so: %s", dlerror()); _exit(1); }    
    void *cxx = dlopen("libc++.so", RTLD_NOW | RTLD_GLOBAL);
     
 
    void *ht = dlopen("libhidlbase.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cxx || !ht) { ERR("libc++.so / libhidlbase.so: %s", dlerror()); _exit(1); }

     
    get_service_fn get_service = (get_service_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");
    register_fn register_as_service = (register_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEE");
    assign_fn assign = (assign_fn)need(cxx, "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc");
    configure_fn configure = (configure_fn)need_sized(ht, "_ZN7android8hardware22configureRpcThreadpoolEmb",
                                                      "_ZN7android8hardware22configureRpcThreadpoolEjb");
    join_fn join = (join_fn)need(ht, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!get_service || !register_as_service || !assign || !configure || !join) _exit(1);

    g_hidlbase = ht;
    g_fmq = dlopen("libfmq.so", RTLD_NOW | RTLD_GLOBAL);       
    if (!g_fmq) ERR("libfmq.so: %s", dlerror());
    fill_type_infos(cxx);        

    CxxString name = {{0, 0, 0}};
    assign(&name, "default");

    configure(1, true);
    Sp svc = get_service(&name, true);       
    if (!svc.p) {
        ERR("Could not get passthrough implementation for android.hardware.graphics.composer@2.1::IComposer/default.");
         
 
        _exit(1);
    }
    int st = register_as_service(svc.p, &name);
    if (st != 0) { ERR("Could not register android.hardware.graphics.composer@2.1::IComposer/default (status %d)", st); _exit(1); }
    INFO("Registration complete for android.hardware.graphics.composer@2.1::IComposer/default.");
    join();
    _exit(1);        
}

#ifdef AEMU_NO_BIONIC_HEADERS
 
typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
extern "C" {
__attribute__((section(".preinit_array"), used)) init_func_t *__PREINIT_ARRAY__ = (init_func_t *)-1;
__attribute__((section(".init_array.0"), used)) init_func_t *__INIT_ARRAY__ = (init_func_t *)-1;
__attribute__((section(".fini_array.0"), used)) fini_func_t *__FINI_ARRAY__ = (fini_func_t *)-1;
__attribute__((used)) void *__dso_handle = 0;

__attribute__((used)) static void _start_main(void *raw_args) {
    struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } array;
    array.preinit_array = &__PREINIT_ARRAY__;
    array.init_array = &__INIT_ARRAY__;
    array.fini_array = &__FINI_ARRAY__;
    __libc_init(raw_args, 0, (int (*)(int, char **, char **))&main, &array);
}
}
 
 
#if defined(__arm__)
__asm__(".text; .arm; .global _start; .type _start,%function; .p2align 2; _start:"
        "mov fp,#0; mov lr,#0; mov r0,sp; bl _start_main; .size _start, .-_start");
#elif defined(__aarch64__)
__asm__(".text; .global _start; .type _start,%function; _start:"
        "bti j; mov x29,#0; mov x30,#0; mov x0,sp; b _start_main; .size _start, .-_start");
#elif defined(__x86_64__)
 
 
__asm__(".text; .global _start; .type _start,@function; _start:"
        "xorl %ebp,%ebp; movq %rsp,%rdi; andq $-16,%rsp; call _start_main; .size _start, .-_start");
#endif
#endif
