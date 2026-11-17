 
 
 
 
 
 
 
 
 
 
 

typedef unsigned int u32;

extern int sigaction(int sig, const void *act, void *old);

__attribute__((naked, noinline)) static long cr_sys3(long n, long a, long b, long c) {
    __asm__ volatile(
        "push {r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; svc #0; pop {r7}; bx lr");
}
#define SYS_read 3
#define SYS_write 4
#define SYS_open 5
#define SYS_close 6
#define SYS_getpid 20
#define SYS_gettid 224

 

static char g_line[512];
static int g_len;

static void put(const char *s) { while (*s && g_len < (int)sizeof(g_line) - 1) g_line[g_len++] = *s++; }
static void puthex(u32 v) {
    char b[11]; b[0] = '0'; b[1] = 'x';
    for (int i = 0; i < 8; i++) { u32 d = (v >> (28 - 4 * i)) & 15; b[2 + i] = (char)(d < 10 ? '0' + d : 'a' + d - 10); }
    b[10] = 0; put(b);
}
static void putdec(u32 v) {
    char b[12]; int i = 11; b[i] = 0;
    do { b[--i] = (char)('0' + v % 10); v /= 10; } while (v && i > 0);
    put(b + i);
}

static int g_logfd = -1;

 
static void flush_line(void) {
    g_line[g_len] = 0;
    if (g_logfd >= 0) {
        static char rec[600];
        int n = 0;
        rec[n++] = 7;  
        const char *tag = "aemu_crash";
        while (*tag) rec[n++] = *tag++;
        rec[n++] = 0;
        for (int i = 0; i < g_len && n < (int)sizeof(rec) - 2; i++) rec[n++] = g_line[i];
        rec[n++] = 0;
        cr_sys3(SYS_write, g_logfd, (long)rec, n);
    }
    g_line[g_len] = '\n';
    cr_sys3(SYS_write, 2, (long)g_line, g_len + 1);
    g_len = 0;
}

 

#define MAXMAP 1024
static char g_map_paths[256 * 1024];
static int g_paths_used, g_map_limit;
static u32 g_ms[MAXMAP], g_me[MAXMAP], g_mo[MAXMAP];
static const char *g_mp[MAXMAP];
static int g_nmaps;
static u32 g_sp, g_stack_end;    

static u32 hexnum(const char **pp) {
    const char *p = *pp; u32 v = 0;
    for (;;) {
        char c = *p;
        if (c >= '0' && c <= '9') v = v * 16 + (u32)(c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (u32)(c - 'a' + 10);
        else break;
        p++;
    }
    *pp = p; return v;
}

 
 
static char g_sp_line[320], g_below_line[320], g_above_line[320];
static const char *g_sp_map, *g_below, *g_above;
static u32 g_sp_e, g_below_e, g_above_s, g_above_e;
static int g_above_r;
static void copy_line(char *dst, const char *src) {
    int i = 0;
    for (; src[i] && i < 319; i++) dst[i] = src[i];
    dst[i] = 0;
}

static void parse_map_line(const char *line) {
    const char *q = line;
    u32 s = hexnum(&q); if (*q != '-') return; q++;
    u32 e = hexnum(&q); if (*q != ' ') return; q++;
    if (!q[0] || !q[1] || !q[2] || !q[3] || q[4] != ' ') return;
    int exec = q[2] == 'x';
    int readable = q[0] == 'r';
    if (g_sp >= s && g_sp < e) {
        g_sp_e = e; copy_line(g_sp_line, line); g_sp_map = g_sp_line;
        if (readable) g_stack_end = e;
    }
    if (e <= g_sp && e > g_below_e) { g_below_e = e; copy_line(g_below_line, line); g_below = g_below_line; }
    if (s > g_sp && (!g_above || s < g_above_s)) {
        g_above_s = s; g_above_e = e; g_above_r = readable; copy_line(g_above_line, line); g_above = g_above_line;
    }
    q += 5;
    u32 off = hexnum(&q);
     
    for (int field = 0; field < 2; field++) {
        while (*q == ' ') q++;
        while (*q && *q != ' ') q++;
    }
    while (*q == ' ') q++;
    if (!exec) return;
    int len = 0;
    while (q[len]) len++;
    if (g_nmaps >= MAXMAP || len + 1 > (int)sizeof(g_map_paths) - g_paths_used) {
        g_map_limit = 1;
        return;
    }
    char *path = g_map_paths + g_paths_used;
    for (int i = 0; i <= len; i++) path[i] = q[i];
    g_paths_used += len + 1;
    g_ms[g_nmaps] = s; g_me[g_nmaps] = e; g_mo[g_nmaps] = off; g_mp[g_nmaps] = path;
    g_nmaps++;
}

static void load_maps_from(const char *path) {
    g_nmaps = g_paths_used = g_map_limit = 0;
    g_sp_map = g_below = g_above = 0; g_sp_e = g_below_e = g_above_s = g_above_e = 0; g_above_r = 0;
    long fd = cr_sys3(SYS_open, (long)path, 0, 0);
    if (fd < 0) return;
     
     
    static char chunk[8192], line[2048];
    int used = 0, long_line = 0;
    for (;;) {
        long n = cr_sys3(SYS_read, fd, (long)chunk, sizeof(chunk));
        if (n <= 0) break;
        for (long i = 0; i < n; i++) {
            if (chunk[i] == '\n') {
                if (!long_line) { line[used] = 0; parse_map_line(line); }
                else g_map_limit = 1;
                used = long_line = 0;
            } else if (used < (int)sizeof(line) - 1) line[used++] = chunk[i];
            else long_line = 1;
        }
    }
    cr_sys3(SYS_close, fd, 0, 0);
    if (used && !long_line) { line[used] = 0; parse_map_line(line); }
}

static void load_maps(void) { load_maps_from("/proc/self/maps"); }

static int find_map(u32 a) {
    for (int i = 0; i < g_nmaps; i++) if (a >= g_ms[i] && a < g_me[i]) return i;
    return -1;
}

static void put_sym(u32 a) {
    int m = find_map(a);
    if (m < 0) { put("?"); return; }
    const char *path = g_mp[m];
    const char *base = path;
    for (const char *c = path; *c; c++) if (*c == '/') base = c + 1;
     
    const char *sys = path;
    for (const char *c = path; *c; c++) if (c[0] == '/' && c[1] == 's' && c[2] == 'y' && c[3] == 's' && c[4] == 't' && c[5] == 'e' && c[6] == 'm' && c[7] == '/') { sys = c; break; }
    put(*sys ? sys : base);
    put("+"); puthex(a - g_ms[m] + g_mo[m]);
}

 

struct bsigaction { void *handler; u32 mask; u32 flags; void *restorer; };  

static const int g_sigs[] = { 4, 6, 7, 8, 11, 16 };
#define SIGUSR2 12
#define SYS_tgkill 268
static volatile int g_dump_lock;    
static volatile int g_dump_active;
static const char *g_names[] = { "SIGILL", "SIGABRT", "SIGBUS", "SIGFPE", "SIGSEGV", "SIGSTKFLT" };
static volatile int g_busy;

static void dump_current(const char *title, u32 addr, u32 *regs) {
    g_logfd = (int)cr_sys3(SYS_open, (long)"/dev/log/main", 1 | 02000  , 0);
    g_sp = regs[13]; g_stack_end = 0;
    load_maps();
    put("*** "); put(title); put(" pid "); putdec((u32)cr_sys3(SYS_getpid, 0, 0, 0));
    put(" tid "); putdec((u32)cr_sys3(SYS_gettid, 0, 0, 0)); put(" addr "); puthex(addr); flush_line();
    char thread_name[16] = { 0 };
    if (cr_sys3(172  , 16  , (long)thread_name, 0) == 0) {
        thread_name[15] = 0;
        put("thread "); put(thread_name); flush_line();
    }
    put("maps exec "); putdec((u32)g_nmaps);
    if (g_map_limit) put(" (symbol table limited)");
    put(" stack end "); puthex(g_stack_end); flush_line();
    put("pc "); puthex(regs[15]); put(" "); put_sym(regs[15]); flush_line();
    put("lr "); puthex(regs[14]); put(" "); put_sym(regs[14]); flush_line();
    for (int r = 0; r < 13; r += 4) {
        for (int k = r; k < r + 4 && k < 13; k++) { put("r"); putdec((u32)k); put("="); puthex(regs[k]); put(" "); }
        flush_line();
    }
    put("sp "); puthex(regs[13]); flush_line();
    if (g_sp_map) {
        put("sp in "); put(g_sp_map); flush_line();
        put("next above "); put(g_above ? g_above : "-"); flush_line();
    }
    else {
        put("sp unmapped; below "); put(g_below ? g_below : "-"); flush_line();
        put("sp unmapped; above "); put(g_above ? g_above : "-"); flush_line();
    }
     
    u32 *sp = (u32 *)regs[13];
    u32 words = g_stack_end > regs[13] ? (g_stack_end - regs[13]) / 4 : 0;
     
    if (!words && g_sp_map && g_above && g_above_r && g_sp_e == g_above_s) {
        sp = (u32 *)g_above_s; words = (g_above_e - g_above_s) / 4;
    }
    if (!words) { put("stack scan unavailable: no readable mapping for sp"); flush_line(); }
    if (words > 4096) words = 4096;
    int shown = 0;
    for (u32 i = 0; i < words && shown < 48; i++) {
        u32 w = sp[i];
        if (find_map(w) < 0) continue;
        put("  #"); putdec((u32)shown); put(" sp+"); puthex(i * 4); put("  "); puthex(w); put(" "); put_sym(w & ~1u);
        flush_line();
        shown++;
    }
    put("*** end"); flush_line();
    if (g_logfd >= 0) cr_sys3(SYS_close, g_logfd, 0, 0);
}

static void handler(int sig, void *info, void *ucv) {
    if (g_busy) return;
    g_busy = 1;
    u32 *uc = (u32 *)ucv;
     
    u32 *regs = uc + 5 + 3;
    u32 addr = info ? ((u32 *)info)[3] : 0;    
    const char *name = "?";
    for (int i = 0; i < 6; i++) if (g_sigs[i] == sig) name = g_names[i];
    dump_current(name, addr, regs);
     
    struct bsigaction dfl = { 0, 0, 0, 0 };
    sigaction(sig, &dfl, 0);
    g_busy = 0;
}

 
 
 
 
static void usr2_handler(int sig, void *info, void *ucv) {
    (void)sig; (void)info;
    u32 *regs = (u32 *)ucv + 5 + 3;
    int self = (int)cr_sys3(SYS_gettid, 0, 0, 0);
    int pid = (int)cr_sys3(SYS_getpid, 0, 0, 0);
    if (!__sync_lock_test_and_set(&g_dump_active, 1)) {
         
        static char dents[8192];
        long fd = cr_sys3(SYS_open, (long)"/proc/self/task", 0x4000  , 0);
        if (fd >= 0) {
            for (;;) {
                long n = cr_sys3(217  , fd, (long)dents, sizeof(dents));
                if (n <= 0) break;
                for (long off = 0; off < n;) {
                    unsigned short reclen = *(unsigned short *)(dents + off + 16);
                    const char *nm = dents + off + 19;
                    int tid = 0;
                    for (const char *c = nm; *c >= '0' && *c <= '9'; c++) tid = tid * 10 + (*c - '0');
                    if (tid > 0 && tid != self) cr_sys3(SYS_tgkill, pid, tid, SIGUSR2);
                    off += reclen;
                }
            }
            cr_sys3(SYS_close, fd, 0, 0);
        }
    }
    while (__sync_lock_test_and_set(&g_dump_lock, 1)) { }
    dump_current("thread stack", 0, regs);
    __sync_lock_release(&g_dump_lock);
}

__attribute__((constructor)) static void crash_init(void) {
    struct bsigaction sa;
    sa.handler = (void *)handler;
    sa.mask = 0;
    sa.flags = 4 | 0x10000000;  
    sa.restorer = 0;
    for (int i = 0; i < 6; i++) sigaction(g_sigs[i], &sa, 0);
    struct bsigaction su = { (void *)usr2_handler, 0, 4 | 0x10000000, 0 };
     
    struct bsigaction old = { 0, 0, 0, 0 };
    if (sigaction(SIGUSR2, 0, &old) == 0 && old.handler == 0) sigaction(SIGUSR2, &su, 0);
}
