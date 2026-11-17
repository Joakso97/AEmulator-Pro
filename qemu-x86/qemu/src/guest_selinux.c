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

/*
 * guest_selinux.c — SELinux «ВСЁ РАЗРЕШЕНО» для прошивок 5.0–7.0 в режиме
 * «init прошивки» (шаг 3а). Что где (патчи — в qemu/patches/):
 *
 *   selinuxfs: каталог, словарь классов, statfs ........... патч 0084
 *   транзакционные файлы access, context, create… ......... патч 0085
 *   /proc/<…>/attr/<имя>, GUEST_SECON через execve ....... патч 0086
 *   метки файлов — расширенный атрибут security.selinux ... патч 0087
 *   SO_PEERSEC ............................................. патч 0088
 *   NETLINK_SELINUX — в syscall.c, через guest_net.c ....... патч 0089
 * Договор со стендом (переменные и форматы журналов) задаёт приложение; в комплект он не входит.
 *
 * ★ Зачем. /init Android 5.0+ без SELinux не идёт: он монтирует selinuxfs и
 * пишет в неё политику («Failed to load policy; rebooting into recovery mode»),
 * спрашивает у ядра метки файлов и атрибуты процесса, а служба свойств — контекст
 * собеседника сокета. Ядра у нас нет (его роль играет транслятор), а настоящей
 * политики нам не нужно: хватит ответов «разрешено всё». Ни одного отказа и ни
 * одного аудита — как ядро в режиме permissive без правил.
 *
 * ★ ВСЁ ТОЛЬКО ПРИ GUEST_FWINIT=1 И GUEST_SELINUX=1 (вторую кладёт стенд для
 * поколения ≥ 21). Без любой из двух переменных ни одна функция не меняет
 * поведения: иначе 4.3 и 4.4 решат, что SELinux включён (libselinux решает это
 * по statfs точки /sys/fs/selinux и по /proc/filesystems). Два исключения, оба —
 * расширенные атрибуты: метки файлов отдельно от режима по GUEST_SELINUX_LABELS=1
 * (Р-104, «план» 7.x) и пространство user.* — у файла телефона ВСЕГДА (Р-112,
 * guest_xattr.h): это не SELinux, а обычные атрибуты, которые ядро даёт любому.
 *
 * ★ selinuxfs — НАСТОЯЩИЙ КАТАЛОГ ХОЗЯИНА В dev ДЕРЕВА, а не файлы, которые кладёт
 * стенд. Гостевой путь /sys/fs/selinux уводится (util/path.c, патч 0084) в
 * <корень>/dev/.selinuxfs, и все вызовы файловой системы — stat, access, open,
 * mmap, opendir, getdents — работают над ним, как над каталогом: ни одного
 * перехвата на каждый из десятка вызовов. /dev гостя — это его tmpfs: стенд
 * очищает его перед «Пуском», и состояние selinuxfs (enforce) живёт ровно до
 * следующей загрузки, как у ядра. Каталог заводит первый же процесс транслятора
 * (init): в соседнем каталоге со случайным именем, потом одним renameat — второй
 * процесс не увидит недоделанного. ⚠️ dev пишет и гость (ссылки с относительной целью
 * транслятор не переписывает): всё, что транслятор делает в этом каталоге, идёт
 * относительно описателей и никогда по ссылкам (см. dir_nofollow, rm_rf_at).
 *
 *   enforce        обычный файл «0»: запись и чтение — последнее записанное;
 *   policyvers, mls, deny_unknown, reject_unknown, checkreqprot — константы;
 *   load, null     ссылка на хозяйский /dev/null: политика принимается любого
 *                  объёма и никуда не идёт;
 *   status         страница 4096 байт: версия 1, остальное — нули;
 *   booleans/, policy_capabilities/ — пусты; initial_contexts/<имя> — контекст;
 *   class/<класс>/index и perms/<право> — словарь классов, см. ниже;
 *   access, context, create, relabel, member, user — места под транзакционные
 *                  файлы; их запись и чтение исполняет перехват (патч 0085).
 *
 * ★ СЛОВАРЬ КЛАССОВ — ИЗ ПОЛИТИКИ САМОЙ ПРОШИВКИ. libselinux узнаёт числа классов и
 * прав открытием class/<класс>/index и просмотром каталога class/<класс>/perms
 * (stringrep.c: string_to_security_class и string_to_av_perm). Не найдено имя —
 * 5.0 отвечает отказом (selinux_check_access вернёт −1), 6.0+ при deny_unknown=0
 * разрешает. Чтобы 5.0 не отказывала, словарь должен знать каждый класс и право,
 * которые спросит прошивка, — и вести их надо по её политике, а не по нашей
 * памяти: двоичная политика /sepolicy дерева (версии 19…30, не больше 16 МБ) содержит таблицу
 * классов с общими и собственными правами и их номерами. Разбираем её сами
 * (policy_parse); не открылась или не разобралась — берём встроенный словарь
 * (BUILTIN) — основные классы Android с настоящими номерами.
 * Каталог класса заводится по ПЕРВОМУ ОБРАЩЕНИЮ к пути под class/<класс>
 * (path() зовёт sefs_touch один раз на имя): любой вызов — stat, open, opendir —
 * находит его на месте. Звонок под замком path(): внутри нельзя вызывать path().
 *
 * ⚠️ Условие «GUEST_FWINIT=1 и GUEST_SELINUX=1» записано и в util/path.c
 * (selinuxfs_rest): util/ не вправе зависеть от linux-user/. Править — в обоих.
 */
#include "qemu/osdep.h"
#include <dirent.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/random.h>
#include <sys/stat.h>
#include "qemu/path.h"
#include "qemu.h"
#include "user-internals.h"

#include "guest_fwinit.h"
#include "guest_selinux.h"
#include "guest_xattr.h"

#define SELINUX_MAGIC  0xf97cff8cu
#define SEFS_SUBDIR    "/dev/.selinuxfs"
#define SEFS_GUEST     "/sys/fs/selinux"

 
extern void (*path_selinuxfs_touch)(const char *rest);

 

static int se_on;
static pthread_once_t se_once = PTHREAD_ONCE_INIT;

static void se_setup(void)
{
    const char *e = getenv("GUEST_SELINUX");

     
    se_on = guest_fwinit() && e && strcmp(e, "1") == 0;
}

bool guest_selinux(void)
{
    pthread_once(&se_once, se_setup);
    return se_on;
}

 
 
 
 
 
 
 
 
 
 
 
static int lb_on;
static pthread_once_t lb_once = PTHREAD_ONCE_INIT;

static void lb_setup(void)
{
    const char *e = getenv("GUEST_SELINUX_LABELS");

    lb_on = e && strcmp(e, "1") == 0;
}

static bool labels_on(void)
{
    if (guest_selinux()) {
        return true;
    }
    pthread_once(&lb_once, lb_setup);
    return lb_on;
}

 
 
 
 
 
 
static void se_warn(const char *what)
{
    char line[256];
    const char *k = getenv("GUEST_KMSG");
    int n = snprintf(line, sizeof line, "qemu-selinux: %s: SELinux (\"всё разрешено\") не включён\n", what);

    if (n > 0 && write(2, line, MIN((size_t)n, sizeof line - 1)) < 0) {
         
    }
    if (k && *k == '/') {
        int fd = open(k, O_WRONLY | O_APPEND | O_NOFOLLOW | O_CLOEXEC);

        if (fd >= 0) {
            if (n > 0 && write(fd, line, MIN((size_t)n, sizeof line - 1)) < 0) {
                 
            }
            close(fd);
        }
    }
}

 

typedef struct {
    char name[48];
    unsigned value;
} VPerm;

typedef struct {
    char name[64];
    unsigned value;
    GArray *perms;                       
} VClass;

static GPtrArray *vocab;                 

typedef struct {
    const unsigned char *p;
    size_t n, o;
    bool bad;
} Rd;

static unsigned rd32(Rd *r)
{
    unsigned v;

    if (r->bad || r->n - r->o < 4) {
        r->bad = true;
        return 0;
    }
    v = r->p[r->o] | r->p[r->o + 1] << 8 | r->p[r->o + 2] << 16 |
        (unsigned)r->p[r->o + 3] << 24;
    r->o += 4;
    return v;
}

static const unsigned char *rdptr(Rd *r, size_t k)
{
    const unsigned char *s = r->p + r->o;

    if (r->bad || k > r->n - r->o) {
        r->bad = true;
        return NULL;
    }
    r->o += k;
    return s;
}

 
static void rd_ebitmap(Rd *r)
{
    unsigned count;

    rd32(r);
    rd32(r);
    count = rd32(r);
    if (count > (1u << 20)) {
        r->bad = true;
        return;
    }
    rdptr(r, (size_t)count * 12);
}

 
static void rd_constraints(Rd *r, unsigned ver, unsigned ncons)
{
    unsigned i, j;

    if (ncons > 65536) {
        r->bad = true;
        return;
    }
    for (i = 0; i < ncons && !r->bad; i++) {
        unsigned nexpr;

        rd32(r);
        nexpr = rd32(r);
        if (nexpr > 65536) {
            r->bad = true;
            return;
        }
        for (j = 0; j < nexpr && !r->bad; j++) {
            unsigned t = rd32(r);

            rd32(r);
            rd32(r);
            if (t == 5) {                        
                rd_ebitmap(r);
                if (ver >= 29) {                 
                    rd_ebitmap(r);
                    rd_ebitmap(r);
                    rd32(r);
                }
            }
        }
    }
}

static bool rd_name(Rd *r, unsigned len, char *dst, size_t cap)
{
    const unsigned char *s;

    if (len == 0 || len >= cap) {
        r->bad = true;
        return false;
    }
    s = rdptr(r, len);
    if (!s) {
        return false;
    }
    memcpy(dst, s, len);
    dst[len] = '\0';
    return true;
}

static void vclass_free(gpointer p)
{
    VClass *c = p;

    g_array_free(c->perms, TRUE);
    g_free(c);
}

typedef struct {
    char name[64];
    GArray *perms;                       
} VCommon;

static void vcommon_free(gpointer p)
{
    VCommon *c = p;

    g_array_free(c->perms, TRUE);
    g_free(c);
}

static VCommon *vcommon_find(GPtrArray *commons, const char *name)
{
    guint i;

    for (i = 0; i < commons->len; i++) {
        VCommon *c = g_ptr_array_index(commons, i);

        if (strcmp(c->name, name) == 0) {
            return c;
        }
    }
    return NULL;
}

 
static bool rd_perms(Rd *r, GArray *dst, unsigned np)
{
    unsigned j;

    if (np > 64) {
        return false;
    }
    for (j = 0; j < np && !r->bad; j++) {
        VPerm p;
        unsigned pl = rd32(r);

        p.value = rd32(r);
        if (!rd_name(r, pl, p.name, sizeof p.name)) {
            return false;
        }
        g_array_append_val(dst, p);
    }
    return !r->bad;
}

 
 
 
 
 
 
 
 
 
 
 
 
static GPtrArray *policy_parse(const unsigned char *buf, size_t n)
{
    Rd r = { buf, n, 0, false };
    GPtrArray *out = g_ptr_array_new_with_free_func(vclass_free);
    GPtrArray *commons = g_ptr_array_new_with_free_func(vcommon_free);
    unsigned ver, nsym, i, k, nel, len;
    const unsigned char *s;
    bool good = false;

    if (rd32(&r) != 0xf97cff8cu || rd32(&r) != 8) {
        goto done;
    }
    s = rdptr(&r, 8);
    if (!s || memcmp(s, "SE Linux", 8) != 0) {
        goto done;
    }
    ver = rd32(&r);
    rd32(&r);                                            
    nsym = rd32(&r);
    rd32(&r);                                            
    if (r.bad || ver < 19 || ver > 30 || nsym < 2 || nsym > 64) {
        goto done;
    }
    if (ver >= 22) {
        rd_ebitmap(&r);
    }
    if (ver >= 23) {
        rd_ebitmap(&r);
    }
     
    rd32(&r);                                            
    nel = rd32(&r);
    if (r.bad || nel > 1024) {
        goto done;
    }
    for (i = 0; i < nel && !r.bad; i++) {
        VCommon *c = g_new0(VCommon, 1);
        unsigned np;

        c->perms = g_array_new(FALSE, TRUE, sizeof(VPerm));
        g_ptr_array_add(commons, c);
        len = rd32(&r);
        rd32(&r);                                        
        rd32(&r);                                        
        np = rd32(&r);
        if (!rd_name(&r, len, c->name, sizeof c->name) || !rd_perms(&r, c->perms, np)) {
            goto done;
        }
    }
     
    rd32(&r);                                            
    nel = rd32(&r);
    if (r.bad || nel == 0 || nel > 1024) {
        goto done;
    }
    for (i = 0; i < nel && !r.bad; i++) {
        VClass *c = g_new0(VClass, 1);
        unsigned len2, np, ncons;
        char com[64] = "";

        c->perms = g_array_new(FALSE, TRUE, sizeof(VPerm));
        g_ptr_array_add(out, c);
        len = rd32(&r);
        len2 = rd32(&r);
        c->value = rd32(&r);
        rd32(&r);                                        
        np = rd32(&r);
        ncons = rd32(&r);
        if (!rd_name(&r, len, c->name, sizeof c->name) ||
            (len2 && !rd_name(&r, len2, com, sizeof com)) ||
            !rd_perms(&r, c->perms, np)) {
            goto done;
        }
        rd_constraints(&r, ver, ncons);
        if (ver >= 19) {
            rd_constraints(&r, ver, rd32(&r));
        }
        if (ver >= 27) {
            rd32(&r);
            rd32(&r);
            rd32(&r);
        }
        if (ver >= 28) {
            rd32(&r);
        }
        if (r.bad) {
            goto done;
        }
        if (com[0]) {
            VCommon *cm = vcommon_find(commons, com);

            if (!cm) {
                goto done;
            }
            for (k = 0; k < cm->perms->len; k++) {
                g_array_append_val(c->perms, g_array_index(cm->perms, VPerm, k));
            }
        }
    }
    good = !r.bad;
done:
    g_ptr_array_free(commons, TRUE);
    if (!good) {
        g_ptr_array_free(out, TRUE);
        return NULL;
    }
    return out;
}

 
 
 
 
 
#define FILE_COMMON "ioctl read write create getattr setattr lock relabelfrom relabelto " \
                    "append unlink link rename execute swapon quotaon mounton"
#define SOCK_COMMON "ioctl read write create getattr setattr lock relabelfrom relabelto " \
                    "append bind connect listen accept getopt setopt shutdown recvfrom " \
                    "sendto recv_msg send_msg name_bind"
static const char *const BUILTIN[] = {
    "1 security compute_av compute_create compute_member check_context load_policy "
        "compute_relabel compute_user setenforce setbool setsecparam setcheckreqprot "
        "read_policy",
    "2 process fork transition sigchld sigkill sigstop signull signal ptrace getsched "
        "setsched getsession getpgid setpgid getcap setcap share getattr setexec "
        "setfscreate noatsecure siginh setrlimit rlimitinh dyntransition setcurrent "
        "execmem execstack execheap setkeycreate setsockcreate",
    "3 system ipc_info syslog_read syslog_mod syslog_console module_request",
    "4 capability chown dac_override dac_read_search fowner fsetid kill setgid setuid "
        "setpcap linux_immutable net_bind_service net_broadcast net_admin net_raw "
        "ipc_lock ipc_owner sys_module sys_rawio sys_chroot sys_ptrace sys_pacct "
        "sys_admin sys_boot sys_nice sys_resource sys_time sys_tty_config mknod lease "
        "audit_write audit_control setfcap",
    "5 filesystem mount remount unmount getattr relabelfrom relabelto transition "
        "associate quotamod quotaget",
    "6 file " FILE_COMMON " execute_no_trans entrypoint execmod open audit_access",
    "7 dir " FILE_COMMON " add_name remove_name reparent search rmdir open audit_access "
        "execmod",
    "8 fd use",
    "9 lnk_file " FILE_COMMON " open audit_access execmod",
    "10 chr_file " FILE_COMMON " execute_no_trans entrypoint execmod open audit_access",
    "11 blk_file " FILE_COMMON " open audit_access execmod",
    "12 sock_file " FILE_COMMON " open audit_access execmod",
    "13 fifo_file " FILE_COMMON " open audit_access execmod",
    "14 socket " SOCK_COMMON,
    "23 unix_stream_socket " SOCK_COMMON " connectto newconn acceptfrom",
    "24 unix_dgram_socket " SOCK_COMMON,
    "69 capability2 mac_override mac_admin syslog wake_alarm block_suspend",
    "82 binder impersonate call set_context_mgr transfer",
    "83 zygote specifyids specifyrlimits specifyinvokewith specifyseinfo",
    "84 property_service set",
    "85 service_manager add find list",
    "86 keystore_key test get insert delete exist saw reset password lock unlock zero "
        "sign verify grant duplicate clear_uid reset_uid sync_uid password_uid",
};

static GPtrArray *builtin_vocab(void)
{
    GPtrArray *out = g_ptr_array_new_with_free_func(vclass_free);
    size_t i;

    for (i = 0; i < ARRAY_SIZE(BUILTIN); i++) {
        gchar **w = g_strsplit(BUILTIN[i], " ", -1);
        VClass *c = g_new0(VClass, 1);
        unsigned v = 0;
        int j;

        c->perms = g_array_new(FALSE, TRUE, sizeof(VPerm));
        c->value = (unsigned)atoi(w[0]);
        snprintf(c->name, sizeof c->name, "%s", w[1]);
        for (j = 2; w[j]; j++) {
            VPerm p;

            snprintf(p.name, sizeof p.name, "%s", w[j]);
            p.value = ++v;
            g_array_append_val(c->perms, p);
        }
        g_ptr_array_add(out, c);
        g_strfreev(w);
    }
    return out;
}

 
#define POLICY_MAX (16u << 20)

 
 
 
 
 
 
 
static GPtrArray *load_vocab(void)
{
    const char *root = path_prefix();
    GPtrArray *v = NULL;

    if (root) {
        char *f = g_strconcat(root, "/sepolicy", NULL);
        int fd = open(f, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
        struct stat st;

        if (fd >= 0 && fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0 &&
            st.st_size <= POLICY_MAX) {
            unsigned char *data = g_malloc((size_t)st.st_size + 1);
            size_t len = 0;
            ssize_t k = 1;

            while (len < (size_t)st.st_size + 1 && (k = read(fd, data + len, (size_t)st.st_size + 1 - len)) > 0) {
                len += (size_t)k;
            }
            if (k >= 0 && len == (size_t)st.st_size) {       
                v = policy_parse(data, len);
            }
            g_free(data);
        }
        if (fd >= 0) {
            close(fd);
        }
        g_free(f);
    }
    return v ? v : builtin_vocab();
}

 
 
 
 
 
static GPtrArray *vocab_get(void)
{
    GPtrArray *v = __atomic_load_n(&vocab, __ATOMIC_ACQUIRE), *none = NULL;

    if (!v) {
        v = load_vocab();
        if (!__atomic_compare_exchange_n(&vocab, &none, v, false, __ATOMIC_ACQ_REL,
                                         __ATOMIC_ACQUIRE)) {
            g_ptr_array_free(v, TRUE);
            v = none;
        }
    }
    return v;
}

static VClass *vocab_by_name(const char *name)
{
    GPtrArray *v = vocab_get();
    guint i;

    for (i = 0; i < v->len; i++) {
        VClass *c = g_ptr_array_index(v, i);

        if (strcmp(c->name, name) == 0) {
            return c;
        }
    }
    return NULL;
}

 

static char *sefs_dir;                   

 
 
 
 
 
 
 
 
 
static int dir_nofollow(int dfd, const char *name)
{
    return openat(dfd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

 
static bool put_file(int dfd, const char *name, mode_t mode, const void *data, size_t len)
{
    int fd = openat(dfd, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode);
    bool ok = fd >= 0;

    if (ok) {
        ok = (len == 0 || write(fd, data, len) == (ssize_t)len) && fchmod(fd, mode) == 0;
        close(fd);
    }
    return ok;
}

static bool put_dir(int dfd, const char *name)
{
    return mkdirat(dfd, name, 0755) == 0 || errno == EEXIST;
}

static bool put_link(int dfd, const char *name, const char *target)
{
    return symlinkat(target, dfd, name) == 0;
}

 
static void rm_rf_at(int dfd, const char *name, int depth)
{
    struct stat st;

    if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0) {
        return;
    }
    if (S_ISDIR(st.st_mode) && depth < 8) {
        int fd = dir_nofollow(dfd, name);
        DIR *d = fd >= 0 ? fdopendir(fd) : NULL;
        struct dirent *e;

        if (d) {
            while ((e = readdir(d)) != NULL) {
                if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..")) {
                    rm_rf_at(dirfd(d), e->d_name, depth + 1);
                }
            }
            closedir(d);
        } else if (fd >= 0) {
            close(fd);
        }
        unlinkat(dfd, name, AT_REMOVEDIR);
    } else {
        unlinkat(dfd, name, 0);
    }
}

 
 
 
 
 
 
 
static uint64_t se_random64(void)
{
    static uint64_t counter;
    uint64_t v = 0;
    struct timespec ts;

    if (getrandom(&v, sizeof v, GRND_NONBLOCK) == (ssize_t)sizeof v) {
        return v;
    }
    clock_gettime(CLOCK_MONOTONIC, &ts);
    v = ((uint64_t)ts.tv_sec << 32) ^ (uint64_t)ts.tv_nsec ^ ((uint64_t)getpid() << 17);
    v ^= __atomic_add_fetch(&counter, 0x9e3779b97f4a7c15ull, __ATOMIC_RELAXED);
    v ^= v >> 31;
    v *= 0xd6e8feb86659fd93ull;
    v ^= v >> 32;
    return v;
}

 
static int mkdir_random(int dfd, const char *prefix, char **name)
{
    int i;

    for (i = 0; i < 16; i++) {
        char *n = g_strdup_printf("%s.%016" PRIx64, prefix, se_random64());

        if (mkdirat(dfd, n, 0755) == 0) {
            *name = n;
            return 0;
        }
        g_free(n);
        if (errno != EEXIST) {
            break;
        }
    }
    return -1;
}

 
static const char *const INITIAL[][2] = {
    { "kernel", "u:r:kernel:s0" },
    { "security", "u:object_r:security_t:s0" },
    { "unlabeled", "u:object_r:unlabeled:s0" },
    { "fs", "u:object_r:labeledfs:s0" },
    { "file", "u:object_r:unlabeled:s0" },
    { "file_labels", "u:object_r:unlabeled:s0" },
    { "any_socket", "u:object_r:unlabeled:s0" },
    { "port", "u:object_r:unlabeled:s0" },
    { "netif", "u:object_r:unlabeled:s0" },
    { "netmsg", "u:object_r:unlabeled:s0" },
    { "node", "u:object_r:unlabeled:s0" },
    { "policy", "u:object_r:security_t:s0" },
    { "devnull", "u:object_r:null_device:s0" },
};

 
 
 
 
 
 
static bool fill_tree(int dfd)
{
    static const char *const txn[] = { "access", "context", "create", "member",
                                       "relabel", "user", "commit_pending_bools" };
    static const struct { const char *name, *text; mode_t mode; } consts[] = {
        { "checkreqprot", "0", 0644 },
        { "deny_unknown", "0", 0444 },
        { "enforce", "0", 0644 },
        { "mls", "1", 0444 },
        { "policyvers", "30", 0444 },
        { "reject_unknown", "0", 0444 },
    };
    unsigned char status[4096] = { 1, 0, 0, 0 };     
    bool ok = true;
    size_t i;
    int ic;

    for (i = 0; i < ARRAY_SIZE(txn); i++) {
        ok = ok && put_link(dfd, txn[i], "/dev/null");
    }
    for (i = 0; i < ARRAY_SIZE(consts); i++) {
        ok = ok && put_file(dfd, consts[i].name, consts[i].mode, consts[i].text,
                            strlen(consts[i].text));
    }
    ok = ok && put_file(dfd, "status", 0444, status, sizeof status);
    ok = ok && put_link(dfd, "load", "/dev/null") && put_link(dfd, "null", "/dev/null");
    ok = ok && put_dir(dfd, "booleans") && put_dir(dfd, "class") &&
         put_dir(dfd, "policy_capabilities") && put_dir(dfd, "initial_contexts");
    ic = ok ? dir_nofollow(dfd, "initial_contexts") : -1;
    for (i = 0; ic >= 0 && i < ARRAY_SIZE(INITIAL); i++) {
        ok = put_file(ic, INITIAL[i][0], 0444, INITIAL[i][1], strlen(INITIAL[i][1]));
    }
    if (ic >= 0) {
        close(ic);
    } else {
        ok = false;
    }
    return ok;
}

static char *sefs_root;                  

 
 
 
 
 
static int sefs_open(const char *sub)
{
    static const char *const base[] = { "dev", ".selinuxfs" };
    int fd = open(sefs_root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    size_t i;
    gchar **parts = sub ? g_strsplit(sub, "/", -1) : NULL;

    for (i = 0; fd >= 0 && i < ARRAY_SIZE(base); i++) {
        int n = dir_nofollow(fd, base[i]);

        close(fd);
        fd = n;
    }
    for (i = 0; fd >= 0 && parts && parts[i]; i++) {
        int n = dir_nofollow(fd, parts[i]);

        close(fd);
        fd = n;
    }
    g_strfreev(parts);
    return fd;
}

static void ensure_tree(void)
{
    const char *root = path_prefix();
    char *tmp = NULL;
    struct stat st;
    int rfd, dfd, tfd;

    if (!root) {
        return;
    }
    sefs_root = g_strdup(root);
    rfd = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (rfd < 0) {
        se_warn("корень дерева гостя не открывается");
        return;
    }
     
    dfd = dir_nofollow(rfd, "dev");
    if (dfd < 0 && errno == ENOENT) {
        mkdirat(rfd, "dev", 0755);
        dfd = dir_nofollow(rfd, "dev");
    }
    close(rfd);
    if (dfd < 0) {
         
        se_warn("<корень>/dev — не каталог (ссылка?): по ссылкам транслятор не ходит");
        return;
    }
    if (fstatat(dfd, ".selinuxfs", &st, AT_SYMLINK_NOFOLLOW) == 0) {
        if (S_ISDIR(st.st_mode)) {
            sefs_dir = g_strconcat(root, SEFS_SUBDIR, NULL);
            close(dfd);
            return;
        }
         
        unlinkat(dfd, ".selinuxfs", 0);
    }
    if (mkdir_random(dfd, ".selinuxfs", &tmp) == 0) {
        tfd = dir_nofollow(dfd, tmp);
        if (tfd >= 0 && fill_tree(tfd) && renameat(dfd, tmp, dfd, ".selinuxfs") == 0) {
            sefs_dir = g_strconcat(root, SEFS_SUBDIR, NULL);
        } else if (fstatat(dfd, ".selinuxfs", &st, AT_SYMLINK_NOFOLLOW) == 0 &&
                   S_ISDIR(st.st_mode)) {
            sefs_dir = g_strconcat(root, SEFS_SUBDIR, NULL);    
        }
        if (tfd >= 0) {
            close(tfd);
        }
        rm_rf_at(dfd, tmp, 0);                   
        g_free(tmp);
    }
    if (!sefs_dir) {
        se_warn("каталог <корень>/dev/.selinuxfs не завёлся");
    }
    close(dfd);
}

 
static bool safe_name(const char *n)
{
    return n[0] && !strchr(n, '/') && strcmp(n, ".") != 0 && strcmp(n, "..") != 0;
}

 
 
 
 
static void class_make(const VClass *c)
{
    char num[16], *tmp = NULL;
    guint i;
    bool ok = false;
    int cfd, tfd = -1, pfd = -1;

    if (!sefs_dir || !safe_name(c->name)) {
        return;
    }
    cfd = sefs_open("class");
    if (cfd < 0) {
        return;
    }
     
 
 
 
 
    if (fstatat(cfd, c->name, &(struct stat){ 0 }, AT_SYMLINK_NOFOLLOW) == 0) {
        close(cfd);
        return;
    }
    if (mkdir_random(cfd, ".c", &tmp) < 0) {
        close(cfd);
        return;
    }
    snprintf(num, sizeof num, "%u", c->value);
    tfd = dir_nofollow(cfd, tmp);
    ok = tfd >= 0 && put_file(tfd, "index", 0444, num, strlen(num)) && put_dir(tfd, "perms");
    pfd = ok ? dir_nofollow(tfd, "perms") : -1;
    ok = ok && pfd >= 0;
    for (i = 0; ok && i < c->perms->len; i++) {
        const VPerm *p = &g_array_index(c->perms, VPerm, i);

         
        if (p->value < 1 || p->value > 32 || !safe_name(p->name)) {
            continue;
        }
        snprintf(num, sizeof num, "%u", p->value);
        ok = put_file(pfd, p->name, 0444, num, strlen(num));
    }
    if (pfd >= 0) {
        close(pfd);
    }
    if (tfd >= 0) {
        close(tfd);
    }
    if (!ok || renameat(cfd, tmp, cfd, c->name) != 0) {
        rm_rf_at(cfd, tmp, 0);                   
    }
    g_free(tmp);
    close(cfd);
}

 
 
 
 
 
 
static void sefs_touch(const char *rest)
{
    char name[64];
    const char *s;
    size_t n;
    VClass *c;

    if (strncmp(rest, "/class", 6) != 0 || (rest[6] != '\0' && rest[6] != '/')) {
        return;
    }
    if (rest[6] == '\0' || rest[7] == '\0') {
        GPtrArray *v = vocab_get();
        guint i;

        for (i = 0; i < v->len; i++) {
            class_make(g_ptr_array_index(v, i));
        }
        return;
    }
    s = rest + 7;
    n = strcspn(s, "/");
    if (n == 0 || n >= sizeof name) {
        return;
    }
    memcpy(name, s, n);
    name[n] = '\0';
    c = vocab_by_name(name);
    if (c) {
        class_make(c);
    }
}

void guest_selinux_attr_init(void);

void guest_selinux_start(void)
{
    if (!guest_selinux()) {
        return;
    }
    guest_selinux_attr_init();
    ensure_tree();
    if (sefs_dir) {
        path_selinuxfs_touch = sefs_touch;
    }
}

 

 
static bool under_selinuxfs(const char *p)
{
    size_t n = strlen(SEFS_GUEST);

    return p && strncmp(p, SEFS_GUEST, n) == 0 && (p[n] == '\0' || p[n] == '/');
}

static void magic_statfs(struct statfs *st)
{
    memset(st, 0, sizeof *st);
    st->f_type = (__typeof__(st->f_type))SELINUX_MAGIC;
    st->f_bsize = 4096;
    st->f_frsize = 4096;
    st->f_namelen = 255;
}

bool guest_selinux_statfs(const char *guest_path, struct statfs *st)
{
    if (!guest_selinux() || !st || !under_selinuxfs(guest_path)) {
        return false;
    }
    magic_statfs(st);
    return true;
}

 
static int se_fd_kind(int fd);

void guest_selinux_fstatfs(int fd, struct statfs *st)
{
    char link[64], host[PATH_MAX], *g;
    ssize_t k;

    if (!guest_selinux() || !sefs_dir || !st || fd < 0) {
        return;
    }
     
 
 
 
 
    switch (se_fd_kind(fd)) {
    case 1:
        magic_statfs(st);
        return;
    case 2:
        magic_statfs(st);
        st->f_type = (__typeof__(st->f_type))0x9fa0;     
        return;
    default:
        break;
    }
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    k = readlink(link, host, sizeof host - 1);
    if (k <= 0) {
        return;
    }
    host[k] = '\0';
     
 
 
 
 
    g = path_to_guest(host);
    if (g && strncmp(g, SEFS_SUBDIR, strlen(SEFS_SUBDIR)) == 0 &&
        (g[strlen(SEFS_SUBDIR)] == '\0' || g[strlen(SEFS_SUBDIR)] == '/')) {
        magic_statfs(st);
    }
    g_free(g);
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
enum { SK_NONE = 0, SK_ACCESS, SK_CONTEXT, SK_CREATE, SK_RELABEL, SK_MEMBER, SK_USER,
       SK_ATTR };

#define SE_FDS 65536                     

typedef struct {
    int kind;
    int acc;                             
    int attr;                            
    int pid;                             
    dev_t dev;
    ino_t ino;
    char *reply;                         
    size_t len, off;
} SeFd;

static SeFd *se_fd[SE_FDS];
static int se_fd_count;

static SeFd *se_lookup(int fd)
{
    SeFd *e;
    struct stat st;

    if (fd < 0 || fd >= SE_FDS || !__atomic_load_n(&se_fd_count, __ATOMIC_RELAXED)) {
        return NULL;
    }
    e = __atomic_load_n(&se_fd[fd], __ATOMIC_ACQUIRE);
    if (!e) {
        return NULL;
    }
    if (fstat(fd, &st) || st.st_dev != e->dev || st.st_ino != e->ino) {
        guest_selinux_close(fd);                 
        return NULL;
    }
    return e;
}

static int se_fd_kind(int fd)
{
    SeFd *e = se_lookup(fd);

    if (!e) {
        return 0;
    }
    return e->kind == SK_ATTR ? 2 : 1;
}

static bool se_register(int fd, int kind, int flags)
{
    SeFd *e = g_new0(SeFd, 1), *old;
    struct stat st;

    if (fd < 0 || fd >= SE_FDS || fstat(fd, &st)) {
        g_free(e);
        return false;
    }
    e->kind = kind;
    e->acc = flags & O_ACCMODE;
    e->dev = st.st_dev;
    e->ino = st.st_ino;
    old = __atomic_exchange_n(&se_fd[fd], e, __ATOMIC_ACQ_REL);
    if (old) {
        g_free(old->reply);
        g_free(old);
    } else {
        __atomic_add_fetch(&se_fd_count, 1, __ATOMIC_RELAXED);
    }
    return true;
}

 
static void se_register_or_fail(int *fd, int kind, int flags)
{
    if (*fd >= 0 && !se_register(*fd, kind, flags)) {
        close(*fd);
        *fd = -1;
        errno = EMFILE;
    }
}

void guest_selinux_close(int fd)
{
    SeFd *old;

    if (fd < 0 || fd >= SE_FDS || !__atomic_load_n(&se_fd_count, __ATOMIC_RELAXED)) {
        return;
    }
    old = __atomic_exchange_n(&se_fd[fd], NULL, __ATOMIC_ACQ_REL);
    if (old) {
        __atomic_sub_fetch(&se_fd_count, 1, __ATOMIC_RELAXED);
        g_free(old->reply);
        g_free(old);
    }
}

 
static const char *sefs_leaf(const char *guest_path)
{
    size_t n = strlen(SEFS_GUEST);

    if (!guest_path || strncmp(guest_path, SEFS_GUEST "/", n + 1) != 0) {
        return NULL;
    }
    return guest_path + n + 1;
}

static int attr_open(const char *guest_path, int flags, int *fd);

bool guest_selinux_try_open(const char *guest_path, int flags, int *fd)
{
    static const struct { const char *name; int kind; } txn[] = {
        { "access", SK_ACCESS }, { "context", SK_CONTEXT }, { "create", SK_CREATE },
        { "relabel", SK_RELABEL }, { "member", SK_MEMBER }, { "user", SK_USER },
    };
    const char *leaf;
    size_t i;

    if (!guest_selinux()) {
        return false;
    }
    if (attr_open(guest_path, flags, fd)) {
        return true;
    }
    if (!sefs_dir || !(leaf = sefs_leaf(guest_path))) {
        return false;
    }
    for (i = 0; i < ARRAY_SIZE(txn); i++) {
        if (strcmp(leaf, txn[i].name) == 0) {
             
 
            *fd = memfd_create("selinux-txn", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
            se_register_or_fail(fd, txn[i].kind, flags);
            return true;
        }
    }
    return false;
}

 

 
static void set_reply(SeFd *e, const char *text, size_t len)
{
    g_free(e->reply);
    e->reply = g_malloc(len + 1);
    memcpy(e->reply, text, len);
    e->reply[len] = '\0';
    e->len = len;
    e->off = 0;
}

 
 
 
 
typedef struct {
    char user[48], role[48], type[96], level[96];
} Ctx;

static bool ctx_parse(const char *s, size_t n, Ctx *c)
{
    char buf[320];
    char *a, *b, *t, *l;

    if (n == 0 || n >= sizeof buf) {
        return false;
    }
    memcpy(buf, s, n);
    buf[n] = '\0';
    a = strchr(buf, ':');
    b = a ? strchr(a + 1, ':') : NULL;
    t = b ? strchr(b + 1, ':') : NULL;
    if (!a || !b || !t) {
        return false;
    }
    *a = *b = *t = '\0';
    l = t + 1;
    if (strlen(buf) >= sizeof c->user || strlen(a + 1) >= sizeof c->role ||
        strlen(b + 1) >= sizeof c->type || strlen(l) >= sizeof c->level || !*l) {
        return false;
    }
    strcpy(c->user, buf);
    strcpy(c->role, a + 1);
    strcpy(c->type, b + 1);
    strcpy(c->level, l);
    return true;
}

static const VClass *vocab_by_value(unsigned value)
{
    GPtrArray *v = vocab_get();
    guint i;

    for (i = 0; i < v->len; i++) {
        const VClass *c = g_ptr_array_index(v, i);

        if (c->value == value) {
            return c;
        }
    }
    return NULL;
}

 
 
 
 
 
 
 
 
 
 
 
static bool compute_new(const char *req, size_t n, char *out, size_t cap)
{
    char buf[700];
    char *sp1, *sp2, *e;
    Ctx s, t;
    unsigned cls;
    const VClass *vc;
    bool process;
    size_t k;

    if (n == 0 || n >= sizeof buf) {
        return false;
    }
    memcpy(buf, req, n);
    buf[n] = '\0';
    sp1 = strchr(buf, ' ');
    sp2 = sp1 ? strchr(sp1 + 1, ' ') : NULL;
    if (!sp1 || !sp2) {
        return false;
    }
    *sp1 = *sp2 = '\0';
    cls = (unsigned)strtoul(sp2 + 1, &e, 10);
    if (e == sp2 + 1 || !ctx_parse(buf, strlen(buf), &s) ||
        !ctx_parse(sp1 + 1, strlen(sp1 + 1), &t)) {
        return false;
    }
    vc = vocab_by_value(cls);
    process = vc && strcmp(vc->name, "process") == 0;
    if (process) {
        k = strlen(t.type);
        if (k > 5 && strcmp(t.type + k - 5, "_exec") == 0) {
            t.type[k - 5] = '\0';
            snprintf(out, cap, "%s:%s:%s:%s", s.user, s.role, t.type, s.level);
        } else {
            snprintf(out, cap, "%s:%s:%s:%s", s.user, s.role, s.type, s.level);
        }
    } else {
        snprintf(out, cap, "%s:object_r:%s:%s", s.user, t.type, t.level);
    }
    return true;
}

static bool attr_write(SeFd *e, const void *buf, size_t n, abi_long *ret);
static size_t attr_read(SeFd *e, void *buf, size_t n);

bool guest_selinux_write(int fd, const void *buf, size_t n, abi_long *ret)
{
    static const char ALL[] = "ffffffff ffffffff 0 ffffffff 1 0";
    SeFd *e = se_lookup(fd);
    char out[400];

    if (!e) {
        return false;
    }
    if (e->acc == O_RDONLY) {                    
        errno = EBADF;
        *ret = get_errno(-1);
        return true;
    }
    switch (e->kind) {
    case SK_ATTR:
        return attr_write(e, buf, n, ret);
    case SK_ACCESS:
         
 
 
 
 
        set_reply(e, ALL, sizeof ALL - 1);
        break;
    case SK_CONTEXT: {
         
        size_t k = n;
        char *c;

        while (k > 0 && (((const char *)buf)[k - 1] == '\0' ||
                         ((const char *)buf)[k - 1] == '\n')) {
            k--;
        }
        if (k == 0 || k > 300) {
            errno = EINVAL;
            *ret = get_errno(-1);
            return true;
        }
        c = g_malloc(k + 1);
        memcpy(c, buf, k);
        c[k] = '\0';
        set_reply(e, c, k + 1);              
        g_free(c);
        break;
    }
    case SK_CREATE:
    case SK_RELABEL:
    case SK_MEMBER: {
        size_t k = n;

        while (k > 0 && (((const char *)buf)[k - 1] == '\0' ||
                         ((const char *)buf)[k - 1] == '\n')) {
            k--;
        }
        if (!compute_new(buf, k, out, sizeof out)) {
            errno = EINVAL;
            *ret = get_errno(-1);
            return true;
        }
        set_reply(e, out, strlen(out) + 1);
        break;
    }
    default:                                 
        set_reply(e, "", 0);
        break;
    }
    *ret = (abi_long)n;
    return true;
}

bool guest_selinux_read(int fd, void *buf, size_t n, abi_long *ret)
{
    SeFd *e = se_lookup(fd);
    size_t k;

    if (!e) {
        return false;
    }
    if (e->acc == O_WRONLY) {                    
        errno = EBADF;
        *ret = get_errno(-1);
        return true;
    }
    if (e->kind == SK_ATTR) {
        *ret = (abi_long)attr_read(e, buf, n);
        return true;
    }
    k = e->reply && e->off < e->len ? MIN(n, e->len - e->off) : 0;
    if (k) {
        memcpy(buf, e->reply + e->off, k);
        e->off += k;
    }
    *ret = (abi_long)k;
    return true;
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define SE_DEFAULT_CTX "u:r:init:s0"

enum { AT_CURRENT = 0, AT_EXEC, AT_FSCREATE, AT_KEYCREATE, AT_SOCKCREATE, AT_PREV, AT_N };
static const char *const AT_NAMES[AT_N] = {
    "current", "exec", "fscreate", "keycreate", "sockcreate", "prev",
};

static const char *attr_state[AT_N];     

static const char *attr_get(int a)
{
    return __atomic_load_n(&attr_state[a], __ATOMIC_ACQUIRE);
}

 
 
 
 
 
 
 
#define INTERN_N 4096

static const char *intern_tab[INTERN_N];

static const char *intern_str(const char *text, size_t n)
{
    uint64_t h = 1469598103934665603ull;
    size_t i, k;

    for (i = 0; i < n; i++) {
        h = (h ^ (unsigned char)text[i]) * 1099511628211ull;
    }
    for (k = 0; k < INTERN_N; k++) {
        const char **slot = &intern_tab[(h + k) & (INTERN_N - 1)];
        const char *p = __atomic_load_n(slot, __ATOMIC_ACQUIRE);

        if (!p) {
            const char *none = NULL;
            char *copy = g_strndup(text, n);

            if (__atomic_compare_exchange_n(slot, &none, copy, false, __ATOMIC_ACQ_REL,
                                            __ATOMIC_ACQUIRE)) {
                return copy;
            }
            g_free(copy);                        
            p = none;
        }
        if (strlen(p) == n && memcmp(p, text, n) == 0) {
            return p;
        }
    }
    return NULL;
}

 
static bool attr_put(int a, const char *text, size_t n)
{
    const char *c = NULL;

    if (text) {
        c = intern_str(text, n);
        if (!c) {
            return false;
        }
    }
    __atomic_store_n(&attr_state[a], c, __ATOMIC_RELEASE);
    return true;
}

 
static const char *ctx_current(void)
{
    const char *c = attr_get(AT_CURRENT);

    return c ? c : SE_DEFAULT_CTX;
}

 
static void ctx_of_pid(int pid, char *buf, size_t cap)
{
    char path[48];
    FILE *f;
    char *line = NULL;
    size_t lc = 0;
    ssize_t k;

    snprintf(buf, cap, "%s", SE_DEFAULT_CTX);
    if (pid <= 0) {
        return;
    }
    if (pid == (int)getpid()) {
        snprintf(buf, cap, "%s", ctx_current());
        return;
    }
    snprintf(path, sizeof path, "/proc/%d/environ", pid);
    f = fopen(path, "re");
    if (!f) {
        return;
    }
     
    while ((k = getdelim(&line, &lc, '\0', f)) > 0) {
        if (strncmp(line, "GUEST_SECON=", 12) == 0 && line[12]) {
            snprintf(buf, cap, "%s", line + 12);
            break;
        }
    }
    free(line);
    fclose(f);
}

void guest_selinux_attr_init(void)
{
    const char *s = getenv("GUEST_SECON");
    Ctx scratch;

    if (s && *s && ctx_parse(s, strlen(s), &scratch)) {
        attr_put(AT_CURRENT, s, strlen(s));
    }
}

int guest_selinux_child_env(char *buf, size_t cap)
{
    const char *c = attr_get(AT_EXEC);

    if (!guest_selinux()) {
        return 0;
    }
    if (!c) {
        c = attr_get(AT_CURRENT);
    }
    if (!c) {
        return 0;
    }
    snprintf(buf, cap, "GUEST_SECON=%s", c);
    return 1;
}

bool guest_selinux_is_child_env(const char *entry)
{
    return guest_selinux() && strncmp(entry, "GUEST_SECON=", 12) == 0;
}

 
 
 
 
 
static int attr_parse(const char *p, int *pid)
{
    const char *who, *s, *name;
    size_t wl;
    int i;

    if (!p || strncmp(p, "/proc/", 6) != 0) {
        return -1;
    }
    who = p + 6;
    s = strchr(who, '/');
    if (!s) {
        return -1;
    }
    wl = (size_t)(s - who);
    if ((wl == 4 && !strncmp(who, "self", 4)) ||
        (wl == 11 && !strncmp(who, "thread-self", 11))) {
        *pid = 0;
    } else {
        char *e;
        long v = strtol(who, &e, 10);

        if (e != s || v <= 0) {
            return -1;
        }
        *pid = v == (long)getpid() ? 0 : (int)v;
    }
    s++;
    if (strncmp(s, "task/", 5) == 0) {
        const char *q = strchr(s + 5, '/');

        if (!q || q == s + 5) {
            return -1;
        }
        s = q + 1;
    }
    if (strncmp(s, "attr/", 5) != 0) {
        return -1;
    }
    name = s + 5;
    for (i = 0; i < AT_N; i++) {
        if (strcmp(name, AT_NAMES[i]) == 0) {
            return i;
        }
    }
    return -1;
}

static int attr_open(const char *guest_path, int flags, int *fd)
{
    int pid, a = attr_parse(guest_path, &pid);

    if (a < 0) {
        return 0;
    }
    *fd = memfd_create("selinux-attr", (flags & O_CLOEXEC) ? MFD_CLOEXEC : 0);
    se_register_or_fail(fd, SK_ATTR, flags);
    if (*fd >= 0) {
        SeFd *e = se_fd[*fd];

        if (e) {
            e->attr = a;
            e->pid = pid;
        }
    }
    return 1;
}

 
static size_t attr_text(const SeFd *e, char *out, size_t cap)
{
    const char *c;

    if (e->pid) {
        if (e->attr == AT_CURRENT || e->attr == AT_PREV) {
            ctx_of_pid(e->pid, out, cap - 1);
            return strlen(out) + 1;
        }
        return 0;
    }
    c = attr_get(e->attr);
    if (!c && (e->attr == AT_CURRENT || e->attr == AT_PREV)) {
        c = ctx_current();
    }
    if (e->attr == AT_PREV) {
        c = ctx_current();
    }
    if (!c) {
        return 0;
    }
    snprintf(out, cap, "%s", c);
    return strlen(out) + 1;
}

static size_t attr_read(SeFd *e, void *buf, size_t n)
{
    char text[400];
    size_t len = attr_text(e, text, sizeof text), k;

    if (e->off >= len) {
        return 0;
    }
    k = MIN(n, len - e->off);
    memcpy(buf, text + e->off, k);
    e->off += k;
    return k;
}

static bool attr_write(SeFd *e, const void *buf, size_t n, abi_long *ret)
{
    size_t k = n;
    Ctx scratch;

    if (e->pid || e->attr == AT_PREV) {          
        errno = EACCES;
        *ret = get_errno(-1);
        return true;
    }
    while (k > 0 && (((const char *)buf)[k - 1] == '\0' ||
                     ((const char *)buf)[k - 1] == '\n')) {
        k--;
    }
    if (k == 0) {
        if (e->attr == AT_CURRENT) {             
            errno = EINVAL;
            *ret = get_errno(-1);
            return true;
        }
        attr_put(e->attr, NULL, 0);
    } else {
        if (!ctx_parse(buf, k, &scratch)) {
            errno = EINVAL;
            *ret = get_errno(-1);
            return true;
        }
        if (!attr_put(e->attr, buf, k)) {        
            errno = ENOMEM;
            *ret = get_errno(-1);
            return true;
        }
    }
    *ret = (abi_long)n;
    return true;
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define SE_LABEL        "u:object_r:unlabeled:s0"
#define XNAME_SELINUX   "security.selinux"
#define XNAME_RESTORE   "security.restorecon_last"

 
 
 
 
 
 
 
 
 
 
 
static void exec_label(const char *gpath, char *out, size_t cap)
{
    static const char *const dirs[] = {
        "/system/bin/", "/system/xbin/", "/sbin/", "/vendor/bin/", "/system/vendor/bin/",
    };
    size_t i, j, n;

    for (i = 0; i < ARRAY_SIZE(dirs); i++) {
        n = strlen(dirs[i]);
        if (strncmp(gpath, dirs[i], n) == 0) {
            const char *name = gpath + n;
            char t[64];

            if (!*name || strchr(name, '/') || strlen(name) >= sizeof t - 6) {
                return;
            }
            for (j = 0; name[j]; j++) {
                t[j] = g_ascii_isalnum(name[j]) || name[j] == '_' ? name[j] : '_';
            }
            t[j] = '\0';
            snprintf(out, cap, "u:object_r:%s_exec:s0", t);
            return;
        }
    }
}

 
 
 
 
static abi_long xattr_target(int by, abi_ulong a1, char *label, size_t cap)
{
    struct stat st;
    int r;

    snprintf(label, cap, "%s", SE_LABEL);
    if (by == 2) {                               
        r = fstat((int)a1, &st);
    } else {
        char *p = lock_user_string(a1);

        if (!p) {
            return -TARGET_EFAULT;
        }
        r = by == 1 ? lstat(path(p), &st) : stat(path(p), &st);
        if (!r && S_ISREG(st.st_mode) && (st.st_mode & 0111)) {
            exec_label(p, label, cap);
        }
        unlock_user(p, a1, 0);
    }
    return r ? get_errno(-1) : 0;
}

 
static abi_long xattr_value(const char *val, size_t len, abi_ulong buf, abi_ulong size)
{
    void *b;

    if (size == 0) {
        return (abi_long)len;
    }
    if (size < len) {
        return -TARGET_ERANGE;
    }
    b = lock_user(VERIFY_WRITE, buf, len, 0);
    if (!b) {
        return -TARGET_EFAULT;
    }
    memcpy(b, val, len);
    unlock_user(b, buf, len);
    return (abi_long)len;
}

 

 
static abi_long host_err(long r)
{
    errno = (int)-r;
    return get_errno(-1);
}

 
 
 
 
 
 
static abi_long user_target(int by, abi_ulong a1, char **gp, const char **hp, int *fd)
{
    *gp = NULL;
    *hp = NULL;
    *fd = -1;
    if (by == GX_BY_FD) {
        *fd = (int)a1;
        return 0;
    }
    *gp = lock_user_string(a1);
    if (!*gp) {
        return -TARGET_EFAULT;
    }
    *hp = by == GX_BY_LINK ? path_nofollow_at(AT_FDCWD, *gp) : path(*gp);
    return 0;
}

 
 
 
 
static abi_long user_get(int by, abi_ulong a1, const char *name, abi_ulong val,
                         abi_ulong size)
{
    size_t cap = MIN((size_t)size, (size_t)GX_SIZE_MAX);
    char *tmp = cap ? g_malloc(cap) : NULL;
    char *gp;
    const char *hp;
    int fd;
    long n;
    abi_long r = user_target(by, a1, &gp, &hp, &fd);

    if (r) {
        g_free(tmp);
        return r;
    }
    n = gx_get(by, hp, fd, name, tmp, (size_t)size);
    unlock_user(gp, a1, 0);
    if (n < 0) {
        r = host_err(n);
    } else {
        r = (abi_long)n;
        if (size && n > 0) {
            void *b = lock_user(VERIFY_WRITE, val, n, 0);

            if (!b) {
                r = -TARGET_EFAULT;
            } else {
                memcpy(b, tmp, n);
                unlock_user(b, val, n);
            }
        }
    }
    g_free(tmp);
    return r;
}

 
static abi_long user_set(int by, abi_ulong a1, const char *name, abi_ulong val,
                         abi_ulong size, abi_ulong flags)
{
    void *v = NULL;
    char *gp;
    const char *hp;
    int fd;
    long n;
    abi_long r;

     
    if (size && size <= GX_SIZE_MAX) {
        v = lock_user(VERIFY_READ, val, size, 1);
        if (!v) {
            return -TARGET_EFAULT;
        }
    }
    r = user_target(by, a1, &gp, &hp, &fd);
    if (!r) {
        n = gx_set(by, hp, fd, name, v, (size_t)size, (int)flags);
        unlock_user(gp, a1, 0);
        r = n < 0 ? host_err(n) : 0;
    }
    unlock_user(v, val, 0);
    return r;
}

 
static abi_long user_remove(int by, abi_ulong a1, const char *name)
{
    char *gp;
    const char *hp;
    int fd;
    long n;
    abi_long r = user_target(by, a1, &gp, &hp, &fd);

    if (r) {
        return r;
    }
    n = gx_remove(by, hp, fd, name);
    unlock_user(gp, a1, 0);
    return n < 0 ? host_err(n) : 0;
}

 
 
 
 
 
static abi_long xattr_list(int by, abi_ulong a1, abi_ulong lst, abi_ulong size)
{
    const char *extra = labels_on() ? XNAME_SELINUX : NULL;
    size_t elen = extra ? sizeof XNAME_SELINUX : 0;
    char *l = NULL, *gp;
    const char *hp;
    int fd;
    long n, f;
    abi_long r = user_target(by, a1, &gp, &hp, &fd);

    if (r) {
        return r;
    }
    n = gx_list(by, hp, fd, extra, elen, &l);
    unlock_user(gp, a1, 0);
    if (n < 0) {
        return host_err(n);
    }
    f = gx_fit((size_t)n, (size_t)size);
    if (f < 0) {
        r = host_err(f);
    } else if (size == 0 || n == 0) {
        r = (abi_long)n;
    } else {
        void *b = lock_user(VERIFY_WRITE, lst, n, 0);

        if (!b) {
            r = -TARGET_EFAULT;
        } else {
            memcpy(b, l, n);
            unlock_user(b, lst, n);
            r = (abi_long)n;
        }
    }
    free(l);
    return r;
}

abi_long guest_selinux_xattr(int op, abi_ulong a1, abi_ulong a2, abi_ulong a3,
                             abi_ulong a4, abi_ulong a5)
{
    int by = (op == GX_LGET || op == GX_LSET || op == GX_LLIST || op == GX_LREMOVE) ? GX_BY_LINK :
             (op == GX_FGET || op == GX_FSET || op == GX_FLIST || op == GX_FREMOVE) ? GX_BY_FD :
             GX_BY_PATH;
    bool get = op == GX_GET || op == GX_LGET || op == GX_FGET;
    bool rm = op == GX_REMOVE || op == GX_LREMOVE || op == GX_FREMOVE;
    abi_long r;
    char *name;
    char label[128];

    if (op == GX_LIST || op == GX_LLIST || op == GX_FLIST) {
         
        return xattr_list(by, a1, a2, a3);
    }
     
    name = lock_user_string(a2);
    if (!name) {
        return -TARGET_EFAULT;
    }
     
 
 
 
 
 
    if (gx_is_user(name)) {
        r = get ? user_get(by, a1, name, a3, a4) :
            rm ? user_remove(by, a1, name) :
            user_set(by, a1, name, a3, a4, a5);
        unlock_user(name, a2, 0);
        return r;
    }
     
    if (!labels_on() || rm ||
        (strcmp(name, XNAME_SELINUX) != 0 && strcmp(name, XNAME_RESTORE) != 0)) {
        unlock_user(name, a2, 0);
        return -TARGET_ENOSYS;
    }
    r = xattr_target(by, a1, label, sizeof label);
    if (!r) {
        bool sel = strcmp(name, XNAME_SELINUX) == 0;

        if (get) {
             
            r = sel ? xattr_value(label, strlen(label) + 1, a3, a4) : -TARGET_ENODATA;
        }                                        
    }
    unlock_user(name, a2, 0);
    return r;
}

 

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
bool guest_selinux_peersec(int sockfd, char *buf, socklen_t *len, abi_long *ret)
{
    struct ucred cr;
    socklen_t cl = sizeof cr;
    char ctx[400];
    socklen_t need;
    int dom = 0;
    socklen_t dl = sizeof dom;

    if (!guest_selinux()) {
        return false;
    }

    if (getsockopt(sockfd, SOL_SOCKET, SO_PEERCRED, &cr, &cl) < 0) {
         
        errno = (errno == EBADF || errno == ENOTSOCK) ? errno : ENOPROTOOPT;
        *ret = get_errno(-1);
        return true;
    }
     
 
 
 
    if (getsockopt(sockfd, SOL_SOCKET, SO_DOMAIN, &dom, &dl) < 0 || dom != AF_UNIX ||
        cr.pid <= 0) {
        errno = ENOPROTOOPT;
        *ret = get_errno(-1);
        return true;
    }
    ctx_of_pid(cr.pid, ctx, sizeof ctx);
    need = (socklen_t)strlen(ctx) + 1;
    if (*len < need) {
        *len = need;
        *ret = -TARGET_ERANGE;
        return true;
    }
    memcpy(buf, ctx, need);
    *len = need;
    *ret = 0;
    return true;
}
