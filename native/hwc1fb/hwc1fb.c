 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#include <stddef.h>
#include <stdint.h>

extern int open(const char *path, int flags, ...);
extern int close(int fd);
extern int ioctl(int fd, unsigned long req, ...);
extern int pthread_create(long *t, const void *attr, void *(*fn)(void *), void *arg);
struct timespec32 { long tv_sec, tv_nsec; };            
extern int clock_gettime(int clk, struct timespec32 *ts);
extern int nanosleep(const struct timespec32 *req, struct timespec32 *rem);    

#define EXPORT __attribute__((visibility("default")))
#define CLOCK_MONOTONIC 1
#define O_RDONLY 0
#define FBIOGET_VSCREENINFO 0x4600
#define EINVAL_ 22

#define HARDWARE_MODULE_TAG 0x48574d54u                
#define HARDWARE_DEVICE_TAG 0x48574454u                
#define HWC_DEVICE_API_VERSION_1_1 0x01010001u         
#define HWC_MODULE_API_VERSION_0_1 0x0001u

enum { HWC_BACKGROUND_LAYER_SUPPORTED = 0, HWC_VSYNC_PERIOD = 1, HWC_DISPLAY_TYPES_SUPPORTED = 2 };
enum { HWC_DISPLAY_NO_ATTRIBUTE = 0, HWC_DISPLAY_VSYNC_PERIOD = 1, HWC_DISPLAY_WIDTH = 2, HWC_DISPLAY_HEIGHT = 3,
       HWC_DISPLAY_DPI_X = 4, HWC_DISPLAY_DPI_Y = 5 };
enum { HWC_EVENT_VSYNC = 0 };

struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {                                      
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    uint32_t reserved[25];
};
struct hw_device {                                      
    uint32_t tag, version;
    struct hw_module *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device *dev);
};

struct layer {                                          
    int32_t type;
    uint32_t hints, flags;
    const void *handle;
    uint32_t transform;
    int32_t blending;
    int32_t crop[4], frame[4];
    uint32_t visible_n; const void *visible;
    int acquire_fence, release_fence;
    uint8_t plane_alpha, pad[3];
    int32_t reserved[24 - 19];
};
struct contents {                                       
    int retire_fence;
    void *dpy, *sur;
    uint32_t flags;
    size_t n;
    struct layer layers[];
};
struct procs {
    void (*invalidate)(const struct procs *p);
    void (*vsync)(const struct procs *p, int disp, int64_t ts);
    void (*hotplug)(const struct procs *p, int disp, int connected);
};
struct hwc {                                            
    struct hw_device common;
    int (*prepare)(struct hwc *d, size_t n, struct contents **c);
    int (*set)(struct hwc *d, size_t n, struct contents **c);
    int (*event_control)(struct hwc *d, int disp, int event, int enabled);
    int (*blank)(struct hwc *d, int disp, int blank);
    int (*query)(struct hwc *d, int what, int *value);
    void (*register_procs)(struct hwc *d, const struct procs *p);
    void (*dump)(struct hwc *d, char *buf, int len);
    int (*get_configs)(struct hwc *d, int disp, uint32_t *configs, size_t *n);
    int (*get_attributes)(struct hwc *d, int disp, uint32_t config, const uint32_t *attrs, int32_t *values);
    void *reserved_proc[4];
};
_Static_assert(sizeof(struct hw_module) == 128, "hw_module_t");
_Static_assert(sizeof(struct hw_device) == 64, "hw_device_t");
_Static_assert(sizeof(struct layer) == 96, "hwc_layer_1_t");
_Static_assert(__builtin_offsetof(struct contents, layers) == 20, "hwc_display_contents_1_t");

struct fb_var {                                         
    uint32_t xres, yres, xres_virtual, yres_virtual, xoffset, yoffset, bits_per_pixel, grayscale;
    uint32_t color[4][3];                               
    uint32_t nonstd, activate, height, width, accel_flags;
    uint32_t pixclock, left_margin, right_margin, upper_margin, lower_margin, hsync_len, vsync_len;
    uint32_t sync, vmode, rotate, colorspace, reserved[4];
};
_Static_assert(sizeof(struct fb_var) == 160, "fb_var_screeninfo");

#define DEFAULT_PERIOD_NS 16666667

static struct {
    int w, h;
    uint32_t period_ns, xdpi1000, ydpi1000;
    const struct procs *procs;
    volatile int vsync_on, thread_started;
} g;

 
static uint32_t udiv(uint32_t n, uint32_t d) {
    uint32_t q = 0, r = 0;
    if (!d) return 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1u);
        if (r >= d) { r -= d; q |= 1u << i; }
    }
    return q;
}

 
static void read_fb(void) {
    g.w = 540; g.h = 960; g.period_ns = DEFAULT_PERIOD_NS; g.xdpi1000 = g.ydpi1000 = 160000;
    int fd = open("/dev/graphics/fb0", O_RDONLY);
    if (fd < 0) fd = open("/dev/fb0", O_RDONLY);
    if (fd < 0) return;
    struct fb_var v;
    for (size_t i = 0; i < sizeof v; i++) ((uint8_t *)&v)[i] = 0;
    if (ioctl(fd, FBIOGET_VSCREENINFO, &v) == 0 && v.xres && v.yres) {
        g.w = (int)v.xres; g.h = (int)v.yres;
         
        uint32_t pix = (v.upper_margin + v.lower_margin + v.yres) * (v.left_margin + v.right_margin + v.xres);
        uint32_t ns = (pix / 1000u) * v.pixclock + ((pix % 1000u) * v.pixclock) / 1000u;
        if (v.pixclock && ns >= 4000000u && ns <= 100000000u) g.period_ns = ns;    
        if (v.width && v.width < 10000 && v.height && v.height < 10000) {
            g.xdpi1000 = udiv(v.xres * 25400u, v.width);              
            g.ydpi1000 = udiv(v.yres * 25400u, v.height);
        }
    }
    close(fd);
}

 

static void *vsync_thread(void *arg) {
    (void)arg;
    struct timespec32 now;
    long long next = 0;                                 
    for (;;) {
        clock_gettime(CLOCK_MONOTONIC, &now);
        long long t = (long long)now.tv_sec * 1000000000LL + now.tv_nsec;
        if (!g.vsync_on) {                              
            struct timespec32 s = { 0, 8000000 };
            nanosleep(&s, 0);
            next = 0;
            continue;
        }
        if (!next || next < t - 4LL * g.period_ns) next = t;    
        next += g.period_ns;
        long long wait = next - t;                      
        if (wait > 0) {
            struct timespec32 s = { 0, (long)(wait > 999999999LL ? 999999999LL : wait) };
            nanosleep(&s, 0);
        }
        const struct procs *p = g.procs;
        if (g.vsync_on && p && p->vsync) p->vsync(p, 0, next);
    }
    return 0;
}

static void start_thread(void) {
    if (g.thread_started) return;
    g.thread_started = 1;
    long tid;
    if (pthread_create(&tid, 0, vsync_thread, 0) != 0) g.thread_started = 0;
}

 

static int hwc_prepare(struct hwc *d, size_t n, struct contents **c) {
    (void)d; (void)n; (void)c;
    return 0;                                           
}

static int hwc_set(struct hwc *d, size_t n, struct contents **c) {
    (void)d;
     
 
    for (size_t i = 0; i < n; i++) {
        struct contents *l = c ? c[i] : 0;
        if (!l) continue;
        for (size_t k = 0; k < l->n; k++) {
            if (l->layers[k].acquire_fence >= 0) { close(l->layers[k].acquire_fence); l->layers[k].acquire_fence = -1; }
            l->layers[k].release_fence = -1;
        }
        l->retire_fence = -1;
    }
    return 0;
}

static int hwc_event_control(struct hwc *d, int disp, int event, int enabled) {
    (void)d;
    if (event != HWC_EVENT_VSYNC || (enabled != 0 && enabled != 1)) return -EINVAL_;
    if (disp != 0) return -EINVAL_;
    g.vsync_on = enabled;
    return 0;
}

static int hwc_blank(struct hwc *d, int disp, int blank) { (void)d; (void)disp; (void)blank; return 0; }

static int hwc_query(struct hwc *d, int what, int *value) {
    (void)d;
    switch (what) {
    case HWC_BACKGROUND_LAYER_SUPPORTED: *value = 0; return 0;
    case HWC_VSYNC_PERIOD: *value = (int)g.period_ns; return 0;
    case HWC_DISPLAY_TYPES_SUPPORTED: *value = 1; return 0;         
    }
    return -EINVAL_;
}

static void hwc_register_procs(struct hwc *d, const struct procs *p) { (void)d; g.procs = p; }

static void hwc_dump(struct hwc *d, char *buf, int len) {
    (void)d;
    static const char s[] = "aemu hwc1fb: HWC 1.1, GLES composition only, software vsync thread\n";
    int i = 0;
    for (; i < len - 1 && s[i]; i++) buf[i] = s[i];
    if (len > 0) buf[i] = 0;
}

static int hwc_get_configs(struct hwc *d, int disp, uint32_t *configs, size_t *n) {
    (void)d;
    if (disp != 0) return -EINVAL_;                     
    if (n && *n >= 1 && configs) { configs[0] = 0; *n = 1; }
    return 0;
}

static int hwc_get_attributes(struct hwc *d, int disp, uint32_t config, const uint32_t *attrs, int32_t *values) {
    (void)d; (void)config;
    if (disp != 0) return -EINVAL_;
    for (int i = 0; attrs[i] != HWC_DISPLAY_NO_ATTRIBUTE; i++) {
        switch (attrs[i]) {
        case HWC_DISPLAY_VSYNC_PERIOD: values[i] = (int32_t)g.period_ns; break;
        case HWC_DISPLAY_WIDTH: values[i] = g.w; break;
        case HWC_DISPLAY_HEIGHT: values[i] = g.h; break;
        case HWC_DISPLAY_DPI_X: values[i] = (int32_t)g.xdpi1000; break;
        case HWC_DISPLAY_DPI_Y: values[i] = (int32_t)g.ydpi1000; break;
        default: values[i] = -1; break;
        }
    }
    return 0;
}

static int hwc_close(struct hw_device *dev) { (void)dev; return 0; }    

 

static struct hwc the_device;
extern struct hw_module HMI;                            

static int hwc_open(const struct hw_module *m, const char *id, struct hw_device **dev) {
    (void)m;
     
    const char want[] = "composer";
    for (int i = 0; i < 9; i++) if (id[i] != want[i]) return -EINVAL_;
    read_fb();
    the_device.common.tag = HARDWARE_DEVICE_TAG;
    the_device.common.version = HWC_DEVICE_API_VERSION_1_1;
    the_device.common.module = &HMI;
    the_device.common.close = hwc_close;
    the_device.prepare = hwc_prepare;
    the_device.set = hwc_set;
    the_device.event_control = hwc_event_control;
    the_device.blank = hwc_blank;
    the_device.query = hwc_query;
    the_device.register_procs = hwc_register_procs;
    the_device.dump = hwc_dump;
    the_device.get_configs = hwc_get_configs;
    the_device.get_attributes = hwc_get_attributes;
    start_thread();
    *dev = &the_device.common;
    return 0;
}

static struct hw_module_methods hwc_methods = { hwc_open };

EXPORT struct hw_module HMI = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = HWC_MODULE_API_VERSION_0_1,
    .hal_api_version = 0,
    .id = "hwcomposer",
    .name = "AEmulator framebuffer hwcomposer",
    .author = "AEmulator Plus",
    .methods = &hwc_methods,
};
