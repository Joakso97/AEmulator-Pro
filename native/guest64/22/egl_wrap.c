 
 
 
 
 
 
 
 
 
#define EXPORT __attribute__((visibility("default")))
#define REAL "/system/lib64/egl/libreal_swiftshader.so"
#define GLES_DIR "/system/lib64/egl/"
#define EGL_NONE 0x3038
#define EGL_CONTEXT_PRIORITY_LEVEL_IMG 0x3100
#define EGL_MALI_WINDOW_ATTRIB 0x304F

extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);

#define FORWARDS(F) \
    F(eglBindAPI) F(eglBindTexImage) F(eglChooseConfig) F(eglClientWaitSync) F(eglClientWaitSyncKHR) F(eglCopyBuffers) \
    F(eglCreateImage) F(eglCreateImageKHR) F(eglCreatePbufferFromClientBuffer) F(eglCreatePbufferSurface) \
    F(eglCreatePixmapSurface) F(eglCreatePlatformPixmapSurface) F(eglCreatePlatformPixmapSurfaceEXT) \
    F(eglCreatePlatformWindowSurface) F(eglCreatePlatformWindowSurfaceEXT) F(eglCreateSync) F(eglCreateSyncKHR) \
    F(eglDestroyContext) F(eglDestroyImage) F(eglDestroyImageKHR) F(eglDestroySurface) \
    F(eglDestroySync) F(eglDestroySyncKHR) F(eglGetConfigAttrib) F(eglGetConfigs) F(eglGetCurrentContext) \
    F(eglGetCurrentDisplay) F(eglGetCurrentSurface) F(eglGetDisplay) F(eglGetError) F(eglGetPlatformDisplay) \
    F(eglGetPlatformDisplayEXT) F(eglGetProcAddress) F(eglGetSyncAttrib) F(eglGetSyncAttribKHR) F(eglInitialize) \
    F(eglMakeCurrent) F(eglQueryAPI) F(eglQueryContext) F(eglQueryString) F(eglQuerySurface) F(eglReleaseTexImage) \
    F(eglReleaseThread) F(eglSurfaceAttrib) F(eglSwapBuffers) F(eglSwapInterval) F(eglTerminate) F(eglWaitClient) \
    F(eglWaitGL) F(eglWaitNative) F(eglWaitSync) F(libEGL_swiftshader)

 
#define DECL(n) void *aemu_p_##n; \
    __asm__(".text\n.globl " #n "\n.type " #n ",%function\n" #n ":\n" \
            "  adrp x16, aemu_p_" #n "\n  ldr x16, [x16, :lo12:aemu_p_" #n "]\n  br x16\n");
FORWARDS(DECL)

static void *(*real_create_context)(void *, void *, void *, const int *);
static void *(*real_create_window_surface)(void *, void *, void *, const int *);

 
static const int *without(const int *attrs, int drop, int *out) {
    int n = 0;
    if (!attrs) return attrs;
    for (; attrs[0] != EGL_NONE && n < 32; attrs += 2) {
        if (attrs[0] == drop) continue;
        out[n++] = attrs[0];
        out[n++] = attrs[1];
    }
    out[n] = EGL_NONE;
    return out;
}

EXPORT void *eglCreateContext(void *dpy, void *config, void *share, const int *attrs) {
    int mine[34];
    return real_create_context(dpy, config, share, without(attrs, EGL_CONTEXT_PRIORITY_LEVEL_IMG, mine));
}

EXPORT void *eglCreateWindowSurface(void *dpy, void *config, void *win, const int *attrs) {
    int mine[34];
    return real_create_window_surface(dpy, config, win, without(attrs, EGL_MALI_WINDOW_ATTRIB, mine));
}

__attribute__((constructor)) static void init(void) {
    void *h = dlopen(REAL, 2  );
    if (!h) return;
     
 
 
    dlopen(GLES_DIR "libGLESv2_swiftshader.so", 2);
    dlopen(GLES_DIR "libGLESv1_CM_swiftshader.so", 2);
#define LOAD(n) aemu_p_##n = dlsym(h, #n);
    FORWARDS(LOAD)
    real_create_context = (void *(*)(void *, void *, void *, const int *))dlsym(h, "eglCreateContext");
    real_create_window_surface = (void *(*)(void *, void *, void *, const int *))dlsym(h, "eglCreateWindowSurface");
}
