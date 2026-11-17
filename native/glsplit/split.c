extern int __android_log_print(int, const char *, const char *, ...);
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include "names.h"

typedef unsigned int GLenum;
typedef int GLint;
typedef int GLsizei;
typedef void GLvoid;

extern void *dlopen(const char *name, int flags);
extern void *dlsym(void *handle, const char *name);
extern void *malloc(unsigned int n);
extern void free(void *p);

#define N (sizeof(kNames) / sizeof(kNames[0]))
static void *g_lib;
static void *g_fn[N];

static void trap(void) { __builtin_trap(); }

static int es1_current(void);
static int zero_fn(void) { return 0; }
static volatile int g_any_es1;    

__attribute__((visibility("hidden"))) void *aemu_split_resolve(unsigned idx) {
     
    if (g_any_es1 && kEs2Only[idx] && es1_current()) return (void *)zero_fn;
    void *f = g_fn[idx];
    if (f) return f;
    if (!g_lib) g_lib = dlopen("/system/lib/egl/libGLES_bridge.so", 0);
    if (g_lib) f = dlsym(g_lib, kNames[idx]);
    if (!f) f = (void *)trap;
    g_fn[idx] = f;
    return f;
}

#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_UNPACK_ROW_LENGTH 0x0CF2
#define GL_UNPACK_SKIP_ROWS 0x0CF3
#define GL_UNPACK_SKIP_PIXELS 0x0CF4
#define GL_PIXEL_UNPACK_BUFFER 0x88EC

 
static int t_align = 4, t_rowlen, t_skiprows, t_skippix, t_pbo;

typedef void (*PixelStoreFn)(GLenum, GLint);
typedef void (*BindBufferFn)(GLenum, unsigned);
typedef void (*TexImageFn)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const GLvoid *);
typedef void (*TexSubImageFn)(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const GLvoid *);

__attribute__((visibility("default"))) void glPixelStorei(GLenum pname, GLint param) {
    switch (pname) {
        case GL_UNPACK_ALIGNMENT: t_align = param; break;
        case GL_UNPACK_ROW_LENGTH: t_rowlen = param; break;
        case GL_UNPACK_SKIP_ROWS: t_skiprows = param; break;
        case GL_UNPACK_SKIP_PIXELS: t_skippix = param; break;
    }
    ((PixelStoreFn)aemu_split_resolve(IDX_glPixelStorei))(pname, param);
}

static void va_bind_note(GLenum target, unsigned buffer);
static void va_context(void *ctx);
static void img_forget_bound(void);

__attribute__((visibility("default"))) void glBindBuffer(GLenum target, unsigned buffer) {
    if (target == GL_PIXEL_UNPACK_BUFFER) t_pbo = buffer != 0;
    va_bind_note(target, buffer);
    ((BindBufferFn)aemu_split_resolve(IDX_glBindBuffer))(target, buffer);
}

 
static int bytes_per_pixel(GLenum format, GLenum type) {
    int comps;
    switch (format) {
        case 0x1906:   case 0x1909:   case 0x1903:  
        case 0x1902:   case 0x8D94:   comps = 1; break;
        case 0x190A:   case 0x8227:   case 0x8228:   comps = 2; break;
        case 0x1907:   case 0x8D98:   comps = 3; break;
        case 0x1908:   case 0x80E1:   case 0x8D99:   comps = 4; break;
        default: return 0;
    }
    switch (type) {
        case 0x1401:   case 0x1400:   return comps;
        case 0x1403:   case 0x1402:  
        case 0x140B:   case 0x8D61:   return comps * 2;
        case 0x1405:   case 0x1404:   case 0x1406:   return comps * 4;
        case 0x8363:   case 0x8033:   case 0x8034:   return 2;
        case 0x8368:   case 0x84FA:   return 4;
        default: return 0;
    }
}

 
static void copy_bytes(unsigned char *d, const unsigned char *s, unsigned long n) {
    if ((((unsigned long)d | (unsigned long)s | n) & 3) == 0) {
        unsigned int *dw = (unsigned int *)d; const unsigned int *sw = (const unsigned int *)s;
        for (unsigned long i = 0; i < n / 4; i++) dw[i] = sw[i];
        return;
    }
    for (unsigned long i = 0; i < n; i++) d[i] = s[i];
}

 
 
 
 
static const void *tighten(GLsizei w, GLsizei h, GLenum format, GLenum type, const void *pixels, int *need) {
    *need = 0;
    if (!pixels || t_pbo || w <= 0 || h <= 0) return pixels;
    int bpp = bytes_per_pixel(format, type);
    if (!bpp) return pixels;
    int align = (t_align == 1 || t_align == 2 || t_align == 4 || t_align == 8) ? t_align : 4;
    unsigned long row = (unsigned long)w * bpp;
    unsigned long srcRowPixels = t_rowlen > 0 ? (unsigned long)t_rowlen : (unsigned long)w;
    unsigned long stride = (srcRowPixels * bpp + align - 1) & ~(unsigned long)(align - 1);  
    if (stride == row && t_skiprows == 0 && t_skippix == 0) {
         
 
        if (align == 1 || (row & (unsigned long)(align - 1)) == 0) return pixels;
    }
    const unsigned char *src = (const unsigned char *)pixels + (unsigned long)t_skiprows * stride + (unsigned long)t_skippix * bpp;
    unsigned char *dst = (unsigned char *)malloc(row * h);
    if (!dst) return pixels;
    for (GLsizei y = 0; y < h; y++) copy_bytes(dst + (unsigned long)y * row, src + (unsigned long)y * stride, row);
    *need = 1;
    return dst;
}

static void unpack_tight(int on) {
    PixelStoreFn ps = (PixelStoreFn)aemu_split_resolve(IDX_glPixelStorei);
    if (on) {
        ps(GL_UNPACK_ALIGNMENT, 1);
        if (t_rowlen) ps(GL_UNPACK_ROW_LENGTH, 0);
        if (t_skiprows) ps(GL_UNPACK_SKIP_ROWS, 0);
        if (t_skippix) ps(GL_UNPACK_SKIP_PIXELS, 0);
    } else {
        ps(GL_UNPACK_ALIGNMENT, t_align);
        if (t_rowlen) ps(GL_UNPACK_ROW_LENGTH, t_rowlen);
        if (t_skiprows) ps(GL_UNPACK_SKIP_ROWS, t_skiprows);
        if (t_skippix) ps(GL_UNPACK_SKIP_PIXELS, t_skippix);
    }
}

__attribute__((visibility("default"))) void glTexImage2D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h,
                                                          GLint border, GLenum format, GLenum type, const GLvoid *pixels) {
    TexImageFn f = (TexImageFn)aemu_split_resolve(IDX_glTexImage2D);
    img_forget_bound();    
    int need;
    const void *p = tighten(w, h, format, type, pixels, &need);
    if (need) unpack_tight(1);
    f(target, level, ifmt, w, h, border, format, type, p);
    if (need) { unpack_tight(0); free((void *)p); }
}

__attribute__((visibility("default"))) void glTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w, GLsizei h,
                                                             GLenum format, GLenum type, const GLvoid *pixels) {
    TexSubImageFn f = (TexSubImageFn)aemu_split_resolve(IDX_glTexSubImage2D);
    int need;
    const void *p = tighten(w, h, format, type, pixels, &need);
    if (need) unpack_tight(1);
    f(target, level, x, y, w, h, format, type, p);
    if (need) { unpack_tight(0); free((void *)p); }
}

 
 
 
 
 
 
 
extern char *getenv(const char *name);
typedef void *(*CreateContextFn)(void *dpy, void *config, void *share, const GLint *attribs);
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_NONE 0x3038

static void ctx_remember(void *ctx, int ver);
extern long syscall(long n, ...);

static int is_surfaceflinger(void) {
    static int v = -1;
    if (v < 0) {
        char buf[128];
        long fd = syscall(5  , "/proc/self/cmdline", 0, 0);
        long n = fd >= 0 ? syscall(3  , fd, buf, sizeof(buf) - 1) : 0;
        if (fd >= 0) syscall(6  , fd);
        v = 0;
        if (n > 0) {
            buf[n] = 0;
            const char *want = "surfaceflinger";
            for (long i = 0; i + 14 <= n && !v; i++) {
                int k = 0;
                while (k < 14 && buf[i + k] == want[k]) k++;
                if (k == 14) v = 1;
            }
        }
    }
    return v;
}

__attribute__((visibility("default"))) void *eglCreateContext(void *dpy, void *config, void *share, const GLint *attribs) {
    CreateContextFn f = (CreateContextFn)aemu_split_resolve(IDX_eglCreateContext);
    static int force = -1;
    if (force < 0) { const char *v = getenv("AEMU_GL_ES2"); force = v && v[0] == '1'; }
    int ver = 1;
    if (attribs) for (int i = 0; attribs[i] != EGL_NONE && i < 64; i += 2)
        if (attribs[i] == EGL_CONTEXT_CLIENT_VERSION) ver = attribs[i + 1];
    void *ctx;
    if (force && ver < 2) {
        GLint list[64];
        int n = 0;
        if (attribs) for (int i = 0; attribs[i] != EGL_NONE && n < 60; i += 2) {
            if (attribs[i] == EGL_CONTEXT_CLIENT_VERSION) continue;
            list[n++] = attribs[i]; list[n++] = attribs[i + 1];
        }
        list[n++] = EGL_CONTEXT_CLIENT_VERSION; list[n++] = 2;
        list[n] = EGL_NONE;
        ver = 2;
        ctx = f(dpy, config, share, list);
    } else ctx = f(dpy, config, share, attribs);
     
    if (is_surfaceflinger()) ver = 1;
    ctx_remember(ctx, ver);
    return ctx;
}

 

extern long syscall(long n, ...);
#define MAXCTX 256
#define MAXTHR 256
static void *g_ctx[MAXCTX];
static unsigned char g_ctxver[MAXCTX];
static int g_tid[MAXTHR];
static unsigned char g_thrver[MAXTHR];

static void ctx_remember(void *ctx, int ver) {
    if (!ctx) return;
    if (ver < 2) g_any_es1 = 1;
    for (int i = 0; i < MAXCTX; i++) if (g_ctx[i] == ctx || g_ctx[i] == 0) { g_ctx[i] = ctx; g_ctxver[i] = (unsigned char)ver; return; }
}
static int ctx_version(void *ctx) {
    for (int i = 0; i < MAXCTX; i++) if (g_ctx[i] == ctx) return g_ctxver[i];
    return 2;
}
 
 
extern int pthread_key_create(unsigned *key, void (*destructor)(void *));
extern void *pthread_getspecific(unsigned key);
extern int pthread_setspecific(unsigned key, const void *value);
static unsigned g_tidkey;
static volatile int g_tidkey_state;    
static int my_tid(void) {
    if (g_tidkey_state != 2) {
        if (__sync_bool_compare_and_swap(&g_tidkey_state, 0, 1)) {
            if (pthread_key_create(&g_tidkey, 0) != 0) { g_tidkey_state = 0; return (int)syscall(224  ); }
            g_tidkey_state = 2;
        } else return (int)syscall(224);
    }
    int t = (int)(long)pthread_getspecific(g_tidkey);
    if (t == 0) { t = (int)syscall(224); pthread_setspecific(g_tidkey, (void *)(long)t); }
    return t;
}
static int es1_current(void) {
    int tid = my_tid();
    for (int i = 0; i < MAXTHR; i++) if (g_tid[i] == tid) return g_thrver[i] < 2;
    return 0;
}
static void thread_set(int ver) {
    int tid = my_tid();
    int free = -1;
    for (int i = 0; i < MAXTHR; i++) {
        if (g_tid[i] == tid) { g_thrver[i] = (unsigned char)ver; return; }
        if (g_tid[i] == 0 && free < 0) free = i;
    }
    if (free >= 0) { g_thrver[free] = (unsigned char)ver; g_tid[free] = tid; }
}

 
 
 
 
 
 
typedef void (*ViewportFn)(GLint, GLint, GLsizei, GLsizei);
typedef unsigned (*QuerySurfaceFn)(void *dpy, void *surface, GLint attr, GLint *value);
static int g_vp_set;
__attribute__((visibility("default"))) void glViewport(GLint x, GLint y, GLsizei w, GLsizei h) {
    g_vp_set = 1;
    ((ViewportFn)aemu_split_resolve(IDX_glViewport))(x, y, w, h);
}

typedef unsigned (*MakeCurrentFn)(void *dpy, void *draw, void *read, void *ctx);
__attribute__((visibility("default"))) unsigned eglMakeCurrent(void *dpy, void *draw, void *read, void *ctx) {
    unsigned r = ((MakeCurrentFn)aemu_split_resolve(IDX_eglMakeCurrent))(dpy, draw, read, ctx);
    if (r && draw && ctx && !g_vp_set && is_surfaceflinger()) {
        GLint w = 0, h = 0;
        QuerySurfaceFn q = (QuerySurfaceFn)aemu_split_resolve(IDX_eglQuerySurface);
        if (q(dpy, draw, 0x3057  , &w) && q(dpy, draw, 0x3056  , &h) && w > 0 && h > 0) {
            ((ViewportFn)aemu_split_resolve(IDX_glViewport))(0, 0, w, h);
            ((ViewportFn)aemu_split_resolve(IDX_glScissor))(0, 0, w, h);
            void (*mode)(GLenum) = (void (*)(GLenum))aemu_split_resolve(IDX_glMatrixMode);
            mode(0x1701  );
            ((void (*)(void))aemu_split_resolve(IDX_glLoadIdentity))();
            ((void (*)(float, float, float, float, float, float))aemu_split_resolve(IDX_glOrthof))(0, (float)w, 0, (float)h, 0, 1);
            mode(0x1700  );
        }
    }
    if (r) { thread_set(ctx ? ctx_version(ctx) : 2); va_context(ctx); }
    return r;
}

 
 
 
 
 
 
 
#define GL_ARRAY_BUFFER 0x8892
#define GL_ELEMENT_ARRAY_BUFFER 0x8893
#define GL_STREAM_DRAW 0x88E0
#define MAXATTR 16
struct va_attr { const void *ptr; GLint size; GLenum type; unsigned char norm, enabled, client; GLsizei stride; };
struct va_state {
    int tid; unsigned array_buf, elem_buf; struct va_attr a[MAXATTR];
    void *ctx;                           
    unsigned sbuf;                       
    unsigned char *shadow; unsigned long shadow_len, shadow_cap;    
    long host_off[MAXATTR];              
    unsigned split_buf;                  
};
static struct va_state g_va[64];

static struct va_state *va_cur(void) {
    int tid = my_tid();
    int free = -1;
    for (int i = 0; i < 64; i++) {
        if (g_va[i].tid == tid) return &g_va[i];
        if (g_va[i].tid == 0 && free < 0) free = i;
    }
    if (free < 0) free = tid & 63;    
    struct va_state *v = &g_va[free];
    for (unsigned k = 0; k < sizeof(*v); k++) ((unsigned char *)v)[k] = 0;
    v->tid = tid;
    return v;
}

static void va_bind_note(GLenum target, unsigned buffer) {
    if (target == GL_ARRAY_BUFFER) va_cur()->array_buf = buffer;
    else if (target == GL_ELEMENT_ARRAY_BUFFER) va_cur()->elem_buf = buffer;
}

typedef void (*AttribPtrFn)(unsigned, GLint, GLenum, unsigned char, GLsizei, const void *);
typedef void (*AttribArrFn)(unsigned);
typedef void (*DrawArraysFn)(GLenum, GLint, GLsizei);
typedef void (*DrawElementsFn)(GLenum, GLsizei, GLenum, const void *);
typedef void (*GenBuffersFn)(GLsizei, unsigned *);
typedef void (*BufferDataFn)(GLenum, long, const void *, GLenum);
typedef void (*BufferSubDataFn)(GLenum, long, long, const void *);

__attribute__((visibility("default"))) void glVertexAttribPointer(unsigned idx, GLint size, GLenum type, unsigned char norm,
                                                                   GLsizei stride, const void *ptr) {
    struct va_state *v = va_cur();
    if (idx < MAXATTR) {
        struct va_attr *a = &v->a[idx];
        a->ptr = ptr; a->size = size; a->type = type; a->norm = norm; a->stride = stride;
        a->client = v->array_buf == 0 && ptr != 0;
        v->host_off[idx] = 0;
    }
    ((AttribPtrFn)aemu_split_resolve(IDX_glVertexAttribPointer))(idx, size, type, norm, stride, ptr);
}
__attribute__((visibility("default"))) void glEnableVertexAttribArray(unsigned idx) {
    if (idx < MAXATTR) va_cur()->a[idx].enabled = 1;
    ((AttribArrFn)aemu_split_resolve(IDX_glEnableVertexAttribArray))(idx);
}
__attribute__((visibility("default"))) void glDisableVertexAttribArray(unsigned idx) {
    if (idx < MAXATTR) va_cur()->a[idx].enabled = 0;
    ((AttribArrFn)aemu_split_resolve(IDX_glDisableVertexAttribArray))(idx);
}

static int type_size(GLenum t) {
    switch (t) {
        case 0x1400: case 0x1401: return 1;               
        case 0x1402: case 0x1403: case 0x140B: case 0x8D61: return 2;  
        default: return 4;                                
    }
}

static int same_bytes(const unsigned char *a, const unsigned char *b, unsigned long n) {
    if (!a) return 0;
    if (!(((unsigned long)a | (unsigned long)b | n) & 3)) {
        const unsigned *x = (const unsigned *)a, *y = (const unsigned *)b;
        for (unsigned long i = 0; i < n / 4; i++) if (x[i] != y[i]) return 0;
        return 1;
    }
    for (unsigned long i = 0; i < n; i++) if (a[i] != b[i]) return 0;
    return 1;
}

 
static int va_upload_split(struct va_state *v, long nverts) {
    long total = 0, off[MAXATTR];
    for (int i = 0; i < MAXATTR; i++) {
        struct va_attr *a = &v->a[i];
        off[i] = -1;
        if (!a->enabled || !a->client) continue;
        long esz = (long)a->size * type_size(a->type);
        long stride = a->stride ? a->stride : esz;
        off[i] = total;
        total += ((stride * (nverts - 1) + esz) + 3) & ~3L;
    }
    if (!total) return 0;
     
    unsigned char *blob = (unsigned char *)malloc((unsigned)total);
    if (!blob) return 0;
    for (int i = 0; i < MAXATTR; i++) {
        if (off[i] < 0) continue;
        struct va_attr *a = &v->a[i];
        long esz = (long)a->size * type_size(a->type);
        long stride = a->stride ? a->stride : esz;
        copy_bytes(blob + off[i], (const unsigned char *)a->ptr, (unsigned long)(stride * (nverts - 1) + esz));
    }
    unsigned buf = 0;
    ((GenBuffersFn)aemu_split_resolve(IDX_glGenBuffers))(1, &buf);
    if (!buf) { free(blob); return 0; }
    BindBufferFn bind = (BindBufferFn)aemu_split_resolve(IDX_glBindBuffer);
    AttribPtrFn ap = (AttribPtrFn)aemu_split_resolve(IDX_glVertexAttribPointer);
    bind(GL_ARRAY_BUFFER, buf);
    ((BufferDataFn)aemu_split_resolve(IDX_glBufferData))(GL_ARRAY_BUFFER, total, blob, GL_STREAM_DRAW);
    free(blob);
    for (int i = 0; i < MAXATTR; i++) {
        if (off[i] < 0) continue;
        struct va_attr *a = &v->a[i];
        ap((unsigned)i, a->size, a->type, a->norm, a->stride, (const void *)off[i]);
    }
    v->split_buf = buf;
    return 2;
}

static void va_restore_split(struct va_state *v) {
    BindBufferFn bind = (BindBufferFn)aemu_split_resolve(IDX_glBindBuffer);
    AttribPtrFn ap = (AttribPtrFn)aemu_split_resolve(IDX_glVertexAttribPointer);
    bind(GL_ARRAY_BUFFER, 0);
    for (int i = 0; i < MAXATTR; i++) {
        struct va_attr *a = &v->a[i];
        if (a->enabled && a->client) { ap((unsigned)i, a->size, a->type, a->norm, a->stride, a->ptr); v->host_off[i] = 0; }
    }
    bind(GL_ARRAY_BUFFER, v->array_buf);
    ((GenBuffersFn)aemu_split_resolve(IDX_glDeleteBuffers))(1, &v->split_buf);   
    v->split_buf = 0;
}

 
static void va_context(void *ctx) {
    struct va_state *v = va_cur();
    if (v->ctx == ctx) return;
    v->ctx = ctx; v->sbuf = 0; v->shadow_len = 0;
    for (int i = 0; i < MAXATTR; i++) v->host_off[i] = 0;
}

 
 
 
 
 
static int va_upload(struct va_state *v, long nverts) {
    static int on = -1;
    if (on < 0) { const char *e = getenv("AEMU_GL_CLIENT_ARRAYS"); on = !(e && e[0] == '0'); }
    if (!on || nverts <= 0 || es1_current()) return 0;
    unsigned long lo = ~0UL, hi = 0, sum = 0;
    for (int i = 0; i < MAXATTR; i++) {
        struct va_attr *a = &v->a[i];
        if (!a->enabled || !a->client) continue;
        long esz = (long)a->size * type_size(a->type);
        long stride = a->stride ? a->stride : esz;
        unsigned long p = (unsigned long)a->ptr, n = (unsigned long)(stride * (nverts - 1) + esz);
        if (p < lo) lo = p;
        if (p + n > hi) hi = p + n;
        sum += n;
    }
    if (!sum) return 0;
    unsigned long len = hi - lo;
    if (len > sum * 4 + 4096) return va_upload_split(v, nverts);    
    BindBufferFn bind = (BindBufferFn)aemu_split_resolve(IDX_glBindBuffer);
    AttribPtrFn ap = (AttribPtrFn)aemu_split_resolve(IDX_glVertexAttribPointer);
    if (!v->sbuf) {
        ((GenBuffersFn)aemu_split_resolve(IDX_glGenBuffers))(1, &v->sbuf);
        if (!v->sbuf) return 0;
        v->shadow_len = 0;
    }
    bind(GL_ARRAY_BUFFER, v->sbuf);
    if (v->shadow_len != len || !same_bytes(v->shadow, (const unsigned char *)lo, len)) {
        ((BufferDataFn)aemu_split_resolve(IDX_glBufferData))(GL_ARRAY_BUFFER, (long)len, (const void *)lo, GL_STREAM_DRAW);
        if (v->shadow_cap < len) {
            free(v->shadow);
            v->shadow_cap = len < 4096 ? 4096 : len;
            v->shadow = (unsigned char *)malloc((unsigned)v->shadow_cap);
            if (!v->shadow) v->shadow_cap = 0;
        }
        if (v->shadow) { copy_bytes(v->shadow, (const unsigned char *)lo, len); v->shadow_len = len; }
        else v->shadow_len = 0;
    }
    for (int i = 0; i < MAXATTR; i++) {
        struct va_attr *a = &v->a[i];
        if (!a->enabled || !a->client) continue;
        long off = (long)((unsigned long)a->ptr - lo);
        if (v->host_off[i] != off + 1) {
            ap((unsigned)i, a->size, a->type, a->norm, a->stride, (const void *)off);
            v->host_off[i] = off + 1;
        }
    }
    return 1;
}

 
 
static void va_restore(struct va_state *v, int used) {
    if (used == 2) { va_restore_split(v); return; }
    ((BindBufferFn)aemu_split_resolve(IDX_glBindBuffer))(GL_ARRAY_BUFFER, v->array_buf);
}

__attribute__((visibility("default"))) void glDrawArrays(GLenum mode, GLint first, GLsizei count) {
    struct va_state *v = va_cur();
    int buf = count > 0 ? va_upload(v, (long)first + count) : 0;
    ((DrawArraysFn)aemu_split_resolve(IDX_glDrawArrays))(mode, first, count);
    if (buf) va_restore(v, buf);
}

__attribute__((visibility("default"))) void glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) {
    struct va_state *v = va_cur();
    int buf = 0;
     
    if (count > 0 && v->elem_buf == 0 && indices) {
        long maxi = 0;
        for (GLsizei i = 0; i < count; i++) {
            long x = type == 0x1401 ? ((const unsigned char *)indices)[i]
                   : type == 0x1403 ? ((const unsigned short *)indices)[i]
                   : (long)((const unsigned int *)indices)[i];
            if (x > maxi) maxi = x;
        }
        buf = va_upload(v, maxi + 1);
    }
    ((DrawElementsFn)aemu_split_resolve(IDX_glDrawElements))(mode, count, type, indices);
    if (buf) va_restore(v, buf);
}

 
 
 
 
 
 
 
 
 
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#define MAXIMG 256
struct img_tex { unsigned tex; GLenum target; void *image; unsigned frame; };
static struct img_tex g_img[MAXIMG];
static unsigned g_frame = 1, g_bound2d, g_boundext;

typedef void (*BindTexFn)(GLenum, unsigned);
typedef void (*ImageTargetFn)(GLenum, void *);
typedef void (*DeleteTexFn)(GLsizei, const unsigned *);
typedef unsigned (*SwapFn)(void *, void *);

static struct img_tex *img_find(unsigned tex) {
    if (!tex) return 0;
    for (int i = 0; i < MAXIMG; i++) if (g_img[i].tex == tex) return &g_img[i];
    return 0;
}
static void img_forget_bound(void) {
    struct img_tex *e = img_find(g_bound2d);
    if (e && e->target == GL_TEXTURE_2D) e->tex = 0;
}

__attribute__((visibility("default"))) void glBindTexture(GLenum target, unsigned tex) {
    ((BindTexFn)aemu_split_resolve(IDX_glBindTexture))(target, tex);
    if (target == GL_TEXTURE_2D) g_bound2d = tex;
    else if (target == GL_TEXTURE_EXTERNAL_OES) g_boundext = tex;
    else return;
    static int on = -1;
    if (on < 0) { const char *v = getenv("AEMU_GL_RETARGET"); on = !(v && v[0] == '0'); }
    struct img_tex *e = on ? img_find(tex) : 0;
    if (e && e->target == target && e->frame != g_frame) {
        e->frame = g_frame;
        ((ImageTargetFn)aemu_split_resolve(IDX_glEGLImageTargetTexture2DOES))(target, e->image);
    }
}

__attribute__((visibility("default"))) void glEGLImageTargetTexture2DOES(GLenum target, void *image) {
    ((ImageTargetFn)aemu_split_resolve(IDX_glEGLImageTargetTexture2DOES))(target, image);
    unsigned tex = target == GL_TEXTURE_EXTERNAL_OES ? g_boundext : g_bound2d;
    if (!tex) return;
    struct img_tex *e = img_find(tex);
    if (!e) for (int i = 0; i < MAXIMG && !e; i++) if (!g_img[i].tex) e = &g_img[i];
    if (!e) e = &g_img[tex % MAXIMG];
    e->tex = tex; e->target = target; e->image = image; e->frame = g_frame;
}

__attribute__((visibility("default"))) void glDeleteTextures(GLsizei n, const unsigned *tex) {
    for (GLsizei i = 0; tex && i < n; i++) { struct img_tex *e = img_find(tex[i]); if (e) e->tex = 0; }
    ((DeleteTexFn)aemu_split_resolve(IDX_glDeleteTextures))(n, tex);
}

__attribute__((visibility("default"))) unsigned eglSwapBuffers(void *dpy, void *surface) {
    g_frame++;
    return ((SwapFn)aemu_split_resolve(IDX_eglSwapBuffers))(dpy, surface);
}

 
 
 
 
 
static unsigned swap_with_damage(void *dpy, void *surface, const GLint *rects, GLint n) {
    (void)rects; (void)n;
    return eglSwapBuffers(dpy, surface);
}
static unsigned set_damage_region(void *dpy, void *surface, const GLint *rects, GLint n) {
    (void)dpy; (void)surface; (void)rects; (void)n;
    return 1;
}
 
 
 
 
 
 
 
__attribute__((constructor)) static void hold_std_fds(void) {
    for (int fd = 0; fd < 3; fd++)
        if (syscall(55  , fd, 1  ) < 0) syscall(5  , "/dev/null", 2  , 0);
}

typedef void *(*CreateImageFn)(void *dpy, void *ctx, unsigned target, void *buf, const GLint *attribs);
__attribute__((visibility("default"))) void *eglCreateImageKHR(void *dpy, void *ctx, unsigned target, void *buf, const GLint *attribs) {
    CreateImageFn f = (CreateImageFn)aemu_split_resolve(IDX_eglCreateImageKHR);
    void *img = f(dpy, ctx, target, buf, attribs);
    if (img || target != 0x3140   || !buf) return img;
    int *usage = (int *)((char *)buf + 48);
    int old = *usage;
    if (old & 0xff) {
        *usage = old & ~0xff;
        img = f(dpy, ctx, target, buf, attribs);
        *usage = old;
        if (img) return img;
    }
     
 
    int *format = (int *)((char *)buf + 44);
    if (*format != 2  ) return img;
    *format = 1;
    *usage = old & ~0xff;
    img = f(dpy, ctx, target, buf, attribs);
    *usage = old;
    *format = 2;
    return img;
}

typedef void *(*ProcFn)(const char *);
__attribute__((visibility("default"))) void *eglGetProcAddress(const char *name) {
    static const char *const names[] = { "eglSwapBuffersWithDamageKHR", "eglSwapBuffersWithDamageEXT", "eglSetDamageRegionKHR" };
    static void *const fns[] = { (void *)swap_with_damage, (void *)swap_with_damage, (void *)set_damage_region };
    for (int i = 0; name && i < 3; i++) {
        int k = 0;
        while (names[i][k] && name[k] == names[i][k]) k++;
        if (!names[i][k] && !name[k]) return fns[i];
    }
    return ((ProcFn)aemu_split_resolve(IDX_eglGetProcAddress))(name);
}

 
 
 
 
 
 
 
 
typedef void *(*CreateWinFn)(void *dpy, void *config, void *win, const GLint *attribs);
typedef void *(*CreatePbufFn)(void *dpy, void *config, const GLint *attribs);
typedef int (*WinQueryFn)(const void *win, int what, int *value);
static void *g_primary_win;
typedef unsigned (*GetAttrFn)(void *dpy, void *config, int attr, int *value);
typedef unsigned (*ChooseFn)(void *dpy, const GLint *attribs, void **configs, int size, int *num);

__attribute__((visibility("default"))) void *eglCreateWindowSurface(void *dpy, void *config, void *win, const GLint *attribs) {
    CreateWinFn f = (CreateWinFn)aemu_split_resolve(IDX_eglCreateWindowSurface);
    if (!is_surfaceflinger() || !win) {
        void *r = f(dpy, config, win, attribs);
        if (r || !win) return r;
         
 
        int fmt = 0, depth = 0, stencil = 0, n = 0;
        WinQueryFn q = *(WinQueryFn *)((char *)win + 84);
        if (q) q(win, 2  , &fmt);
        GetAttrFn ga = (GetAttrFn)aemu_split_resolve(IDX_eglGetConfigAttrib);
        ga(dpy, config, 0x3025  , &depth);
        ga(dpy, config, 0x3026  , &stencil);
        int rgb565 = fmt == 4, alpha = fmt == 1 ? 8 : 0;
        GLint ca[] = { 0x3024, rgb565 ? 5 : 8, 0x3023, rgb565 ? 6 : 8, 0x3022, rgb565 ? 5 : 8, 0x3021, alpha,
                       0x3025, depth, 0x3026, stencil, 0x3033  , 4  ,
                       0x3040  , 4  , EGL_NONE };
        void *cfg = 0;
        if (((ChooseFn)aemu_split_resolve(IDX_eglChooseConfig))(dpy, ca, &cfg, 1, &n) && n > 0 && cfg != config)
            r = f(dpy, cfg, win, attribs);
        return r;
    }
    if (!g_primary_win) g_primary_win = win;
    if (win == g_primary_win) return f(dpy, config, win, attribs);
    int w = 1, h = 1;
    WinQueryFn q = *(WinQueryFn *)((char *)win + 84);    
    if (q) { q(win, 0  , &w); q(win, 1  , &h); }
    if (w <= 0 || w > 4096) w = 1;
    if (h <= 0 || h > 4096) h = 1;
    GLint pa[] = { 0x3057  , w, 0x3056  , h, EGL_NONE };
    return ((CreatePbufFn)aemu_split_resolve(IDX_eglCreatePbufferSurface))(dpy, config, pa);
}
