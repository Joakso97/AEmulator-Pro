 
 
 
#ifndef TRACKBALL_NODE
#define TRACKBALL_NODE "/dev/input/aemu-trackball"
#endif
#ifndef TRACKBALL_SOCKET
#define TRACKBALL_SOCKET "/dev/aemu_trackball"
#endif
struct tb_sockaddr { unsigned short family; char path[108]; };

static int tb_equal(const char *a, const char *b) {
    if (!a) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

 
static long trackball_open(const char *path, int flags) {
    if (!tb_equal(path, TRACKBALL_NODE)) return -4096;
    if (flags & 0100  ) return -22;
    struct tb_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    addr.family = 1;
    for (unsigned i = 0; i < sizeof(TRACKBALL_SOCKET); i++) addr.path[i] = TRACKBALL_SOCKET[i];
    long fd = sys3(281  , 1, 1 | (flags & 02000000), 0);
    if (fd < 0) return fd;
     
    long r = sys3(283  , fd, (long)&addr, sizeof(addr));
    if (r >= 0 && (flags & 04000)) r = sys3(55  , fd, 4  , 04000);
    if (r < 0) { sys3(6, fd, 0, 0); return r; }
    return fd;
}

static int trackball_fd(int fd) {
    struct tb_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    unsigned len = sizeof(addr);
    if (sys3(287  , fd, (long)&addr, (long)&len) < 0 || addr.family != 1) return 0;
     
    unsigned n = 0;
    while (n < sizeof(addr.path) && addr.path[n]) n++;
    const unsigned suffix = sizeof(TRACKBALL_SOCKET) - 1;
    if (n < suffix) return 0;
    for (unsigned i = 0; i < suffix; i++) if (addr.path[n - suffix + i] != TRACKBALL_SOCKET[i]) return 0;
    return 1;
}

static void tb_bit(unsigned char *buf, unsigned len, unsigned bit) {
    if (bit / 8 < len) buf[bit / 8] |= 1u << (bit % 8);
}

static int trackball_ioctl(unsigned req, void *arg) {
    unsigned nr = req & 255, len = (req >> 16) & 0x3fff;
    unsigned char *b = arg;
    if (nr == 0x90   || nr == 0xa0  ) return 0;
    if (!b) return fail(-14  );
    if (!(req & 0x80000000u)) return fail(-25  );
    if (nr == 1   && len == 4) { *(unsigned *)b = 0x10001; return 0; }
    if (nr == 2   && len == 8) {
        unsigned short *id = arg;
        id[0] = 6  ; id[1] = 0; id[2] = 0; id[3] = 1;
        return 0;
    }
    if (nr >= 6 && nr <= 8) {  
        const char *s = nr == 6 ? "AEmulator Trackball" : nr == 7 ? "aemu/trackball" : "sunset-trackball";
        unsigned i = 0;
        if (!len) return 0;
        while (i + 1 < len && s[i]) { b[i] = s[i]; i++; }
        b[i++] = 0;
        return (int)i;
    }
    if (nr == 9   || nr == 0x18   ||
        nr == 0x19 || nr == 0x1a || nr == 0x1b || (nr >= 0x20 && nr <= 0x3f)) {
        for (unsigned i = 0; i < len; i++) b[i] = 0;
        if (nr == 0x20) { tb_bit(b, len, 0); tb_bit(b, len, 1); tb_bit(b, len, 2); }
        if (nr == 0x21) tb_bit(b, len, 272  );
        if (nr == 0x22) { tb_bit(b, len, 0  ); tb_bit(b, len, 1  ); }
        return (int)len;
    }
    return fail(-25  );
}
