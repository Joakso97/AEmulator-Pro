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

// guest_binder.c — изображает драйвер /dev/binder для гостевой прошивки.
//
// ★ Форк файла glue_binder.c из более ранней нашей работы, где он уже
// поднимал полный стек Android 7. Взят целиком и намеренно: в нём
// закодированы выстраданные инварианты ПОРЯДКА команд, каждый с описанием того
// краха, который он лечит (BAD COMMAND 29190, SIGSEGV в RefBase::incStrong,
// Watchdog по заблокированному потоку). Переписывать это заново — значит
// повторить те же ошибки.
//
// ★★ Отличие от донора: там шим жил В ГОСТЕВОМ процессе (LD_PRELOAD, гость
// 64-битный), здесь он живёт ВНУТРИ qemu (гость 32-битный). Отсюда три вида
// правок, и только они:
//   1. гостевые адреса переводятся g2h/h2g — мы в том же адресном пространстве,
//      но гостевой указатель это abi_ulong, а не void*;
//   2. массив смещений объектов у 32-битного гостя 4-байтный, а протокол демона
//      несёт 8-байтные — пересчёт на обеих границах;
//   3. mmap делается через target_mmap: область обязана лечь в адресное
//      пространство ГОСТЯ и попасть в его учёт страниц.
//
// Устройство осталось прежним:
//   open("/dev/binder")  -> socketpair, гостю отдаётся конец A (на нём работает
//                           poll, потому что это настоящий сокет);
//   ioctl(BINDER_*)      -> сообщения демону binderd, ответы -> команды BR_* ;
//   mmap(fd)             -> анонимная область в памяти ГОСТЯ, куда МЫ кладём
//                           данные входящих транзакций;
//   close(fd)            -> демон разошлёт извещения о смерти.
//
// Соединение с демоном — на КАЖДЫЙ ПОТОК гостя: несколько потоков, читающих
// один сокет, растаскивали бы чужие сообщения.
//
// Путь к сокету демона берётся из GUEST_BINDER, лог — из GUEST_BINDER_LOG.

#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"
#include "user-mmap.h"

#include <pthread.h>
#include <poll.h>
#include <sys/syscall.h>

#include "binder_proto.h"
#include "guest_binder.h"
#include "guest_fb.h"
#include "guest_ids.h"
#include "guest_ashmem.h"
#include "guest_input.h"
#include "guest_alarm.h"
#include "guest_fs.h"
#include "guest_trace.h"

 
 
static pid_t guest_gettid(void) { return (pid_t)syscall(SYS_gettid); }
#define gettid guest_gettid

 
 
static int g_verbose;
static FILE *g_blog;

static void blog_init(void)
{
    static int done;
    if (done) {
        return;
    }
    done = 1;
    const char *path = getenv("GUEST_BINDER_LOG");
    if (!path || !*path) {
        return;
    }
    g_blog = (path[0] == '-' && !path[1]) ? stderr : fopen(path, "ae");
     
 
 
 
 
 
 
 
 
 
    const char *v = getenv("GUEST_BINDER_VERBOSE");
    g_verbose = g_blog != NULL && v && *v && *v != '0';
}

 
static void bwarn(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void bwarn(const char *fmt, ...)
{
    if (!g_blog) {
        return;
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    fprintf(g_blog, "[%3ld.%03ld pid=%d tid=%d] ", (long)ts.tv_sec % 1000,
            ts.tv_nsec / 1000000, getpid(), (int)guest_gettid());
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_blog, fmt, ap);
    va_end(ap);
    fputc('\n', g_blog);
    fflush(g_blog);
}

static void blog(const char *fmt, ...)
{
    if (!g_verbose) {
        return;
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    fprintf(g_blog, "[%3ld.%03ld pid=%d tid=%d] ", (long)ts.tv_sec % 1000,
            ts.tv_nsec / 1000000, getpid(), (int)guest_gettid());
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_blog, fmt, ap);
    va_end(ap);
    fputc('\n', g_blog);
    fflush(g_blog);
}
#define glog blog

 
 
static char g_binder[512];

static void binder_path_init(void)
{
    static int done;
    if (done) {
        return;
    }
    done = 1;
    const char *e = getenv("GUEST_BINDER");
    if (e && *e) {
        snprintf(g_binder, sizeof g_binder, "%s", e);
    }
}

static int connect_sock(const char *spec)
{
     
     
     
     
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    snprintf(a.sun_path, sizeof a.sun_path, "%s", spec);
    if (connect(fd, (struct sockaddr *)&a, sizeof a) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
     
 
 
 
 
 
 
 
 
 
    if (fd < 3) {
        int hi = fcntl(fd, F_DUPFD_CLOEXEC, 3);
        if (hi >= 0) { close(fd); fd = hi; }
    }
    return fd;
}

#define SB_MAXSESS 4
 
 
#define SB_MAXTHR  64
#define SB_PEND    (64 * 1024)
 
 
 
#define SB_MAXMSG  (4 * 1024 * 1024)

 
 
 
struct blk { size_t off, size; int used; int cool; uint32_t dsize, osize; struct blk *next; };

struct thr;

struct sess {
    int used;
    int proto;                  
    int ver_asked;              
    int engaged;                
    int fd_guest;               
    int fd_wake;                
    uint8_t *map;
    size_t map_size;
    struct blk *blocks;
    pthread_mutex_t lock;
    pthread_t pump;             
    int pump_fd;                
    int pump_live;
     
 
 
 
 
 
 
 
 
 
    struct qmsg *dq, **dq_tail;
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    int closes_death;
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    int closing;
    struct { int fd; pid_t pid; } tfd[SB_MAXTHR];
    int ntfd;
     
 
 
 
 
 
 
 
 
 
 
 
    struct { uint64_t addr; struct thr *t; } aw[SB_MAXTHR];
    int aw_n;
};

 
 
 
 
 
 
 
 
struct qmsg { struct qmsg *next; size_t n; uint8_t buf[]; };
struct thr;
static void dq_drop(struct thr *t);

struct thr {
    int fd;                     
    struct sess *s;
    struct qmsg *dq, **dq_tail;    
    int looper;
    int completes;              
    int failed;                 
    int acks;                   
    uint32_t iseq;              
    uint8_t *rx;                
    uint32_t rxcap;
     
     
     
    int sent;
    uint32_t last_code, last_flags, last_handle;
     
 
 
 
    int in_txn;                 
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    int in_death;               
     
 
 
 
 
 
 
 
    int tx_at_death;
    uint64_t inbuf[8];          
    uint32_t incode[8];         
    int pending_sync;           
     
 
 
 
 
 
    char tr_st[16];
    int tr_n;
    int tx_total;               
     
 
 
 
 
 
    int cpl_given;
    int cpl_orphan;             
    int will_read;              
     
    int lost;
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    uint64_t async_buf;
    int async_flush;
     
 
 
 
 
    int orphan_msg;
};

static struct sess g_sess[SB_MAXSESS];
static pthread_key_t g_thrkey;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static int g_first_thread_done;

 
 
 
 
 
#define SB_TRACE_N 64

 
 
 
 
 
 
 
 
 
 
 
 
static void txn_sniff(const void *data, unsigned long long dsize,
                      char *out, unsigned outn) {
    const unsigned char *d = (const unsigned char *)data;
    unsigned long long n = dsize / 2, best = 0, bestlen = 0, cur = 0;
    out[0] = 0;
    for (unsigned long long i = 0; i < n; i++) {
        unsigned char lo = d[i * 2], hi = d[i * 2 + 1];
        int ok = (hi == 0 && lo >= 0x20 && lo < 0x7f);
        if (ok) { cur++; if (cur > bestlen) { bestlen = cur; best = i + 1 - cur; } }
        else cur = 0;
    }
    if (bestlen < 6) return;
     
 
    unsigned keep = outn - 1;
    if (bestlen > keep) { best += bestlen - keep; bestlen = keep; }
    unsigned k = 0;
    for (unsigned long long i = 0; i < bestlen; i++) out[k++] = (char)d[(best + i) * 2];
    out[k] = 0;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
static void txn_obj0(const void *data, unsigned long long dsize,
                     const void *offs8, unsigned long long noff,
                     char *out, unsigned outn) {
    out[0] = 0;
    if (!noff || outn < 24) return;
    unsigned long long off;
    memcpy(&off, offs8, 8);
    if (off + 16 > dsize) return;
    const unsigned char *o = (const unsigned char *)data + off;
    unsigned int type, w2;
    memcpy(&type, o, 4);
    memcpy(&w2, o + 8, 4);
    snprintf(out, outn, " маркер=%s0x%x",
             type == 0x73622a85u ? "свой:"   : type == 0x73682a85u ? "ссылка:" :
             type == 0x77622a85u ? "свойсл:" : type == 0x77682a85u ? "ссылсл:" : "иное:",
             w2);
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static void txn_iface(const void *data, unsigned long long dsize,
                      char *out, unsigned outn) {
    const unsigned char *d = (const unsigned char *)data;
    out[0] = 0;
    if (dsize < 8 || outn < 8) return;
    unsigned int len;
    memcpy(&len, d + 4, 4);
    if (len < 3 || len > 96 || 8ull + (unsigned long long)(len + 1) * 2 > dsize) return;
    unsigned start = 0;
    for (unsigned i = 0; i < len; i++) {
        unsigned short c;
        memcpy(&c, d + 8 + i * 2, 2);
        if (c == '.') start = i + 1;             
    }
    unsigned k = 0;
    for (unsigned i = start; i < len && k + 1 < outn; i++) {
        unsigned short c;
        memcpy(&c, d + 8 + i * 2, 2);
        out[k++] = (c >= 32 && c < 127) ? (char)c : '?';
    }
    out[k] = 0;
}

 
 
 
 
static void tr_begin(struct thr *t, char kind, const char *ifn, uint32_t code)
{
    if (t->tr_n < (int)sizeof t->tr_st) t->tr_st[t->tr_n++] = kind;
    guest_trace_mark('B', "binder%c %s#%u", kind == 'o' ? '>' : '<',
                     ifn[0] ? ifn : "?", (unsigned)code);
}

static void tr_end(struct thr *t, char kind)
{
    if (t->tr_n > 0 && t->tr_st[t->tr_n - 1] == kind) {
        t->tr_n--;
        guest_trace_mark('E', " ");
    }
}

 
 
 
 

 
static void async_done_send(struct thr *t) {
    if (t->fd < 0) return;
    struct sb_hdr ah = { .len = sizeof ah, .type = SB_ASYNC_DONE,
                          .tid = (uint32_t)gettid(), .flags = SB_F_MAGIC };
    (void)!write(t->fd, &ah, sizeof ah);
}

 
 
 
 
 
 
static void async_on_received(struct thr *t) {
    if (__atomic_exchange_n(&t->async_flush, 0, __ATOMIC_ACQ_REL))
        bwarn("binder: ★ сброшен отложенный «разобрал» нити: из сокета принята новая односторонняя — "
              "слот демона уже у неё, старый узел освободит предохранитель демона");
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static void async_hold(struct thr *t, struct sess *s, uint64_t buf) {
    int clash = 0, full = 0, stale = 0;
    uint64_t old = 0;
    pthread_mutex_lock(&s->lock);
    int i = -1;
    if (t->async_buf) {
        for (int k = 0; k < SB_MAXTHR; k++) if (s->aw[k].t == t) { i = k; break; }
        if (i >= 0) { clash = 1; old = t->async_buf; }
         
         
         
    }
    if (i < 0) {
        for (int k = 0; k < SB_MAXTHR; k++) if (!s->aw[k].t) { i = k; break; }
        if (i >= 0) __atomic_add_fetch(&s->aw_n, 1, __ATOMIC_RELEASE);
    }
    if (i >= 0) {
        s->aw[i].t = t;
        s->aw[i].addr = buf;
        t->async_buf = buf;
         
        stale = __atomic_exchange_n(&t->async_flush, 0, __ATOMIC_ACQ_REL);
    } else {
         
        full = 1;
        t->async_buf = 0;
        __atomic_store_n(&t->async_flush, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&s->lock);
    if (clash)
        bwarn("binder: ★ вторая односторонняя в руках у нити (старый буфер 0x%llx, новый "
              "0x%llx): слот демона теперь у новой, старый узел освободит только "
              "предохранитель демона (5 с)",
              (unsigned long long)old, (unsigned long long)buf);
    if (stale)
        bwarn("binder: ★ сброшен отложенный «разобрал» нити: слот демона уже у новой "
              "односторонней (буфер 0x%llx) — старый узел освободит предохранитель демона",
              (unsigned long long)buf);
    if (full)
        bwarn("binder: ★ таблица односторонних полна (%d) — отпускаю в начале ближайшего "
              "ioctl, как прежде", SB_MAXTHR);
}

 
 
static void async_on_free(struct thr *t, struct sess *s, uint64_t addr) {
    if (!__atomic_load_n(&s->aw_n, __ATOMIC_ACQUIRE)) return;    
    struct thr *holder = NULL;
    int foreign = 0;
    pthread_mutex_lock(&s->lock);
    for (int i = 0; i < SB_MAXTHR; i++) {
        if (!s->aw[i].t || s->aw[i].addr != addr) continue;
        holder = s->aw[i].t;
        s->aw[i].t = NULL;
        s->aw[i].addr = 0;
        __atomic_sub_fetch(&s->aw_n, 1, __ATOMIC_RELEASE);
        holder->async_buf = 0;
         
 
 
 
 
 
 
        if (holder != t) { __atomic_store_n(&holder->async_flush, 1, __ATOMIC_RELEASE); foreign = 1; }
        break;
    }
    pthread_mutex_unlock(&s->lock);
     
    if (holder == t) async_done_send(t);
     
 
 
 
 
 
    if (foreign) {
        static int nforeign;
        int n = __atomic_add_fetch(&nforeign, 1, __ATOMIC_RELAXED);
        if (n <= 8 || (n % 100) == 0)
            bwarn("binder: ★ буфер односторонней 0x%llx — чужая нить вернула: «разобрал» "
                  "уйдёт в начале ближайшего ioctl держателя (всего таких %d)",
                  (unsigned long long)addr, n);
    }
}

 
 
 
 
static void async_forget(struct thr *t) {
    for (int k = 0; k < SB_MAXSESS; k++) {
        struct sess *s = &g_sess[k];
        if (!s->used) continue;
        pthread_mutex_lock(&s->lock);
        for (int i = 0; i < SB_MAXTHR; i++) {
            if (s->aw[i].t != t) continue;
            s->aw[i].t = NULL;
            s->aw[i].addr = 0;
            __atomic_sub_fetch(&s->aw_n, 1, __ATOMIC_RELEASE);
        }
        pthread_mutex_unlock(&s->lock);
    }
    t->async_buf = 0;
}

static void thr_free(void *p) {
    struct thr *t = (struct thr *)p;
    if (!t) return;
    async_forget(t);
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (t->dq) {
        struct sess *ss = t->s;
        int moved = 0, dropped = 0;
        if (ss) pthread_mutex_lock(&ss->lock);
        while (t->dq) {
            struct qmsg *q = t->dq;
            t->dq = q->next;
            q->next = NULL;
            struct sb_hdr *h = (struct sb_hdr *)q->buf;
            if (ss && q->n >= sizeof *h && h->type == SB_R_TRANSACTION) {
                if (!ss->dq_tail) ss->dq_tail = &ss->dq;
                *ss->dq_tail = q; ss->dq_tail = &q->next;
                moved++;
            } else { free(q); dropped++; }
        }
        t->dq_tail = &t->dq;
        if (ss) pthread_mutex_unlock(&ss->lock);
        if (moved || dropped)
            bwarn("binder: поток уходит с отложенным: %d чужих транзакций отдал "
                  "процессу, %d выбросил (адресованы ему одному)", moved, dropped);
    }
    if (t->fd >= 0) {
        struct sess *ss = t->s;
        if (ss) {
             
             
            pid_t me = getpid();
            pthread_mutex_lock(&ss->lock);
            for (int i = 0; i < ss->ntfd; i++) {
                if (ss->tfd[i].fd == t->fd && ss->tfd[i].pid == me) {
                    ss->tfd[i] = ss->tfd[--ss->ntfd];
                    break;
                }
            }
            pthread_mutex_unlock(&ss->lock);
        }
        struct sb_hdr h = { .len = sizeof h, .type = SB_BYE, .flags = SB_F_MAGIC };
        (void)!write(t->fd, &h, sizeof h);
        close(t->fd);
    }
    free(t->rx);
    free(t);
}

static void thrkey_init(void) { pthread_key_create(&g_thrkey, thr_free); }

 

 
 
 
static void *buf_alloc(struct sess *s, size_t size) {
    size = (size + 7) & ~(size_t)7;
    if (!size) size = 8;
    pthread_mutex_lock(&s->lock);
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    struct blk *best = NULL, *hot = NULL;
    for (struct blk *b = s->blocks; b; b = b->next) {
        if (b->used || b->size < size) continue;
        if (b->cool > 0) { if (!hot) hot = b; continue; }
        best = b;
        break;
    }
    if (!best) best = hot;
    if (!best) { pthread_mutex_unlock(&s->lock); return NULL; }
     
 
    for (struct blk *b = s->blocks; b; b = b->next)
        if (b->cool > 0) b->cool--;
    if (best->size > size + 64) {                        
        struct blk *tail = (struct blk *)malloc(sizeof *tail);
        if (tail) {
            tail->off = best->off + size;
            tail->size = best->size - size;
            tail->used = 0;
            tail->cool = 0;
            tail->dsize = tail->osize = 0;
            tail->next = best->next;
            best->next = tail;
            best->size = size;
        }
    }
    best->used = 1;
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (best->off + size > s->map_size) {
        bwarn("binder: ★★★ блок вылез за область: смещение %zu + %zu > %zu — "
              "ОТКАЗЫВАЕМ (иначе испортили бы память гостя)",
              (size_t)best->off, size, (size_t)s->map_size);
        best->used = 0;
        pthread_mutex_unlock(&s->lock);
        return NULL;
    }
    void *p = s->map + best->off;
    pthread_mutex_unlock(&s->lock);
    return p;
}

 
static void buf_note(struct sess *s, void *addr, uint32_t dsize, uint32_t osize) {
    pthread_mutex_lock(&s->lock);
    size_t off = (size_t)((uint8_t *)addr - s->map);
    for (struct blk *b = s->blocks; b; b = b->next)
        if (b->off == off) { b->dsize = dsize; b->osize = osize; break; }
    pthread_mutex_unlock(&s->lock);
}

 
 
static int buf_take(struct sess *s, uint64_t addr, uint32_t *dsize, uint32_t *osize) {
    int found = 0;
    pthread_mutex_lock(&s->lock);
    size_t off = (size_t)(addr - (uint64_t)(uintptr_t)s->map);
    for (struct blk *b = s->blocks; b; b = b->next)
        if (b->off == off) { *dsize = b->dsize; *osize = b->osize; b->dsize = b->osize = 0; found = 1; break; }
    pthread_mutex_unlock(&s->lock);
    return found;
}

static void buf_free(struct sess *s, uint64_t addr) {
    pthread_mutex_lock(&s->lock);
    size_t off = (size_t)(addr - (uint64_t)(uintptr_t)s->map);
    for (struct blk *b = s->blocks; b; b = b->next) {
        if (b->off != off) continue;
        b->used = 0;
        b->cool = 16;                  
         
        for (struct blk *q = s->blocks; q; q = q->next)
            while (!q->used && q->next && !q->next->used) {
                struct blk *n = q->next;
                q->size += n->size;
                if (n->cool > q->cool) q->cool = n->cool;
                q->next = n->next;
                free(n);
            }
        break;
    }
    pthread_mutex_unlock(&s->lock);
}


 
 
static int rd_all(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    while (len) {
        ssize_t n = read(fd, p, len);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        if (n == 0) { errno = EPIPE; return -1; }
        p += n; len -= (size_t)n;
    }
    return 0;
}

 

 
 
 
 
 
 

static void *pump_main(void *arg) {
    struct sess *s = (struct sess *)arg;
    int fd = connect_sock(g_binder);
    if (fd < 0) { glog("binder: будильник не подключился: %s", strerror(errno)); return NULL; }
    s->pump_fd = fd;
    struct { struct sb_hdr h; struct sb_hello b; } msg;
    memset(&msg, 0, sizeof msg);
    msg.h.len = sizeof msg;
    msg.h.type = SB_HELLO;
    msg.h.tid = (uint32_t)gettid();
    msg.h.flags = SB_F_MAGIC;              
    msg.b.pid = (uint32_t)getpid();
     
     
    msg.b.uid = (uint32_t)guest_guest_uid();
    msg.b.gid = (uint32_t)guest_guest_gid();
    msg.b.first = 2;                        
    if (write(fd, &msg, sizeof msg) != (ssize_t)sizeof msg) { close(fd); s->pump_fd = -1; return NULL; }
    s->pump_live = 1;
    glog("binder: будильник поднят");
    for (;;) {
        struct sb_hdr h;
         
         
         
         
        if (rd_all(fd, &h, sizeof h) < 0) break;
        if ((h.flags & SB_F_MAGIC_MASK) != SB_F_MAGIC) {
            glog("binder: ★ будильник поймал не наш заголовок (len=%u тип=%u)", h.len, h.type);
            break;
        }
        if (h.len > sizeof h) {             
            char junk[512];
            uint32_t left = h.len - (uint32_t)sizeof h;
            while (left) {
                ssize_t k = read(fd, junk, left > sizeof junk ? sizeof junk : left);
                if (k <= 0) break;
                left -= (uint32_t)k;
            }
        }
        char one = 'w';
        ssize_t wr = write(s->fd_wake, &one, 1);    
         
        struct pollfd pf = { .fd = s->fd_guest, .events = POLLIN };
        int pr = poll(&pf, 1, 0);
        glog("binder: будильник -> байт в fd=%d (%zd); гостевой fd=%d готов? %d (0x%x)",
             s->fd_wake, wr, s->fd_guest, pr, pf.revents);
    }
    s->pump_live = 0;
    close(fd);
    s->pump_fd = -1;
    glog("binder: будильник ушёл");
    return NULL;
}

static void pump_start(struct sess *s) {
    if (s->pump_live || !g_binder[0]) return;
    s->pump_fd = -1;
    if (pthread_create(&s->pump, NULL, pump_main, s) != 0)
        glog("binder: не удалось создать будильник");
}

 
 
 
 
static void binder_atfork_child(void) {
    for (int i = 0; i < SB_MAXSESS; i++) {
        struct sess *s = &g_sess[i];
        if (!s->used) continue;
        if (s->pump_fd >= 0) { close(s->pump_fd); s->pump_fd = -1; }
        s->pump_live = 0;
         
         
        while (s->dq) { struct qmsg *q = s->dq; s->dq = q->next; free(q); }
        s->dq_tail = &s->dq;
        pthread_mutex_init(&s->lock, NULL);       
         
         
        s->ntfd = 0;
        s->closing = 0;
         
         
        memset(s->aw, 0, sizeof s->aw);
        s->aw_n = 0;
    }
    struct thr *t = (struct thr *)pthread_getspecific(g_thrkey);
    if (t && t->fd >= 0) { close(t->fd); t->fd = -1; dq_drop(t); }
     
     
     
     
    if (t) { t->acks = 0; t->completes = 0; t->failed = 0; t->iseq = 0; t->sent = 0;
             t->in_txn = 0; t->in_death = 0; t->tx_at_death = 0;
             t->pending_sync = 0; t->tx_total = 0; t->cpl_given = 0;
             t->async_buf = 0; t->async_flush = 0; }
    g_first_thread_done = 0;
    for (int i = 0; i < SB_MAXSESS; i++)
        if (g_sess[i].used) pump_start(&g_sess[i]);
}

 

 
static const char *binder_sess_dump(void) {
    static __thread char b[128];
    int n = snprintf(b, sizeof b, "сессии:");
    for (int i = 0; i < SB_MAXSESS && n > 0 && n < (int)sizeof b; i++)
        n += snprintf(b + n, sizeof b - (size_t)n, " [%d]%s fd=%d", i,
                      g_sess[i].used ? "занят" : "свободен", g_sess[i].fd_guest);
    return b;
}

 
 
 
 
 
 
 
 
#if defined(TARGET_I386)
#define SB_V8_NEG 1
#else
#define SB_V8_NEG 0
#endif

static int g_proto_v8;             
static int g_proto_forced;         

static void proto_record_path(char *out, size_t n)
{
    out[0] = 0;
    const char *sl = strrchr(g_binder, '/');
    if (!sl) return;
    snprintf(out, n, "%.*s/binder.proto", (int)(sl - g_binder), g_binder);
}

static void proto_load(void)
{
    static int done;
    if (done) return;
    done = 1;
    if (!SB_V8_NEG) return;
    const char *e = getenv("GUEST_BINDER_PROTO");
    if (e && (*e == '7' || *e == '8')) {
        g_proto_forced = 1;
        g_proto_v8 = (*e == '8');
        return;
    }
    char path[640];
    proto_record_path(path, sizeof path);
    if (!path[0]) return;
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[64] = "";
    if (fgets(line, sizeof line, f) && atoi(line) == 8) {
        g_proto_v8 = 1;
        glog("binder: по записи %s гость ждёт протокол 8 — называю 8", path);
    }
    fclose(f);
}

static int proto_want(void)
{
    proto_load();
    return (SB_V8_NEG && g_proto_v8) ? 8 : 7;
}

 
static void proto_check_reject(struct sess *s)
{
    if (!SB_V8_NEG || !s || !s->ver_asked || s->engaged) return;
    if (s->proto != 7 || g_proto_forced) {
        glog("binder: гость закрыл описатель, не приняв версию %d", s->proto);
        return;
    }
    if (__atomic_exchange_n(&g_proto_v8, 1, __ATOMIC_ACQ_REL)) return;    
    glog("binder: ★ гость ОТКАЗАЛСЯ от протокола 7 — дальше называю и веду протокол 8 (64-битные структуры)");
    char path[640];
    proto_record_path(path, sizeof path);
    if (!path[0]) return;
    FILE *f = fopen(path, "w");
    if (!f) { glog("binder: не могу записать %s: %s", path, strerror(errno)); return; }
    fprintf(f, "8\n# гость (pid %d) отказался от протокола 7\n", (int)getpid());
    fclose(f);
    glog("binder: записано в %s", path);
}

static struct sess *sess_by_fd(int fd) {
    for (int i = 0; i < SB_MAXSESS; i++)
        if (g_sess[i].used && g_sess[i].fd_guest == fd) return &g_sess[i];
    return NULL;
}

 
 
static int binder_open_fake(void) {
    struct sess *s = NULL;
    for (int i = 0; i < SB_MAXSESS; i++) if (!g_sess[i].used) { s = &g_sess[i]; break; }
    if (!s) { errno = ENFILE; return -1; }
    int sv[2];
     
     
     
     
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0) return -1;
    memset(s, 0, sizeof *s);
    s->used = 1;
    s->fd_guest = sv[0];
    s->fd_wake = sv[1];
    pthread_mutex_init(&s->lock, NULL);
    s->pump_fd = -1;
    pthread_once(&g_once, thrkey_init);
    static int atfork_done;
    if (!atfork_done) { atfork_done = 1; pthread_atfork(NULL, NULL, binder_atfork_child); }
    pump_start(s);
    glog("binder: сессия открыта, fd=%d", s->fd_guest);
    return s->fd_guest;
}

static void binder_close_fake(struct sess *s) {
    glog("binder: сессия закрыта fd=%d", s->fd_guest);
     
 
 
 
 
    s->closing = 1;
    {
        pid_t me = getpid();
        int woke = 0;
        pthread_mutex_lock(&s->lock);
        for (int i = 0; i < s->ntfd; i++) {
            if (s->tfd[i].pid == me) {
                shutdown(s->tfd[i].fd, SHUT_RDWR);
                woke++;
            }
        }
        pthread_mutex_unlock(&s->lock);
        if (woke) glog("binder: закрытие сессии — разбужено потоков %d", woke);
    }
    if (s->map) target_munmap(h2g(s->map), s->map_size);
    while (s->blocks) { struct blk *b = s->blocks; s->blocks = b->next; free(b); }
    close(s->fd_wake);
    s->used = 0;
}

 

static struct thr *thr_get(struct sess *s) {
    pthread_once(&g_once, thrkey_init);
    struct thr *t = (struct thr *)pthread_getspecific(g_thrkey);
    if (t) return t;
    t = (struct thr *)calloc(1, sizeof *t);
    if (!t) return NULL;
    t->fd = -1;
    t->s = s;
    pthread_setspecific(g_thrkey, t);

    if (!g_binder[0]) { glog("binder: не задан GUEST_BINDER"); return t; }
    t->fd = connect_sock(g_binder);
    if (t->fd < 0) { glog("binder: демон недоступен (%s): %s", g_binder, strerror(errno)); return t; }
    struct { struct sb_hdr h; struct sb_hello b; } msg;
    memset(&msg, 0, sizeof msg);
    msg.h.len = sizeof msg;
    msg.h.type = SB_HELLO;
    msg.h.tid = (uint32_t)gettid();
    msg.h.flags = SB_F_MAGIC;              
    msg.b.pid = (uint32_t)getpid();
     
     
    msg.b.uid = (uint32_t)guest_guest_uid();
    msg.b.gid = (uint32_t)guest_guest_gid();
    msg.b.first = g_first_thread_done ? 0 : 1;
    g_first_thread_done = 1;
    if (write(t->fd, &msg, sizeof msg) != (ssize_t)sizeof msg) {
        glog("binder: HELLO не ушёл"); close(t->fd); t->fd = -1; return t;
    }
     
    struct sb_hdr ack;
     
     
     
    if (rd_all(t->fd, &ack, sizeof ack) < 0 ||
        (ack.flags & SB_F_MAGIC_MASK) != SB_F_MAGIC || ack.type != SB_R_OK) {
        glog("binder: демон не подтвердил HELLO — связь рвём, чтобы не поехал поток");
        close(t->fd); t->fd = -1; return t;
    }
    t->iseq = ack.tid;                      
     
    pthread_mutex_lock(&s->lock);
    if (s->ntfd < SB_MAXTHR) {
        s->tfd[s->ntfd].fd = t->fd;
        s->tfd[s->ntfd].pid = getpid();
        s->ntfd++;
    }
    pthread_mutex_unlock(&s->lock);
    return t;
}

 
 
 
 
static int wr_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        if (n == 0) { errno = EPIPE; return -1; }
        p += n; len -= (size_t)n;
    }
    return 0;
}

static int dsend(struct thr *t, uint16_t type, const void *body, uint32_t blen,
                 const int *fds, int nfds) {
    if (t->fd < 0) { errno = ENOTCONN; return -1; }
    struct sb_hdr h = { .len = (uint32_t)(sizeof h + blen), .type = type,
                         .nfds = (uint16_t)nfds, .tid = (uint32_t)gettid(),
                         .flags = SB_F_MAGIC };
    struct iovec iov[2] = { { &h, sizeof h }, { (void *)body, blen } };
    char cbuf[CMSG_SPACE(sizeof(int) * SB_MAXFDS)];    
    struct msghdr m;
    memset(&m, 0, sizeof m);
    m.msg_iov = iov;
    m.msg_iovlen = blen ? 2 : 1;
    if (nfds > SB_MAXFDS) { errno = E2BIG; return -1; }    
    if (nfds > 0) {
        memset(cbuf, 0, sizeof cbuf);
        m.msg_control = cbuf;
        m.msg_controllen = CMSG_SPACE(sizeof(int) * (size_t)nfds);
        struct cmsghdr *cm = CMSG_FIRSTHDR(&m);
        cm->cmsg_level = SOL_SOCKET;
        cm->cmsg_type = SCM_RIGHTS;
        cm->cmsg_len = CMSG_LEN(sizeof(int) * (size_t)nfds);
        memcpy(CMSG_DATA(cm), fds, sizeof(int) * (size_t)nfds);
    }
    if (nfds > 0) {
        m.msg_iovlen = 1;                        
        ssize_t n;
        do { n = sendmsg(t->fd, &m, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
        if (n != (ssize_t)sizeof h) return -1;
        return blen ? wr_all(t->fd, body, blen) : 0;
    }
    if (wr_all(t->fd, &h, sizeof h) < 0) return -1;
    return blen ? wr_all(t->fd, body, blen) : 0;
}

 
static int drecv(struct thr *t, uint8_t **bufp, size_t cap, int *fds, int *nfds) {
    *nfds = 0;
    struct sb_hdr h;
    char cbuf[CMSG_SPACE(sizeof(int) * SB_MAXFDS)];    
    struct iovec iov = { &h, sizeof h };
    struct msghdr m;
    memset(&m, 0, sizeof m);
    m.msg_iov = &iov; m.msg_iovlen = 1;
    m.msg_control = cbuf; m.msg_controllen = sizeof cbuf;
     
     
     
     
     
     
     
     
     
     
     
     
    ssize_t n;
    do { n = recvmsg(t->fd, &m, MSG_WAITALL); }
    while (n < 0 && errno == EINTR && (glog("binder: чтение прервано сигналом, повтор"), 1));
    if (n <= 0) return -1;
    if (n < (ssize_t)sizeof h) {
        glog("binder: заголовок пришёл кусками (%zd из %zu), дочитываем", n, sizeof h);
        if (rd_all(t->fd, (uint8_t *)&h + n, sizeof h - (size_t)n) < 0) return -1;
    }
    for (struct cmsghdr *cm = CMSG_FIRSTHDR(&m); cm; cm = CMSG_NXTHDR(&m, cm)) {
        if (cm->cmsg_level != SOL_SOCKET || cm->cmsg_type != SCM_RIGHTS) continue;
        int cnt = (int)((cm->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        for (int i = 0; i < cnt; i++) {
            int fd;
            memcpy(&fd, CMSG_DATA(cm) + i * sizeof(int), sizeof(int));
            if (*nfds < SB_MAXFDS) fds[(*nfds)++] = fd;
            else close(fd);                   
        }
    }
     
 
 
 
 
 
 
 
    if ((int)h.nfds != *nfds) {
        static unsigned said;
        if (said < 8 || (said & 1023) == 0)
            glog("binder: ★ описателей пришло %d, а обещано %u (тип %u, №%u, случай %u)",
                 *nfds, (unsigned)h.nfds, (unsigned)h.type, (unsigned)h.tid, said + 1);
        said++;
    }

     
     
    if ((h.flags & SB_F_MAGIC_MASK) != SB_F_MAGIC || h.len < sizeof h) {
        glog("binder: ★ РАССИНХРОН — заголовок не наш: len=%u тип=%u №%u флаги=%#x",
             h.len, h.type, h.tid, h.flags);
        errno = EPROTO;
        return -1;
    }
    if (t->iseq && h.tid != t->iseq + 1)
        glog("binder: ★ пропуск сообщений: ждали №%u, пришло №%u (тип %u)",
             t->iseq + 1, h.tid, h.type);
    t->iseq = h.tid;

    uint32_t blen = h.len - (uint32_t)sizeof h;
    uint8_t *buf = *bufp;
    if (blen > cap - sizeof h) {
         
         
         
         
         
         
         
         
         
         
         
         
         
        if (h.len > SB_MAXMSG) {
            glog("binder: ★ длина сообщения невозможна: %u (тип %u) — связь рвём",
                 h.len, h.type);
            errno = EPROTO;
            return -1;
        }
        if (t->rxcap < h.len) {
            uint8_t *p = (uint8_t *)realloc(t->rx, h.len);
            if (!p) { glog("binder: нет памяти под сообщение %u байт", h.len); errno = ENOMEM; return -1; }
            t->rx = p;
            t->rxcap = h.len;
            glog("binder: приёмный буфер потока вырос до %u байт (тип %u)", h.len, h.type);
        }
        buf = t->rx;
        *bufp = buf;
    }
    memcpy(buf, &h, sizeof h);
    size_t got = 0;
    while (got < blen) {
         
         
         
         
         
        struct pollfd pf = { .fd = t->fd, .events = POLLIN };
        int pr;
        do { pr = poll(&pf, 1, 10000); } while (pr < 0 && errno == EINTR);
        if (pr <= 0) {
            glog("binder: ★ тело не пришло: ждали %u байт, взяли %zu; "
                 "заголовок тип=%u len=%u №%u флаги=%#x",
                 blen, got, h.type, h.len, h.tid, h.flags);
            errno = EPROTO;
            return -1;
        }
        ssize_t k = read(t->fd, buf + sizeof h + got, blen - got);
        if (k < 0 && errno == EINTR) continue;       
        if (k <= 0) return -1;
        got += (size_t)k;
    }
     
    if (h.type == SB_R_TRANSACTION && blen >= sizeof(struct sb_txn) &&
        (((const struct sb_txn *)(buf + sizeof h))->flags & TF_ONE_WAY)) {
        async_on_received(t);
    }
    return (int)(sizeof h + blen);
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int guest_mem_fd(void) {
    static int fd = -2;
    if (fd == -2) {
        fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
        if (fd < 0) glog("binder: нет /proc/self/mem (%s) — читаю напрямую",
                         strerror(errno));
    }
    return fd;
}

 
static int guest_read(void *dst, abi_ulong gaddr, size_t len) {
    if (len == 0) return 1;
    int fd = guest_mem_fd();
    if (fd < 0) {                          
        memcpy(dst, g2h_untagged(gaddr), len);
        return 1;
    }
     
     
    off_t off = (off_t)(uintptr_t)g2h_untagged(gaddr);
    uint8_t *p = (uint8_t *)dst;
    size_t done = 0;
    while (done < len) {
        ssize_t n = pread(fd, p + done, len - done, off + (off_t)done);
        if (n > 0) { done += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return 0;
    }
    return 1;
}

static int obj_off_ok(uint64_t off, size_t dsize) {
    if (off & 3) return 0;
    if (off > dsize) return 0;
    return dsize - (size_t)off >= sizeof(struct flat_binder_object);
}

 
 
 
static void bad_offsets(struct sess *s, const char *dir,
                        const struct binder_transaction_data *tr,
                        const uint8_t *b, size_t dsz, size_t osz, uint64_t i) {
    const uint8_t *pool = s ? s->map : NULL;
    size_t plen = s ? s->map_size : 0;
    const uint8_t *dbuf = (const uint8_t *)(uintptr_t)tr->data.ptr.buffer;
    const uint8_t *obuf = (const uint8_t *)(uintptr_t)tr->data.ptr.offsets;
    int d_in = pool && dbuf >= pool && dbuf < pool + plen;
    int o_in = pool && obuf >= pool && obuf < pool + plen;
    const uint64_t *offs = (const uint64_t *)(b + sizeof(struct sb_txn) + dsz);
    bwarn("binder: %s — БИТОЕ смещение объекта №%llu = 0x%llx "
         "(цель=%u код=%u флаги=0x%x данные=%zu смещений=%zu) "
         "буфер=%p%s объекты=%p%s пул=%p+%zu",
         dir, (unsigned long long)i, (unsigned long long)offs[i],
         (unsigned)tr->target.handle, (unsigned)tr->code, (unsigned)tr->flags,
         dsz, osz, (const void *)dbuf, d_in ? " (В ПУЛЕ)" : "",
         (const void *)obuf, o_in ? " (В ПУЛЕ)" : "", (const void *)pool, plen);
    char line[256]; size_t n = 0;
    for (uint64_t k = 0; k < osz / 8 && k < 8; k++)
        n += (size_t)snprintf(line + n, sizeof line - n, "%s0x%llx",
                              k ? " " : "", (unsigned long long)offs[k]);
    bwarn("binder:   смещения целиком: %s", line);
}

 

struct outbuf { uint8_t *p; size_t cap, len; int v8; };

static int out_raw(struct outbuf *o, uint32_t cmd, const void *body, size_t blen) {
    if (o->len + 4 + blen > o->cap) return -1;          
    memcpy(o->p + o->len, &cmd, 4);
    o->len += 4;
    if (blen) { memcpy(o->p + o->len, body, blen); o->len += blen; }
    return 0;
}

 
static int out_put(struct outbuf *o, uint32_t cmd, const void *body, size_t blen) {
    if (!o->v8) return out_raw(o, cmd, body, blen);
    switch (cmd) {
    case BR_TRANSACTION: case BR_REPLY: {
        const struct binder_transaction_data *a = (const struct binder_transaction_data *)body;
        struct binder_transaction_data_v8 b;
        memset(&b, 0, sizeof b);
        b.target.ptr = a->target.ptr;
        b.cookie = a->cookie;
        b.code = a->code;
        b.flags = a->flags;
        b.sender_pid = (int32_t)a->sender_pid;
        b.sender_euid = (uint32_t)a->sender_euid;
        b.data_size = a->data_size;
        b.offsets_size = a->offsets_size;
        b.data.ptr.buffer = a->data.ptr.buffer;
        b.data.ptr.offsets = a->data.ptr.offsets;
        return out_raw(o, cmd == BR_TRANSACTION ? BR8_TRANSACTION : BR8_REPLY, &b, sizeof b);
    }
    case BR_INCREFS: case BR_ACQUIRE: case BR_RELEASE: case BR_DECREFS: case BR_ATTEMPT_ACQUIRE: {
        const struct binder_ptr_cookie *a = (const struct binder_ptr_cookie *)body;
        struct binder_ptr_cookie_v8 b = { .ptr = a->ptr, .cookie = a->cookie };
        uint32_t c8 = cmd == BR_INCREFS ? BR8_INCREFS : cmd == BR_ACQUIRE ? BR8_ACQUIRE :
                      cmd == BR_RELEASE ? BR8_RELEASE : cmd == BR_DECREFS ? BR8_DECREFS :
                      BR8_ATTEMPT_ACQUIRE;
        return out_raw(o, c8, &b, sizeof b);
    }
    case BR_DEAD_BINDER: case BR_CLEAR_DEATH_NOTIFICATION_DONE: {
        uint32_t c32;
        memcpy(&c32, body, 4);
        uint64_t c = c32;
        return out_raw(o, cmd == BR_DEAD_BINDER ? BR8_DEAD_BINDER : BR8_CLEAR_DEATH_NOTIFICATION_DONE,
                       &c, sizeof c);
    }
    default:
        return out_raw(o, cmd, body, blen);
    }
}

 
static int v8_expand_ok(const binder_size_t *offs, size_t noff, size_t dsz) {
    size_t prev = 0;
    for (size_t i = 0; i < noff; i++) {
        uint64_t o = offs[i];
        if ((o & 3) || o < prev || o > dsz || dsz - (size_t)o < sizeof(struct flat_binder_object)) return 0;
        prev = (size_t)o + sizeof(struct flat_binder_object);
    }
    return 1;
}

 
static void v8_expand(const uint8_t *src, size_t dsz, const binder_size_t *offs, size_t noff, uint8_t *dst) {
    size_t sp = 0, dp = 0;
    size_t ndsz = dsz + noff * 8;
    uint64_t *no = (uint64_t *)(dst + ndsz);
    for (size_t i = 0; i < noff; i++) {
        size_t o = offs[i];
        memcpy(dst + dp, src + sp, o - sp);
        dp += o - sp;
        struct flat_binder_object f;
        memcpy(&f, src + o, sizeof f);
        struct flat_binder_object_v8 n;
        memset(&n, 0, sizeof n);
        n.type = f.type;
        n.flags = f.flags;
        n.binder = f.binder;
        n.cookie = f.cookie;
        no[i] = dp;
        memcpy(dst + dp, &n, sizeof n);
        dp += sizeof n;
        sp = o + sizeof f;
    }
    memcpy(dst + dp, src + sp, dsz - sp);
}

 
 
static size_t v8_shrink(uint8_t *d, size_t dsz, size_t noff) {
    const uint64_t *offs = (const uint64_t *)(d + dsz);
    uint64_t *no = (uint64_t *)malloc(noff * 8);
    uint8_t *tmp = (uint8_t *)malloc(dsz ? dsz : 1);
    size_t sp = 0, dp = 0;
    if (!no || !tmp) { free(no); free(tmp); return dsz; }
    for (size_t i = 0; i < noff; i++) {
        uint64_t o = offs[i];
        if ((o & 3) || o < sp || o > dsz || dsz - (size_t)o < sizeof(struct flat_binder_object_v8)) {
            free(no); free(tmp);
            return dsz;                      
        }
        memcpy(tmp + dp, d + sp, (size_t)o - sp);
        dp += (size_t)o - sp;
        struct flat_binder_object_v8 f;
        memcpy(&f, d + o, sizeof f);
        struct flat_binder_object n;
        memset(&n, 0, sizeof n);
        n.type = f.type;
        n.flags = f.flags;
        n.binder = (binder_uintptr_t)f.binder;
        n.cookie = (binder_uintptr_t)f.cookie;
        no[i] = dp;
        memcpy(tmp + dp, &n, sizeof n);
        dp += sizeof n;
        sp = (size_t)o + sizeof f;
    }
    memcpy(tmp + dp, d + sp, dsz - sp);
    dp += dsz - sp;
    memcpy(d, tmp, dp);
    memcpy(d + dp, no, noff * 8);
    free(no); free(tmp);
    return dp;
}

 
static int msg_to_br(struct thr *t, struct sess *s, uint8_t *msg, int *fds, int nfds,
                     struct outbuf *o) {
    (void)t;
    struct sb_hdr *h = (struct sb_hdr *)msg;
    uint8_t *body = msg + sizeof *h;
    switch (h->type) {
    case SB_R_COMPLETE:      t->cpl_given++;
                              return out_put(o, BR_TRANSACTION_COMPLETE, NULL, 0);
    case SB_R_DEAD_REPLY:
    case SB_R_FAILED_REPLY:
         
         
         
         
         
         
         
         
         
         
         
         
         
         
         
         
         
         
        if (t->pending_sync > 0) {
            t->pending_sync--;      
        }
        tr_end(t, 'o');
        if (t->completes > 0) {
            t->completes--;
            glog("binder: транзакция не прошла (%s) — подтверждение отменено",
                 h->type == SB_R_DEAD_REPLY ? "мёртвый адресат" : "отказ");
        }
        return out_put(o, h->type == SB_R_DEAD_REPLY ? BR_DEAD_REPLY : BR_FAILED_REPLY,
                       NULL, 0);
    case SB_R_SPAWN:         return out_put(o, BR_SPAWN_LOOPER, NULL, 0);
    case SB_R_OK:            return out_put(o, BR_NOOP, NULL, 0);
     
    case SB_R_DEAD_BINDER: {
        binder_uintptr_t c = (binder_uintptr_t)*(uint64_t *)body;
        int r = out_put(o, BR_DEAD_BINDER, &c, sizeof c);
         
 
 
 
 
        if (r == 0) { t->in_death++; t->tx_at_death = t->tx_total; }
        return r;
    }
    case SB_R_CLEAR_DEATH_DONE: {
        binder_uintptr_t c = (binder_uintptr_t)*(uint64_t *)body;
        return out_put(o, BR_CLEAR_DEATH_NOTIFICATION_DONE, &c, sizeof c);
    }
    case SB_R_ERROR:         return out_put(o, BR_ERROR, body, 4);
    case SB_R_ACK:
         
        if (t->acks > 0) t->acks--;
        return 0;
    case SB_R_REF: {
        struct sb_ptr_cookie *pc = (struct sb_ptr_cookie *)body;
        struct binder_ptr_cookie b = { .ptr = pc->ptr, .cookie = pc->cookie };
        uint32_t cmd = pc->op == SB_REF_INCREFS ? BR_INCREFS :
                       pc->op == SB_REF_ACQUIRE ? BR_ACQUIRE :
                       pc->op == SB_REF_RELEASE ? BR_RELEASE : BR_DECREFS;
        return out_put(o, cmd, &b, sizeof b);
    }
    case SB_R_TRANSACTION:
    case SB_R_REPLY: {
        struct sb_txn *m = (struct sb_txn *)body;
        uint8_t *data = body + sizeof *m;
         
        size_t noff = (size_t)m->offsets_size / 8;
        size_t osz_g = noff * sizeof(binder_size_t);
        size_t total = (size_t)m->data_size + osz_g;
        void *buf = buf_alloc(s, total);
        if (!buf) {
            bwarn("binder: приёмный буфер кончился (%zu байт)", total);
            return out_put(o, BR_FAILED_REPLY, NULL, 0);
        }
         
 
 
 
 
 
 
 
 
 
 
 
 
 
 
         
 
 
 
 
 
 
 
 
 
 
 
 
 
         
 
         
 
 
 
 
 
 
 
 
        if (h->type == SB_R_TRANSACTION && m->data_size < 8 &&
            m->code != 0x5f504e47u   &&
            m->code != 0x5f4e5446u  ) {
            bwarn("binder: приём — ПУСТАЯ транзакция (данные=%llu код=%u "
                  "флаги=0x%x узел=0x%llx)",
                  (unsigned long long)m->data_size, (unsigned)m->code,
                  (unsigned)m->flags, (unsigned long long)m->node_ptr);
        }
         
 
 
 
 
 
 
 
 
 
        if (h->type == SB_R_TRANSACTION) {
            static int seen, seen_app;
            int want = seen < SB_TRACE_N;
            char ifn0[48]; ifn0[0] = 0;
            if ((m->flags & TF_ONE_WAY) || want)
                txn_iface(data, m->data_size, ifn0, sizeof ifn0);
            if (!want && (m->flags & TF_ONE_WAY) &&
                !strcmp(ifn0, "IApplicationThread") && seen_app < 4000) {
                want = 1; seen_app++;
            } else if (want) seen++;
            if (want) {
                char ifn[48], ob[40];
                memcpy(ifn, ifn0, sizeof ifn);
                if (!ifn[0]) txn_iface(data, m->data_size, ifn, sizeof ifn);
                char sn[42];
                txn_obj0(data, m->data_size, data + m->data_size, noff, ob, sizeof ob);
                txn_sniff(data, m->data_size, sn, sizeof sn);
                bwarn("binder: ГОСТЮ  %-22s код=%-4u %s данные=%llu%s %s",
                      ifn[0] ? ifn : "?", (unsigned)m->code,
                      (m->flags & TF_ONE_WAY) ? "одност" : "  ждёт",
                      (unsigned long long)m->data_size, ob, sn);
            }
        }
        memcpy(buf, data, (size_t)m->data_size);
        if (noff) {
            const uint64_t *wo = (const uint64_t *)(data + m->data_size);
            binder_size_t *go = (binder_size_t *)((uint8_t *)buf + m->data_size);
            for (size_t i = 0; i < noff; i++) go[i] = (binder_size_t)wo[i];
        }
        buf_note(s, buf, (uint32_t)m->data_size, (uint32_t)osz_g);
         
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
        if (noff) {
            binder_size_t *offs = (binder_size_t *)((uint8_t *)buf + m->data_size);
            for (uint64_t i = 0; i < noff; i++) {
                 
                 
                if (!obj_off_ok(offs[i], (size_t)m->data_size)) {
                    bwarn("binder: приём — смещение объекта №%llu = 0x%llx вне данных "
                         "(данные=%llu смещений=%llu код=%u)",
                         (unsigned long long)i, (unsigned long long)offs[i],
                         (unsigned long long)m->data_size,
                         (unsigned long long)m->offsets_size, (unsigned)m->code);
                    break;
                }
                struct flat_binder_object *fo =
                    (struct flat_binder_object *)((uint8_t *)buf + offs[i]);
                if (fo->type != BINDER_TYPE_FD) continue;
                 
 
 
 
 
 
 
 
                if (fo->handle < (uint32_t)nfds) fo->handle = (uint32_t)fds[fo->handle];
                else {
                    static unsigned lost;
                    if (lost < 8 || (lost & 1023) == 0)
                        bwarn("binder: приём — описатель №%u вне пришедших (%d) — ставлю −1 "
                              "(всего %u; тип сообщения %u, код %u)",
                              (unsigned)fo->handle, nfds, lost + 1,
                              (unsigned)h->type, (unsigned)m->code);
                    lost++;
                    fo->handle = (uint32_t)-1;
                }
            }
        }
         
        size_t gdsz = (size_t)m->data_size, gosz = osz_g;
        if (s->proto == 8 && noff) {
            const binder_size_t *go8 = (const binder_size_t *)((uint8_t *)buf + m->data_size);
            if (v8_expand_ok(go8, noff, gdsz)) {
                size_t nd = gdsz + noff * 8, no8 = noff * 8;
                uint8_t *nb = (uint8_t *)buf_alloc(s, nd + no8);
                if (!nb) {
                    bwarn("binder: приёмный буфер (протокол 8) кончился (%zu байт)", nd + no8);
                    buf_free(s, (uint64_t)(uintptr_t)buf);
                    return out_put(o, BR_FAILED_REPLY, NULL, 0);
                }
                v8_expand((const uint8_t *)buf, gdsz, go8, noff, nb);
                buf_note(s, nb, (uint32_t)nd, (uint32_t)no8);
                buf_free(s, (uint64_t)(uintptr_t)buf);
                buf = nb;
                gdsz = nd;
                gosz = no8;
            } else {
                bwarn("binder: приём (протокол 8) — смещения объектов не по порядку/битые, объекты не отдаю "
                      "(код=%u данные=%zu смещений=%zu)", (unsigned)m->code, gdsz, noff);
                gosz = 0;
                buf_note(s, buf, (uint32_t)gdsz, 0);    
            }
        }
        struct binder_transaction_data tr;
        memset(&tr, 0, sizeof tr);
        tr.target.ptr = (h->type == SB_R_TRANSACTION) ? m->node_ptr : 0;
        tr.cookie = (h->type == SB_R_TRANSACTION) ? m->node_cookie : 0;
        tr.code = m->code;
        tr.flags = m->flags;
        tr.sender_pid = (pid_t)m->sender_pid;
        tr.sender_euid = (uid_t)m->sender_euid;
        tr.data_size = (binder_size_t)gdsz;
        tr.offsets_size = (binder_size_t)gosz;
         
        tr.data.ptr.buffer = h2g(buf);
        tr.data.ptr.offsets = h2g((uint8_t *)buf + gdsz);
        uint32_t cmd = (h->type == SB_R_TRANSACTION) ? BR_TRANSACTION : BR_REPLY;
        if (out_put(o, cmd, &tr, sizeof tr) < 0) { buf_free(s, (uint64_t)(uintptr_t)buf); return -1; }
         
 
 
 
 
 
 
        if (cmd == BR_TRANSACTION) {
             
 
 
 
 
 
            if ((m->flags & TF_ONE_WAY) && !t->orphan_msg) async_hold(t, s, (uint64_t)(uintptr_t)buf);
            if (t->in_txn < (int)(sizeof t->inbuf / sizeof t->inbuf[0])) {
                t->incode[t->in_txn] = m->code;
                t->inbuf[t->in_txn++] = (uint64_t)(uintptr_t)buf;
            }
            if (guest_trace_on()) {
                char ifn[48];
                txn_iface(data, m->data_size, ifn, sizeof ifn);
                if (m->flags & TF_ONE_WAY)
                    guest_trace_mark('N', "binder< %s#%u 1", ifn[0] ? ifn : "?",
                                     (unsigned)m->code);
                else tr_begin(t, 'i', ifn, m->code);
            }
        } else {
            if (t->pending_sync > 0)
                t->pending_sync--;      
            tr_end(t, 'o');
        }
        return 0;
    }
    default:
        bwarn("binder: непонятное сообщение демона %u", h->type);
        return 0;
    }
}

 
 
 
 
 
 
 
 
 
 
 
 
 
static int drecv_to(struct thr *t, uint8_t **bufp, size_t cap, int *fds, int *nfds,
                    int total_ms, const char *what)
{
    int left = total_ms;
    while (left > 0) {
        struct pollfd pf = { .fd = t->fd, .events = POLLIN };
        int step = left > 5000 ? 5000 : left;
        int pr;
        do { pr = poll(&pf, 1, step); } while (pr < 0 && errno == EINTR);
        if (pr < 0) return -1;
        if (pr > 0) return drecv(t, bufp, cap, fds, nfds);
        left -= step;
        glog("binder: ★ %s — тишина от демона %d с (ждём подтверждений %d, "
             "долг гостю %d, отказов %d, последняя посылка код=%u флаги=0x%x цель=%u)",
             what, (total_ms - left) / 1000, t->acks, t->completes, t->failed,
             t->last_code, t->last_flags, t->last_handle);
    }
    return -2;
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static size_t bc_process(struct thr *t, struct sess *s, const uint8_t *w, size_t wlen,
                         int *unsup, uint32_t *unsup_cmd) {
    size_t off = 0;
    *unsup = 0;
    *unsup_cmd = 0;
    while (off + 4 <= wlen) {
        uint32_t cmd;
        memcpy(&cmd, w + off, 4);
        off += 4;
        switch (cmd) {
        case BC_TRANSACTION:
        case BC_REPLY: {
            if (off + sizeof(struct binder_transaction_data) > wlen) return off - 4;
            const struct binder_transaction_data *tr =
                (const struct binder_transaction_data *)(w + off);
            off += sizeof *tr;
            size_t dsz = (size_t)tr->data_size;
             
             
            const size_t osw = (s && s->proto == 8) ? 8 : sizeof(binder_size_t);    
            size_t noff = (size_t)tr->offsets_size / osw;
            size_t osz = noff * 8;
            uint32_t blen = (uint32_t)(sizeof(struct sb_txn) + dsz + osz);
            uint8_t *b = (uint8_t *)malloc(blen);
            if (!b) return off - 4;
            struct sb_txn *m = (struct sb_txn *)b;
            memset(m, 0, sizeof *m);
            m->handle = tr->target.handle;
            m->code = tr->code;
            m->flags = tr->flags;
            m->data_size = dsz;
            m->offsets_size = osz;
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
            uint8_t *rawoff = NULL;
            int ok = guest_read(b + sizeof *m, (abi_ulong)tr->data.ptr.buffer, dsz);
            if (ok && noff) {
                rawoff = (uint8_t *)malloc(noff * osw);
                ok = rawoff && guest_read(rawoff, (abi_ulong)tr->data.ptr.offsets,
                                          noff * osw);
            }
            if (!ok) {
                const uint8_t *pool = s ? s->map : NULL;
                const uint8_t *dbuf = (const uint8_t *)g2h_untagged(tr->data.ptr.buffer);
                bwarn("binder: посылка с НЕДОСТУПНОЙ памятью гостя — отказ "
                     "(цель=%u код=%u данные=0x%llx+%zu смещений=%zu)%s",
                     (unsigned)tr->target.handle, (unsigned)tr->code,
                     (unsigned long long)tr->data.ptr.buffer, dsz, noff,
                     (pool && dbuf >= pool && dbuf < pool + s->map_size)
                         ? " ДАННЫЕ В НАШЕМ ПУЛЕ" : "");
                free(rawoff);
                free(b);
                 
                 
                t->failed++;
                break;
            }
            if (noff) {
                uint64_t *wo = (uint64_t *)(b + sizeof *m + dsz);
                for (size_t i = 0; i < noff; i++)
                    wo[i] = (osw == 8) ? ((const uint64_t *)rawoff)[i] : ((const binder_size_t *)rawoff)[i];
                free(rawoff);
                if (osw == 8) {                    
                    dsz = v8_shrink(b + sizeof *m, dsz, noff);
                    m->data_size = dsz;
                    blen = (uint32_t)(sizeof(struct sb_txn) + dsz + osz);
                }
            }
             
 
 
 
 
 
 
 
 
 
 
 
 
 
             
 
 
            if (cmd == BC_REPLY && dsz == 4 && noff == 0) {
                uint32_t w0 = 0, w1 = 0;
                memcpy(&w0, b + sizeof *m, 4);
                if (dsz >= 8) memcpy(&w1, b + sizeof *m + 4, 4);
                static int said_rp;
                if (w0 >= 0x00010000u && w0 < 0xc0000000u && said_rp < 60) {
                    said_rp++;
                    const uint8_t *pool = s ? s->map : NULL;
                    const uint8_t *dbuf = (const uint8_t *)g2h_untagged(tr->data.ptr.buffer);
                    int in_pool = pool && dbuf >= pool && dbuf < pool + s->map_size;
                     
 
                    uint32_t again = 0;
                    (void)guest_read(&again, (abi_ulong)tr->data.ptr.buffer, 4);
                    bwarn("binder: ★★★ ОТВЕЧАЕМ УКАЗАТЕЛЕМ 0x%08x (следом 0x%08x, "
                          "повторное чтение 0x%08x): данные=%zu флаги=0x%x "
                          "адрес=0x%llx%s | отвечаем на код=%u, входящих в работе %d, "
                          "своих вызовов без ответа %d, посылок за жизнь %d",
                          (unsigned)w0, (unsigned)w1, (unsigned)again, dsz,
                          (unsigned)tr->flags,
                          (unsigned long long)tr->data.ptr.buffer,
                          in_pool ? " ★В НАШЕМ ПУЛЕ★" : " (куча гостя)",
                          t->in_txn > 0 ? (unsigned)t->incode[t->in_txn - 1] : 0u,
                          t->in_txn, t->pending_sync, t->tx_total);
                }
            }
             
             
 
 
 
 
 
            int fds[SB_MAXFDS]; int nfds = 0, nfdobj = 0;
            if (osz) {
                uint64_t *offs = (uint64_t *)(b + sizeof *m + dsz);
                for (uint64_t i = 0; i < noff; i++) {
                     
                     
                     
                     
                     
                     
                     
                    if (!obj_off_ok(offs[i], dsz)) {
                        bad_offsets(s, "отправка", tr, b, dsz, osz, i);
                        break;
                    }
                    struct flat_binder_object *fo =
                        (struct flat_binder_object *)(b + sizeof *m + offs[i]);
                    if (fo->type != BINDER_TYPE_FD) continue;
                    nfdobj++;
                    if (nfds < SB_MAXFDS) fds[nfds++] = (int)fo->handle;
                }
            }
            if (nfdobj > nfds)
                bwarn("binder: ★★★ Р-133: описателей в посылке %d — больше предела %d; демон откажет ей "
                      "(код=%u данные=%zu)", nfdobj, SB_MAXFDS, (unsigned)tr->code, dsz);
             
 
 
 
 
 
            char ifn[48]; ifn[0] = 0;
            int oneway = (tr->flags & TF_ONE_WAY) != 0;
            int traced = guest_trace_on();
            if (cmd == BC_TRANSACTION) {
                static int seen_tx, seen_app;
                int want = seen_tx < SB_TRACE_N;
                 
 
 
 
 
                if (oneway || want || traced) txn_iface(b + sizeof *m, dsz, ifn, sizeof ifn);
                 
 
 
 
 
 
 
                if (!want && oneway && !strcmp(ifn, "IApplicationThread") &&
                    seen_app < 4000) { want = 1; seen_app++; }
                else if (want) seen_tx++;
                if (want) {
                    char ob[40], sn[42];
                    txn_obj0(b + sizeof *m, dsz, b + sizeof *m + dsz, noff, ob, sizeof ob);
                    txn_sniff(b + sizeof *m, dsz, sn, sizeof sn);
                    bwarn("binder: ОТ ГОСТЯ %-22s код=%-4u %s данные=%llu%s %s",
                          ifn[0] ? ifn : "?", (unsigned)tr->code,
                          oneway ? "одност" : "  ждёт",
                          (unsigned long long)dsz, ob, sn);
                }
            }
             
 
 
 
 
 
 
 
 
 
 
 
 
            if (dsend(t, cmd == BC_TRANSACTION ? SB_TRANSACT : SB_REPLY,
                      b, blen, fds, nfds) < 0) {
                t->lost++;
                 
 
 
 
 
 
 
                if (t->lost <= 8 || (t->lost % 4096) == 0)
                    bwarn("binder: ★★★ ПОСЫЛКА НЕ УШЛА К ДЕМОНУ: %s код=%u %s данные=%zu "
                          "(fd=%d, ошибка «%s»); потеряно посылок %u",
                          ifn[0] ? ifn : "?", (unsigned)tr->code,
                          oneway ? "односторонняя" : "ждёт ответа", dsz,
                          t->fd, strerror(errno), t->lost);
                 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
                free(b);
                t->failed++;
                break;
            }
            t->acks++;
            t->sent++;
            t->tx_total++;
            if (cmd == BC_TRANSACTION && !(tr->flags & TF_ONE_WAY)) t->pending_sync++;
            t->last_code = tr->code;
            t->last_flags = tr->flags;
            t->last_handle = tr->target.handle;
            free(b);
             
 
 
 
 
            if (cmd == BC_REPLY) tr_end(t, 'i');
            else if (traced) {
                if (oneway) guest_trace_mark('N', "binder> %s#%u 1",
                                             ifn[0] ? ifn : "?", (unsigned)tr->code);
                else tr_begin(t, 'o', ifn, tr->code);
            }
             
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
            if (t->will_read) t->completes++;
            else {
                t->cpl_orphan++;
                if (t->cpl_orphan <= 8 || (t->cpl_orphan % 100) == 0)
                    bwarn("binder: посылка БЕЗ чтения (код=%u флаги=0x%x цель=%u) — "
                          "подтверждение не начисляю, его никто не ждёт "
                          "(всего таких %d)",
                          tr->code, tr->flags, tr->target.handle, t->cpl_orphan);
            }
            break;
        }
        case BC_FREE_BUFFER: {
             
             
            if (off + 4 > wlen) return off - 4;
            uint32_t gaddr;
            memcpy(&gaddr, w + off, 4);
            off += 4;
            uint64_t addr = (uint64_t)(uintptr_t)g2h_untagged(gaddr);
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
             
            static int nofree = -1;
            if (nofree < 0) { const char *e = getenv("GUEST_NO_FREEBUF"); nofree = (e && *e == '1'); }
            uint32_t dsz = 0, osz = 0;
            const int fb8 = (s && s->proto == 8);
            const size_t fosw = fb8 ? 8 : sizeof(binder_size_t);
            const size_t ffsz = fb8 ? sizeof(struct flat_binder_object_v8) : sizeof(struct flat_binder_object);
            if (!nofree && buf_take(s, addr, &dsz, &osz) && osz >= fosw) {
                const uint8_t *base = (const uint8_t *)(uintptr_t)addr;
                for (uint64_t i = 0; i < osz / fosw; i++) {
                    uint64_t offi;
                    if (fb8) memcpy(&offi, base + dsz + i * 8, 8);
                    else { binder_size_t o4; memcpy(&o4, base + dsz + i * 4, 4); offi = o4; }
                    if ((offi & 3) || offi > dsz || dsz - (size_t)offi < ffsz) break;
                    uint32_t otype, ohandle;
                    uint64_t obinder, ocookie;
                    memcpy(&otype, base + offi, 4);
                    memcpy(&ohandle, base + offi + 8, 4);
                    if (fb8) { memcpy(&obinder, base + offi + 8, 8); memcpy(&ocookie, base + offi + 16, 8); }
                    else {
                        binder_uintptr_t b4, c4;
                        memcpy(&b4, base + offi + 8, 4); memcpy(&c4, base + offi + 12, 4);
                        obinder = b4; ocookie = c4;
                    }
                    if (otype == BINDER_TYPE_BINDER || otype == BINDER_TYPE_WEAK_BINDER) {
                        struct sb_ptr_cookie nf = {
                            .op = (otype == BINDER_TYPE_BINDER) ? 1u : 0u,
                            .ptr = obinder,
                            .cookie = ocookie,
                        };
                        dsend(t, SB_NODE_FREE, &nf, sizeof nf, NULL, 0);
                        continue;
                    }
                    if (otype != BINDER_TYPE_HANDLE && otype != BINDER_TYPE_WEAK_HANDLE)
                        continue;    
                    struct sb_ref r = { .op = (otype == BINDER_TYPE_HANDLE)
                                                ? SB_REF_RELEASE_BUF : SB_REF_DECREFS_BUF,
                                         .handle = ohandle };
                    dsend(t, SB_REF, &r, sizeof r, NULL, 0);
                }
            }
            for (int k = 0; k < t->in_txn; k++) {
                if (t->inbuf[k] != addr) continue;
                for (int j = k + 1; j < t->in_txn; j++) {
                    t->inbuf[j - 1] = t->inbuf[j];
                    t->incode[j - 1] = t->incode[j];    
                }
                t->in_txn--;
                break;
            }
            buf_free(s, addr);
             
 
 
 
 
 
            async_on_free(t, s, addr);
            break;
        }
        case BC_INCREFS: case BC_ACQUIRE: case BC_RELEASE: case BC_DECREFS: {
            if (off + 4 > wlen) return off - 4;
            struct sb_ref r;
            memcpy(&r.handle, w + off, 4);
            off += 4;
            r.op = cmd == BC_INCREFS ? SB_REF_INCREFS : cmd == BC_ACQUIRE ? SB_REF_ACQUIRE :
                   cmd == BC_RELEASE ? SB_REF_RELEASE : SB_REF_DECREFS;
            dsend(t, SB_REF, &r, sizeof r, NULL, 0);
            break;
        }
        case BC_INCREFS_DONE: case BC_ACQUIRE_DONE: {
            if (off + sizeof(struct binder_ptr_cookie) > wlen) return off - 4;
            struct binder_ptr_cookie done;
            memcpy(&done, w + off, sizeof done);
            off += sizeof done;
             
 
 
 
 
 
 
 
 
 
 
 
 
 
            struct sb_ptr_cookie pc = {
                .op = cmd == BC_INCREFS_DONE ? SB_REF_INCREFS : SB_REF_ACQUIRE,
                .ptr = (uint64_t)done.ptr,
                .cookie = (uint64_t)done.cookie,
            };
            dsend(t, SB_REF_DONE, &pc, sizeof pc, NULL, 0);
            break;
        }
        case BC_REGISTER_LOOPER: case BC_ENTER_LOOPER: case BC_EXIT_LOOPER: {
            uint32_t op = cmd == BC_REGISTER_LOOPER ? 0 : cmd == BC_ENTER_LOOPER ? 1 : 2;
            t->looper = (op != 2);
            dsend(t, SB_LOOPER, &op, sizeof op, NULL, 0);
            break;
        }
        case BC_REQUEST_DEATH_NOTIFICATION: case BC_CLEAR_DEATH_NOTIFICATION: {
            if (off + sizeof(struct binder_handle_cookie) > wlen) return off - 4;
            const struct binder_handle_cookie *hc =
                (const struct binder_handle_cookie *)(w + off);
            off += sizeof *hc;
            struct sb_death d = { .op = (cmd == BC_REQUEST_DEATH_NOTIFICATION) ? 0u : 1u,
                                   .handle = hc->handle, .cookie = hc->cookie };
            dsend(t, SB_DEATH, &d, sizeof d, NULL, 0);
            break;
        }
        case BC_DEAD_BINDER_DONE: {
            if (off + sizeof(binder_uintptr_t) > wlen) return off - 4;
            off += sizeof(binder_uintptr_t);
             
            if (t->in_death > 0) t->in_death--;
             
 
            if (s) s->closes_death = 1;
            break;
        }
         
 
 
 
 
 
 
 
 
 
 
 
 
        case BC_ATTEMPT_ACQUIRE:
        case BC_ACQUIRE_RESULT:
        default:
            *unsup = 1;
            *unsup_cmd = cmd;
            return off - 4;
        }
    }
    return off;
}

 
static const char *bc_unsupported_name(uint32_t cmd) {
    return cmd == BC_ATTEMPT_ACQUIRE ? "BC_ATTEMPT_ACQUIRE"
         : cmd == BC_ACQUIRE_RESULT ? "BC_ACQUIRE_RESULT" : "неизвестная";
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int bc_unsupported(struct binder_write_read *bwr, size_t used, uint32_t cmd) {
    static unsigned n_unsup;
    unsigned n = __atomic_add_fetch(&n_unsup, 1, __ATOMIC_RELAXED);
    if (n <= 8 || (n & (n - 1)) == 0)
        bwarn("binder: ★★★ команда %s (0x%x) драйвером не поддерживается — отказ EINVAL, "
              "как у ядра: разобрано %zu из %llu байт, чтения нет (всего таких %u)",
              bc_unsupported_name(cmd), (unsigned)cmd, used,
              (unsigned long long)bwr->write_size, n);
    bwr->write_consumed = used;
    bwr->read_consumed = 0;
    errno = EINVAL;
    return -1;
}

 

 
static void wake_sync(struct sess *s, int more) {
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
    char junk[64];
    int had = 0;
    while (recv(s->fd_guest, junk, sizeof junk, MSG_DONTWAIT) > 0) had = 1;
    (void)had;
    if (more) { char one = 'w'; (void)!write(s->fd_wake, &one, 1); }
}

static int msg_to_br(struct thr *t, struct sess *s, uint8_t *msg, int *fds, int nfds,
                    struct outbuf *o);

static void dq_push(struct thr *t, const uint8_t *msg, size_t n) {
    struct qmsg *q = (struct qmsg *)malloc(sizeof *q + n);
    if (!q) { glog("binder: нет памяти под отложенное сообщение (%zu байт)", n); return; }
    q->next = NULL; q->n = n;
    memcpy(q->buf, msg, n);
    if (!t->dq_tail) t->dq_tail = &t->dq;
    *t->dq_tail = q;
    t->dq_tail = &q->next;
}

 
 
static void dq_flush(struct thr *t, struct sess *s, struct outbuf *o) {
     
 
 
 
 
    if (s) {
         
 
 
 
 
 
 
 
 
        struct qmsg *rest;
        pthread_mutex_lock(&s->lock);
        rest = s->dq;
        s->dq = NULL;
        s->dq_tail = &s->dq;
        pthread_mutex_unlock(&s->lock);
        while (rest) {
            struct qmsg *q = rest;
            int nfds0 = 0, r;                   
            t->orphan_msg = 1;                  
            r = msg_to_br(t, s, q->buf, NULL, nfds0, o);
            t->orphan_msg = 0;
            if (r < 0) break;                   
            rest = q->next;
            free(q);
        }
        if (rest) {
            struct qmsg **tl = &rest;
            while (*tl) tl = &(*tl)->next;
            pthread_mutex_lock(&s->lock);
            *tl = s->dq;                        
            if (!s->dq) s->dq_tail = tl;
            s->dq = rest;
            pthread_mutex_unlock(&s->lock);
        }
    }
    while (t->dq) {
        struct qmsg *q = t->dq;
        if (msg_to_br(t, s, q->buf, NULL, 0, o) < 0) return;    
        t->dq = q->next;
        if (!t->dq) t->dq_tail = &t->dq;
        free(q);
    }
}

static void dq_drop(struct thr *t) {
     
 
 
 
 
 
    while (t->dq) { struct qmsg *q = t->dq; t->dq = q->next; free(q); }
    t->dq_tail = &t->dq;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define GUEST_STUCK_MS 8000           

static int rescue_off(void) {
    static int v = -1;
    if (v < 0) { const char *e = getenv("GUEST_NO_BINDER_RESCUE"); v = e && *e && *e != '0'; }
    return v;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int in_death_work(const struct thr *t) {
     
 
 
 
 
 
 
 
 
 
 
 
 
    return t->s && t->s->closes_death &&
           t->in_death > 0 && t->tx_total > t->tx_at_death;
}

static int rescue_possible(const struct thr *t) {
    if (rescue_off()) return 0;
    if (t->pending_sync > 0) return 0;    
    if (t->tx_total == 0) return 0;       
    return !t->looper || t->in_txn > 0 || in_death_work(t);
}

 
 
 
 
 
static int gone_errno(struct sess *s) { return (s && s->closing) ? EBADF : ENOTCONN; }

 
static __thread const uint8_t *g_wbuf_ov;

static int binder_write_read(struct thr *t, struct sess *s, struct binder_write_read *bwr) {
    if (g_verbose) glog("binder: WR вход tid=%d запись=%llu чтение=%llu", gettid(),
                        (unsigned long long)bwr->write_size, (unsigned long long)bwr->read_size);
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (__atomic_exchange_n(&t->async_flush, 0, __ATOMIC_ACQ_REL)) async_done_send(t);
    t->sent = 0;
    t->will_read = bwr->read_size > 0;
    if (bwr->write_size) {
        int unsup;
        uint32_t unsup_cmd;
        const uint8_t *wbuf = g_wbuf_ov ? g_wbuf_ov
                                         : (const uint8_t *)g2h_untagged((abi_ulong)bwr->write_buffer);
        size_t used = bc_process(t, s, wbuf,
                                 (size_t)bwr->write_size, &unsup, &unsup_cmd);
         
        if (unsup) return bc_unsupported(bwr, used, unsup_cmd);
         
 
 
 
 
 
 
 
        if (used != (size_t)bwr->write_size) {
            uint32_t bad = 0;
            if (used + 4 <= (size_t)bwr->write_size)
                memcpy(&bad, wbuf + used, 4);
            bwarn("binder: ★★★ КОМАНДНЫЙ БУФЕР РАЗОБРАН НЕ ДО КОНЦА: %zu из %llu байт, "
                  "первое непонятое слово 0x%x — ОСТАТОК ПРОПАДЁТ",
                  used, (unsigned long long)bwr->write_size, (unsigned)bad);
        }
        bwr->write_consumed = bwr->write_size;
    }
    bwr->read_consumed = 0;
    if (!bwr->read_size) return 0;

    struct outbuf o = { .p = (uint8_t *)g2h_untagged((abi_ulong)bwr->read_buffer),
                        .cap = (size_t)bwr->read_size, .len = 0, .v8 = (s->proto == 8) };
    int more = 0, from_daemon = 0, waited = 0;

     
     

     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
     
    while (t->acks > 0) {
        struct sb_hdr wh = { .len = sizeof wh, .type = SB_WAIT,
                              .tid = (uint32_t)gettid(), .flags = SB_F_MAGIC };
        waited = 1;
        if (t->fd < 0 || write(t->fd, &wh, sizeof wh) != (ssize_t)sizeof wh) { errno = gone_errno(s); return -1; }
        uint8_t stackmsg[SB_PEND], *msg = stackmsg;
        int fds[SB_MAXFDS], nfds = 0;
        int n = drecv_to(t, &msg, sizeof stackmsg, fds, &nfds, 30000, "жду подтверждение посылки");
        if (n == -2) {
             
             
             
            bwarn("binder: ★★ ПОДТВЕРЖДЕНИЯ НЕТ 30 с — отпускаю гостя "
                 "(ждали %d, долг %d, посылка код=%u флаги=0x%x цель=%u)",
                 t->acks, t->completes, t->last_code, t->last_flags, t->last_handle);
            t->acks = 0;
            break;
        }
        if (n < 0) { glog("binder: WR разрыв связи с демоном (ждали подтверждение)"); errno = gone_errno(s); return -1; }
        more = (((struct sb_hdr *)msg)->flags & SB_F_MORE) != 0;
        from_daemon = 1;
         
         
         
         
         
         
         
         
         
         
         
         
         
         
        uint16_t ty = ((struct sb_hdr *)msg)->type;
        if ((ty == SB_R_TRANSACTION || ty == SB_R_REPLY) && t->completes > 0) {
            if (nfds == 0) { dq_push(t, msg, (size_t)n); continue; }
             
             
             
            while (t->completes > 0 && out_put(&o, BR_TRANSACTION_COMPLETE, NULL, 0) == 0)
                { t->completes--; t->cpl_given++; }
        }
        if (msg_to_br(t, s, msg, fds, nfds, &o) < 0) { dq_push(t, msg, (size_t)n); break; }
    }

     
     
    while (t->failed > 0 && out_put(&o, BR_FAILED_REPLY, NULL, 0) == 0) t->failed--;

     
    while (t->completes > 0 && out_put(&o, BR_TRANSACTION_COMPLETE, NULL, 0) == 0)
        { t->completes--; t->cpl_given++; }

     
    if (!t->completes) dq_flush(t, s, &o);

     
     
     
     
     
     
    if (t->sent > 0 && o.len == 0) {
        bwarn("binder: ★★ УЧЁТ ПОТЕРЯН — приняли посылок %d (код=%u флаги=0x%x цель=%u), "
             "а отдать гостю нечего (ждём подтверждений %d, долг %d, отказов %d, "
             "отложено %s) — выдаю подтверждения сам",
             t->sent, t->last_code, t->last_flags, t->last_handle,
             t->acks, t->completes, t->failed, t->dq ? "есть" : "нет");
        while (t->sent > 0 && out_put(&o, BR_TRANSACTION_COMPLETE, NULL, 0) == 0)
            { t->sent--; t->cpl_given++; }
    }

     
     
     
     
     
     
     
     
     
     
     
     
     
    if (o.len == 0 && t->dq) {
         
         
        dq_flush(t, s, &o);
        if (o.len == 0) {
            bwarn("binder: ★ отложенное есть, а отдать не вышло (подтверждений %d) — "
                 "не ухожу в ожидание", t->completes);
            if (from_daemon) wake_sync(s, more);
            bwr->read_consumed = 0;
            return 0;
        }
    }

     
     
     
    const int rescue = rescue_possible(t);
    while (o.len == 0) {
        struct sb_hdr wh = { .len = sizeof wh, .type = SB_WAIT,
                              .tid = (uint32_t)gettid(), .flags = SB_F_MAGIC };
        waited = 1;
        if (t->fd < 0 || write(t->fd, &wh, sizeof wh) != (ssize_t)sizeof wh) {
            errno = gone_errno(s);
            return -1;
        }
        uint8_t stackmsg[SB_PEND], *msg = stackmsg;
        int fds[SB_MAXFDS], nfds = 0;
        int n;
        if (rescue) {
            n = drecv_to(t, &msg, sizeof stackmsg, fds, &nfds, GUEST_STUCK_MS, "жду работу");
            if (n == -2) {
                 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
                int ours = t->cpl_given < t->tx_total;
                 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
                static int nofail = -1;
                if (nofail < 0) {
                    const char *e = getenv("GUEST_BINDER_NO_FAIL");
                    nofail = e && *e && *e != '0';
                }
                 
 
 
 
 
 
 
 
 
 
                int fail = !ours && !nofail && (t->in_txn > 0 || in_death_work(t));
                bwarn("binder: ★★★ ВЕЧНОЕ ОЖИДАНИЕ РАЗОРВАНО — потоку нечего ждать "
                      "(в пуле %d, внутри чужих транзакций %d, внутри извещений о "
                      "смерти %d, свои двусторонние без "
                      "ответа %d, посылок за жизнь %d, подтверждений выдано %d, "
                      "принято в этом заходе %d, подтверждений в очереди %d, не принял "
                      "демон %d, односторонняя не разобрана %d, последняя посылка "
                      "код=%u флаги=0x%x цель=%u) — потеря %s, %s",
                      t->looper, t->in_txn, t->in_death, t->pending_sync, t->tx_total,
                      t->cpl_given,
                      t->sent, t->completes, t->lost,
                      (t->async_buf != 0 || t->async_flush != 0),
                      t->last_code, t->last_flags, t->last_handle,
                      ours ? "НАША" : "у гостя",
                      ours ? "выдаю подтверждение"
                           : fail ? "отдаю ОТКАЗ — иначе поток держит свои замки вечно"
                                  : "выхожу ПУСТЫМ (подтверждение здесь убивает процесс)");
                if (ours) out_put(&o, BR_TRANSACTION_COMPLETE, NULL, 0);
                else if (fail) out_put(&o, BR_FAILED_REPLY, NULL, 0);
                break;
            }
        } else {
            n = drecv(t, &msg, sizeof stackmsg, fds, &nfds);
        }
        if (n < 0) { glog("binder: WR разрыв связи с демоном"); errno = gone_errno(s); return -1; }
        more = (((struct sb_hdr *)msg)->flags & SB_F_MORE) != 0;
        from_daemon = 1;
        if (g_verbose) glog("binder: WR получено сообщение тип=%u (%d байт, ещё=%d)",
                            ((struct sb_hdr *)msg)->type, n, more);
        if (msg_to_br(t, s, msg, fds, nfds, &o) < 0) {
            dq_push(t, msg, (size_t)n);                
            break;
        }
    }
     
     
     
     
     
     
     
     
     
    if (from_daemon) wake_sync(s, more);

     
     
     
    if (waited && t->fd >= 0) {
        struct sb_hdr uh = { .len = sizeof uh, .type = SB_UNWAIT,
                              .tid = (uint32_t)gettid(), .flags = SB_F_MAGIC };
        (void)!write(t->fd, &uh, sizeof uh);
    }

    if (g_verbose) glog("binder: WR выход, отдано %zu байт (ещё работа: %d)", o.len, more);
    bwr->read_consumed = o.len;
    return 0;
}

 
struct cmap { uint32_t *cv, *ov; size_t n; };

static uint8_t *bc_v8_to_v7(const uint8_t *in, size_t n, size_t *outn, struct cmap *m) {
    uint8_t *out = (uint8_t *)malloc(n + 8);
    m->cv = (uint32_t *)malloc((n / 4 + 2) * sizeof(uint32_t));
    m->ov = (uint32_t *)malloc((n / 4 + 2) * sizeof(uint32_t));
    m->n = 0;
    if (!out || !m->cv || !m->ov) { free(out); free(m->cv); free(m->ov); return NULL; }
    size_t off = 0, ol = 0;
    int stop = 0;
    while (!stop && off + 4 <= n) {
        uint32_t cmd;
        memcpy(&cmd, in + off, 4);
        m->cv[m->n] = (uint32_t)ol; m->ov[m->n] = (uint32_t)off; m->n++;
        size_t psz;                  
        if (cmd == BC8_TRANSACTION || cmd == BC8_REPLY) psz = sizeof(struct binder_transaction_data_v8);
        else if (cmd == BC8_FREE_BUFFER || cmd == BC8_DEAD_BINDER_DONE) psz = 8;
        else if (cmd == BC8_INCREFS_DONE || cmd == BC8_ACQUIRE_DONE || cmd == BC8_ATTEMPT_ACQUIRE) psz = 16;
        else if (cmd == BC8_REQUEST_DEATH || cmd == BC8_CLEAR_DEATH) psz = sizeof(struct binder_handle_cookie_v8);
        else if (cmd == BC_ACQUIRE_RESULT || cmd == BC_INCREFS || cmd == BC_ACQUIRE ||
                 cmd == BC_RELEASE || cmd == BC_DECREFS) psz = 4;
        else if (cmd == BC_REGISTER_LOOPER || cmd == BC_ENTER_LOOPER || cmd == BC_EXIT_LOOPER) psz = 0;
        else { memcpy(out + ol, &cmd, 4); ol += 4; off += 4; stop = 1; break; }    
        if (off + 4 + psz > n) { m->n--; stop = 1; break; }                        
        const uint8_t *pl = in + off + 4;
        if (cmd == BC8_TRANSACTION || cmd == BC8_REPLY) {
            struct binder_transaction_data_v8 a;
            memcpy(&a, pl, sizeof a);
            struct binder_transaction_data b;
            memset(&b, 0, sizeof b);
            b.target.ptr = (binder_uintptr_t)a.target.ptr;
            b.cookie = (binder_uintptr_t)a.cookie;
            b.code = a.code; b.flags = a.flags;
            b.sender_pid = (pid_t)a.sender_pid; b.sender_euid = (uid_t)a.sender_euid;
            b.data_size = (binder_size_t)a.data_size;
            b.offsets_size = (binder_size_t)a.offsets_size;        
            b.data.ptr.buffer = (binder_uintptr_t)a.data.ptr.buffer;
            b.data.ptr.offsets = (binder_uintptr_t)a.data.ptr.offsets;
            uint32_t c7 = cmd == BC8_TRANSACTION ? BC_TRANSACTION : BC_REPLY;
            memcpy(out + ol, &c7, 4); memcpy(out + ol + 4, &b, sizeof b); ol += 4 + sizeof b;
        } else if (cmd == BC8_FREE_BUFFER || cmd == BC8_DEAD_BINDER_DONE) {
            uint64_t v; memcpy(&v, pl, 8);
            uint32_t c7 = cmd == BC8_FREE_BUFFER ? BC_FREE_BUFFER : BC_DEAD_BINDER_DONE, v4 = (uint32_t)v;
            memcpy(out + ol, &c7, 4); memcpy(out + ol + 4, &v4, 4); ol += 8;
        } else if (cmd == BC8_INCREFS_DONE || cmd == BC8_ACQUIRE_DONE || cmd == BC8_ATTEMPT_ACQUIRE) {
            struct binder_ptr_cookie_v8 a; memcpy(&a, pl, sizeof a);
            struct binder_ptr_cookie b = { .ptr = (binder_uintptr_t)a.ptr, .cookie = (binder_uintptr_t)a.cookie };
            uint32_t c7 = cmd == BC8_INCREFS_DONE ? BC_INCREFS_DONE :
                          cmd == BC8_ACQUIRE_DONE ? BC_ACQUIRE_DONE : BC_ATTEMPT_ACQUIRE;
            memcpy(out + ol, &c7, 4); memcpy(out + ol + 4, &b, sizeof b); ol += 4 + sizeof b;
        } else if (cmd == BC8_REQUEST_DEATH || cmd == BC8_CLEAR_DEATH) {
            struct binder_handle_cookie_v8 a; memcpy(&a, pl, sizeof a);
            struct binder_handle_cookie b = { .handle = a.handle, .cookie = (binder_uintptr_t)a.cookie };
            uint32_t c7 = cmd == BC8_REQUEST_DEATH ? BC_REQUEST_DEATH_NOTIFICATION : BC_CLEAR_DEATH_NOTIFICATION;
            memcpy(out + ol, &c7, 4); memcpy(out + ol + 4, &b, sizeof b); ol += 4 + sizeof b;
        } else {                       
            memcpy(out + ol, in + off, 4 + psz); ol += 4 + psz;
        }
        off += 4 + psz;
    }
    m->cv[m->n] = (uint32_t)ol; m->ov[m->n] = (uint32_t)off; m->n++;      
    *outn = ol;
    return out;
}

static uint64_t cmap_back(const struct cmap *m, uint64_t c) {
    uint64_t best = 0;
    for (size_t i = 0; i < m->n; i++) {
        if (m->cv[i] == c) return m->ov[i];
        if (m->cv[i] < c) best = m->ov[i];
    }
    return best;
}

static int binder_write_read_v8(struct thr *t, struct sess *s, struct binder_write_read_v8 *b8) {
    struct binder_write_read w;
    memset(&w, 0, sizeof w);
    w.read_size = (binder_size_t)b8->read_size;
     
    w.read_buffer = (binder_uintptr_t)b8->read_buffer;
    uint8_t *cv = NULL;
    struct cmap cm = { 0 };
    if (b8->write_size) {
        size_t cvn = 0;
        cv = bc_v8_to_v7((const uint8_t *)g2h_untagged((abi_ulong)b8->write_buffer), (size_t)b8->write_size, &cvn, &cm);
        if (!cv) { errno = ENOMEM; return -1; }
        w.write_size = (binder_size_t)cvn;
        g_wbuf_ov = cv;
    }
    int r = binder_write_read(t, s, &w);
    g_wbuf_ov = NULL;
    if (b8->write_size) {
        b8->write_consumed = cmap_back(&cm, w.write_consumed);
        free(cv); free(cm.cv); free(cm.ov);
    }
    b8->read_consumed = w.read_consumed;
    return r;
}

 
static int binder_ioctl(int fd, unsigned long req, void *arg, int *ret) {
    struct sess *s = sess_by_fd(fd);
    if (!s) return 0;
    struct thr *t = thr_get(s);
    *ret = 0;
    if (req != BINDER_VERSION) s->engaged = 1;       
    switch (req) {
    case BINDER_VERSION: {
         
        int v = proto_want();
        s->ver_asked = 1;
        s->proto = v;
        if (arg) ((struct binder_version *)arg)->protocol_version = v;
        glog("binder: гостю названа версия протокола %d", v);
        return 1;
    }
    case BINDER_WRITE_READ_V8:
        if (!arg || s->proto != 8) { *ret = -1; errno = EINVAL; return 1; }
        *ret = binder_write_read_v8(t, s, (struct binder_write_read_v8 *)arg);
        return 1;
    case BINDER_SET_MAX_THREADS:
        if (arg) dsend(t, SB_SET_MAX_THREADS, arg, 4, NULL, 0);
        return 1;
    case BINDER_SET_CONTEXT_MGR: {
        dsend(t, SB_SET_CTX_MGR, NULL, 0, NULL, 0);
        uint8_t stackmsg[256], *msg = stackmsg; int fds[SB_MAXFDS], nfds = 0;
        int n = drecv(t, &msg, sizeof stackmsg, fds, &nfds);
        struct sb_hdr *h = (struct sb_hdr *)msg;
        if (n < 0 || h->type != SB_R_OK) { *ret = -1; errno = EBUSY; }
        else glog("binder: стали диспетчером контекста");
        return 1;
    }
    case BINDER_THREAD_EXIT:
        if (t->fd >= 0) {
            struct sb_hdr h = { .len = sizeof h, .type = SB_BYE,
                                 .tid = (uint32_t)gettid(), .flags = SB_F_MAGIC };
            (void)!write(t->fd, &h, sizeof h);
            close(t->fd);
            t->fd = -1;
        }
        return 1;
    case BINDER_WRITE_READ:
        if (!arg || s->proto == 8) { *ret = -1; errno = EINVAL; return 1; }
        *ret = binder_write_read(t, s, (struct binder_write_read *)arg);
        return 1;
    case BINDER_SET_IDLE_TIMEOUT:
    case BINDER_SET_IDLE_PRIORITY:
        return 1;
    default:
        glog("binder: ioctl 0x%lx не поддержан", req);
        *ret = -1;
        errno = EINVAL;
        return 1;
    }
}

 
 
static int binder_mmap(int fd, size_t len, abi_ulong *out) {
    struct sess *s = sess_by_fd(fd);
    if (!s) return 0;
    s->engaged = 1;
    if (s->map) { *out = h2g(s->map); return 1; }
     
     
     
     
    abi_long g = target_mmap(0, len, PROT_READ | PROT_WRITE,
                             MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (g == -1) { *out = (abi_ulong)-1; return 1; }
    void *p = g2h_untagged((abi_ulong)g);
    s->map = (uint8_t *)p;
    s->map_size = len;
    struct blk *b = (struct blk *)malloc(sizeof *b);
    b->off = 0; b->size = len; b->used = 0; b->cool = 0;
    b->dsize = b->osize = 0; b->next = NULL;
    s->blocks = b;
    glog("binder: mmap %zu байт -> гость 0x%llx (хозяин %p)", len,
         (unsigned long long)g, p);
    *out = (abi_ulong)g;
    return 1;
}

 
 
 
 

 
int guest_binder_try_open(const char *path, int flags, int *fd)
{
     
    if (guest_trace_try_open(path, fd)) {
        return 1;
    }
     
     
    if (guest_ashmem_try_open(path, fd)) {
        return 1;
    }
     
     
    if (guest_input_try_open(path, fd)) {
        return 1;
    }
     
    if (guest_alarm_try_open(path, fd)) {
        return 1;
    }
     
     
     
     
    if (guest_fs_try_open(path, flags, fd)) {
        return 1;
    }
    if (!path || strcmp(path, "/dev/binder") != 0) {
        return 0;
    }
    blog_init();
    binder_path_init();
    if (!g_binder[0]) {
         
         
        blog("binder: GUEST_BINDER не задан — открытие отклонено");
        errno = ENOENT;
        *fd = -1;
        return 1;
    }
    *fd = binder_open_fake();
    return 1;
}

 
int guest_binder_owns(int fd)
{
    return sess_by_fd(fd) != NULL;
}

 
int guest_binder_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret)
{
     
     
    if (guest_fb_ioctl(fd, req, arg, ret)) {
        return 1;
    }
    if (guest_ashmem_ioctl(fd, req, arg, ret)) {
        return 1;
    }
     
    if (guest_input_ioctl(fd, req, arg, ret)) {
        return 1;
    }
     
    if (guest_alarm_ioctl(fd, req, arg, ret)) {
        return 1;
    }
    if (!sess_by_fd(fd)) {
        return 0;
    }
    int r = 0;
    void *host = arg ? g2h_untagged(arg) : NULL;
    int handled = binder_ioctl(fd, req, host, &r);
    if (handled) {
         
         
         
        *ret = (r < 0) ? -1 : 0;
    }
    return handled;
}

 
int guest_binder_mmap(int fd, abi_ulong len, abi_ulong *addr)
{
    return binder_mmap(fd, (size_t)len, addr);
}

void guest_binder_close(int fd)
{
    guest_fb_forget(fd);
    guest_ashmem_close(fd);
    guest_input_close(fd);
    guest_alarm_close(fd);
    struct sess *s = sess_by_fd(fd);
    if (s) {
        proto_check_reject(s);       
        binder_close_fake(s);
    }
}
