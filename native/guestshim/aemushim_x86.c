 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 

#define EXPORT __attribute__((visibility("default")))

#define NR_open          5
#define NR_ioctl        54
#define NR_openat      295
#define NR_readlink     85
#define NR_readlinkat  305
#define NR_socketcall  102
#define SC_GETSOCKNAME   6
#define SC_SETSOCKOPT   14
#define NR_prctl       172
#define NR_timerfd_create 322
#define NR_setxattr    226
#define NR_lsetxattr   227
#define NR_fsetxattr   228
#define NR_getxattr    229
#define NR_lgetxattr   230
#define NR_fgetxattr   231
#define ENODATA         61
#define ERANGE          34
#define ENOSYS          38
#define EOPNOTSUPP      95

extern int *__errno(void);

 
static inline long sys4(long nr, long a, long b, long c, long d)
{
    long r;
    __asm__ volatile("xchgl %%edi, %%ebx\n\tint $0x80\n\txchgl %%edi, %%ebx"
                     : "=a"(r), "+D"(a)
                     : "0"(nr), "c"(b), "d"(c), "S"(d)
                     : "memory");
    return r;
}

static inline long sys3(long nr, long a, long b, long c) { return sys4(nr, a, b, c, 0); }

 
static inline long sys5(long nr, long a, long b, long c, long d, long e)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\tmovl %2, %%ebx\n\tint $0x80\n\tpopl %%ebx"
                     : "=a"(r)
                     : "0"(nr), "r"(a), "c"(b), "d"(c), "S"(d), "D"(e)
                     : "memory");
    return r;
}

static int fail(long r) { *__errno() = (int)-r; return -1; }

static int starts_at(const char *s, int n, int at, const char *m, int mlen)
{
    if (at + mlen > n) return 0;
    for (int k = 0; k < mlen; k++) if (s[at + k] != m[k]) return 0;
    return 1;
}

 
static int strip_root(char *s, int n)
{
    static const char m[] = "/files/images/";
    for (int i = 0; i + 14 < n; i++) {
        if (!starts_at(s, n, i, m, 14)) continue;
        int j = i + 14;
        while (j < n && s[j] != '/') j++;
        if (j + 5 > n || s[j + 1] != 'r' || s[j + 2] != 'o' || s[j + 3] != 'o' || s[j + 4] != 't') return n;
        int from = j + 5, len = n - from;
        if (len <= 0) { s[0] = '/'; return 1; }
        for (int q = 0; q < len; q++) s[q] = s[from + q];
        return len;
    }
    return n;
}

static int whole(const char *s, int n, const char *m)
{
    int k = 0;
    while (k < n && m[k] && s[k] == m[k]) k++;
    return k == n && !m[k];
}

static long guest_fd_name_(char *buf, long n, unsigned long size)
{
    n = strip_root(buf, (int)n);
     
    int logfile = 0;
    {
        static const char m[] = "/files/images/";
        for (int i = 0; i + 14 < n && !logfile; i++) {
            if (!starts_at(buf, (int)n, i, m, 14)) continue;
            for (int j = i + 14; j + 5 <= n; j++)
                if (buf[j] == '/' && buf[j + 1] == 'r' && buf[j + 2] == 'u' && buf[j + 3] == 'n' && buf[j + 4] == '/') { logfile = 1; break; }
        }
    }
     
    if (n > 0 && whole(buf, (int)n, "/dev/__properties__")) logfile = 1;
    if (size >= 9 && (logfile || (n > 5 && buf[0] == 'p' && buf[1] == 'i' && buf[2] == 'p' && buf[3] == 'e' && buf[4] == ':'))) {
        static const char dn[] = "/dev/null";
        for (int m = 0; m < 9; m++) buf[m] = dn[m];
        return 9;
    }
     
     
     
    {
        static const char al[] = "/memfd:guest-alarm";
        if (size >= 9 && n >= 18 && starts_at(buf, (int)n, 0, al, 18)) {
            static const char dn[] = "/dev/null";
            for (int m = 0; m < 9; m++) buf[m] = dn[m];
            return 9;
        }
    }
     
    if (whole(buf, (int)n, "/dhd.owners")) {
        static const char as[] = "/system/framework/aemu-owners.jar";
        unsigned long m = 0;
        while (as[m] && m < size) { buf[m] = as[m]; m++; }
        n = (long)m;
    }
    return n;
}

 
static long guest_fd_name(char *buf, long n, unsigned long size)
{
    long r = guest_fd_name_(buf, n, size);
    for (long i = r; i < n; i++) buf[i] = 0;
    return r;
}

EXPORT long readlink(const char *path, char *buf, unsigned long size)
{
    long r = sys3(NR_readlink, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

EXPORT long readlinkat(int dirfd, const char *path, char *buf, unsigned long size)
{
    long r = sys4(NR_readlinkat, dirfd, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

struct sockaddr_un_ { unsigned short family; char path[108]; };

EXPORT int getsockname(int fd, void *addr, unsigned *len)
{
    unsigned long args[3] = { (unsigned long)fd, (unsigned long)addr, (unsigned long)len };
    long r = sys3(NR_socketcall, SC_GETSOCKNAME, (long)args, 0);
    if (r < 0) return fail(r);
    struct sockaddr_un_ *u = (struct sockaddr_un_ *)addr;
    if (u && len && *len > 2 && u->family == 1   && u->path[0]) {
        int n = (int)*len - 2;
        while (n > 0 && u->path[n - 1] == 0) n--;
        int m = strip_root(u->path, n);
        if (m != n) { u->path[m] = 0; *len = 2 + m + 1; }
    }
    return 0;
}

 
 
 
 
#include "vinput.h"

#define VI_O_LARGEFILE 0100000

EXPORT int open(const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100  ) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    long v = vinput_open(path, flags);
    if (v != -4096) return v < 0 ? fail(v) : (int)v;
    long r = sys3(NR_open, (long)path, flags | VI_O_LARGEFILE, mode);
    return r < 0 ? fail(r) : (int)r;
}
EXPORT int open64(const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100  ) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return open(path, flags, mode);
}
EXPORT int __open_2(const char *path, int flags) { return open(path, flags, 0); }

EXPORT int openat(int dirfd, const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100  ) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    long v = vinput_open(path, flags);
    if (v != -4096) return v < 0 ? fail(v) : (int)v;
    long r = sys4(NR_openat, dirfd, (long)path, flags | VI_O_LARGEFILE, mode);
    return r < 0 ? fail(r) : (int)r;
}
EXPORT int openat64(int dirfd, const char *path, int flags, ...)
{
    int mode = 0;
    if (flags & 0100  ) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return openat(dirfd, path, flags, mode);
}
EXPORT int __openat_2(int dirfd, const char *path, int flags) { return openat(dirfd, path, flags, 0); }

EXPORT int ioctl(int fd, int req, void *arg)
{
    if (((unsigned)req >> 8 & 255) == 'E') {
        int vk = vinput_fd(fd);
        if (vk) return vinput_ioctl(vk, (unsigned)req, arg);
    }
    long r = sys3(NR_ioctl, fd, req, (long)arg);
    return r < 0 ? fail(r) : (int)r;
}

 
 
 
 
 
 
static int same_name(const char *n, const char *want)
{
    if (!n) return 0;
    for (; *want; n++, want++) if (*n != *want) return 0;
    return !*n;
}

static long emu_get(const char *name, void *value, unsigned long size)
{
    static const char ctx[] = "u:object_r:system_data_file:s0";
    if (!same_name(name, "security.selinux")) return fail(-ENODATA);
    unsigned long m = sizeof ctx;                          
    if (!size) return (long)m;
    if (size < m) return fail(-ERANGE);
    for (unsigned long k = 0; k < m; k++) ((char *)value)[k] = ctx[k];
    return (long)m;
}

static long do_get(long nr, long target, const char *name, void *value, unsigned long size)
{
    long r = sys4(nr, target, (long)name, (long)value, (long)size);
    if (r >= 0) return r;
    if (r == -ENOSYS) return emu_get(name, value, size);
    return fail(r);
}

static int do_set(long nr, long target, const char *name, const void *value, unsigned long size, int flags)
{
    long r = sys5(nr, target, (long)name, (long)value, (long)size, flags);
    if (r >= 0 || r == -ENOSYS || r == -EOPNOTSUPP) return 0;
    return fail(r);
}

 
 
 
EXPORT long getxattr(const char *path, const char *name, void *value, unsigned long size)
{
    if (same_name(name, "user.default")) return 0;
    if (same_name(name, "user.serial")) return fail(-ENODATA);
    return do_get(NR_getxattr, (long)path, name, value, size);
}
EXPORT long lgetxattr(const char *path, const char *name, void *value, unsigned long size) { return do_get(NR_lgetxattr, (long)path, name, value, size); }
EXPORT long fgetxattr(int fd, const char *name, void *value, unsigned long size)           { return do_get(NR_fgetxattr, fd, name, value, size); }
EXPORT int setxattr(const char *p, const char *n, const void *v, unsigned long s, int fl)  { return do_set(NR_setxattr, (long)p, n, v, s, fl); }
EXPORT int lsetxattr(const char *p, const char *n, const void *v, unsigned long s, int fl) { return do_set(NR_lsetxattr, (long)p, n, v, s, fl); }
EXPORT int fsetxattr(int fd, const char *n, const void *v, unsigned long s, int fl)        { return do_set(NR_fsetxattr, fd, n, v, s, fl); }

 
 
 
EXPORT int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5)
{
    if (option == 22  ) return 0;
    long r = sys5(NR_prctl, option, (long)a2, (long)a3, (long)a4, (long)a5);
    return r >= 0 ? (int)r : fail(r);
}

 
 
 
EXPORT int setsockopt(int fd, int level, int name, const void *val, unsigned int len)
{
    if (level == 1   && name == 36  ) return 0;
    unsigned long args[5] = { (unsigned long)fd, (unsigned long)level, (unsigned long)name, (unsigned long)val, len };
    long r = sys3(NR_socketcall, SC_SETSOCKOPT, (long)args, 0);
    return r >= 0 ? (int)r : fail(r);
}

 
 
EXPORT int timerfd_create(int clock, int flags)
{
    if (clock == 8  ) clock = 0  ;
    else if (clock == 9  ) clock = 7  ;
    long r = sys3(NR_timerfd_create, clock, flags, 0);
    return r < 0 ? fail(r) : (int)r;
}
