 
 
 
 
 
 
 
 
 
 
#define SYS_setrlimit 75
#define EPERM 1

static long sc2(long nr, long a, long b) {
    register long r7 __asm__("r7") = nr;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1) : "memory");
    return r0;
}

int setrlimit(int resource, const void *rlim) {
    long r = sc2(SYS_setrlimit, resource, (long)rlim);
    if (r == -EPERM) return 0;       
    return r < 0 ? -1 : 0;
}
