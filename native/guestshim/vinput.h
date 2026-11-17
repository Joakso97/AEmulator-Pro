 
 
 
 
 
#ifndef VINPUT_H
#define VINPUT_H

#define VI_PAD_NODE "/dev/input/aemu-touchpad"
#define VI_PAD_SOCKET "/dev/aemu_touchpad"
#define VI_KBD_NODE "/dev/input/aemu-keyboard"
#define VI_KBD_SOCKET "/dev/aemu_keyboard"
#define VI_NONE 0
#define VI_PAD 1
#define VI_KBD 2
#define VI_PAD_MAX 4095

struct vi_sockaddr { unsigned short family; char path[108]; };

static int vi_equal(const char *a, const char *b) {
    if (!a) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

#if defined(__i386__)
 
static long vi_socketcall(long call, unsigned long *args) { return sys3(102  , call, (long)args, 0); }
static long vi_socket(long type) { unsigned long a[3] = { 1  , (unsigned long)type, 0 }; return vi_socketcall(1, a); }
static long vi_connect(long fd, const void *addr, unsigned long len) {
    unsigned long a[3] = { (unsigned long)fd, (unsigned long)addr, len };
    return vi_socketcall(3, a);
}
static long vi_getpeername(long fd, void *addr, unsigned *len) {
    unsigned long a[3] = { (unsigned long)fd, (unsigned long)addr, (unsigned long)len };
    return vi_socketcall(7, a);
}
#else
static long vi_socket(long type) { return sys3(281  , 1  , type, 0); }
static long vi_connect(long fd, const void *addr, unsigned long len) { return sys3(283  , fd, (long)addr, (long)len); }
static long vi_getpeername(long fd, void *addr, unsigned *len) { return sys3(287  , fd, (long)addr, (long)len); }
#endif

static const char *vi_socket_of(const char *path) {
    if (vi_equal(path, VI_PAD_NODE)) return VI_PAD_SOCKET;
    if (vi_equal(path, VI_KBD_NODE)) return VI_KBD_SOCKET;
    return 0;
}

 
static long vinput_open(const char *path, int flags) {
    const char *sock = vi_socket_of(path);
    if (!sock) return -4096;
    if (flags & 0100  ) return -22;
    struct vi_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    addr.family = 1;
    for (unsigned i = 0; sock[i]; i++) addr.path[i] = sock[i];
    long fd = vi_socket(1 | (flags & 02000000));
    if (fd < 0) return fd;
     
    long r = vi_connect(fd, &addr, sizeof(addr));
    if (r >= 0 && (flags & 04000)) r = sys3(55  , fd, 4  , 04000);
    if (r < 0) { sys3(6, fd, 0, 0); return r; }
    return fd;
}

 
static int vinput_fd(int fd) {
    struct vi_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    unsigned len = sizeof(addr);
    if (vi_getpeername(fd, &addr, &len) < 0 || addr.family != 1) return VI_NONE;
    unsigned n = 0;
    while (n < sizeof(addr.path) && addr.path[n]) n++;
    for (int kind = VI_PAD; kind <= VI_KBD; kind++) {
        const char *suffix = kind == VI_PAD ? VI_PAD_SOCKET : VI_KBD_SOCKET;
        unsigned sl = 0;
        while (suffix[sl]) sl++;
        if (n < sl) continue;
        unsigned i = 0;
        while (i < sl && addr.path[n - sl + i] == suffix[i]) i++;
        if (i == sl) return kind;
    }
    return VI_NONE;
}

static void vi_bit(unsigned char *buf, unsigned len, unsigned bit) {
    if (bit / 8 < len) buf[bit / 8] |= 1u << (bit % 8);
}

static int vinput_ioctl(int kind, unsigned req, void *arg) {
    unsigned nr = req & 255, len = (req >> 16) & 0x3fff;
    unsigned char *b = arg;
    if (nr == 0x90   || nr == 0xa0   || nr == 0x03  ) return 0;
    if (!b) return fail(-14  );
    if (!(req & 0x80000000u)) return fail(-25  );
    if (nr == 1   && len == 4) { *(unsigned *)b = 0x10001; return 0; }
    if (nr == 2   && len == 8) {
        unsigned short *id = arg;
        id[0] = 6  ; id[1] = 0; id[2] = (unsigned short)(kind == VI_PAD ? 2 : 3); id[3] = 1;
        return 0;
    }
    if (nr >= 6 && nr <= 8) {  
        const char *s = kind == VI_PAD ? (nr == 6 ? "AEmulator Touchpad" : nr == 7 ? "aemu/touchpad" : "aemu-touchpad")
                                       : (nr == 6 ? "AEmulator Keyboard" : nr == 7 ? "aemu/keyboard" : "aemu-keyboard");
        unsigned i = 0;
        if (!len) return 0;
        while (i + 1 < len && s[i]) { b[i] = s[i]; i++; }
        b[i++] = 0;
        return (int)i;
    }
    if (nr == 9   || nr == 0x18   || nr == 0x19 || nr == 0x1a || nr == 0x1b ||
        (nr >= 0x20 && nr <= 0x3f)) {
        for (unsigned i = 0; i < len; i++) b[i] = 0;
        if (nr == 9 && kind == VI_PAD) vi_bit(b, len, 0  );
        if (nr == 0x20) {  
            vi_bit(b, len, 0  ); vi_bit(b, len, 1  );
            if (kind == VI_PAD) vi_bit(b, len, 3  );
        }
        if (nr == 0x21) {  
            if (kind == VI_KBD) { for (unsigned k = 1; k < 249; k++) vi_bit(b, len, k); }
            else { vi_bit(b, len, 272  ); vi_bit(b, len, 273  ); vi_bit(b, len, 330  ); }
        }
        if (nr == 0x23 && kind == VI_PAD) {  
            vi_bit(b, len, 48); vi_bit(b, len, 53); vi_bit(b, len, 54); vi_bit(b, len, 57); vi_bit(b, len, 58);
        }
        return (int)len;
    }
    if (nr >= 0x40 && nr < 0x80 && kind == VI_PAD) {  
        int *a = arg;
        unsigned axis = nr - 0x40, words = len / 4;
        for (unsigned i = 0; i < words && i < 6; i++) a[i] = 0;
        if (words < 3) return fail(-22  );
        if (axis == 53 || axis == 54) a[2] = VI_PAD_MAX;
        else if (axis == 48 || axis == 58) a[2] = 255;
        else if (axis == 57) a[2] = 31;
        else return fail(-22  );
        return 0;
    }
    return fail(-25  );
}
#endif
