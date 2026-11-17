// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ PART OF A MODIFIED QEMU. This file was ADDED to the QEMU sources
// (linux-user) by the ISTRATII_TECH SENSES project and is compiled into
// qemu-arm, therefore it is distributed under the terms of the GNU General
// Public License version 2 or (at your option) any later version.
//
// Full licence text: see COPYING in the project root.
// Everything changed in QEMU is listed in NOTICE.
//
// Copyright (c) 2026 istratiitech <https://github.com/istratiitech>
// Changed: 2026-08-29 … 2026-10-11

/* guest_ashmem.c — /dev/ashmem для гостя, целиком внутри qemu.
 *
 * Зачем ВТОРАЯ реализация ashmem (первая — guest/ashmem_shim.c). Гостевой шим
 * подменяет пять функций libcutils через LD_PRELOAD, и для программ это
 * работает. Но линкер bionic 2.3 НЕ подставляет preload в библиотеки, которые
 * загружены через dlopen, а HAL-модули загружаются именно так. Поймано на живом
 * стеке: SurfaceFlinger поднял gralloc.default.so, и тот пошёл напрямую —
 *
 *   open("/dev/ashmem",O_RDWR|O_LARGEFILE) = -1 errno=2
 *
 * — после чего SF падал в mspace_malloc по нулевому указателю (si_addr=0x4).
 * Симптом на пустом месте: ashmem «работал» для Dalvik и не работал для HAL.
 *
 * Здесь перехват на уровне устройства, поэтому он покрывает ЛЮБОЙ путь: и
 * dlopen, и статические вызовы, и код, который открывает устройство сам.
 *
 * Регион = memfd: безымянная разделяемая память, ровно то, чем ashmem и был.
 * SET_SIZE становится ftruncate, PIN/UNPIN — заглушками (страницы у нас никогда
 * не отбираются), mmap гость делает сам и получает настоящую общую память.
 *
 * ⚠️ Номера ioctl сверены с libcutils.so ИЗ ПРОШИВКИ, а не с заголовками:
 * SET_NAME=0x41007701, SET_SIZE=0x40047703, SET_PROT_MASK=0x40047705,
 * PIN=0x40087707, UNPIN=0x40087708. Мелкие _IO-номера в файле литералами не
 * лежат (компилятор ставит их в movw), поэтому проверены по смыслу.
 *
 * ★★★★★ ОПИСАТЕЛЬ ПРИХОДИТ ИЗ ЧУЖОГО ПРОЦЕССА — ТАБЛИЦЫ МАЛО.
 *
 * Здесь была вторая ошибка, от которой браузер прошивки падал при запуске (14 сентября).
 * Область искалась ТОЛЬКО в таблице regs[], а таблица живёт в памяти одного
 * экземпляра qemu. Движок браузера же раздаёт общую память между своими процессами,
 * посылая описатель по сокету (SCM_RIGHTS). У получателя в таблице его нет —
 * значит guest_ashmem_ioctl отвечал «не моё», запрос уходил настоящему ioctl,
 * тот видел memfd, не знал ioctl'ов ashmem и отвечал −1. А движок браузера на этот
 * −1 намеренно ломает процесс. Снято с живого стенда:
 *
 *   Fatal signal 5 (SIGTRAP) at 0x7a6b6a52, thread 19590 (Browser_ChildIOT)
 *   R00=ffffffff R01=00007706        ← возврат −1, запрос ASHMEM_GET_PROT_MASK
 *   байты у PC: be00 de42            ← bkpt #0 — умышленный слом
 *   у LR: … bl ioctl; orr r0,r0,r0 asr #31; cmp r0,#0; blt → bkpt
 *
 * То же случилось бы при dup() описателя и при переполнении таблицы.
 *
 * Поэтому «наш ли описатель» теперь решается ПО САМОМУ ОПИСАТЕЛЮ: memfd видно
 * по имени в /proc/self/fd/N («memfd:guest-ashmem»), размер берётся из fstat, а
 * права — из печатей memfd (F_SEAL_FUTURE_WRITE). Ровно так ashmem поверх
 * memfd сделан и в самом Android, начиная с 11-й версии. Таблица осталась
 * только ради имени области: оно нужно лишь для отладки.
 */
#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"

#include <pthread.h>
#include <sys/mman.h>

#include "guest_ashmem.h"

#define ASHMEM_SET_NAME         0x41007701u
#define ASHMEM_GET_NAME         0x81007702u
#define ASHMEM_SET_SIZE         0x40047703u
#define ASHMEM_GET_SIZE         0x00007704u
#define ASHMEM_SET_PROT_MASK    0x40047705u
#define ASHMEM_GET_PROT_MASK    0x00007706u
#define ASHMEM_PIN              0x40087707u
#define ASHMEM_UNPIN            0x40087708u
#define ASHMEM_GET_PIN_STATUS   0x00007709u
#define ASHMEM_PURGE_ALL_CACHES 0x0000770au

#define ASHMEM_NAME_LEN 256
#define ASHMEM_IS_PINNED 1
#define ASHMEM_NOT_PURGED 0

 
 
 
 
#define GUEST_ASHMEM_TAG "guest-ashmem"

 
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002u
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#endif
#ifndef F_GET_SEALS
#define F_GET_SEALS 1034
#endif
#ifndef F_SEAL_WRITE
#define F_SEAL_WRITE 0x0008
#endif
 
#ifndef F_SEAL_FUTURE_WRITE
#define F_SEAL_FUTURE_WRITE 0x0010
#endif

#define MAXREG 256

static struct region {
    int used;
    int fd;
    uint32_t size;
    uint32_t prot;
    char name[ASHMEM_NAME_LEN];
} regs[MAXREG];

static struct region *by_fd(int fd)
{
    for (int i = 0; i < MAXREG; i++) {
        if (regs[i].used && regs[i].fd == fd) {
            return &regs[i];
        }
    }
    return NULL;
}

 
 
 
 
static FILE *g_alog;
static int alog_tried;

static void alog(const char *fmt, ...)
{
    va_list ap;

    if (!alog_tried) {
        const char *path = getenv("GUEST_ASHMEM_LOG");

        alog_tried = 1;
        if (path && *path) {
            g_alog = fopen(path, "ae");
        }
    }
    if (!g_alog) {
        return;
    }
    fprintf(g_alog, "[pid=%d] ", (int)getpid());
    va_start(ap, fmt);
    vfprintf(g_alog, fmt, ap);
    va_end(ap);
    fputc('\n', g_alog);
    fflush(g_alog);
}

 
 
 
 
 
 
 
 
static int ashmem_req(unsigned long req)
{
    return ((req >> 8) & 0xffu) == 0x77u;
}

 
 
 
 
static int is_ours(int fd)
{
    char link[48], target[128];
    ssize_t n;

    if (fd < 0) {
        return 0;
    }
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    n = readlink(link, target, sizeof target - 1);
    if (n <= 0) {
        return 0;
    }
    target[n] = '\0';
    return strstr(target, "memfd:" GUEST_ASHMEM_TAG) != NULL;
}

 
 
 
 
 
 
 
static uint32_t prot_of(int fd)
{
    int seals = fcntl(fd, F_GET_SEALS);
    struct region *r = by_fd(fd);

    if (seals > 0 && (seals & (F_SEAL_WRITE | F_SEAL_FUTURE_WRITE))) {
        return PROT_READ | PROT_EXEC;
    }
     
    if (r && !(r->prot & PROT_WRITE)) {
        return PROT_READ | PROT_EXEC;
    }
    return PROT_READ | PROT_WRITE | PROT_EXEC;
}

 
static int seal_readonly(int fd)
{
     
 
 
 
 
 
     
 
 
 
 
    static int no_future = -1;

    if (no_future < 0) {
        const char *e = getenv("GUEST_ASHMEM_NOFUTURE");

        no_future = e && *e && *e != '0';
    }
    if (!no_future && fcntl(fd, F_ADD_SEALS, F_SEAL_FUTURE_WRITE) == 0) {
        return 0;
    }
    if (fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE) == 0) {
        return 0;
    }
    return -1;
}

int guest_ashmem_try_open(const char *path, int *fd)
{
    if (!path || strcmp(path, "/dev/ashmem") != 0) {
        return 0;
    }
    struct region *r = NULL;

    for (int i = 0; i < MAXREG; i++) {
        if (!regs[i].used) {
            r = &regs[i];
            break;
        }
    }

     
 
 
 
 
 
    int mfd = memfd_create(GUEST_ASHMEM_TAG, MFD_ALLOW_SEALING);
    if (mfd < 0) {
         
        mfd = memfd_create(GUEST_ASHMEM_TAG, 0);
    }
    if (mfd < 0) {
        *fd = -1;
        return 1;                       
    }
     
 
 
 
 
    if (r) {
        memset(r, 0, sizeof *r);
        r->used = 1;
        r->fd = mfd;
        snprintf(r->name, sizeof r->name, "unnamed");
         
        r->prot = PROT_READ | PROT_WRITE | PROT_EXEC;
    } else {
        alog("таблица имён полна — область %d живёт без записи", mfd);
    }
    *fd = mfd;
    return 1;
}

int guest_ashmem_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret)
{
    struct region *r;

     
    if (!ashmem_req(req)) {
        return 0;
    }
    r = by_fd(fd);
    if (!r && !is_ours(fd)) {
        return 0;
    }
    if (!r) {
         
        alog("описатель %d не в таблице (запрос 0x%lx) — отвечаем по memfd",
             fd, req);
    }

    switch (req) {
    case ASHMEM_SET_NAME:
        if (arg && r) {
             
            snprintf(r->name, sizeof r->name, "%.*s", ASHMEM_NAME_LEN - 1,
                     (const char *)g2h_untagged(arg));
        }
        *ret = 0;
        return 1;

    case ASHMEM_GET_NAME:
        if (arg) {
            char name[ASHMEM_NAME_LEN];

            snprintf(name, sizeof name, "%s",
                     r ? r->name : GUEST_ASHMEM_TAG);
            memcpy(g2h_untagged(arg), name, sizeof name);
        }
        *ret = 0;
        return 1;

    case ASHMEM_SET_SIZE:
         
 
 
 
        if (ftruncate(fd, (off_t)(uint32_t)arg) < 0) {
            *ret = -1;
            return 1;
        }
        if (r) {
            r->size = (uint32_t)arg;
        }
        *ret = 0;
        return 1;

    case ASHMEM_GET_SIZE: {
         
        struct stat st;

        if (fstat(fd, &st) == 0) {
            *ret = (abi_long)st.st_size;
        } else if (r) {
            *ret = (abi_long)r->size;
        } else {
            *ret = -1;
        }
        return 1;
    }

    case ASHMEM_SET_PROT_MASK:
         
 
 
 
        if (((uint32_t)arg & ~prot_of(fd)) != 0) {
            errno = EINVAL;
            *ret = -1;
            return 1;
        }
        if (!((uint32_t)arg & PROT_WRITE) && seal_readonly(fd) < 0) {
            alog("описатель %d: печать «только чтение» не встала (%s)",
                 fd, strerror(errno));
             
 
 
 
 
 
 
 
            static int told;

            if (!told) {
                told = 1;
                alog("печать не встала, права держу маской шима: отображения этой области на запись "
                     "отказываю сам (EPERM); дальше об этом не пишу");
            }
        }
        if (r) {
            r->prot = (uint32_t)arg;
        }
         
 
 
 
 
        *ret = 0;
        return 1;

    case ASHMEM_GET_PROT_MASK:
        *ret = (abi_long)prot_of(fd);
        return 1;

    case ASHMEM_PIN:
         
        *ret = ASHMEM_NOT_PURGED;
        return 1;

    case ASHMEM_UNPIN:
        *ret = 0;
        return 1;

    case ASHMEM_GET_PIN_STATUS:
        *ret = ASHMEM_IS_PINNED;
        return 1;

    case ASHMEM_PURGE_ALL_CACHES:
        *ret = 0;
        return 1;

    default:
         
        errno = EINVAL;
        *ret = -1;
        return 1;
    }
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int g_reopen_denied;

static int reopen_forbidden_by_env(void)
{
    static int v = -1;

    if (v < 0) {
        const char *e = getenv("GUEST_ASHMEM_NOREOPEN");

        v = e && *e && *e != '0';
    }
    return v;
}

int guest_ashmem_mmap_prep(int fd, int prot, int host_flags, int *rfd)
{
    struct region *r;
    int seals, sealed, sealed_w;

    *rfd = -1;
     
    if (fd < 0 || !(host_flags & MAP_SHARED) || (host_flags & MAP_ANONYMOUS) || !is_ours(fd)) {
        return 0;
    }
    r = by_fd(fd);
    seals = fcntl(fd, F_GET_SEALS);
    sealed = seals > 0 && (seals & (F_SEAL_WRITE | F_SEAL_FUTURE_WRITE));
    sealed_w = seals > 0 && (seals & F_SEAL_WRITE);
    if (prot & PROT_WRITE) {
        if (!sealed && r && !(r->prot & PROT_WRITE)) {
            alog("описатель %d: отображение на запись области «только чтение» — отказ (печати нет, маска шима)",
                 fd);
            errno = EPERM;
            return -1;
        }
        return 0;                       
    }
     
 
 
 
 
    if (!sealed_w) {
         
 
 
 
 
        if (!sealed && r && !(r->prot & PROT_WRITE)) {
            return 3;
        }
        return 0;
    }
    {
        char link[48];
        int ro = -1;

        if (!g_reopen_denied) {
            if (reopen_forbidden_by_env()) {
                errno = EACCES;                     
            } else {
                snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
                ro = open(link, O_RDONLY | O_CLOEXEC);
            }
        } else {
            errno = EACCES;
        }
        if (ro < 0) {
            static int told_private;
            int e = errno;

            if (e == EACCES || e == EPERM) {
                g_reopen_denied = 1;
            }
            if (!told_private) {
                told_private = 1;
                alog("описатель %d: переоткрыть только на чтение нельзя (%s) — запечатанную область "
                     "отображаю на хозяине как MAP_PRIVATE; дальше об этом не пишу", fd, strerror(e));
            }
            return 2;
        }
        *rfd = ro;
        alog("описатель %d: отображение «только чтение» запечатанной области — по описателю, "
             "переоткрытому только на чтение (%d)", fd, ro);
    }
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define PRMAX 64
#define PR_PAGE 0x1000u

static struct { uint64_t a, b; } g_pr[PRMAX];
static int g_npr;                         
static int g_pr_any;                      
static pthread_mutex_t g_prl = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t g_pr_once = PTHREAD_ONCE_INIT;
static int g_pr_told_full;

static void pr_atfork_child(void)
{
    pthread_mutex_init(&g_prl, NULL);     
}

static void pr_init(void)
{
    pthread_atfork(NULL, NULL, pr_atfork_child);
}

static uint64_t pr_end(abi_ulong start, abi_ulong len)
{
    return (uint64_t)start + (((uint64_t)len + PR_PAGE - 1) & ~(uint64_t)(PR_PAGE - 1));
}

static void pr_full_note(const char *what)
{
    if (!g_pr_told_full) {
        g_pr_told_full = 1;
        alog("таблица диапазонов подмены MAP_PRIVATE полна (%d): %s; дальше об этом не пишу", PRMAX, what);
    }
}

 
static void pr_sub_l(uint64_t a, uint64_t b)
{
    int i = 0;

    while (i < g_npr) {
        uint64_t ra = g_pr[i].a, rb = g_pr[i].b;

        if (rb <= a || ra >= b) {
            i++;
        } else if (ra < a && rb > b) {                  
            if (g_npr < PRMAX) {
                g_pr[g_npr].a = b;
                g_pr[g_npr].b = rb;
                g_npr++;
                g_pr[i].b = a;
                i++;
            } else {
                pr_full_note("запись с дырой отброшена целиком");
                g_pr[i] = g_pr[--g_npr];
            }
        } else if (ra < a) {                            
            g_pr[i].b = a;
            i++;
        } else if (rb > b) {                            
            g_pr[i].a = b;
            i++;
        } else {                                        
            g_pr[i] = g_pr[--g_npr];
        }
    }
}

 
static void pr_add_l(uint64_t a, uint64_t b)
{
    pr_sub_l(a, b);
    if (b <= a) {
        return;
    }
    if (g_npr < PRMAX) {
        g_pr[g_npr].a = a;
        g_pr[g_npr].b = b;
        g_npr++;
    } else {
        pr_full_note("mprotect на запись по новому отображению отказывать не будем");
    }
}

static void pr_publish(void)
{
    __atomic_store_n(&g_pr_any, g_npr > 0, __ATOMIC_RELEASE);
}

 
void guest_ashmem_range_note(abi_ulong start, abi_ulong len)
{
    if (!len) {
        return;
    }
    pthread_once(&g_pr_once, pr_init);
    pthread_mutex_lock(&g_prl);
    pr_add_l(start, pr_end(start, len));
    pr_publish();
    pthread_mutex_unlock(&g_prl);
}

 
void guest_ashmem_range_forget(abi_ulong start, abi_ulong len)
{
    if (!len || !__atomic_load_n(&g_pr_any, __ATOMIC_ACQUIRE)) {
        return;
    }
    pthread_mutex_lock(&g_prl);
    pr_sub_l(start, pr_end(start, len));
    pr_publish();
    pthread_mutex_unlock(&g_prl);
}

 
int guest_ashmem_range_covers(abi_ulong start, abi_ulong len)
{
    uint64_t cur = start, end;
    int found = 1, r = 0;

    if (!len || !__atomic_load_n(&g_pr_any, __ATOMIC_ACQUIRE)) {
        return 0;
    }
    end = pr_end(start, len);
    pthread_mutex_lock(&g_prl);
    while (found && cur < end) {
        found = 0;
        for (int i = 0; i < g_npr; i++) {
            if (g_pr[i].a <= cur && cur < g_pr[i].b) {
                cur = g_pr[i].b;
                found = 1;
                break;
            }
        }
    }
    r = cur >= end;
    pthread_mutex_unlock(&g_prl);
    return r;
}

 
void guest_ashmem_range_moved(abi_ulong old, abi_ulong oldlen, abi_ulong newaddr, abi_ulong keeplen, int cov)
{
    if (!oldlen || !__atomic_load_n(&g_pr_any, __ATOMIC_ACQUIRE)) {
        return;
    }
    pthread_mutex_lock(&g_prl);
    pr_sub_l(old, pr_end(old, oldlen));
    if (cov && keeplen) {
        pr_add_l(newaddr, pr_end(newaddr, keeplen));
    }
    pr_publish();
    pthread_mutex_unlock(&g_prl);
}

 
 
 
 
 
int guest_ashmem_mprotect_denied(abi_ulong start, abi_ulong len, int prot)
{
    uint64_t end;
    int denied = 0;
    static int told;

    if (!(prot & PROT_WRITE) || !len || (start & (PR_PAGE - 1)) ||
        !__atomic_load_n(&g_pr_any, __ATOMIC_ACQUIRE)) {
        return 0;
    }
    end = pr_end(start, len);
    pthread_mutex_lock(&g_prl);
    for (int i = 0; i < g_npr; i++) {
        if (g_pr[i].a < end && start < g_pr[i].b) {
            denied = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_prl);
    if (denied && !told) {
        told = 1;
        alog("mprotect на запись по отображению запечатанной области (MAP_PRIVATE вместо MAP_SHARED) — EACCES, "
             "как у ashmem; дальше об этом не пишу");
    }
    return denied;
}

void guest_ashmem_close(int fd)
{
    struct region *r = by_fd(fd);

    if (r) {
        r->used = 0;
    }
}
