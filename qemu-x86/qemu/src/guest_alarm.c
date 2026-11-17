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
// Changed: 2026-10-05

// guest_alarm.c — устройство будильников гостя: /dev/alarm (Р-120).
//
// ★ Зачем и что отвечает устройство — в шапке guest_alarm_core.h (там же все решения: разбор номеров ioctl,
// состояние, таймеры хозяина). Здесь — только то, что знает о qemu: перехват открытия, описатель устройства,
// память гостя и ожидание, которое сигнал гостя прерывает.
//
// Образец — guest_input.c: гость открывает путь, перехват отдаёт ему свой описатель, ioctl приходят с
// ГОСТЕВЫМ адресом (abi_ulong). Точки входа зовёт guest_binder.c — все наши устройства перехватываются одной
// точкой (в syscall.c про них знает только она).
//
// Описатель устройства. Гостю нужен обычный описатель, который можно закрыть и передать по fork; своего
// содержимого у него нет. Отдаём memfd (как ashmem): у каждого неповторимый inode, по нему мы узнаём СВОЙ
// описатель и отличаем его от чужого, занявшего тот же номер после close мимо нашего перехвата (dup2 поверх,
// close_range). Номер, который не наш, мы устройством не считаем и свою запись о нём выбрасываем.
// ⚠️ Описатель — close-on-exec: после execve таблица пуста (qemu запускается заново), а таймеры хозяина
// без неё осиротели бы.
//
// ⚠️ Несколько открытий не делят состояние (как у ядра для одного хозяина): в каждом процессе гостя
// 2.3–4.x /dev/alarm открывается ради SystemClock.elapsedRealtime() (GET_TIME), а будильники ставит один
// процесс — служба будильников.
//
// ⚠️ Предел: dup() описателя устройства гость сделать может, но ioctl по копии уйдёт мимо нас (номер
// другой). Служба будильников не дублирует; ядро же дало бы такую копию в общее открытие.

#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"
#include "user/safe-syscall.h"

#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>

 
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001u
#endif

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#ifndef GA_POLL
static int al_poll(struct pollfd *fds, unsigned n)
{
    return (int)safe_syscall(__NR_ppoll, fds, (size_t)n, NULL, NULL, (size_t)8);
}
#define GA_POLL(fds, n) al_poll((fds), (unsigned)(n))
#endif

#include "guest_alarm_core.h"
#include "guest_alarm.h"

#define AL_MAX 16

typedef struct {
    int used;
    int fd;                      
    dev_t dev;                   
    ino_t ino;
    GaDev d;
} AlSess;

static AlSess g_al[AL_MAX] = { [0 ... AL_MAX - 1] = { .d = GA_DEV_INITIALIZER } };
static pthread_mutex_t g_al_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_al_n;               

static void al_drop_locked(AlSess *s)
{
    ga_dev_close(&s->d);
    s->used = 0;
    __atomic_fetch_sub(&g_al_n, 1, __ATOMIC_RELEASE);
}

 
static AlSess *al_find_locked(int fd)
{
    for (int i = 0; i < AL_MAX; i++) {
        AlSess *s = &g_al[i];
        struct stat st;

        if (!s->used || s->fd != fd) {
            continue;
        }
        if (fstat(fd, &st) == 0 && st.st_dev == s->dev && st.st_ino == s->ino) {
            return s;
        }
         
        al_drop_locked(s);
        return NULL;
    }
    return NULL;
}

 
static void al_sweep_locked(void)
{
    for (int i = 0; i < AL_MAX; i++) {
        AlSess *s = &g_al[i];
        struct stat st;

        if (s->used && !(fstat(s->fd, &st) == 0 && st.st_dev == s->dev && st.st_ino == s->ino)) {
            al_drop_locked(s);
        }
    }
}

static AlSess *al_free_locked(void)
{
    for (int i = 0; i < AL_MAX; i++) {
        if (!g_al[i].used) {
            return &g_al[i];
        }
    }
    return NULL;
}

int guest_alarm_try_open(const char *path, int *fd)
{
    AlSess *s;
    struct stat st;
    int mfd;
    int e;

    if (!path || strcmp(path, "/dev/alarm") != 0) {
        return 0;
    }
    mfd = memfd_create("guest-alarm", MFD_CLOEXEC);
    if (mfd < 0) {
        *fd = -1;                        
        return 1;
    }
    if (fstat(mfd, &st) < 0) {
        e = errno;
        close(mfd);
        errno = e;
        *fd = -1;
        return 1;
    }
    pthread_mutex_lock(&g_al_lock);
    s = al_free_locked();
    if (!s) {
        al_sweep_locked();
        s = al_free_locked();
    }
    if (!s) {
        pthread_mutex_unlock(&g_al_lock);
        close(mfd);
        errno = EMFILE;
        *fd = -1;
        return 1;
    }
    s->used = 1;
    s->fd = mfd;
    s->dev = st.st_dev;
    s->ino = st.st_ino;
    ga_dev_open(&s->d);
    __atomic_fetch_add(&g_al_n, 1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&g_al_lock);
    *fd = mfd;
    return 1;
}

 
int guest_alarm_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret)
{
    AlSess *s;
    GaCmd c;
    uint8_t buf[8];
    long r;
    int sz;
    int e;

     
    if (!__atomic_load_n(&g_al_n, __ATOMIC_ACQUIRE)) {
        return 0;
    }
    pthread_mutex_lock(&g_al_lock);
    s = al_find_locked(fd);
    pthread_mutex_unlock(&g_al_lock);
    if (!s) {
        return 0;
    }

    if (ga_decode((uint32_t)req, &c) < 0) {
        *ret = -1;                       
        return 1;
    }
     
    if (c.op == GA_OP_CLOEXEC || c.op == GA_OP_NOCLOEXEC) {
        *ret = fcntl(fd, F_SETFD, c.op == GA_OP_CLOEXEC ? FD_CLOEXEC : 0) < 0 ? -1 : 0;
        return 1;
    }
    if (c.op == GA_OP_NOP) {
        *ret = 0;
        return 1;
    }

    memset(buf, 0, sizeof buf);
    sz = ga_arg_size(&c);
    if (sz > 0 && ga_arg_in(&c)) {
        void *p = lock_user(VERIFY_READ, arg, sz, 1);

        if (!p) {
            errno = EFAULT;
            *ret = -1;
            return 1;
        }
        memcpy(buf, p, (size_t)sz);
        unlock_user(p, arg, 0);
    }
    r = ga_exec(&s->d, &c, buf);
    e = errno;                           
    if (r >= 0 && sz > 0 && ga_arg_out(&c)) {
        void *p = lock_user(VERIFY_WRITE, arg, sz, 0);

        if (!p) {
            r = -1;
            e = EFAULT;
        } else {
            memcpy(p, buf, (size_t)sz);
            unlock_user(p, arg, sz);
        }
    }
    if (r < 0) {
        errno = e;
        *ret = -1;
    } else {
        *ret = (abi_long)r;
    }
    return 1;
}

void guest_alarm_close(int fd)
{
    AlSess *s;

    if (!__atomic_load_n(&g_al_n, __ATOMIC_ACQUIRE)) {
        return;
    }
    pthread_mutex_lock(&g_al_lock);
    s = al_find_locked(fd);
    if (s) {
        al_drop_locked(s);
    }
    pthread_mutex_unlock(&g_al_lock);
}
