 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 

#define EXPORT __attribute__((visibility("default")))

typedef unsigned long ulong;
struct timeval_s { long tv_sec; long tv_usec; };
struct timespec_s { long tv_sec; long tv_nsec; };

 
EXPORT int mount(const char *src, const char *target, const char *type, ulong flags, const void *data) {
    (void)src; (void)target; (void)type; (void)flags; (void)data;
    return 0;
}
EXPORT int umount(const char *target) { (void)target; return 0; }
EXPORT int umount2(const char *target, int flags) { (void)target; (void)flags; return 0; }

 
EXPORT int swapon(const char *path, int flags) { (void)path; (void)flags; return 0; }
EXPORT int swapoff(const char *path) { (void)path; return 0; }
EXPORT int acct(const char *file) { (void)file; return 0; }

 
EXPORT int settimeofday(const struct timeval_s *tv, const void *tz) { (void)tv; (void)tz; return 0; }
EXPORT int clock_settime(int clk, const struct timespec_s *tp) { (void)clk; (void)tp; return 0; }
EXPORT int stime(const long *t) { (void)t; return 0; }

 
 
static long sys3(long n, long a, long b, long c);
static void power_request(const char *what, const char *arg) {
    char buf[96]; int n = 0;
    while (*what && n < 40) buf[n++] = *what++;
    if (arg && *arg) { buf[n++] = ','; while (*arg && n < 90) buf[n++] = *arg++; }
    long fd = sys3(5  , (long)"/dev/aemu_power", 0x241  , 0666);
    if (fd >= 0) { sys3(4  , fd, (long)buf, n); sys3(6  , fd, 0, 0); }
}
EXPORT int reboot(int cmd) {
    power_request(cmd == 0x4321FEDC   || cmd == (int)0xCDEF0123   ? "shutdown" : "reboot", 0);
    return 0;
}
EXPORT int __reboot(int m1, int m2, int cmd, void *arg) {
    (void)m1; (void)m2;
    if (cmd == (int)0xA1B2C3D4  ) power_request("reboot", (const char *)arg);
    else reboot(cmd);
    return 0;
}
EXPORT int android_reboot(int cmd, int flags, char *arg) {
    (void)flags;
    power_request(cmd == (int)0xDEAD0002 ? "shutdown" : "reboot", cmd == (int)0xDEAD0003 ? arg : 0);
    return 0;
}

 
EXPORT int init_module(void *img, ulong len, const char *params) { (void)img; (void)len; (void)params; return 0; }
EXPORT int delete_module(const char *name, int flags) { (void)name; (void)flags; return 0; }
EXPORT int klogctl(int type, char *buf, int len) { (void)type; (void)buf; (void)len; return 0; }

 

extern char **environ;

 
__attribute__((naked, noinline)) static long sys3(long n, long a, long b, long c) {
    __asm__ volatile(
        "push {r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; svc #0; pop {r7}; bx lr");
}
#define SYS_read 3
#define SYS_write 4
#define SYS_open 5
#define SYS_close 6
#define SYS_execve 11
#define SYS_access 33
#define ENOENT 2
#define E2BIG 7

extern int *__errno(void);

static int fail(long r) { *__errno() = (int)-r; return -1; }

#include "netmgr.h"

static long raw_execve(const char *path, char *const argv[], char *const envp[]) {
    return sys3(SYS_execve, (long)path, (long)argv, (long)envp);
}

#define MAXARGS 512

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    char head[256];
    long fd = sys3(SYS_open, (long)path, 0  , 0);
    if (fd >= 0) {
        long n = sys3(SYS_read, fd, (long)head, sizeof(head) - 1);
        sys3(SYS_close, fd, 0, 0);
        if (n > 2 && head[0] == '#' && head[1] == '!') {
            head[n] = 0;
             
            char *p = head + 2, *interp, *arg = 0;
            while (*p == ' ' || *p == 9) p++;
            interp = p;
            while (*p && *p != ' ' && *p != 9 && *p != 10 && *p != 13) p++;
            if (*p == ' ' || *p == 9) {
                *p++ = 0;
                while (*p == ' ' || *p == 9) p++;
                if (*p && *p != 10 && *p != 13) {
                    arg = p;
                    while (*p && *p != 10 && *p != 13) p++;
                    while (p > arg && (p[-1] == ' ' || p[-1] == 9)) p--;
                }
            }
            *p = 0;
            if (*interp) {
                char *nargv[MAXARGS];
                int k = 0;
                nargv[k++] = interp;
                if (arg) nargv[k++] = arg;
                nargv[k++] = (char *)path;
                if (argv && argv[0]) for (int i = 1; argv[i]; i++) {
                    if (k >= MAXARGS - 1) return fail(-E2BIG);
                    nargv[k++] = argv[i];
                }
                nargv[k] = 0;
                return fail(raw_execve(interp, nargv, envp));
            }
        }
    }
    return fail(raw_execve(path, argv, envp));
}

EXPORT int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

EXPORT int execvp(const char *file, char *const argv[]) {
    int slash = 0;
    for (const char *c = file; *c; c++) if (*c == '/') { slash = 1; break; }
    if (slash) return execve(file, argv, environ);
    const char *path = 0;
    for (char **e = environ; e && *e; e++) {
        const char *v = *e;
        if (v[0] == 'P' && v[1] == 'A' && v[2] == 'T' && v[3] == 'H' && v[4] == '=') { path = v + 5; break; }
    }
    if (!path) path = "/system/bin:/system/xbin:/vendor/bin:/sbin";
    char buf[512];
    int last = -ENOENT;
    while (1) {
        const char *end = path;
        while (*end && *end != ':') end++;
        int len = (int)(end - path), flen = 0;
        while (file[flen]) flen++;
        if (len > 0 && len + 1 + flen < (int)sizeof(buf)) {
            for (int i = 0; i < len; i++) buf[i] = path[i];
            buf[len] = '/';
            for (int i = 0; i <= flen; i++) buf[len + 1 + i] = file[i];
            if (sys3(SYS_access, (long)buf, 1  , 0) == 0) {
                execve(buf, argv, environ);
                last = -*__errno();
            }
        }
        if (!*end) break;
        path = end + 1;
    }
    return fail(last);
}

 
 
 
 
 
 
 
 
#define EROFS 30
#define AT_FDCWD (-100)
static int g_ro = -1;
static int ro_enabled(void) {
    if (g_ro < 0) {
        g_ro = 0;
        for (char **e = environ; e && *e; e++) {
            const char *v = *e, *k = "AEMU_RO_SYSTEM=1";
            int i = 0;
            while (k[i] && v[i] == k[i]) i++;
            if (!k[i]) { g_ro = 1; break; }
        }
    }
    return g_ro;
}
static int has(const char *s, const char *sub) {
    for (; *s; s++) {
        int i = 0;
        while (sub[i] && s[i] == sub[i]) i++;
        if (!sub[i]) return 1;
    }
    return 0;
}
static int ends(const char *s, const char *suf) {
    int n = 0, m = 0;
    while (s[n]) n++;
    while (suf[m]) m++;
    if (m > n) return 0;
    for (int i = 0; i < m; i++) if (s[n - m + i] != suf[i]) return 0;
    return 1;
}
static int ro_path(const char *p) {
    if (!p || !ro_enabled()) return 0;
    if (!(p[0] == '/' && p[1] == 's' && p[2] == 'y' && p[3] == 's' && p[4] == 't' && p[5] == 'e' && p[6] == 'm' && p[7] == '/')) return 0;
    return has(p, "/oat/") || has(p, "/framework/arm") || ends(p, ".odex") || ends(p, ".oat") || ends(p, ".art") || ends(p, ".vdex");
}
#define SYS_unlink 10
#define SYS_chmod 15
#define SYS_rename 38
#define SYS_rmdir 40
#define SYS_unlinkat 328
static int ret(long r) { return r < 0 ? fail(r) : (int)r; }
EXPORT int unlink(const char *p) { return ro_path(p) ? fail(-EROFS) : ret(sys3(SYS_unlink, (long)p, 0, 0)); }
EXPORT int rmdir(const char *p) { return ro_path(p) ? fail(-EROFS) : ret(sys3(SYS_rmdir, (long)p, 0, 0)); }
EXPORT int remove(const char *p) {
    if (ro_path(p)) return fail(-EROFS);
    long r = sys3(SYS_unlink, (long)p, 0, 0);
    if (r == -21  ) r = sys3(SYS_rmdir, (long)p, 0, 0);
    return ret(r);
}
EXPORT int rename(const char *a, const char *b) { return ro_path(a) || ro_path(b) ? fail(-EROFS) : ret(sys3(SYS_rename, (long)a, (long)b, 0)); }
EXPORT int chmod(const char *p, unsigned m) { return ro_path(p) ? fail(-EROFS) : ret(sys3(SYS_chmod, (long)p, (long)m, 0)); }
EXPORT int unlinkat(int fd, const char *p, int fl) {
    return (fd == AT_FDCWD || p[0] == '/') && ro_path(p) ? fail(-EROFS) : ret(sys3(SYS_unlinkat, fd, (long)p, fl));
}
 
 
 
 
 
 
 
static const char QT[] = "/proc/net/xt_qtaguid/";
static const char *qt_redirect(const char *path, char *buf, int n) {
    if (!path) return path;
     
 
    static const char BP[] = "/proc/bootprof";
    int b = 0;
    while (BP[b] && path[b] == BP[b]) b++;
    if (!BP[b] && !path[b]) return "/dev/null";
    int i = 0;
    while (QT[i] && path[i] == QT[i]) i++;
    if (QT[i]) {
         
        if (!path[i] && i == (int)sizeof(QT) - 2) return "/data/.aemu_qtaguid";
        return path;
    }
    const char *name = path + i;
    if (name[0] == 'c' && name[1] == 't' && name[2] == 'r' && name[3] == 'l' && !name[4]) return "/dev/null";
    const char *pre = "/data/.aemu_qtaguid/";
    int k = 0;
    while (pre[k] && k < n - 1) { buf[k] = pre[k]; k++; }
    for (int j = 0; name[j] && k < n - 1; j++) buf[k++] = name[j];
    buf[k] = 0;
    return buf;
}

 
 
 
 
 
 
 
 
 
 
typedef void AemuFile;
extern AemuFile *fdopen(int fd, const char *mode);

EXPORT AemuFile *fopen(const char *path, const char *mode) {
    if (!path || !mode) { fail(-22  ); return 0; }
    int flags;
    if (mode[0] == 'r') flags = 0  ;
    else if (mode[0] == 'w') flags = 01   | 0100   | 01000  ;
    else if (mode[0] == 'a') flags = 01   | 0100   | 02000  ;
    else { fail(-22  ); return 0; }
    for (const char *p = mode + 1; *p; p++) {
        if (*p == '+') flags = (flags & ~3) | 02  ;
        else if (*p == 'e') flags |= 02000000  ;
        else if (*p == 'x') flags |= 0200  ;
    }
    char b[128];
    long fd = sys3(SYS_open, (long)qt_redirect(path, b, sizeof(b)), flags | 0400000  , 0666);
    if (fd < 0) { fail(fd); return 0; }
    AemuFile *fp = fdopen((int)fd, mode);
    if (!fp) {
        int saved = *__errno();
        sys3(SYS_close, fd, 0, 0);
        *__errno() = saved;
        return 0;
    }
    if (mode[0] == 'a') sys3(19  , fd, 0, 2  );
    return fp;
}

EXPORT AemuFile *fopen64(const char *path, const char *mode) { return fopen(path, mode); }

EXPORT int access(const char *path, int mode) {
    char b[128];
    long r = sys3(SYS_access, (long)qt_redirect(path, b, sizeof(b)), mode, 0);
    return r < 0 ? fail(r) : 0;
}

 
 
 
 
 
 
 
static int oom_open(const char *path) {
    static const char P[] = "/proc/";
    int i = 0;
    while (P[i] && path[i] == P[i]) i++;
    if (P[i]) return -1;
    const char *pid = path + i;
    int n = 0;
    while (pid[n] >= '0' && pid[n] <= '9') n++;
    if (!n || n > 10 || pid[n] != '/') return -1;
    const char *f = pid + n + 1;
    static const char A[] = "oom_adj", S[] = "oom_score_adj";
    int ka = 0, ks = 0;
    while (A[ka] && f[ka] == A[ka]) ka++;
    while (S[ks] && f[ks] == S[ks]) ks++;
    char kind;
    if (!A[ka] && !f[ka]) kind = 'a';
    else if (!S[ks] && !f[ks]) kind = 's';
    else return -1;
    long fd = sys3(SYS_open, (long)"/dev/aemu_oom", 01   | 04000   | 02000000  , 0);
    if (fd < 0) fd = sys3(SYS_open, (long)"/dev/null", 01 | 02000000, 0);
    if (fd < 0) return -1;
    char h[16];
    int k = 0;
    h[k++] = '\n';
    for (int j = 0; j < n; j++) h[k++] = pid[j];
    h[k++] = ' '; h[k++] = kind; h[k++] = ' ';
    sys3(SYS_write, fd, (long)h, k);
    return (int)fd;
}

 
 
 
 
 
 
 
 
 
__attribute__((constructor)) static void hold_std_fds(void) {
    for (long fd = 0; fd < 3; fd++)
        if (sys3(55  , fd, 1  , 0) < 0) sys3(SYS_open, (long)"/dev/null", 2  , 0);
}

 
 
#include "bcmgpu.h"    
#include "adss.h"      

EXPORT int close(int fd) {
    long r = sys3(SYS_close, fd, 0, 0);
    if (r >= 0) bcmgpu_closed(fd);
    if (r >= 0) adss_closed(fd);
    if (r >= 0 && fd >= 0 && fd < 3) sys3(SYS_open, (long)"/dev/null", 2  , 0);
    return r >= 0 ? (int)r : (int)fail(r);
}

 
 
EXPORT long read(int fd, void *buf, unsigned long n) {
    long a = adss_read(fd, buf, n);
    if (a != -4096) return fail(a);
    long r = sys3(SYS_read, fd, (long)buf, (long)n);
    return r < 0 ? (long)fail(r) : r;
}

static char *g_argv0;
static unsigned long g_main_sp;
__attribute__((constructor)) static void find_argv(void) {
    g_main_sp = (unsigned long)__builtin_frame_address(0);
    char **e = environ;
    if (!e || e[-1]) return;
    long n = 0;
    char **a = e - 2;
     
    while (n < 64 && (unsigned long)a[0] > 4096) { a--; n++; }
    if (n > 0 && (long)a[0] == n) g_argv0 = a[1];
}
#define SYS_getpid 20
#define SYS_pipe2 359
 
static int proc_is(const char *path, const char *suffix) {
    static const char P[] = "/proc/";
    int i = 0;
    while (P[i] && path[i] == P[i]) i++;
    if (P[i]) return 0;
    const char *who = path + i;
    int n = 0;
    if (who[0] == 's' && who[1] == 'e' && who[2] == 'l' && who[3] == 'f') n = 4;
    else {
        long pid = 0;
        while (who[n] >= '0' && who[n] <= '9' && n < 10) pid = pid * 10 + (who[n++] - '0');
        if (!n || pid != sys3(SYS_getpid, 0, 0, 0)) return 0;
    }
    int k = 0;
    while (suffix[k] && who[n + k] == suffix[k]) k++;
    return !suffix[k] && !who[n + k];
}
static int cmdline_open(const char *path) {
    if (!g_argv0) return -1;
    if (!proc_is(path, "/cmdline")) return -1;
    int fds[2];
    if (sys3(SYS_pipe2, (long)fds, 02000000  , 0) < 0) return -1;
    long len = 0;
    while (len < 1024 && g_argv0[len]) len++;
    sys3(SYS_write, fds[1], (long)g_argv0, len + 1);
    sys3(SYS_close, fds[1], 0, 0);
    return fds[0];
}


 
 
 
 
 
 
#define SYS_lseek 19
#define SYS_memfd_create 385
static char g_maps_in[1 << 20], g_maps_out[(1 << 20) + 4096];
static volatile int g_maps_lock;
static unsigned long hexv(const char **pp) {
    const char *p = *pp; unsigned long v = 0;
    for (;; p++) {
        char c = *p;
        if (c >= '0' && c <= '9') v = v * 16 + (unsigned long)(c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (unsigned long)(c - 'a' + 10);
        else break;
    }
    *pp = p; return v;
}
static int maps_open(const char *path) {
    if (!g_main_sp || !proc_is(path, "/maps")) return -1;
    if (__sync_lock_test_and_set(&g_maps_lock, 1)) return -1;
    int res = -1;
    long fd = sys3(SYS_open, (long)path, 0400000, 0);
    if (fd < 0) { __sync_lock_release(&g_maps_lock); return -1; }
    long total = 0;
    for (;;) {
        long n = sys3(SYS_read, fd, (long)(g_maps_in + total), (long)sizeof(g_maps_in) - 1 - total);
        if (n < 0) { total = -1; break; }
        if (n == 0) break;
        total += n;
        if (total >= (long)sizeof(g_maps_in) - 1) { total = -1; break; }
    }
    sys3(SYS_close, fd, 0, 0);
    if (total <= 0) goto out;
    g_maps_in[total] = 0;
    int changed = 0, found = 0;
    long o = 0;
    char *p = g_maps_in;
    while (*p) {
        char *line = p, *e = p;
        while (*e && *e != 0x0a) e++;
        p = *e ? e + 1 : e;
        long len = e - line;
        const char *q = line;
        unsigned long s0 = hexv(&q), e0 = 0;
        if (*q == '-') { q++; e0 = hexv(&q); }
        int holds = s0 <= g_main_sp && g_main_sp < e0;
        int tagAt = -1;
        for (long i = 0; i + 7 <= len; i++)
            if (line[i] == '[' && line[i+1] == 's' && line[i+2] == 't' && line[i+3] == 'a' && line[i+4] == 'c' && line[i+5] == 'k' && line[i+6] == ']') { tagAt = (int)i; break; }
        if (holds) found = 1;
        long keep = len;
        int add = 0;
        if (tagAt >= 0 && !holds) { keep = tagAt; while (keep > 0 && line[keep-1] == ' ') keep--; changed = 1; }
        else if (tagAt < 0 && holds) { add = 1; changed = 1; }
        for (long i = 0; i < keep; i++) g_maps_out[o++] = line[i];
        if (add) { const char T[] = "                       [stack]"; for (int i = 0; T[i]; i++) g_maps_out[o++] = T[i]; }
        if (*e) g_maps_out[o++] = 0x0a;
        if (o > (long)sizeof(g_maps_out) - 128) { changed = 0; break; }
    }
    if (!changed || !found) goto out;
    long m = sys3(SYS_memfd_create, (long)"maps", 0, 0);
    if (m < 0) goto out;
    long w = 0;
    while (w < o) { long n = sys3(SYS_write, m, (long)(g_maps_out + w), o - w); if (n <= 0) break; w += n; }
    if (w != o) { sys3(SYS_close, m, 0, 0); goto out; }
    sys3(SYS_lseek, m, 0, 0);
    res = (int)m;
out:
    __sync_lock_release(&g_maps_lock);
    return res;
}

#include "trackball.h"
#include "vinput.h"

 
 
 
 
 
 
static unsigned long long udiv64(unsigned long long n, unsigned long d) {
    unsigned long long q = 0, r = 0;
    for (int i = 63; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d) { r -= d; q |= 1ULL << i; }
    }
    return q;
}
static unsigned long env_ulong(const char *key) {
    for (char **e = environ; e && *e; e++) {
        const char *v = *e;
        int i = 0;
        while (key[i] && v[i] == key[i]) i++;
        if (key[i] || v[i] != '=') continue;
        unsigned long x = 0;
        for (v += i + 1; *v >= '0' && *v <= '9'; v++) x = x * 10 + (unsigned long)(*v - '0');
        return x;
    }
    return 0;
}
static int meminfo_open(const char *path) {
    static const char M[] = "/proc/meminfo";
    int i = 0;
    while (M[i] && path[i] == M[i]) i++;
    if (M[i] || path[i]) return -1;
    unsigned long guest = env_ulong("AEMU_RAM_KB");
    if (!guest) return -1;
    char in[8192], out[8192 + 512];
    long fd = sys3(SYS_open, (long)path, 0400000, 0);
    if (fd < 0) return -1;
    long total = 0;
    for (;;) {
        long n = sys3(SYS_read, fd, (long)(in + total), (long)sizeof(in) - 1 - total);
        if (n <= 0) break;
        total += n;
        if (total >= (long)sizeof(in) - 1) break;
    }
    sys3(SYS_close, fd, 0, 0);
    if (total <= 0) return -1;
    in[total] = 0;
     
    unsigned long host = 0;
    const char *p = in;
    while (*p && (*p < '0' || *p > '9')) p++;
    while (*p >= '0' && *p <= '9') host = host * 10 + (unsigned long)(*p++ - '0');
    if (!host || guest >= host) return -1;
    long o = 0;
    p = in;
    while (*p && o < (long)sizeof(out) - 64) {
        const char *line = p, *e = p;
        while (*e && *e != 0x0a) e++;
        const char *d = line;
        while (d < e && (*d < '0' || *d > '9')) d++;
        const char *de = d;
        unsigned long long v = 0;
        while (de < e && *de >= '0' && *de <= '9') v = v * 10 + (unsigned long long)(*de++ - '0');
        int kb = de + 3 <= e && de[0] == ' ' && de[1] == 'k' && de[2] == 'B';
        if (d < e && kb) {
            unsigned long s = (unsigned long)udiv64(v * guest, host);
            char num[12]; int k = 0;
            do { num[k++] = (char)('0' + (int)(s % 10)); s /= 10; } while (s && k < 11);
             
            int extra = k - (int)(de - d);
            const char *pe = d;
            while (extra > 0 && pe - line >= 2 && pe[-1] == ' ' && pe[-2] == ' ') { pe--; extra--; }
            for (const char *c = line; c < pe; c++) out[o++] = *c;
            for (int j = (int)(de - d); j > k; j--) out[o++] = ' ';
            while (k) out[o++] = num[--k];
            for (const char *c = de; c < e; c++) out[o++] = *c;
        } else {
            for (const char *c = line; c < e; c++) out[o++] = *c;
        }
        if (*e) out[o++] = 0x0a;
        p = *e ? e + 1 : e;
    }
    long m = sys3(SYS_memfd_create, (long)"meminfo", 0, 0);
    if (m < 0) return -1;
    long w = 0;
    while (w < o) { long n = sys3(SYS_write, m, (long)(out + w), o - w); if (n <= 0) break; w += n; }
    sys3(SYS_lseek, m, 0, 0);
    return (int)m;
}

EXPORT int open(const char *path, int flags, ...) {
    char b[128];
    int mode = 0;
    if (flags & 0100  ) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    long tb = trackball_open(path, flags);
    if (tb != -4096) return tb < 0 ? fail(tb) : (int)tb;
    tb = vinput_open(path, flags);
    if (tb != -4096) return tb < 0 ? fail(tb) : (int)tb;
    tb = bcmgpu_open(path, flags);
    if (tb != -4096) return tb < 0 ? fail(tb) : (int)tb;
    tb = adss_open(path, flags);
    if (tb != -4096) return tb < 0 ? fail(tb) : (int)tb;
    if (path && (flags & 3) && path[0] == '/' && path[1] == 'p') {
        int fd = oom_open(path);
        if (fd >= 0) return fd;
    }
    if (path && !(flags & 3) && path[0] == '/' && path[1] == 'p') {
        int fd = cmdline_open(path);
        if (fd >= 0) return fd;
        fd = meminfo_open(path);
        if (fd >= 0) return fd;
        fd = maps_open(path);
        if (fd >= 0) return fd;
    }
    long r = sys3(SYS_open, (long)qt_redirect(path, b, sizeof(b)), flags | 0400000  , mode);
    return r < 0 ? fail(r) : (int)r;
}

EXPORT int __open_2(const char *path, int flags) { return open(path, flags); }
EXPORT int open64(const char *path, int flags, ...) {
    int mode = 0;
    if (flags & 0100) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return open(path, flags, mode);
}

 
 
 
 
 
 
#define RESERVED(n) EXPORT void _ZN7android10VectorImpl19reservedVectorImpl##n##Ev(void *self) { (void)self; } \
    EXPORT void _ZN7android16SortedVectorImpl25reservedSortedVectorImpl##n##Ev(void *self) { (void)self; }
RESERVED(1) RESERVED(2) RESERVED(3) RESERVED(4) RESERVED(5) RESERVED(6) RESERVED(7) RESERVED(8)

 
 
 
 
 
 
 
 
 
__attribute__((naked, noinline)) static long sys4(long n, long a, long b, long c, long d) {
    __asm__ volatile(
        "push {r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; ldr r3, [sp, #4]; svc #0; pop {r7}; bx lr");
}
#define SYS_nanosleep 162
#include "vibration.h"
#ifndef AEMU_NO_PROPERTY_CLIENT
#include "property-client.h"
#endif
EXPORT int openat(int dirfd, const char *path, int flags, ...) {
    int mode = 0;
    if (flags & 0100) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    long r = trackball_open(path, flags);
    if (r == -4096) r = vinput_open(path, flags);
    if (r == -4096) r = bcmgpu_open(path, flags);
    if (r == -4096) r = adss_open(path, flags);
    if (r == -4096) r = sys4(322  , dirfd, (long)path, flags | 0400000, mode);
    return r < 0 ? fail(r) : (int)r;
}
EXPORT int __openat_2(int dirfd, const char *path, int flags) { return openat(dirfd, path, flags); }
EXPORT int openat64(int dirfd, const char *path, int flags, ...) {
    int mode = 0;
    if (flags & 0100) {
        __builtin_va_list ap; __builtin_va_start(ap, flags); mode = __builtin_va_arg(ap, int); __builtin_va_end(ap);
    }
    return openat(dirfd, path, flags, mode);
}
#define SYS_rt_sigprocmask 175
#define SIG_SETMASK 2
#define EINTR 4

EXPORT int sigsuspend(const unsigned long *mask) {
    unsigned long want[2] = { mask ? mask[0] : 0, 0 }, old[2] = { 0, 0 };
    sys4(SYS_rt_sigprocmask, SIG_SETMASK, (long)want, (long)old, 8);
    struct timespec_s ts = { 0, 5 * 1000 * 1000 };
    sys3(SYS_nanosleep, (long)&ts, 0, 0);
    sys4(SYS_rt_sigprocmask, SIG_SETMASK, (long)old, 0, 8);
    *__errno() = EINTR;
    return -1;
}

 
 
 
 
 
 
 
#define SYS_rt_sigaction 174
#define SA_SIGINFO_ 4
#define SA_RESTORER_ 0x04000000
struct bionic_sigaction { void *handler; unsigned long mask; int flags; void (*restorer)(void); };
struct kernel_sigaction { void *handler; unsigned long flags; void (*restorer)(void); unsigned long mask[2]; };
static struct bionic_sigaction guest_act[32];

static int wrapped(int sig) { return sig == 4   || sig == 5   || sig == 7   || sig == 8   || sig == 11  ; }

static void fault_trampoline(int sig, void *info, void *uc) {
    struct bionic_sigaction *a = &guest_act[sig & 31];
    if (a->flags & SA_SIGINFO_) ((void (*)(int, void *, void *))a->handler)(sig, info, uc);
    else ((void (*)(int))a->handler)(sig);
     
    ulong *pc = (ulong *)((char *)uc + 20 + 4 * 18);
    ulong *cpsr = pc + 1;
    if (*pc & 1) { *pc &= ~1UL; *cpsr |= 0x20; }
}

EXPORT int sigaction(int sig, const struct bionic_sigaction *act, struct bionic_sigaction *old) {
    struct kernel_sigaction k, ko;
    struct kernel_sigaction *kp = 0;
    int wrap = wrapped(sig) && act && act->handler != (void *)0 && act->handler != (void *)1;
    if (act) {
        k.handler = act->handler; k.flags = (unsigned long)act->flags; k.restorer = act->restorer;
        k.mask[0] = act->mask; k.mask[1] = 0;
        if (wrap) { k.handler = (void *)fault_trampoline; k.flags |= SA_SIGINFO_; }
        kp = &k;
    }
    long r = sys4(SYS_rt_sigaction, sig, (long)kp, (long)&ko, 8);
    if (r < 0) return fail(r);
    if (old) {
        if (ko.handler == (void *)fault_trampoline) *old = guest_act[sig & 31];
        else { old->handler = ko.handler; old->mask = ko.mask[0]; old->flags = (int)ko.flags; old->restorer = ko.restorer; }
    }
    if (wrap) guest_act[sig & 31] = *act;
    return 0;
}


 
 
 
 
 
#define SYS_tgkill 268
 
 
 
 
 
EXPORT int setrlimit(int resource, const void *rlim) {
    long r = sys3(75  , resource, (long)rlim, 0);
    if (r == -1  ) return 0;
    return r < 0 ? fail(r) : 0;
}

EXPORT int tgkill(int tgid, int tid, int sig) {
    if (sig == 33  ) { *__errno() = 22; return -1; }
    long r = sys3(SYS_tgkill, tgid, tid, sig);
    return r < 0 ? fail(r) : 0;
}

 
 
 
 
 
 
 
 
#define BINDER_WRITE_READ_ 0xc0186201
#define BC_TRANSACTION_ 0x40286300
#define BR_TRANSACTION_ 0x80287202
#define BR_REPLY_ 0x80287203
#define BR_DEAD_BINDER_ 0x8004720f
#define TF_ONE_WAY_ 1
#define SYS_ioctl 54
#define SYS_gettid 224
struct bwr { long write_size, write_consumed; ulong write_buffer; long read_size, read_consumed; ulong read_buffer; };
static volatile int waiting_tid[64];

static int wait_slot(int tid, int add) {
    for (int k = 0; k < 64; k++) if (waiting_tid[k] == tid) { if (!add) waiting_tid[k] = 0; return 1; }
    if (add) for (int k = 0; k < 64; k++) if (__sync_bool_compare_and_swap(&waiting_tid[k], 0, tid)) return 1;
    return 0;
}

 
 
static unsigned char g_held[1024];
static volatile int g_held_len, g_held_lock;
static void held_lock(void) { while (!__sync_bool_compare_and_swap(&g_held_lock, 0, 1)) sys3(158  , 0, 0, 0); }
static void held_unlock(void) { __sync_lock_release(&g_held_lock); }

 
 
static int is_player_notify(const unsigned char *td) {
    const unsigned char *d = (const unsigned char *)(ulong)*(const unsigned *)(td + 32);
    unsigned size = *(const unsigned *)(td + 24);
    const char *want = "android.media.IMediaPlayerClient";
    if (!d || size < 8 + 64) return 0;
    if (*(const unsigned *)(d + 4) != 32) return 0;
    for (int i = 0; i < 32; i++) if (d[8 + 2 * i] != (unsigned char)want[i] || d[9 + 2 * i]) return 0;
     
    int msg = *(const int *)(d + 76);
    return msg != 1 && msg != 4;
}

 
 
 
 
 
 
 
static void pin_service(const unsigned char *td) {
    if (*(const unsigned *)td != 0 || *(const unsigned *)(td + 8) != 3  ) return;
    const unsigned char *d = (const unsigned char *)(ulong)*(const unsigned *)(td + 32);
    const unsigned *offs = (const unsigned *)(ulong)*(const unsigned *)(td + 36);
    unsigned size = *(const unsigned *)(td + 24), n = *(const unsigned *)(td + 28) / 4;
    if (!d || !offs) return;
    for (unsigned i = 0; i < n; i++) {
        if (offs[i] + 16 > size) continue;
        const unsigned *o = (const unsigned *)(d + offs[i]);
        if (o[0] != 0x73622a85   || !o[2]) continue;
        volatile int *refs = (volatile int *)(ulong)o[2];
        __sync_fetch_and_add(&refs[0], 1);
        __sync_fetch_and_add(&refs[1], 1);
    }
}

 
static int iface_is(const unsigned char *td, const char *want, int n) {
    const unsigned char *d = (const unsigned char *)(ulong)*(const unsigned *)(td + 32);
    unsigned size = *(const unsigned *)(td + 24);
    if (!d || size < 8 + 2 * (unsigned)n) return 0;
    if (*(const unsigned *)(d + 4) != (unsigned)n) return 0;
    for (int i = 0; i < n; i++) if (d[8 + 2 * i] != (unsigned char)want[i] || d[9 + 2 * i]) return 0;
    return 1;
}
 
static volatile int g_bind_state, g_bind_tid;

 
 
 
 
 
 
 
#define BC_FREE_BUFFER_ 0x40046303
#define OW_MAX 64
static struct { unsigned node, buf, ticket; int tid; } g_ow[OW_MAX];    
static struct { unsigned node, issued; } g_own[32];
static volatile int g_ow_lock;
static void ow_lock(void) { while (!__sync_bool_compare_and_swap(&g_ow_lock, 0, 1)) sys3(158  , 0, 0, 0); }
static void ow_unlock(void) { __sync_lock_release(&g_ow_lock); }

static void ow_freed(unsigned buf) {
    ow_lock();
    for (int i = 0; i < OW_MAX; i++) if (g_ow[i].buf == buf) g_ow[i].buf = 0;
    ow_unlock();
}
 
static int ow_take(unsigned node, unsigned buf, int tid) {
    int slot = -1, n = -1;
    ow_lock();
    for (int i = 0; i < 32; i++) if (g_own[i].node == node) { n = i; break; }
    if (n < 0) {
         
        for (int i = 0; i < 32 && n < 0; i++) {
            int busy = 0;
            for (int k = 0; k < OW_MAX; k++) if (g_ow[k].buf && g_ow[k].node == g_own[i].node) busy = 1;
            if (!busy) { n = i; g_own[i].node = node; g_own[i].issued = 0; }
        }
    }
    if (n >= 0) for (int i = 0; i < OW_MAX; i++) if (!g_ow[i].buf) {
        g_ow[i].node = node; g_ow[i].buf = buf; g_ow[i].ticket = g_own[n].issued++; g_ow[i].tid = tid;
        slot = i;
        break;
    }
    ow_unlock();
    return slot;
}
static int ow_must_wait(int slot, int tid) {
    int r = 0;
    ow_lock();
    for (int i = 0; i < OW_MAX; i++)
        if (g_ow[i].buf && g_ow[i].node == g_ow[slot].node && g_ow[i].tid != tid && g_ow[i].ticket < g_ow[slot].ticket) r = 1;
    ow_unlock();
    return r;
}

static void ow_drop_older(int slot) {
    ow_lock();
    for (int i = 0; i < OW_MAX; i++)
        if (g_ow[i].buf && g_ow[i].node == g_ow[slot].node && g_ow[i].ticket < g_ow[slot].ticket) g_ow[i].buf = 0;
    ow_unlock();
}

static int is_waiting(int tid) { for (int k = 0; k < 64; k++) if (waiting_tid[k] == tid) return 1; return 0; }

 
 
 
 
 
EXPORT int cacheflush(long start, long end, long flags) {
    if (end > start && end - start < (1L << 22)) {
        unsigned long s = (unsigned long)start & ~4095UL, e = ((unsigned long)end + 4095UL) & ~4095UL;
        sys3(125  , (long)s, (long)(e - s), 7  );
    }
    long r = sys3(0xf0002  , start, end, flags);
    return r < 0 ? fail(r) : 0;
}

 
 
static long bwr_raw(int fd, struct bwr *b) {
    long r = sys3(SYS_ioctl, fd, BINDER_WRITE_READ_, (long)b);
    for (int tries = 0; r == -107   && tries < 25; tries++) {
        struct timespec_s ts = { 0, 80 * 1000 * 1000 };
        sys3(162  , (long)&ts, 0, 0);
        r = sys3(SYS_ioctl, fd, BINDER_WRITE_READ_, (long)b);
    }
    return r;
}

 
 
 
 
 
 
 
 
 
 
#define BC_REPLY_ 0x40286301
#define BR_TRANSACTION_COMPLETE_ 0x7206
#define BT_SEGS 3
struct bt_seg { int level, len; unsigned char d[192]; };
struct bt {
    volatile int tid;
    int np, hd, owed, last_sync;  
    unsigned char ph[16];         
    struct bt_seg seg[BT_SEGS];
};
static struct bt g_bt[64];

static struct bt *bt_get(int tid) {
    for (int k = 0; k < 64; k++) if (g_bt[k].tid == tid) return &g_bt[k];
    for (int k = 0; k < 64; k++) if (__sync_bool_compare_and_swap(&g_bt[k].tid, 0, tid)) {
        struct bt *t = &g_bt[k];
        t->np = t->hd = t->owed = 0;
        for (int i = 0; i < BT_SEGS; i++) t->seg[i].len = 0;
        return t;
    }
    return 0;
}
static int is_reply_cmd(unsigned cmd) { return cmd == BR_REPLY_ || cmd == 0x7205   || cmd == 0x7211  ; }
static struct bt_seg *bt_seg_at(struct bt *t, int level) {
    for (int i = 0; i < BT_SEGS; i++) if (t->seg[i].len && t->seg[i].level == level) return &t->seg[i];
    return 0;
}
static int bt_stash(struct bt *t, int level, const unsigned char *d, long len) {
    if (len <= 0 || len > (long)sizeof t->seg[0].d || bt_seg_at(t, level)) return 0;
    for (int i = 0; i < BT_SEGS; i++) if (!t->seg[i].len) {
        for (long k = 0; k < len; k++) t->seg[i].d[k] = d[k];
        t->seg[i].level = level; t->seg[i].len = (int)len;
        return 1;
    }
    return 0;
}
 
 
static void bt_read(struct bt *t, unsigned char *rb, long from, long *end, int hold_reply) {
    for (long p = from; p + 4 <= *end;) {
        unsigned cmd = *(unsigned *)(rb + p);
        long len = 4 + ((cmd >> 16) & 0x3fff);
        if (p + len > *end) break;
        if (is_reply_cmd(cmd)) {
            if (hold_reply && bt_stash(t, t->np, rb + p, *end - p)) { *end = p; return; }
             
            if (cmd != BR_REPLY_ && t->owed > 0) { t->owed--; if (t->last_sync && t->np > 0) t->np--; }
            else if (t->np > 0) t->np--;
        } else if (cmd == BR_TRANSACTION_COMPLETE_) {
            if (t->owed > 0) t->owed--;
        } else if (cmd == BR_TRANSACTION_) {
            if (!(*(unsigned *)(rb + p + 4 + 12) & TF_ONE_WAY_)) t->hd++;
            else if (t->np > 0 && p + len < *end && bt_stash(t, t->np, rb + p + len, *end - p - len)) { *end = p + len; return; }
        }
        p += len;
    }
}
static int bt_only_noop(const unsigned char *rb, long from, long end) {
    for (long p = from; p + 4 <= end;) {
        unsigned cmd = *(const unsigned *)(rb + p);
        if (cmd != 0x720c  ) return 0;
        p += 4 + ((cmd >> 16) & 0x3fff);
    }
    return 1;
}
static long bwr_ordered(int fd, struct bwr *b, int tid) {
    struct bt *t = bt_get(tid);
    if (!t) return bwr_raw(fd, b);
    unsigned char *rb = (unsigned char *)b->read_buffer;
    int ntx = 0, sync_tx = 0, nrep = 0;
    long w0 = b->write_consumed;
    for (long p = w0; p + 4 <= b->write_size;) {
        unsigned cmd = *(unsigned *)(b->write_buffer + p);
        if (cmd == BC_TRANSACTION_) { ntx++; if (p + 20 <= b->write_size && !(*(unsigned *)(b->write_buffer + p + 4 + 12) & TF_ONE_WAY_)) sync_tx = 1; }
        else if (cmd == BC_REPLY_) nrep++;
        p += 4 + ((cmd >> 16) & 0x3fff);
    }
    long start = b->read_consumed, r;
     
    if (sync_tx && t->np > 0 && t->np <= (int)sizeof t->ph && t->hd == t->ph[t->np - 1] && b->read_size > start) {
        struct bt_seg *s;
        while (!(s = bt_seg_at(t, t->np))) {
            struct bwr q = *b;
            q.write_size = q.write_consumed = 0;
            r = bwr_raw(fd, &q);
            if (r < 0) return r;
            long end = q.read_consumed;
            bt_read(t, rb, start, &end, 1);
             
            if (end > start && !bt_only_noop(rb, start, end)) { b->read_consumed = end; return 0; }
        }
    }
    struct bt_seg *s = bt_seg_at(t, t->np);
    if (s && !ntx && !nrep && !t->owed && b->read_size - start >= s->len) {
        if (b->write_size > b->write_consumed) {
            struct bwr q = *b;
            q.read_size = q.read_consumed;
            r = bwr_raw(fd, &q);
            if (r < 0) return r;
            b->write_consumed = q.write_consumed;
        }
        long end = start + s->len;
        for (int i = 0; i < s->len; i++) rb[start + i] = s->d[i];
        s->len = 0;
        bt_read(t, rb, start, &end, 0);
        b->read_consumed = end;
        r = 0;
    } else {
        r = bwr_raw(fd, b);
        if (r < 0) return r;
        for (long p = w0; p + 4 <= b->write_consumed && p + 4 <= b->write_size;) {    
            unsigned cmd = *(unsigned *)(b->write_buffer + p);
            if (cmd == BC_TRANSACTION_) {
                t->owed++;
                t->last_sync = !(*(unsigned *)(b->write_buffer + p + 4 + 12) & TF_ONE_WAY_);
                if (t->last_sync) {
                    if (t->np < (int)sizeof t->ph) t->ph[t->np] = (unsigned char)t->hd;
                    t->np++;
                }
            } else if (cmd == BC_REPLY_) { t->owed++; t->last_sync = 0; if (t->hd > 0) t->hd--; }
            p += 4 + ((cmd >> 16) & 0x3fff);
        }
        long end = b->read_consumed;
        bt_read(t, rb, start, &end, 0);
        b->read_consumed = end;
    }
    if (!t->np && !t->hd && !t->owed) {
        int any = 0;
        for (int i = 0; i < BT_SEGS; i++) any |= t->seg[i].len;
        if (!any) t->tid = 0;
    }
    return r;
}

EXPORT int ioctl(int fd, int req, void *arg) {
    { long bg = bcmgpu_ioctl(fd, (unsigned)req, arg); if (bg != -4096) return bg < 0 ? (int)fail(bg) : (int)bg; }
    { long ad = adss_ioctl(fd, (unsigned)req, arg); if (ad != -4096) return ad < 0 ? (int)fail(ad) : (int)ad; }
    if (((unsigned)req >> 8 & 255) == 'E' && trackball_fd(fd)) return trackball_ioctl((unsigned)req, arg);
    if (((unsigned)req >> 8 & 255) == 'E') { int vk = vinput_fd(fd); if (vk) return vinput_ioctl(vk, (unsigned)req, arg); }
    if ((unsigned)req != BINDER_WRITE_READ_ || !arg) {
        long r = sys3(SYS_ioctl, fd, req, (long)arg);
        return r < 0 ? fail(r) : (int)r;
    }
    struct bwr *b = (struct bwr *)arg;
    int tid = (int)sys3(SYS_gettid, 0, 0, 0);
    if (g_bind_state == 1 && g_bind_tid == tid) g_bind_state = 2;    
     
    for (long p = b->write_consumed; p + 4 <= b->write_size;) {
        unsigned cmd = *(unsigned *)(b->write_buffer + p);
        unsigned sz = (cmd >> 16) & 0x3fff;
        if (cmd == BC_TRANSACTION_ && p + 4 + 16 <= b->write_size && !(*(unsigned *)(b->write_buffer + p + 4 + 12) & TF_ONE_WAY_))
            wait_slot(tid, 1);
        if (cmd == BC_TRANSACTION_ && p + 4 + 40 <= b->write_size) pin_service((const unsigned char *)(b->write_buffer + p + 4));
        if (cmd == BC_FREE_BUFFER_ && p + 8 <= b->write_size) ow_freed(*(unsigned *)(b->write_buffer + p + 4));
        p += 4 + sz;
    }
    long start = b->read_consumed;
    long r = bwr_ordered(fd, b, tid);
    if (r < 0) return fail(r);
    int nested_oneway = 0, replied = 0, before_bind = 0;
    for (long p = start; p + 4 <= b->read_consumed;) {
        unsigned cmd = *(unsigned *)(b->read_buffer + p);
        unsigned sz = (cmd >> 16) & 0x3fff;
        if (cmd == BR_TRANSACTION_ && p + 4 + 40 <= b->read_consumed
            && iface_is((const unsigned char *)(b->read_buffer + p + 4), "android.app.IApplicationThread", 30)) {
            if (*(unsigned *)(b->read_buffer + p + 4 + 8) == 13  ) {
                if (!g_bind_state) { g_bind_tid = tid; g_bind_state = 1; }
            } else if (g_bind_state != 2 && !(g_bind_state == 1 && g_bind_tid == tid)) before_bind = 1;
        }
        if (cmd == BR_REPLY_ || cmd == 0x7205   || cmd == 0x7211  ) replied = 1;
        else if (cmd == BR_TRANSACTION_ && p + 4 + 16 <= b->read_consumed && (*(unsigned *)(b->read_buffer + p + 4 + 12) & TF_ONE_WAY_))
            nested_oneway = 1;
        p += 4 + sz;
    }
    int waiting = is_waiting(tid);
     
 
 
 
    if (waiting) {
        unsigned char *rb = (unsigned char *)b->read_buffer;
        held_lock();
        for (long p = start; p + 4 <= b->read_consumed;) {
            unsigned cmd = *(unsigned *)(rb + p);
            long len = 4 + ((cmd >> 16) & 0x3fff);
            if (cmd == BR_DEAD_BINDER_ && p + len <= b->read_consumed && g_held_len + len <= (long)sizeof g_held) {
                for (long i = 0; i < len; i++) g_held[g_held_len + i] = rb[p + i];
                g_held_len += (int)len;
                for (long i = p; i + len < b->read_consumed; i++) rb[i] = rb[i + len];
                b->read_consumed -= len;
                continue;
            }
            p += len;
        }
        held_unlock();
    }
    if (nested_oneway && !replied && waiting) {
         
 
 
        unsigned char *rb = (unsigned char *)b->read_buffer;
        held_lock();
        for (long p = start; p + 4 <= b->read_consumed;) {
            unsigned cmd = *(unsigned *)(rb + p);
            long len = 4 + ((cmd >> 16) & 0x3fff);
            if (cmd == BR_TRANSACTION_ && p + len <= b->read_consumed && (*(unsigned *)(rb + p + 4 + 12) & TF_ONE_WAY_)
                && is_player_notify(rb + p + 4) && g_held_len + len <= (long)sizeof g_held) {
                for (long i = 0; i < len; i++) g_held[g_held_len + i] = rb[p + i];
                g_held_len += (int)len;
                for (long i = p; i + len < b->read_consumed; i++) rb[i] = rb[i + len];
                b->read_consumed -= len;
                continue;
            }
            p += len;
        }
        held_unlock();
    }
     
 
    if (before_bind && waiting) {
        struct timespec_s ts = { 0, 5 * 1000 * 1000 };
        for (int i = 0; i < 600 && g_bind_state != 2; i++) sys3(162  , (long)&ts, 0, 0);
    } else
    if (nested_oneway && !replied && waiting && sys3(199  , 0, 0, 0) >= 10000) {
        struct timespec_s ts = { 0, 30 * 1000 * 1000 };
        sys3(162  , (long)&ts, 0, 0);
    }
    if (!waiting && g_held_len) {
        held_lock();
        if (g_held_len && b->read_consumed + g_held_len <= b->read_size) {
            unsigned char *rb = (unsigned char *)b->read_buffer;
            for (int i = 0; i < g_held_len; i++) rb[b->read_consumed + i] = g_held[i];
            b->read_consumed += g_held_len;
            g_held_len = 0;
        }
        held_unlock();
    }
    for (long p = start; p + 4 <= b->read_consumed;) {
        unsigned cmd = *(unsigned *)(b->read_buffer + p);
        const unsigned *td = (const unsigned *)(b->read_buffer + p + 4);
        if (cmd == BR_TRANSACTION_ && p + 4 + 40 <= b->read_consumed && (td[3] & TF_ONE_WAY_) && td[0] && td[8]) {
            int slot = ow_take(td[0], td[8], tid);
            struct timespec_s ts = { 0, 2 * 1000 * 1000 };
            int i = 0;
            for (; slot >= 0 && i < 2500 && ow_must_wait(slot, tid); i++) sys3(162  , (long)&ts, 0, 0);
            if (i == 2500) ow_drop_older(slot);    
        }
        p += 4 + ((cmd >> 16) & 0x3fff);
    }
    if (replied) wait_slot(tid, 0);
    return (int)r;
}

 
 
 
 
 
 
static int strip_root(char *s, int n) {
     
    for (int i = 0; i + 14 < n; i++) {
        const char *m = "/files/images/";
        int k = 0;
        while (k < 14 && s[i + k] == m[k]) k++;
        if (k < 14) continue;
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

#define SYS_readlink 85
#define SYS_readlinkat 332
#define SYS_getsockname 286
 
 
static long guest_fd_name_(char *buf, long n, unsigned long size);
 
 
static long guest_fd_name(char *buf, long n, unsigned long size) {
    long r = guest_fd_name_(buf, n, size);
    for (long i = r; i < n; i++) buf[i] = 0;
    return r;
}
static long guest_fd_name_(char *buf, long n, unsigned long size) {
    n = strip_root(buf, (int)n);
     
    int logfile = 0;
    for (int i = 0; i + 14 < n && !logfile; i++) {
        const char *m = "/files/images/";
        int k = 0;
        while (k < 14 && buf[i + k] == m[k]) k++;
        if (k == 14) for (int j = i + 14; j + 5 <= n; j++) if (buf[j] == '/' && buf[j + 1] == 'r' && buf[j + 2] == 'u' && buf[j + 3] == 'n' && buf[j + 4] == '/') { logfile = 1; break; }
    }
     
    { const char *pp = "/dev/__properties__"; int k = 0; while (k < n && pp[k] && buf[k] == pp[k]) k++; if (!pp[k]) logfile = 1; }
    if (size >= 9 && (logfile || (n > 5 && buf[0] == 'p' && buf[1] == 'i' && buf[2] == 'p' && buf[3] == 'e' && buf[4] == ':'))) {
        const char *dn = "/dev/null";
        for (int m = 0; m < 9; m++) buf[m] = dn[m];
        return 9;
    }
    const char *own = "/dhd.owners", *as = "/system/framework/aemu-owners.jar";
    int k = 0;
    while (k < n && own[k] && buf[k] == own[k]) k++;
    if (k == n && !own[k]) {
        int m = 0;
        while (as[m] && (unsigned long)m < size) { buf[m] = as[m]; m++; }
        n = m;
    }
    return n;
}
EXPORT long readlink(const char *path, char *buf, unsigned long size) {
    long r = sys3(SYS_readlink, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}
EXPORT long readlinkat(int dirfd, const char *path, char *buf, unsigned long size) {
    long r = sys4(SYS_readlinkat, dirfd, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}
struct sockaddr_un_ { unsigned short family; char path[108]; };
EXPORT int getsockname(int fd, void *addr, unsigned *len) {
    int offline = nm_getsockname(fd, addr, len);
    if (offline != -4096) return offline;
    long r = sys3(SYS_getsockname, fd, (long)addr, (long)len);
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

 
 
 
static long sys5(long n, long a, long b, long c, long d, long e);
 
 
 
EXPORT long getxattr(const char *p, const char *n, void *v, unsigned long s) {
    const char *want = "user.default";
    int k = 0;
    while (n && want[k] && n[k] == want[k]) k++;
    if (n && !want[k] && !n[k]) return 0;
     
 
    const char *ser = "user.serial";
    k = 0;
    while (n && ser[k] && n[k] == ser[k]) k++;
    if (n && !ser[k] && !n[k]) return fail(-61  );
    long r = sys4(229, (long)p, (long)n, (long)v, (long)s);
    return r >= 0 ? r : fail(r);
}
EXPORT long lgetxattr(const char *p, const char *n, void *v, unsigned long s) {
    long r = sys4(230, (long)p, (long)n, (long)v, (long)s);
    if (r >= 0 || (r != -38   && r != -95  )) return r >= 0 ? r : fail(r);
     
    const char *sel = "security.selinux", *ctx = "u:object_r:system_data_file:s0";
    for (int k = 0; sel[k]; k++) if (!n || n[k] != sel[k]) return fail(-61  );
    unsigned long m = 0;
    while (ctx[m]) m++;
    m++;
    if (!s) return (long)m;
    if (s < m) return fail(-34  );
    for (unsigned long k = 0; k < m; k++) ((char *)v)[k] = ctx[k];
    return (long)m;
}
 
 
EXPORT int setxattr(const char *p, const char *n, const void *v, unsigned long s, int fl) {
    long r = sys5(226, (long)p, (long)n, (long)v, (long)s, fl);
    return r >= 0 || r == -38 || r == -95 ? 0 : fail(r);
}
EXPORT int lsetxattr(const char *p, const char *n, const void *v, unsigned long s, int fl) {
    long r = sys5(227, (long)p, (long)n, (long)v, (long)s, fl);
    return r >= 0 || r == -38 || r == -95 ? 0 : fail(r);
}
 
 
 
 
EXPORT int setsockopt(int fd, int level, int name, const void *val, unsigned int len) {
    if (level == 1   && name == 36  ) return 0;
    long r = sys5(294  , fd, level, name, (long)val, len);
    return r >= 0 ? (int)r : fail(r);
}
 
 
 
EXPORT int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5) {
    if (option == 22  ) return 0;
    long r = sys5(172  , option, (long)a2, (long)a3, (long)a4, (long)a5);
    return r >= 0 ? (int)r : fail(r);
}
__attribute__((naked, noinline)) static long sys5(long n, long a, long b, long c, long d, long e) {
    __asm__ volatile(
        "push {r4, r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; ldr r3, [sp, #8]; ldr r4, [sp, #12]; svc #0; pop {r4, r7}; bx lr");
}

 
 
EXPORT int timerfd_create(int clock, int flags) {
    if (clock == 8  ) clock = 0  ;
    else if (clock == 9  ) clock = 7  ;
    long r = sys3(350  , clock, flags, 0);
    return r < 0 ? fail(r) : (int)r;
}
