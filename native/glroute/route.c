 
 
 
 
 
 
 
 
 
 
 
#include "names.h"

extern void *dlopen(const char *name, int flags);
extern void *dlsym(void *handle, const char *name);
extern int prctl(int option, ...);
extern int pthread_key_create(int *key, void (*destructor)(void *));
extern void *pthread_getspecific(int key);
extern int pthread_setspecific(int key, const void *value);

#define N (sizeof(kNames) / sizeof(kNames[0]))
#define SELF "/system/lib/egl/libGLES.so"         
enum { GPU, SOFT };
static const char *const kLibs[2][3] = {
    { "/system/lib/egl/libGLES_split.so", 0, 0 },
    { "/system/lib/egl/libEGL_swiftshader.so", "/system/lib/egl/libGLESv2_swiftshader.so",
      "/system/lib/egl/libGLESv1_CM_swiftshader.so" },
};
static void *g_lib[2][3];
static void *g_fn[2][N];
static void *g_dpy[2];
static volatile int g_up[2];
static int g_key;
static volatile int g_keyed;        

 
 
static const char *const kSoft[] = { "RenderThread", "Chrome_", "Compositor", "CrGpu", "GpuThread" };

static int zero_fn(void) { return 0; }

static int backend(void) {
    if (g_keyed != 2) {
        if (!__sync_bool_compare_and_swap(&g_keyed, 0, 1)) return GPU;       
        if (pthread_key_create(&g_key, 0)) { g_keyed = 0; return GPU; }
        g_keyed = 2;
    }
    long v = (long)pthread_getspecific(g_key);
    if (!v) {
        char n[17] = {0};
        prctl(16  , n, 0, 0, 0);
        v = 1 + GPU;
        for (unsigned k = 0; k < sizeof kSoft / sizeof kSoft[0]; k++) {
            unsigned i = 0;
            while (kSoft[k][i] && n[i] == kSoft[k][i]) i++;
            if (!kSoft[k][i]) v = 1 + SOFT;
        }
        pthread_setspecific(g_key, (void *)v);
    }
    return (int)v - 1;
}

static void *lookup(int be, unsigned idx) {
    void *f = g_fn[be][idx];
    if (f) return f;
    for (int i = 0; i < 3 && !f && kLibs[be][i]; i++) {
        if (!g_lib[be][i]) g_lib[be][i] = dlopen(kLibs[be][i], 0);
        if (g_lib[be][i]) f = dlsym(g_lib[be][i], kNames[idx]);
    }
    if (!f) f = (void *)zero_fn;        
    g_fn[be][idx] = f;
    return f;
}

__attribute__((visibility("hidden"))) void *aemu_route_resolve(unsigned idx) { return lookup(backend(), idx); }

 
__attribute__((visibility("hidden"))) void *aemu_route_dpy(void) {
    int be = backend();
    if (!g_dpy[be]) g_dpy[be] = ((void *(*)(void *))lookup(be, IDX_eglGetDisplay))(0);
    if (!g_up[be]) {
        g_up[be] = 1;
        ((unsigned (*)(void *, int *, int *))lookup(be, IDX_eglInitialize))(g_dpy[be], 0, 0);
    }
    return g_dpy[be];
}

#define EXPORT __attribute__((visibility("default")))
#define THE_DISPLAY ((void *)0x52)

EXPORT void *eglGetDisplay(void *native) { (void)native; return THE_DISPLAY; }

EXPORT unsigned eglInitialize(void *dpy, int *major, int *minor) {
    (void)dpy;
    if (!aemu_route_dpy()) return 0;
    if (major) *major = 1;
    if (minor) *minor = 4;
    return 1;
}

 
EXPORT unsigned eglTerminate(void *dpy) { (void)dpy; return 1; }

EXPORT void *eglGetCurrentDisplay(void) {
    return ((void *(*)(void))lookup(backend(), IDX_eglGetCurrentDisplay))() ? THE_DISPLAY : 0;
}

 
EXPORT void *eglGetProcAddress(const char *name) {
    static void *self;
    if (!self) self = dlopen(SELF, 0);
    return self ? dlsym(self, name) : 0;
}
