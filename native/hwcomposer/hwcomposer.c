 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <time.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/fb.h>

#include <sys/socket.h>
#include <sys/un.h>

#include <hardware/hwcomposer2.h>

#include "../grallocwrap/note.h"
#include "../glbridge/common.h"
extern int __system_property_get(const char *name, char *value);

 
 
struct aemu_ts { long tv_sec; long tv_nsec; };
extern int aemu_clock_gettime(int clk, struct aemu_ts *ts) __asm__("clock_gettime");
extern int aemu_clock_nanosleep(int clk, int flags, const struct aemu_ts *req, struct aemu_ts *rem) __asm__("clock_nanosleep");
#define timespec aemu_ts
#define clock_gettime aemu_clock_gettime
#define clock_nanosleep aemu_clock_nanosleep

#define LOG_TAG "hwc2fb"
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...)
    __attribute__((weak));
extern int vsnprintf(char *, size_t, const char *, __builtin_va_list);
 
 
static void hwc_log(int prio, const char *fmt, ...) {
    __builtin_va_list ap;
    __builtin_va_start(ap, fmt);
    if (__android_log_print) {
        char b[256];
        vsnprintf(b, sizeof(b), fmt, ap);
        __android_log_print(prio, LOG_TAG, "%s", b);
    } else {
        char b[300];
        int n = vsnprintf(b, sizeof(b) - 1, fmt, ap);
        if (n > 0) {
            if (n > (int)sizeof(b) - 2) n = (int)sizeof(b) - 2;
            b[n++] = '\n';
            int fd = open("/data/local/tmp/hwc2fb.log", O_WRONLY | O_CREAT | O_APPEND, 0666);
            if (fd >= 0) { write(fd, b, (size_t)n); close(fd); }
        }
    }
    __builtin_va_end(ap);
}
#define LOGI(...) hwc_log(4, __VA_ARGS__)
#define LOGE(...) hwc_log(6, __VA_ARGS__)

 
#define TR(name, ...) do { static unsigned c_; if (c_ < 6) { c_++; LOGI(name ": " __VA_ARGS__); } } while (0)

#define EXPORT __attribute__((visibility("default")))
#define MAX_LAYERS 128
#define VSYNC_NS 16666667
#define DPI_MILLI 240000

 
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

#define MAX_SLOTS 128
static buffer_handle_t g_client_target;                  
static buffer_handle_t g_ct_slot[MAX_SLOTS];             

struct layer { int used; hwc2_layer_t id; int32_t type; };
static struct layer g_layers[MAX_LAYERS];
static hwc2_layer_t g_next_layer = 1;

static int g_changed_n;
static hwc2_layer_t g_changed_id[MAX_LAYERS];

static HWC2_PFN_HOTPLUG g_cb_hotplug; static hwc2_callback_data_t g_cbd_hotplug;
static HWC2_PFN_REFRESH g_cb_refresh;  static hwc2_callback_data_t g_cbd_refresh;
static HWC2_PFN_VSYNC g_cb_vsync;      static hwc2_callback_data_t g_cbd_vsync;
static HWC2_PFN_VSYNC_2_4 g_cb_vsync24; static hwc2_callback_data_t g_cbd_vsync24;
static int g_hotplug_pending;
static int g_vsync_on;
static int g_power = HWC2_POWER_MODE_ON;
static int g_active_config = 1;
static int g_thread_started;
static int g_validated;

 
static char g_fbpath[96] = "/dev/graphics/fb0";
static int g_w = 540, g_h = 960;
static int g_fbbpp = 16;
static int g_stride_cfg = 0;       
static int g_fdidx_cfg = -1;       
static int g_swaprb = 0;
static int g_bpp_cfg = 0;
static int g_vecmode = 2;          
static int g_fb = -1;
static uint32_t g_fb_linebytes;
static uint8_t *g_frame;            
static unsigned g_frames, g_failures;

 
static int parse_int(const char *s) {
    int v = 0, neg = 0;
    if (*s == '-') { neg = 1; s++; }
    while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0');
    return neg ? -v : v;
}

static void load_config(void) {
    char buf[1024];
    int fd = open("/data/local/tmp/hwc2fb.conf", O_RDONLY);
    if (fd < 0) return;
    int n = (int)read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return;
    buf[n] = 0;
    char *p = buf;
    while (*p) {
        char *e = p;
        while (*e && *e != '\n') e++;
        char save = *e; *e = 0;
        char *eq = strchr(p, '=');
        if (eq) {
            *eq = 0;
            const char *k = p, *v = eq + 1;
            if (!strcmp(k, "fb")) { strncpy(g_fbpath, v, sizeof(g_fbpath) - 1); }
            else if (!strcmp(k, "width")) g_w = parse_int(v);
            else if (!strcmp(k, "height")) g_h = parse_int(v);
            else if (!strcmp(k, "fbbpp")) g_fbbpp = parse_int(v);
            else if (!strcmp(k, "stride")) g_stride_cfg = parse_int(v);
            else if (!strcmp(k, "fd")) g_fdidx_cfg = parse_int(v);
            else if (!strcmp(k, "swaprb")) g_swaprb = parse_int(v);
            else if (!strcmp(k, "bpp")) g_bpp_cfg = parse_int(v);
            else if (!strcmp(k, "vec")) g_vecmode = parse_int(v);
        }
        if (!save) break;
        p = e + 1;
    }
}

static void now_ts(struct timespec *ts) { clock_gettime(CLOCK_MONOTONIC, ts); }

static int32_t close_fence(int fd) { if (fd >= 0) close(fd); return 0; }

static void wait_fence(int fd) {
    if (fd < 0) return;
    struct pollfd pfd; pfd.fd = fd; pfd.events = POLLIN; pfd.revents = 0;
    poll(&pfd, 1, 1000);
    close(fd);
}


 
 
 
 
static int g_pp[2] = { -1, -1 };
static int probe_byte(const volatile void *p) {
    uint8_t b;
    if (g_pp[0] < 0 && pipe(g_pp) < 0) return 0;
    if (write(g_pp[1], (const void *)p, 1) != 1) return 0;
    if (read(g_pp[0], &b, 1) != 1) return 0;            
    if (write(g_pp[1], &b, 1) != 1) return 0;           
    return read(g_pp[0], (void *)p, 1) == 1;            
}
static int probe_rw(void *p, size_t bytes) {
    if (!p || bytes == 0 || ((uintptr_t)p & 3)) return 0;
    return probe_byte(p) && probe_byte((uint8_t *)p + bytes - 1);
}

 
 
struct mtk_vec { uint8_t *begin, *end, *cap; };

static int vec_is_empty_rw(void *v) {
    if (!probe_rw(v, sizeof(struct mtk_vec))) return 0;
    const struct mtk_vec *x = (const struct mtk_vec *)v;
    return !x->begin && !x->end && !x->cap;
}

 
static int32_t vec_fill(struct mtk_vec *v, const void *vals, size_t esz, uint32_t cnt) {
    size_t bytes = esz * (size_t)cnt;
    if (cnt == 0) { v->end = v->begin; return HWC2_ERROR_NONE; }
    if (!v->begin || (size_t)(v->cap - v->begin) < bytes) {
        uint8_t *buf = (uint8_t *)malloc(bytes);
        if (!buf) return HWC2_ERROR_NO_RESOURCES;
        if (v->begin) free(v->begin);
        v->begin = buf;
        v->cap = buf + bytes;
    }
    memcpy(v->begin, vals, bytes);
    v->end = v->begin + bytes;
    return HWC2_ERROR_NONE;
}

 
 
 
 
 
 
static pthread_mutex_t g_fb_lock = PTHREAD_MUTEX_INITIALIZER;
static int fb_open_locked(void);
static int fb_open(void) {
    pthread_mutex_lock(&g_fb_lock);
    int r = fb_open_locked();
    pthread_mutex_unlock(&g_fb_lock);
    return r;
}
static int fb_open_locked(void) {
    if (g_fb >= 0) return 0;
    g_fb = open(g_fbpath, O_RDWR);
    if (g_fb < 0) {
        static int warned;
        if (!warned) { warned = 1; LOGE("cannot open %s: the size stays %dx%d", g_fbpath, g_w, g_h); }
        return -1;
    }
    struct fb_var_screeninfo v;
    memset(&v, 0, sizeof(v));
    if (ioctl(g_fb, FBIOGET_VSCREENINFO, &v) == 0 && v.xres && v.yres &&
        (v.bits_per_pixel == 16 || v.bits_per_pixel == 32)) {
        g_fbbpp = (int)v.bits_per_pixel;
        g_w = (int)v.xres;
        g_h = (int)v.yres;
        LOGI("fb ioctl: %dx%d %dbpp", g_w, g_h, g_fbbpp);
    } else {
        LOGI("fb is not a real fbdev, using %dx%d %dbpp", g_w, g_h, g_fbbpp);
    }
    g_fb_linebytes = (uint32_t)g_w * (uint32_t)(g_fbbpp / 8);
    g_frame = (uint8_t *)calloc(1, (size_t)g_fb_linebytes * (size_t)g_h);
    return g_frame ? 0 : -1;
}

static void fb_flush(void) {
    size_t sz = (size_t)g_fb_linebytes * (size_t)g_h;
    size_t off = 0;
    while (off < sz) {
        ssize_t r = pwrite(g_fb, g_frame + off, sz - off, (off_t)off);
        if (r <= 0) { g_failures++; return; }
        off += (size_t)r;
    }
}

static void put_px(int x, int y, uint8_t r, uint8_t g, uint8_t b) {
    if (g_fbbpp == 16) {
        uint16_t *d = (uint16_t *)(g_frame + (size_t)y * g_fb_linebytes) + x;
        *d = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
    } else {
        uint8_t *d = g_frame + (size_t)y * g_fb_linebytes + (size_t)x * 4;
        d[0] = r; d[1] = g; d[2] = b; d[3] = 0xff;
    }
}

#define DMA_BUF_SYNC_START_READ 0x40086201ULL  
static void dmabuf_sync(int fd, int end) {
    uint64_t flags = (end ? 4u : 0u) | 1u;    
    ioctl(fd, 0x40086200, &flags);            
}

 
 
 
 
 
static int g_direct = -1, g_glfd = -1;
static int direct_present(buffer_handle_t h) {
    if (g_direct < 0) { char v[92] = {0}; g_direct = __system_property_get("debug.aemu.gl", v) > 0 && v[0] == '3'; }
    if (!g_direct || !h) return 0;
    struct meta_note *n = meta_note_at(h);
    if (!n || n->magic != META_NOTE || !n->id || n->w <= 0 || n->h <= 0) return 0;
    struct { uint32_t op, len; uint64_t id; uint32_t w, h; } __attribute__((packed)) q = { OP_PRESENT, 16, n->id, (uint32_t)n->w, (uint32_t)n->h };
    uint32_t ok = 0;
    for (int tries = 0; tries < 2; tries++) {
        if (g_glfd < 0) {
            struct sockaddr_un a; memset(&a, 0, sizeof(a)); a.sun_family = AF_UNIX;
            strncpy(a.sun_path, AEMU_GL_SOCKET, sizeof(a.sun_path) - 1);
            int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (fd >= 0 && connect(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { close(fd); fd = -1; }
            if (fd < 0) { g_direct = 0; LOGE("direct: no renderer at %s, copying to fb0", AEMU_GL_SOCKET); return 0; }
            g_glfd = fd;
        }
        if (write(g_glfd, &q, sizeof(q)) == (ssize_t)sizeof(q) && read(g_glfd, &ok, 4) == 4) return (int)ok;
        close(g_glfd); g_glfd = -1;
    }
    return 0;
}

static void present_buffer(buffer_handle_t h) {
    if (!h) return;
    if (direct_present(h)) { if (g_frames < 3) LOGI("direct: frame shown by the host"); g_frames++; return; }
    if (fb_open() < 0) { if ((g_failures++ % 120) == 0) LOGE("cannot open %s", g_fbpath); return; }
    const native_handle_t *nh = h;
    if (nh->numFds < 0 || nh->numInts < 0 || nh->numFds > 1024 || nh->numInts > 1024) return;

    if (g_frames < 3) {
        char ints[160]; int o = 0;
        for (int i = 0; i < nh->numInts && i < 12 && o < 140; i++)
            o += (int)snprintf(ints + o, sizeof(ints) - (size_t)o, "%d ", nh->data[nh->numFds + i]);
        ints[o] = 0;
        LOGI("client target: numFds=%d numInts=%d ints=[%s]", nh->numFds, nh->numInts, ints);
    }

    size_t need = (size_t)g_w * (size_t)g_h * 2;
    int fd = -1; size_t size = 0;
    if (g_fdidx_cfg >= 0 && g_fdidx_cfg < nh->numFds) {
        fd = nh->data[g_fdidx_cfg];
        off_t s = lseek(fd, 0, SEEK_END); lseek(fd, 0, SEEK_SET);
        size = s > 0 ? (size_t)s : 0;
    } else {
        for (int i = 0; i < nh->numFds; i++) {
            int f = nh->data[i];
            if (f < 0) continue;
            off_t s = lseek(f, 0, SEEK_END); lseek(f, 0, SEEK_SET);
            if (s > 0 && (size_t)s >= need) { fd = f; size = (size_t)s; break; }
        }
    }
    if (fd < 0 || size < need) {
        if ((g_failures++ % 120) == 0)
            LOGE("no mappable fd in client target (numFds=%d, need>=%u bytes)", nh->numFds, (unsigned)need);
        return;
    }

    int bpp = g_bpp_cfg ? g_bpp_cfg : (size >= (size_t)g_w * (size_t)g_h * 4 ? 4 : 2);
    size_t stride_px;
    if (g_stride_cfg) stride_px = (size_t)g_stride_cfg;
    else {
         
 
 
 
        static const int al[] = {1, 16, 32, 64, 128, 256};
        stride_px = (size_t)g_w;
        for (unsigned i = 0; i < sizeof(al) / sizeof(al[0]); i++) {
            size_t s = ((size_t)g_w + (size_t)al[i] - 1) / (size_t)al[i] * (size_t)al[i];
            if (s * (size_t)g_h * (size_t)bpp <= size && s > stride_px) stride_px = s;
        }
        if (stride_px < (size_t)g_w) stride_px = (size_t)g_w;
    }
    if (g_frames < 3) LOGI("blit: fd=%d size=%u bpp=%d stride=%u px", fd, (unsigned)size, bpp, (unsigned)stride_px);

    uint8_t *map = (uint8_t *)mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    if (map == (uint8_t *)MAP_FAILED) {
        if ((g_failures++ % 120) == 0) LOGE("mmap of client target failed (fd=%d size=%u)", fd, (unsigned)size);
        return;
    }
    dmabuf_sync(fd, 0);
    for (int y = 0; y < g_h; y++) {
        const uint8_t *row = map + (size_t)y * stride_px * (size_t)bpp;
        if (bpp == 4) {
            const uint32_t *s = (const uint32_t *)row;
            for (int x = 0; x < g_w; x++) {
                uint32_t p = s[x];
                uint8_t r = (uint8_t)p, g = (uint8_t)(p >> 8), b = (uint8_t)(p >> 16);
                if (g_swaprb) { uint8_t t = r; r = b; b = t; }
                put_px(x, y, r, g, b);
            }
        } else {
            const uint16_t *s = (const uint16_t *)row;
            if (g_fbbpp == 16) memcpy(g_frame + (size_t)y * g_fb_linebytes, s, (size_t)g_w * 2);
            else for (int x = 0; x < g_w; x++) {
                uint16_t p = s[x];
                put_px(x, y, (uint8_t)((p >> 11) << 3), (uint8_t)(((p >> 5) & 63) << 2), (uint8_t)((p & 31) << 3));
            }
        }
    }
    dmabuf_sync(fd, 1);
    munmap(map, size);
    fb_flush();
    g_frames++;
}

 
static void *worker(void *arg) {
    (void)arg;
    struct timespec t; now_ts(&t);
    for (;;) {
        t.tv_nsec += VSYNC_NS;
        if (t.tv_nsec >= 1000000000) { t.tv_nsec -= 1000000000; t.tv_sec++; }
        clock_nanosleep(CLOCK_MONOTONIC, 1  , &t, NULL);

        pthread_mutex_lock(&g_lock);
        HWC2_PFN_HOTPLUG hp = g_cb_hotplug; hwc2_callback_data_t hpd = g_cbd_hotplug;
        int hp_pend = g_hotplug_pending; if (hp && hp_pend) g_hotplug_pending = 0;
        HWC2_PFN_VSYNC v = g_cb_vsync; hwc2_callback_data_t vd = g_cbd_vsync;
        HWC2_PFN_VSYNC_2_4 v24 = g_cb_vsync24; hwc2_callback_data_t v24d = g_cbd_vsync24;
        int von = g_vsync_on;
        pthread_mutex_unlock(&g_lock);

        if (hp && hp_pend) { LOGI("hotplug: display 0 connected"); hp(hpd, 0, HWC2_CONNECTION_CONNECTED); }
        if (von) {
            int64_t ts = (int64_t)t.tv_sec * 1000000000LL + (int64_t)t.tv_nsec;
            if (v24) v24(v24d, 0, ts, VSYNC_NS);
            else if (v) v(vd, 0, ts);
        }
    }
    return NULL;
}

 
#define CHK_DISPLAY(d) do { if ((d) != 0) return HWC2_ERROR_BAD_DISPLAY; } while (0)

static struct layer *find_layer(hwc2_layer_t id) {
    for (int i = 0; i < MAX_LAYERS; i++)
        if (g_layers[i].used && g_layers[i].id == id) return &g_layers[i];
    return NULL;
}
#define CHK_LAYER(d, l) do { CHK_DISPLAY(d); pthread_mutex_lock(&g_lock); \
    int ok_ = find_layer(l) != NULL; pthread_mutex_unlock(&g_lock); \
    if (!ok_) return HWC2_ERROR_BAD_LAYER; } while (0)

 
static int32_t h_createVirtualDisplay(hwc2_device_t *dev, uint32_t w, uint32_t h, int32_t *f, hwc2_display_t *o) {
    (void)dev; (void)w; (void)h; (void)f; (void)o; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_destroyVirtualDisplay(hwc2_device_t *dev, hwc2_display_t d) {
    (void)dev; (void)d; return HWC2_ERROR_BAD_DISPLAY;
}
static void h_dump(hwc2_device_t *dev, uint32_t *outSize, char *out) {
    (void)dev;
    static const char msg[] = "hwc2fb: client-composition only, writes fb0\n";
    if (!outSize) return;
    if (!out) { *outSize = (uint32_t)sizeof(msg) - 1; return; }
    uint32_t n = *outSize < sizeof(msg) - 1 ? *outSize : (uint32_t)sizeof(msg) - 1;
    memcpy(out, msg, n); *outSize = n;
}
static uint32_t h_getMaxVirtualDisplayCount(hwc2_device_t *dev) { (void)dev; return 0; }

static int32_t h_registerCallback(hwc2_device_t *dev, int32_t desc, hwc2_callback_data_t data, hwc2_function_pointer_t ptr) {
    TR("registerCallback", "desc=%d data=%p ptr=%p", (int)desc, (void *)data, (void *)ptr);
    (void)dev;
    HWC2_PFN_HOTPLUG deliver = NULL;
    pthread_mutex_lock(&g_lock);
    switch (desc) {
    case HWC2_CALLBACK_HOTPLUG:
        g_cb_hotplug = (HWC2_PFN_HOTPLUG)ptr; g_cbd_hotplug = data;
         
 
        g_hotplug_pending = 0;
        deliver = (HWC2_PFN_HOTPLUG)ptr;
        break;
    case HWC2_CALLBACK_REFRESH:  g_cb_refresh = (HWC2_PFN_REFRESH)ptr; g_cbd_refresh = data; break;
    case HWC2_CALLBACK_VSYNC:    g_cb_vsync = (HWC2_PFN_VSYNC)ptr; g_cbd_vsync = data; break;
    case HWC2_CALLBACK_VSYNC_2_4: g_cb_vsync24 = (HWC2_PFN_VSYNC_2_4)ptr; g_cbd_vsync24 = data; break;
    default: break;  
 
 
    }
    pthread_mutex_unlock(&g_lock);
    if (deliver) { LOGI("hotplug: display 0 connected"); deliver(data, 0, HWC2_CONNECTION_CONNECTED); }
    return HWC2_ERROR_NONE;
}
static int32_t h_getDataspaceSaturationMatrix(hwc2_device_t *dev, int32_t ds, float *m) {
    (void)dev; (void)ds;
    if (!m) return HWC2_ERROR_BAD_PARAMETER;
    for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1.0f : 0.0f;
    return HWC2_ERROR_NONE;
}

 
static int32_t h_acceptDisplayChanges(hwc2_device_t *dev, hwc2_display_t d) {
    (void)dev; CHK_DISPLAY(d);
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used) g_layers[i].type = HWC2_COMPOSITION_CLIENT;
    g_changed_n = 0;
    pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t create_layer_common(hwc2_display_t d, hwc2_layer_t *out) {
    CHK_DISPLAY(d);
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_LAYERS; i++) if (!g_layers[i].used) {
        g_layers[i].used = 1; g_layers[i].id = g_next_layer++; g_layers[i].type = HWC2_COMPOSITION_INVALID;
        hwc2_layer_t id = g_layers[i].id;
        pthread_mutex_unlock(&g_lock);
        if (probe_rw(out, sizeof(*out))) *out = id;
        return HWC2_ERROR_NONE;
    }
    pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NO_RESOURCES;
}
static int32_t h_createLayer(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t *out) {
    TR("createLayer", "d=%u out=%p", (unsigned)d, (void *)out);
    (void)dev;
    return create_layer_common(d, out);
}
static int32_t h_destroyLayer(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l) {
    (void)dev; CHK_DISPLAY(d);
    pthread_mutex_lock(&g_lock);
    struct layer *ly = find_layer(l);
    if (!ly) { pthread_mutex_unlock(&g_lock); return HWC2_ERROR_BAD_LAYER; }
    ly->used = 0;
    pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t h_getActiveConfig(hwc2_device_t *dev, hwc2_display_t d, hwc2_config_t *c) {
    TR("getActiveConfig", "c=%p", (void *)c);
    (void)dev; CHK_DISPLAY(d); if (c) *c = (hwc2_config_t)g_active_config; return HWC2_ERROR_NONE;
}
static int32_t h_getChangedCompositionTypes(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, hwc2_layer_t *ls, int32_t *ts) {
    TR("getChangedCompositionTypes", "n=%p ls=%p ts=%p", (void *)n, (void *)ls, (void *)ts);
    (void)dev; CHK_DISPLAY(d);
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    pthread_mutex_lock(&g_lock);
    if (g_vecmode != 0 && ls && vec_is_empty_rw(n) && probe_rw(ls, sizeof(struct mtk_vec)) && vec_is_empty_rw(ls)) {
         
        hwc2_layer_t ids[MAX_LAYERS]; int32_t types[MAX_LAYERS];
        uint32_t k = (uint32_t)g_changed_n;
        for (uint32_t i = 0; i < k; i++) { ids[i] = g_changed_id[i]; types[i] = HWC2_COMPOSITION_CLIENT; }
        int32_t r = vec_fill((struct mtk_vec *)(void *)n, ids, sizeof(ids[0]), k);
        if (!r) r = vec_fill((struct mtk_vec *)(void *)ls, types, sizeof(types[0]), k);
        pthread_mutex_unlock(&g_lock);
        return r;
    }
    if (!ls || !ts || *n == 0 || !probe_rw(ls, (size_t)*n * sizeof(*ls)) || !probe_rw(ts, (size_t)*n * sizeof(*ts))) *n = (uint32_t)g_changed_n;
    else {
        uint32_t k = *n < (uint32_t)g_changed_n ? *n : (uint32_t)g_changed_n;
        for (uint32_t i = 0; i < k; i++) { ls[i] = g_changed_id[i]; ts[i] = HWC2_COMPOSITION_CLIENT; }
        *n = k;
    }
    pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t h_getClientTargetSupport(hwc2_device_t *dev, hwc2_display_t d, uint32_t w, uint32_t h, int32_t fmt, int32_t ds) {
    TR("getClientTargetSupport", "%ux%u fmt=%d", (unsigned)w, (unsigned)h, (int)fmt);
    (void)dev; (void)ds; CHK_DISPLAY(d);
    fb_open();  
    if ((int)w != g_w || (int)h != g_h) return HWC2_ERROR_UNSUPPORTED;
    if (fmt == HAL_PIXEL_FORMAT_RGBA_8888 || fmt == HAL_PIXEL_FORMAT_RGBX_8888 ||
        fmt == HAL_PIXEL_FORMAT_BGRA_8888 || fmt == HAL_PIXEL_FORMAT_RGB_565) return HWC2_ERROR_NONE;
    return HWC2_ERROR_UNSUPPORTED;
}
 
struct mtk_vec_i32 { int32_t *begin; int32_t *end; int32_t *cap; };

static int32_t fill_vec_i32(struct mtk_vec_i32 *v, const int32_t *vals, uint32_t cnt) {
    if (!v->begin || (v->cap - v->begin) < (long)cnt) {
        int32_t *buf = (int32_t *)malloc(cnt * sizeof(int32_t));    
        if (!buf) return HWC2_ERROR_NO_RESOURCES;
        if (v->begin) free(v->begin);
        v->begin = buf;
        v->cap = buf + cnt;
    }
    memcpy(v->begin, vals, cnt * sizeof(int32_t));
    v->end = v->begin + cnt;
    return HWC2_ERROR_NONE;
}

 
 
 
 
 
static int32_t out_i32_list(const char *who, uint32_t *n, void *arr, const int32_t *vals, uint32_t cnt) {
    int vec = (g_vecmode == 1) || (g_vecmode == 2 && arr != NULL && n[0] == 0);
    if (cnt == 0) { *n = 0; return HWC2_ERROR_NONE; }
    if (vec) {
        LOGI("%s: std::vector convention", who);
        return fill_vec_i32((struct mtk_vec_i32 *)(void *)n, vals, cnt);
    }
    if (!arr) { *n = cnt; return HWC2_ERROR_NONE; }
    uint32_t k = *n < cnt ? *n : cnt;
    memcpy(arr, vals, k * sizeof(int32_t));
    *n = k;
    return HWC2_ERROR_NONE;
}

static int32_t h_getColorModes(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, int32_t *m) {
    (void)dev; CHK_DISPLAY(d);
    TR("getColorModes", "n=%p n0=%u arr=%p", (void *)n, n ? (unsigned)n[0] : 0u, (void *)m);
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    static const int32_t modes[1] = { HAL_COLOR_MODE_NATIVE };
    return out_i32_list("getColorModes", n, m, modes, 1);
}
static int32_t h_getRenderIntents(hwc2_device_t *dev, hwc2_display_t d, int32_t mode, uint32_t *n, int32_t *o) {
    (void)dev; CHK_DISPLAY(d);
    TR("getRenderIntents", "mode=%d n=%p n0=%u arr=%p", (int)mode, (void *)n, n ? (unsigned)n[0] : 0u, (void *)o);
    if (mode != HAL_COLOR_MODE_NATIVE) return HWC2_ERROR_BAD_PARAMETER;
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    static const int32_t ri[1] = { HAL_RENDER_INTENT_COLORIMETRIC };
    return out_i32_list("getRenderIntents", n, o, ri, 1);
}
static int32_t h_setColorMode(hwc2_device_t *dev, hwc2_display_t d, int32_t m) {
    (void)dev; CHK_DISPLAY(d); return m == HAL_COLOR_MODE_NATIVE ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_PARAMETER;
}
static int32_t h_setColorModeWithRenderIntent(hwc2_device_t *dev, hwc2_display_t d, int32_t m, int32_t i) {
    (void)dev; CHK_DISPLAY(d);
    return (m == HAL_COLOR_MODE_NATIVE && i == HAL_RENDER_INTENT_COLORIMETRIC) ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_PARAMETER;
}
static int32_t h_getDisplayAttribute(hwc2_device_t *dev, hwc2_display_t d, hwc2_config_t cfg, int32_t attr, int32_t *out) {
    TR("getDisplayAttribute", "cfg=%u attr=%d out=%p", (unsigned)cfg, (int)attr, (void *)out);
    (void)dev; CHK_DISPLAY(d);
    if (cfg != 1) return HWC2_ERROR_BAD_CONFIG;
    if (!out) return HWC2_ERROR_BAD_PARAMETER;
    fb_open();  
    switch (attr) {
    case HWC2_ATTRIBUTE_WIDTH: *out = g_w; break;
    case HWC2_ATTRIBUTE_HEIGHT: *out = g_h; break;
    case HWC2_ATTRIBUTE_VSYNC_PERIOD: *out = VSYNC_NS; break;
    case HWC2_ATTRIBUTE_DPI_X:
    case HWC2_ATTRIBUTE_DPI_Y: *out = DPI_MILLI; break;
    case HWC2_ATTRIBUTE_CONFIG_GROUP: *out = 0; break;
    default: *out = -1; return HWC2_ERROR_BAD_PARAMETER;
    }
    return HWC2_ERROR_NONE;
}
 
 
 
 
 
 
 
 
 
 
 
static int32_t h_getDisplayConfigs(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, hwc2_config_t *c) {
    TR("getDisplayConfigs", "n=%p n0=%u c=%p", (void *)n, probe_rw(n, 4) ? (unsigned)n[0] : 0u, (void *)c);
    (void)dev; CHK_DISPLAY(d);
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    int vec = g_vecmode == 1 ||
              (g_vecmode == 2 && c != NULL && probe_rw(n, 4) && n[0] == 0 && vec_is_empty_rw(n));
    if (vec) {
         
        static const int32_t cfg[1] = { 1 };                    
        LOGI("getDisplayConfigs: std::vector convention");
        return fill_vec_i32((struct mtk_vec_i32 *)(void *)n, cfg, 1);
    }
    if (!c) { *n = 1; return HWC2_ERROR_NONE; }                
    if (*n >= 1) c[0] = 1;                                      
    *n = *n >= 1 ? 1 : 0;
    return HWC2_ERROR_NONE;
}
static int32_t h_getDisplayName(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, char *name) {
    TR("getDisplayName", "n=%p name=%p", (void *)n, (void *)name);
    (void)dev; CHK_DISPLAY(d);
    static const char nm[] = "hwc2fb";
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    if (!probe_rw(n, 4)) return HWC2_ERROR_BAD_PARAMETER;
    if (!name || !probe_rw(name, 4)) { *n = (uint32_t)sizeof(nm) - 1; return HWC2_ERROR_NONE; }
    uint32_t k = *n < sizeof(nm) - 1 ? *n : (uint32_t)sizeof(nm) - 1;
    memcpy(name, nm, k); *n = k; return HWC2_ERROR_NONE;
}
static int32_t h_getDisplayRequests(hwc2_device_t *dev, hwc2_display_t d, int32_t *req, uint32_t *n, hwc2_layer_t *ls, int32_t *lr) {
    TR("getDisplayRequests", "req=%p n=%p ls=%p lr=%p", (void *)req, (void *)n, (void *)ls, (void *)lr);
    (void)dev; (void)ls; (void)lr; CHK_DISPLAY(d);
    if (probe_rw(req, 4)) *req = 0;
    if (probe_rw(n, 4)) *n = 0;
    return HWC2_ERROR_NONE;
}
static int32_t h_getDisplayType(hwc2_device_t *dev, hwc2_display_t d, int32_t *t) {
    TR("getDisplayType", "t=%p", (void *)t);
    (void)dev; CHK_DISPLAY(d); if (t) *t = HWC2_DISPLAY_TYPE_PHYSICAL; return HWC2_ERROR_NONE;
}
static int32_t h_getDozeSupport(hwc2_device_t *dev, hwc2_display_t d, int32_t *s) {
    TR("getDozeSupport", "s=%p", (void *)s);
    (void)dev; CHK_DISPLAY(d); if (s) *s = 0; return HWC2_ERROR_NONE;
}
static int32_t h_getHdrCapabilities(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, int32_t *t, float *mx, float *avg, float *mn) {
    TR("getHdrCapabilities", "n=%p t=%p mx=%p avg=%p mn=%p", (void *)n, (void *)t, (void *)mx, (void *)avg, (void *)mn);
    (void)dev; (void)t; CHK_DISPLAY(d);
     
 
    if (probe_rw(n, 4)) *n = 0;
    if (mx != (float *)(void *)n && probe_rw(mx, 4)) *mx = 0;
    if (avg != (float *)(void *)n && probe_rw(avg, 4)) *avg = 0;
    if (mn != (float *)(void *)n && probe_rw(mn, 4)) *mn = 0;
    return HWC2_ERROR_NONE;
}
static int32_t h_getReleaseFences(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, hwc2_layer_t *ls, int32_t *fences) {
    TR("getReleaseFences", "n=%p ls=%p f=%p", (void *)n, (void *)ls, (void *)fences);
    (void)dev; CHK_DISPLAY(d);
    if (!n) return HWC2_ERROR_BAD_PARAMETER;
    pthread_mutex_lock(&g_lock);
    if (g_vecmode != 0 && ls && vec_is_empty_rw(n) && probe_rw(ls, sizeof(struct mtk_vec)) && vec_is_empty_rw(ls)) {
         
 
        hwc2_layer_t ids[MAX_LAYERS]; int32_t fds[MAX_LAYERS];
        uint32_t k = 0;
        for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used) { ids[k] = g_layers[i].id; fds[k] = -1; k++; }
        int32_t r = vec_fill((struct mtk_vec *)(void *)n, ids, sizeof(ids[0]), k);
        if (!r) r = vec_fill((struct mtk_vec *)(void *)ls, fds, sizeof(fds[0]), k);
        pthread_mutex_unlock(&g_lock);
        return r;
    }
    uint32_t cnt = 0;
    int usable = ls && fences && *n > 0 && probe_rw(ls, (size_t)*n * sizeof(*ls)) && probe_rw(fences, (size_t)*n * sizeof(*fences));
    for (int i = 0; i < MAX_LAYERS; i++) if (g_layers[i].used) {
        if (usable && cnt < *n) { ls[cnt] = g_layers[i].id; fences[cnt] = -1; }
        cnt++;
    }
    pthread_mutex_unlock(&g_lock);
    if (!usable) *n = cnt; else if (cnt < *n) *n = cnt;
    return HWC2_ERROR_NONE;
}
static int32_t h_presentDisplay(hwc2_device_t *dev, hwc2_display_t d, int32_t *fence) {
    (void)dev; CHK_DISPLAY(d);
    if (fence) *fence = -1;
    return HWC2_ERROR_NONE;
}
static int32_t h_presentDisplay_real(hwc2_device_t *dev, hwc2_display_t d, int32_t *fence) {
    TR("presentDisplay", "fence=%p", (void *)fence);
    int32_t e = h_presentDisplay(dev, d, fence);
    if (e) return e;
    pthread_mutex_lock(&g_lock);
    buffer_handle_t h = g_client_target; int on = (g_power != HWC2_POWER_MODE_OFF);
    pthread_mutex_unlock(&g_lock);
    if (on) present_buffer(h);
    return HWC2_ERROR_NONE;
}
static int32_t h_setActiveConfig(hwc2_device_t *dev, hwc2_display_t d, hwc2_config_t c) {
    (void)dev; CHK_DISPLAY(d); return c == 1 ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}
static int32_t h_setClientTarget(hwc2_device_t *dev, hwc2_display_t d, buffer_handle_t t, int32_t acq, int32_t ds, hwc_region_t dmg) {
    TR("setClientTarget", "t=%p acq=%d ds=%d", (const void *)t, (int)acq, (int)ds);
    (void)dev; (void)ds; (void)dmg; CHK_DISPLAY(d);
    wait_fence(acq);
    pthread_mutex_lock(&g_lock); g_client_target = t; pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t h_setColorTransform(hwc2_device_t *dev, hwc2_display_t d, const float *m, int32_t hint) {
    (void)dev; (void)m; (void)hint; CHK_DISPLAY(d); return HWC2_ERROR_NONE;
}
static int32_t h_getPerFrameMetadataKeys(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, int32_t *k) {
    TR("getPerFrameMetadataKeys", "n=%p k=%p", (void *)n, (void *)k);
    (void)dev; (void)k; CHK_DISPLAY(d); if (n) *n = 0; return HWC2_ERROR_NONE;
}
static int32_t h_setOutputBuffer(hwc2_device_t *dev, hwc2_display_t d, buffer_handle_t b, int32_t f) {
    (void)dev; (void)b; (void)f; return d == 0 ? HWC2_ERROR_UNSUPPORTED : HWC2_ERROR_BAD_DISPLAY;
}
static int32_t h_setPowerMode(hwc2_device_t *dev, hwc2_display_t d, int32_t m) {
    TR("setPowerMode", "mode=%d", (int)m);
    (void)dev; CHK_DISPLAY(d);
    if (m < 0 || m > 4) return HWC2_ERROR_BAD_PARAMETER;
    pthread_mutex_lock(&g_lock); g_power = m; pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t h_getReadbackBufferAttributes(hwc2_device_t *dev, hwc2_display_t d, int32_t *f, int32_t *ds) {
    (void)dev; (void)f; (void)ds; (void)d; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getReadbackBufferFence(hwc2_device_t *dev, hwc2_display_t d, int32_t *f) {
    (void)dev; (void)f; (void)d; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_setReadbackBuffer(hwc2_device_t *dev, hwc2_display_t d, buffer_handle_t b, int32_t f) {
    (void)dev; (void)b; (void)d; close_fence(f); return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_setVsyncEnabled(hwc2_device_t *dev, hwc2_display_t d, int32_t e) {
    TR("setVsyncEnabled", "en=%d", (int)e);
    (void)dev; CHK_DISPLAY(d);
    if (e != HWC2_VSYNC_ENABLE && e != HWC2_VSYNC_DISABLE) return HWC2_ERROR_BAD_PARAMETER;
    pthread_mutex_lock(&g_lock); g_vsync_on = (e == HWC2_VSYNC_ENABLE); pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}
static int32_t h_validateDisplay(hwc2_device_t *dev, hwc2_display_t d, uint32_t *nTypes, uint32_t *nReqs) {
    TR("validateDisplay", "nTypes=%p nReqs=%p", (void *)nTypes, (void *)nReqs);
    (void)dev; CHK_DISPLAY(d);
    pthread_mutex_lock(&g_lock);
    g_changed_n = 0;
    for (int i = 0; i < MAX_LAYERS; i++)
        if (g_layers[i].used && g_layers[i].type != HWC2_COMPOSITION_CLIENT)
            g_changed_id[g_changed_n++] = g_layers[i].id;
    if (nTypes) *nTypes = (uint32_t)g_changed_n;
    if (nReqs) *nReqs = 0;
    g_validated = 1;
    int32_t r = g_changed_n ? HWC2_ERROR_HAS_CHANGES : HWC2_ERROR_NONE;
    pthread_mutex_unlock(&g_lock);
    return r;
}
static int32_t h_setCursorPosition(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, int32_t x, int32_t y) {
    (void)dev; (void)x; (void)y; CHK_LAYER(d, l); return HWC2_ERROR_NONE;
}
static int32_t h_setLayerBuffer(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, buffer_handle_t b, int32_t acq) {
    TR("setLayerBuffer", "l=%u b=%p acq=%d", (unsigned)l, (const void *)b, (int)acq);
    (void)dev; (void)b; if (d != 0) { close_fence(acq); return HWC2_ERROR_BAD_DISPLAY; }
    close_fence(acq); CHK_LAYER(d, l); return HWC2_ERROR_NONE;
}
static int32_t h_setLayerCompositionType(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, int32_t t) {
    (void)dev; CHK_DISPLAY(d);
    pthread_mutex_lock(&g_lock);
    struct layer *ly = find_layer(l);
    if (ly) ly->type = t;
    pthread_mutex_unlock(&g_lock);
    return ly ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_LAYER;
}
static int32_t h_setLayerPlaneAlpha(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, float a) {
    (void)dev; (void)a; CHK_LAYER(d, l); return HWC2_ERROR_NONE;
}

 
#define LAYER_NOP(NAME, ...) \
    static int32_t NAME(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, __VA_ARGS__) \
    { (void)dev; CHK_LAYER(d, l); return HWC2_ERROR_NONE; }
LAYER_NOP(h_setLayerSurfaceDamage, hwc_region_t v)
LAYER_NOP(h_setLayerBlendMode, int32_t v)
LAYER_NOP(h_setLayerColor, hwc_color_t v)
LAYER_NOP(h_setLayerFloatColor, hwc_float_color_t v)
LAYER_NOP(h_setLayerDataspace, int32_t v)
LAYER_NOP(h_setLayerDisplayFrame, hwc_rect_t v)
LAYER_NOP(h_setLayerSidebandStream, const native_handle_t *v)
LAYER_NOP(h_setLayerSourceCrop, hwc_frect_t v)
LAYER_NOP(h_setLayerTransform, int32_t v)
LAYER_NOP(h_setLayerVisibleRegion, hwc_region_t v)
LAYER_NOP(h_setLayerZOrder, uint32_t v)
LAYER_NOP(h_setLayerColorTransform, const float *v)
static int32_t h_setLayerPerFrameMetadata(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, uint32_t n, const int32_t *k, const float *m) {
    (void)dev; (void)n; (void)k; (void)m; CHK_LAYER(d, l); return HWC2_ERROR_NONE;
}
static int32_t h_setLayerPerFrameMetadataBlobs(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, uint32_t n, const int32_t *k, const uint32_t *s, const uint8_t *m) {
    (void)dev; (void)n; (void)k; (void)s; (void)m; CHK_LAYER(d, l); return HWC2_ERROR_NONE;
}
static int32_t h_setLayerGenericMetadata(hwc2_device_t *dev, hwc2_display_t d, hwc2_layer_t l, uint32_t kl, const char *k, bool mand, uint32_t vl, const uint8_t *v) {
    (void)dev; (void)d; (void)l; (void)kl; (void)k; (void)mand; (void)vl; (void)v; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getLayerGenericMetadataKey(hwc2_device_t *dev, uint32_t idx, uint32_t *len, char *key, bool *mand) {
     
    (void)dev; (void)idx; (void)len; (void)key; (void)mand;
    return HWC2_ERROR_UNSUPPORTED;
}

 
static int32_t h_getDisplayIdentificationData(hwc2_device_t *dev, hwc2_display_t d, uint8_t *port, uint32_t *n, uint8_t *data) {
    (void)dev; (void)port; (void)n; (void)data; (void)d; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getDisplayCapabilities(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, uint32_t *caps) {
    TR("getDisplayCapabilities", "n=%p caps=%p", (void *)n, (void *)caps);
    (void)dev; (void)caps; CHK_DISPLAY(d); if (n) *n = 0; return HWC2_ERROR_NONE;
}
static int32_t h_getDisplayedContentSamplingAttributes(hwc2_device_t *dev, hwc2_display_t d, int32_t *f, int32_t *ds, uint8_t *c) {
    (void)dev; (void)d; (void)f; (void)ds; (void)c; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_setDisplayedContentSamplingEnabled(hwc2_device_t *dev, hwc2_display_t d, int32_t e, uint8_t m, uint64_t mf) {
    (void)dev; (void)d; (void)e; (void)m; (void)mf; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getDisplayedContentSample(hwc2_device_t *dev, hwc2_display_t d, uint64_t mf, uint64_t ts, uint64_t *fc, int32_t ss[4], uint64_t *s[4]) {
    (void)dev; (void)d; (void)mf; (void)ts; (void)fc; (void)ss; (void)s; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getDisplayBrightnessSupport(hwc2_device_t *dev, hwc2_display_t d, bool *s) {
    (void)dev; CHK_DISPLAY(d); if (s) *s = false; return HWC2_ERROR_NONE;
}
static int32_t h_setDisplayBrightness(hwc2_device_t *dev, hwc2_display_t d, float b) {
    (void)dev; (void)d; (void)b; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getDisplayConnectionType(hwc2_device_t *dev, hwc2_display_t d, uint32_t *t) {
    (void)dev; CHK_DISPLAY(d); if (t) *t = HWC2_DISPLAY_CONNECTION_TYPE_INTERNAL; return HWC2_ERROR_NONE;
}
static int32_t h_getDisplayVsyncPeriod(hwc2_device_t *dev, hwc2_display_t d, hwc2_vsync_period_t *p) {
    (void)dev; CHK_DISPLAY(d); if (p) *p = VSYNC_NS; return HWC2_ERROR_NONE;
}
static int32_t h_setActiveConfigWithConstraints(hwc2_device_t *dev, hwc2_display_t d, hwc2_config_t c,
        hwc_vsync_period_change_constraints_t *cons, hwc_vsync_period_change_timeline_t *tl) {
    (void)dev; (void)cons; CHK_DISPLAY(d);
    if (c != 1) return HWC2_ERROR_BAD_CONFIG;
    struct timespec t; now_ts(&t);
    if (tl) {
        tl->newVsyncAppliedTimeNanos = (int64_t)t.tv_sec * 1000000000LL + (int64_t)t.tv_nsec;
        tl->refreshRequired = 0; tl->refreshTimeNanos = 0;
    }
    return HWC2_ERROR_NONE;
}
static int32_t h_setAutoLowLatencyMode(hwc2_device_t *dev, hwc2_display_t d, bool on) {
    (void)dev; (void)d; (void)on; return HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getSupportedContentTypes(hwc2_device_t *dev, hwc2_display_t d, uint32_t *n, uint32_t *t) {
    TR("getSupportedContentTypes", "n=%p t=%p", (void *)n, (void *)t);
    (void)dev; (void)t; CHK_DISPLAY(d); if (n) *n = 0; return HWC2_ERROR_NONE;
}
static int32_t h_setContentType(hwc2_device_t *dev, hwc2_display_t d, int32_t t) {
    (void)dev; CHK_DISPLAY(d); return t == HWC2_CONTENT_TYPE_NONE ? HWC2_ERROR_NONE : HWC2_ERROR_UNSUPPORTED;
}
static int32_t h_getClientTargetProperty(hwc2_device_t *dev, hwc2_display_t d, hwc_client_target_property_t *p) {
    TR("getClientTargetProperty", "p=%p", (void *)p);
    (void)dev; CHK_DISPLAY(d); if (p) { p->pixelFormat = HAL_PIXEL_FORMAT_RGBA_8888; p->dataspace = 0; } return HWC2_ERROR_NONE;
}

 
 
 
 
 
#define VSTUB(N) \
    static int32_t h_vendor_##N(uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3, uint32_t a4, uint32_t a5, uint32_t a6, uint32_t a7) { \
        TR("vendor" #N, "(%08x %08x %08x %08x | %08x %08x %08x %08x)", a0, a1, a2, a3, a4, a5, a6, a7); \
        return HWC2_ERROR_UNSUPPORTED; }
 
 

 
static int32_t h_vendor_79(hwc2_device_t *dev, hwc2_display_t d, int64_t ts, int32_t interval) {
    TR("vendor79 setExpectedPresentTime", "d=%u ts=%lld iv=%d", (unsigned)d, (long long)ts, (int)interval);
    (void)dev; (void)ts; (void)interval;
    CHK_DISPLAY(d);
    return HWC2_ERROR_NONE;
}

 
static int32_t h_vendor_81(hwc2_device_t *dev, hwc2_display_t d, uint32_t slots, hwc2_layer_t *out) {
    TR("vendor81 createLayer", "d=%u slots=%u out=%p", (unsigned)d, (unsigned)slots, (void *)out);
    (void)dev; (void)slots;
    return create_layer_common(d, out);
}

 
static int32_t h_vendor_85(hwc2_device_t *dev, hwc2_display_t d, uint32_t count) {
    TR("vendor85 setClientTargetSlotCount", "d=%u count=%u", (unsigned)d, (unsigned)count);
    (void)dev; (void)count;
    CHK_DISPLAY(d);
    return HWC2_ERROR_NONE;
}

 
 
 
 
static int32_t h_vendor_86(hwc2_device_t *dev, hwc2_display_t d, uint32_t slot, uint32_t cached, buffer_handle_t buf, int32_t acq) {
    TR("vendor86 setClientTarget", "d=%u slot=%u cached=%u buf=%p acq=%d", (unsigned)d, (unsigned)slot, (unsigned)cached, (const void *)buf, (int)acq);
    (void)dev;
    if (d != 0) { close_fence(acq); return HWC2_ERROR_BAD_DISPLAY; }
    wait_fence(acq);
    pthread_mutex_lock(&g_lock);
    buffer_handle_t use = NULL;
    if (buf && !cached) { if (slot < MAX_SLOTS) g_ct_slot[slot] = buf; use = buf; }
    else if (slot < MAX_SLOTS) use = g_ct_slot[slot];
    if (!use) use = buf;                        
    if (use) g_client_target = use;             
    pthread_mutex_unlock(&g_lock);
    return HWC2_ERROR_NONE;
}

 
 
 
 
 
 
#define VSETTER(N, NAME) \
    static int32_t h_vendor_##N(uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3, uint32_t a4, uint32_t a5, uint32_t a6, uint32_t a7) { \
        TR("vendor" #N " " NAME, "(%08x %08x %08x %08x | %08x %08x %08x %08x)", a0, a1, a2, a3, a4, a5, a6, a7); \
        return HWC2_ERROR_NONE; }
VSETTER(82, "setLayerBuffer?") VSETTER(83, "setLayerBrightness?") VSETTER(84, "setLayerBlockingRegion?")

/* Everything else stays "unsupported", with a trace of its first calls:
 *   78 getPhysicalDisplayOrientation, 80 getClientTargetProperty (3 out pointers of unknown type: left unsupported, the
 *   adapter falls back to RGBA_8888), 87 getOverlaySupport, 88 getHdrConversionCapabilities -- SurfaceFlinger tolerates all. */
VSTUB(71) VSTUB(72) VSTUB(73) VSTUB(74) VSTUB(75) VSTUB(76) VSTUB(77) VSTUB(78) VSTUB(80)
VSTUB(87) VSTUB(88) VSTUB(89) VSTUB(90) VSTUB(91)
static const hwc2_function_pointer_t g_vendor_stubs[21] = {
    (hwc2_function_pointer_t)h_vendor_71, (hwc2_function_pointer_t)h_vendor_72, (hwc2_function_pointer_t)h_vendor_73,
    (hwc2_function_pointer_t)h_vendor_74, (hwc2_function_pointer_t)h_vendor_75, (hwc2_function_pointer_t)h_vendor_76,
    (hwc2_function_pointer_t)h_vendor_77, (hwc2_function_pointer_t)h_vendor_78, (hwc2_function_pointer_t)h_vendor_79,
    (hwc2_function_pointer_t)h_vendor_80, (hwc2_function_pointer_t)h_vendor_81, (hwc2_function_pointer_t)h_vendor_82,
    (hwc2_function_pointer_t)h_vendor_83, (hwc2_function_pointer_t)h_vendor_84, (hwc2_function_pointer_t)h_vendor_85,
    (hwc2_function_pointer_t)h_vendor_86, (hwc2_function_pointer_t)h_vendor_87, (hwc2_function_pointer_t)h_vendor_88,
    (hwc2_function_pointer_t)h_vendor_89, (hwc2_function_pointer_t)h_vendor_90, (hwc2_function_pointer_t)h_vendor_91 };
#define F(id, fn) case id: return (hwc2_function_pointer_t)(fn)
static hwc2_function_pointer_t dev_getFunction(struct hwc2_device *dev, int32_t desc) {
    (void)dev;
    LOGI("getFunction(%d)", (int)desc);
    switch (desc) {
    F(HWC2_FUNCTION_ACCEPT_DISPLAY_CHANGES, h_acceptDisplayChanges);
    F(HWC2_FUNCTION_CREATE_LAYER, h_createLayer);
    F(HWC2_FUNCTION_CREATE_VIRTUAL_DISPLAY, h_createVirtualDisplay);
    F(HWC2_FUNCTION_DESTROY_LAYER, h_destroyLayer);
    F(HWC2_FUNCTION_DESTROY_VIRTUAL_DISPLAY, h_destroyVirtualDisplay);
    F(HWC2_FUNCTION_DUMP, h_dump);
    F(HWC2_FUNCTION_GET_ACTIVE_CONFIG, h_getActiveConfig);
    F(HWC2_FUNCTION_GET_CHANGED_COMPOSITION_TYPES, h_getChangedCompositionTypes);
    F(HWC2_FUNCTION_GET_CLIENT_TARGET_SUPPORT, h_getClientTargetSupport);
    F(HWC2_FUNCTION_GET_COLOR_MODES, h_getColorModes);
    F(HWC2_FUNCTION_GET_DISPLAY_ATTRIBUTE, h_getDisplayAttribute);
    F(HWC2_FUNCTION_GET_DISPLAY_CONFIGS, h_getDisplayConfigs);
    F(HWC2_FUNCTION_GET_DISPLAY_NAME, h_getDisplayName);
    F(HWC2_FUNCTION_GET_DISPLAY_REQUESTS, h_getDisplayRequests);
    F(HWC2_FUNCTION_GET_DISPLAY_TYPE, h_getDisplayType);
    F(HWC2_FUNCTION_GET_DOZE_SUPPORT, h_getDozeSupport);
    F(HWC2_FUNCTION_GET_HDR_CAPABILITIES, h_getHdrCapabilities);
    F(HWC2_FUNCTION_GET_MAX_VIRTUAL_DISPLAY_COUNT, h_getMaxVirtualDisplayCount);
    F(HWC2_FUNCTION_GET_RELEASE_FENCES, h_getReleaseFences);
    F(HWC2_FUNCTION_PRESENT_DISPLAY, h_presentDisplay_real);
    F(HWC2_FUNCTION_REGISTER_CALLBACK, h_registerCallback);
    F(HWC2_FUNCTION_SET_ACTIVE_CONFIG, h_setActiveConfig);
    F(HWC2_FUNCTION_SET_CLIENT_TARGET, h_setClientTarget);
    F(HWC2_FUNCTION_SET_COLOR_MODE, h_setColorMode);
    F(HWC2_FUNCTION_SET_COLOR_TRANSFORM, h_setColorTransform);
    F(HWC2_FUNCTION_SET_CURSOR_POSITION, h_setCursorPosition);
    F(HWC2_FUNCTION_SET_LAYER_BLEND_MODE, h_setLayerBlendMode);
    F(HWC2_FUNCTION_SET_LAYER_BUFFER, h_setLayerBuffer);
    F(HWC2_FUNCTION_SET_LAYER_COLOR, h_setLayerColor);
    F(HWC2_FUNCTION_SET_LAYER_COMPOSITION_TYPE, h_setLayerCompositionType);
    F(HWC2_FUNCTION_SET_LAYER_DATASPACE, h_setLayerDataspace);
    F(HWC2_FUNCTION_SET_LAYER_DISPLAY_FRAME, h_setLayerDisplayFrame);
    F(HWC2_FUNCTION_SET_LAYER_PLANE_ALPHA, h_setLayerPlaneAlpha);
    F(HWC2_FUNCTION_SET_LAYER_SIDEBAND_STREAM, h_setLayerSidebandStream);
    F(HWC2_FUNCTION_SET_LAYER_SOURCE_CROP, h_setLayerSourceCrop);
    F(HWC2_FUNCTION_SET_LAYER_SURFACE_DAMAGE, h_setLayerSurfaceDamage);
    F(HWC2_FUNCTION_SET_LAYER_TRANSFORM, h_setLayerTransform);
    F(HWC2_FUNCTION_SET_LAYER_VISIBLE_REGION, h_setLayerVisibleRegion);
    F(HWC2_FUNCTION_SET_LAYER_Z_ORDER, h_setLayerZOrder);
    F(HWC2_FUNCTION_SET_OUTPUT_BUFFER, h_setOutputBuffer);
    F(HWC2_FUNCTION_SET_POWER_MODE, h_setPowerMode);
    F(HWC2_FUNCTION_SET_VSYNC_ENABLED, h_setVsyncEnabled);
    F(HWC2_FUNCTION_VALIDATE_DISPLAY, h_validateDisplay);
     
    F(HWC2_FUNCTION_SET_LAYER_FLOAT_COLOR, h_setLayerFloatColor);
    F(HWC2_FUNCTION_SET_LAYER_PER_FRAME_METADATA, h_setLayerPerFrameMetadata);
    F(HWC2_FUNCTION_GET_PER_FRAME_METADATA_KEYS, h_getPerFrameMetadataKeys);
    F(HWC2_FUNCTION_SET_READBACK_BUFFER, h_setReadbackBuffer);
    F(HWC2_FUNCTION_GET_READBACK_BUFFER_ATTRIBUTES, h_getReadbackBufferAttributes);
    F(HWC2_FUNCTION_GET_READBACK_BUFFER_FENCE, h_getReadbackBufferFence);
    F(HWC2_FUNCTION_GET_RENDER_INTENTS, h_getRenderIntents);
    F(HWC2_FUNCTION_SET_COLOR_MODE_WITH_RENDER_INTENT, h_setColorModeWithRenderIntent);
    F(HWC2_FUNCTION_GET_DATASPACE_SATURATION_MATRIX, h_getDataspaceSaturationMatrix);
     
    F(HWC2_FUNCTION_GET_DISPLAY_IDENTIFICATION_DATA, h_getDisplayIdentificationData);
    F(HWC2_FUNCTION_GET_DISPLAY_CAPABILITIES, h_getDisplayCapabilities);
    F(HWC2_FUNCTION_SET_LAYER_COLOR_TRANSFORM, h_setLayerColorTransform);
    F(HWC2_FUNCTION_GET_DISPLAYED_CONTENT_SAMPLING_ATTRIBUTES, h_getDisplayedContentSamplingAttributes);
    F(HWC2_FUNCTION_SET_DISPLAYED_CONTENT_SAMPLING_ENABLED, h_setDisplayedContentSamplingEnabled);
    F(HWC2_FUNCTION_GET_DISPLAYED_CONTENT_SAMPLE, h_getDisplayedContentSample);
    F(HWC2_FUNCTION_SET_LAYER_PER_FRAME_METADATA_BLOBS, h_setLayerPerFrameMetadataBlobs);
    F(HWC2_FUNCTION_GET_DISPLAY_BRIGHTNESS_SUPPORT, h_getDisplayBrightnessSupport);
    F(HWC2_FUNCTION_SET_DISPLAY_BRIGHTNESS, h_setDisplayBrightness);
     
    F(HWC2_FUNCTION_GET_DISPLAY_CONNECTION_TYPE, h_getDisplayConnectionType);
    F(HWC2_FUNCTION_GET_DISPLAY_VSYNC_PERIOD, h_getDisplayVsyncPeriod);
    F(HWC2_FUNCTION_SET_ACTIVE_CONFIG_WITH_CONSTRAINTS, h_setActiveConfigWithConstraints);
    F(HWC2_FUNCTION_SET_AUTO_LOW_LATENCY_MODE, h_setAutoLowLatencyMode);
    F(HWC2_FUNCTION_GET_SUPPORTED_CONTENT_TYPES, h_getSupportedContentTypes);
    F(HWC2_FUNCTION_SET_CONTENT_TYPE, h_setContentType);
    F(HWC2_FUNCTION_GET_CLIENT_TARGET_PROPERTY, h_getClientTargetProperty);
    F(HWC2_FUNCTION_SET_LAYER_GENERIC_METADATA, h_setLayerGenericMetadata);
    F(HWC2_FUNCTION_GET_LAYER_GENERIC_METADATA_KEY, h_getLayerGenericMetadataKey);
    default:
        if (desc >= 71 && desc <= 91) return g_vendor_stubs[desc - 71];
        return NULL;
    }
}

static void dev_getCapabilities(struct hwc2_device *dev, uint32_t *outCount, int32_t *outCaps) {
    (void)dev; (void)outCaps; *outCount = 0;
}

 
static int dev_close(struct hw_device_t *d) { (void)d; return 0; }

static hwc2_device_t g_device;

static int hwc_open(const struct hw_module_t *module, const char *name, struct hw_device_t **device) {
    if (!name || strcmp(name, HWC_HARDWARE_COMPOSER)) return -22;
    pthread_mutex_lock(&g_lock);
    if (!g_thread_started) {
        load_config();
        memset(&g_device, 0, sizeof(g_device));
        g_device.common.tag = HARDWARE_DEVICE_TAG;
        g_device.common.version = HWC_DEVICE_API_VERSION_2_0;
        g_device.common.module = (struct hw_module_t *)module;
        g_device.common.close = dev_close;
        g_device.getCapabilities = dev_getCapabilities;
        g_device.getFunction = dev_getFunction;
        g_thread_started = 1;
        pthread_mutex_unlock(&g_lock);
        LOGI("hwc2fb open: %dx%d -> %s (%dbpp)", g_w, g_h, g_fbpath, g_fbbpp);
        pthread_t th;
        pthread_create(&th, NULL, worker, NULL);
        pthread_detach(th);
    } else pthread_mutex_unlock(&g_lock);
    *device = &g_device.common;
    return 0;
}

static struct hw_module_methods_t hwc_methods = { .open = hwc_open };

EXPORT hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = HWC_MODULE_API_VERSION_0_1,
    .hal_api_version = HARDWARE_HAL_API_VERSION,
    .id = HWC_HARDWARE_MODULE_ID,
    .name = "HWC2 framebuffer composer",
    .author = "hwc2fb",
    .methods = &hwc_methods,
};
