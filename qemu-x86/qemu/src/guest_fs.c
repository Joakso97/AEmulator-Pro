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
// Changed: 2026-08-29 … 2026-10-10

/* guest_fs.c — гостю показываем ЕГО раздел, а не диск телефона.
 *
 * ★★★ Зачем. Гостевой /data — это каталог в песочнице приложения, и statfs по
 * нему отдаёт цифры ВСЕГО накопителя хозяина: 471 ГБ, из них свободно
 * 3,3 ГБ. По абсолютной величине места вдоволь, а по доле — 0,7 %, и вот что
 * из этого выходит в Android 2.3:
 *
 *   * DeviceStorageMonitorService считает порог как долю от общего размера
 *     (верхнего ограничения в 2.3 ещё нет) — 10 % от 471 ГБ это 47 ГБ. Свободные
 *     3,3 ГБ меньше порога, и в шторке навсегда повисает «Low on space»;
 *   * PackageManager по этому признаку зовёт installd освобождать кэш, а тот
 *     обходит все пакеты и на каждом жалуется:
 *       E installd  Couldn't openat cache: No such file or directory
 *     — за одну загрузку таких строк набирается полторы сотни;
 *   * DropBoxManagerService.trimToFit падает в system_process на StatFs;
 *   * приложения, поднятые ради широковещания, валятся в обработчике ошибки.
 *
 * Аппараты тех лет имели раздел данных примерно в полтора гигабайта, и все эти
 * расчёты писались под такой порядок. Столько и показываем: числа взяты с
 * потолка, но они
 * ЗАНИЖЕНЫ относительно настоящего свободного места на телефоне — значит гость
 * не сможет пообещать себе больше, чем есть на самом деле.
 *
 * ⚠️ Подменяем только ветви гостя (/data, /cache, /mnt, /system, /sdcard).
 * Остальное — как есть: пусть /proc и /dev отвечают правдой.
 */
#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"
#include "qemu/path.h"

#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>

#include "guest_fs.h"
#include "guest_fwinit.h"
#include "guest_proc.h"
#include "guest_selinux.h"

 
#define FS_BSIZE   4096u
#define FS_BLOCKS  393216u       
#define FS_FREE    262144u       
#define FS_FILES   65536u
#define FS_FFREE   60000u

static int ours(const char *p)
{
    static const char *const pre[] = {
        "/data", "/cache", "/mnt", "/system", "/sdcard", "/tmp", NULL
    };

    if (!p || p[0] != '/') {
        return 0;
    }
    for (int i = 0; pre[i]; i++) {
        size_t n = strlen(pre[i]);

        if (!strncmp(p, pre[i], n) && (p[n] == 0 || p[n] == '/')) {
            return 1;
        }
    }
    return 0;
}

void guest_fs_fake(const char *path, struct statfs *st)
{
     
 
 
 
    if (guest_selinux_statfs(path, st)) {
        return;
    }
    if (!st || !ours(path)) {
        return;
    }
    st->f_bsize = FS_BSIZE;
    st->f_frsize = FS_BSIZE;
    st->f_blocks = FS_BLOCKS;
    st->f_bfree = FS_FREE;
    st->f_bavail = FS_FREE;
    st->f_files = FS_FILES;
    st->f_ffree = FS_FFREE;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static const char CGROUP_TEXT[] = "2:cpu:/\n";

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static const char NETDEV_TEXT[] =
    "Inter-|   Receive                                                "
    "|  Transmit\n"
    " face |bytes    packets errs drop fifo frame compressed multicast"
    "|bytes    packets errs drop fifo colls carrier compressed\n"
    "    lo:       0       0    0    0    0     0          0         0"
    "        0       0    0    0    0     0       0          0\n";

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static const char MEMINFO_TEXT[] =
    "MemTotal:        1846588 kB\n"
    "MemFree:          742216 kB\n"
    "Buffers:           28152 kB\n"
    "Cached:           402384 kB\n"
    "SwapCached:            0 kB\n"
    "Active:           520316 kB\n"
    "Inactive:         281944 kB\n"
    "Active(anon):     372660 kB\n"
    "Inactive(anon):     2372 kB\n"
    "Active(file):     147656 kB\n"
    "Inactive(file):   279572 kB\n"
    "Unevictable:         256 kB\n"
    "Mlocked:               0 kB\n"
    "HighTotal:       1245184 kB\n"
    "HighFree:         381208 kB\n"
    "LowTotal:         601404 kB\n"
    "LowFree:          361008 kB\n"
    "SwapTotal:             0 kB\n"
    "SwapFree:              0 kB\n"
    "Dirty:                 0 kB\n"
    "Writeback:             0 kB\n"
    "AnonPages:        371992 kB\n"
    "Mapped:           182664 kB\n"
    "Shmem:              3080 kB\n"
    "Slab:              54620 kB\n"
    "SReclaimable:      24176 kB\n"
    "SUnreclaim:        30444 kB\n"
    "KernelStack:        9432 kB\n"
    "PageTables:        18256 kB\n"
    "NFS_Unstable:          0 kB\n"
    "Bounce:                0 kB\n"
    "WritebackTmp:          0 kB\n"
    "CommitLimit:      923292 kB\n"
    "Committed_AS:   16558312 kB\n"
    "VmallocTotal:     245760 kB\n"
    "VmallocUsed:       92416 kB\n"
    "VmallocChunk:      70652 kB\n";

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static const char ROUTE_TEXT[] =
    "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\t"
    "MTU\tWindow\tIRTT\n"
    "lo\t0000007F\t00000000\t0001\t0\t0\t0\t000000FF\t0\t0\t0\n";

static const char ARP_TEXT[] =
    "IP address       HW type     Flags       HW address            Mask"
    "     Device\n";

 
static const char IF_INET6_TEXT[] =
    "00000000000000000000000000000001 01 80 10 80       lo\n";

static const char TCP_TEXT[] =
    "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when "
    "retrnsmt   uid  timeout inode\n";

static const char UDP_TEXT[] =
    "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when "
    "retrnsmt   uid  timeout inode ref pointer drops\n";

static const char TCP6_TEXT[] =
    "  sl  local_address                         remote_address"
    "                        st tx_queue rx_queue tr tm->when retrnsmt   uid"
    "  timeout inode\n";

static const char UDP6_TEXT[] =
    "  sl  local_address                         remote_address"
    "                        st tx_queue rx_queue tr tm->when retrnsmt   uid"
    "  timeout inode ref pointer drops\n";

static const char QTAGUID_TEXT[] =
    "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes"
    " tx_packets rx_tcp_bytes rx_tcp_packets rx_udp_bytes rx_udp_packets"
    " rx_other_bytes rx_other_packets tx_tcp_bytes tx_tcp_packets"
    " tx_udp_bytes tx_udp_packets tx_other_bytes tx_other_packets\n";

 
 
 
 
 
 
 
 
 
static const char QTAGUID_IFACE_FMT_TEXT[] =
    "ifname total_skb_rx_bytes total_skb_rx_packets total_skb_tx_bytes"
    " total_skb_tx_packets\n";

 
static const struct {
    const char *path;
    const char *text;
} SUBST[] = {
    { "/proc/net/dev",               NETDEV_TEXT  },
    { "/proc/meminfo",               MEMINFO_TEXT },
    { "/proc/net/route",             ROUTE_TEXT   },
    { "/proc/net/arp",               ARP_TEXT     },
    { "/proc/net/if_inet6",          IF_INET6_TEXT},
    { "/proc/net/tcp",               TCP_TEXT     },
    { "/proc/net/udp",               UDP_TEXT     },
    { "/proc/net/tcp6",              TCP6_TEXT    },
    { "/proc/net/udp6",              UDP6_TEXT    },
    { "/proc/net/xt_qtaguid/stats",  QTAGUID_TEXT },
    { "/proc/net/xt_qtaguid/iface_stat_fmt", QTAGUID_IFACE_FMT_TEXT },
    { "/proc/net/xt_qtaguid/iface_stat_all", "" },       
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    { "/proc/net/xt_qtaguid/ctrl", "" },
};

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int give_text(const char *text, size_t len, int *fd)
{
    int d = -1;

#ifdef __NR_memfd_create
    d = (int)syscall(__NR_memfd_create, "guestfs", 0);
#endif
    if (d < 0) {
        FILE *f = tmpfile();

        if (!f) {
            return 0;                  
        }
        d = dup(fileno(f));
        fclose(f);
        if (d < 0) {
            return 0;
        }
    }
    for (size_t off = 0; off < len; ) {
        ssize_t w = write(d, text + off, len - off);

        if (w <= 0) {
            close(d);
            return 0;
        }
        off += (size_t)w;
    }
    lseek(d, 0, SEEK_SET);
    *fd = d;
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static const struct {
    const char *name;
    int sleeps;                          
} FBWAIT_NODES[] = {
    { "/sys/power/wait_for_fb_sleep", 1 },
    { "/sys/power/wait_for_fb_wake",  0 },
};

 
static const char FBWAIT_AWAKE[] = "awake";

 
static int fbwait_node(const char *gpath)
{
    for (size_t i = 0; i < ARRAY_SIZE(FBWAIT_NODES); i++) {
        if (!strcmp(gpath, FBWAIT_NODES[i].name)) {
            return (int)i;
        }
    }
    return -1;
}

 
static int fbwait_in_tree(const char *gpath)
{
    const char *host = path(gpath);

    return host && access(host, F_OK) == 0;
}

 
static int fbwait_flags(int d, int flags)
{
    if ((flags & O_NONBLOCK) && fcntl(d, F_SETFL, fcntl(d, F_GETFL) | O_NONBLOCK) < 0) {
        return -1;
    }
    return fcntl(d, F_SETFD, (flags & O_CLOEXEC) ? FD_CLOEXEC : 0);
}

 
static int fbwait_open_sleep(int flags)
{
    int d = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    int e;

    if (d < 0) {
        return -1;                           
    }
    if (fbwait_flags(d, flags) < 0) {
        e = errno;
        close(d);
        errno = e;
        return -1;
    }
    return d;
}

 
static int fbwait_open_wake(int flags)
{
    int p[2];
    int e;

    if (pipe2(p, O_CLOEXEC) < 0) {
        return -1;
    }
    if (write(p[1], FBWAIT_AWAKE, sizeof FBWAIT_AWAKE - 1) != (ssize_t)(sizeof FBWAIT_AWAKE - 1) || fbwait_flags(p[0], flags) < 0) {
        e = errno;
        close(p[0]);
        close(p[1]);
        errno = e;
        return -1;
    }
    close(p[1]);                             
    return p[0];
}

 
static int fbwait_open(int node, int flags, int *fd)
{
    if ((flags & O_ACCMODE) != O_RDONLY) {
        errno = EACCES;
        *fd = -1;
        return 1;
    }
    *fd = FBWAIT_NODES[node].sleeps ? fbwait_open_sleep(flags) : fbwait_open_wake(flags);
    return 1;
}

int guest_fs_try_open(const char *path, int flags, int *fd)
{
    size_t n;

     
    if (path) {
        int node = fbwait_node(path);

        if (node >= 0 && !fbwait_in_tree(path)) {
            return fbwait_open(node, flags, fd);
        }
    }
    if (!path || strncmp(path, "/proc/", 6)) {
        return 0;
    }
    {
         
 
 
 
 
 
        char tbuf[1024];                 
        const char *t = guest_fwinit_proc_text(path, tbuf, sizeof tbuf);

        if (t) {
            return give_text(t, strlen(t), fd);
        }
    }
     
 
 
 
    if (!strcmp(path, "/proc/emmc")) {
        char *data;
        size_t len;

        if (guest_fwinit_emmc(&data, &len)) {
            int r = give_text(data, len, fd);

            g_free(data);
            return r;
        }
         
 
 
 
        if (guest_fwinit()) {
            *fd = -1;
            errno = ENOENT;
            return 1;
        }
        return 0;
    }
     
 
 
 
 
 
 
 
 
 
    if (!strcmp(path, "/proc/version") && qemu_uname_release && *qemu_uname_release) {
        char t[192];
        int k = snprintf(t, sizeof t, "Linux version %s (android-build@localhost) "
                         "(gcc version 4.8 (GCC) ) #1 SMP PREEMPT "
                         "Mon Jan 1 00:00:00 UTC 2018\n", qemu_uname_release);

        return k > 0 && k < (int)sizeof t ? give_text(t, (size_t)k, fd) : 0;
    }
     
 
 
 
 
 
    const char *rest = strstr(path + 6, "/net/") ? guest_proc_mine_rest(path) : NULL;
    const char *look = path;
    char alias[128];

    if (rest && !strncmp(rest, "net/", 4) &&
        snprintf(alias, sizeof alias, "/proc/%s", rest) < (int)sizeof alias) {
        look = alias;
    }
    for (size_t i = 0; i < ARRAY_SIZE(SUBST); i++) {
        if (!strcmp(look, SUBST[i].path)) {
            return give_text(SUBST[i].text, strlen(SUBST[i].text), fd);
        }
    }
     
 
 
 
 
 
 
 
 
 
 
    if (!strncmp(look, "/proc/net/xt_quota/", 19) && look[19]) {
        return give_text("", 0, fd);
    }
    n = strlen(path);
    if (n < 7 || strcmp(path + n - 7, "/cgroup")) {
        return 0;
    }
    return give_text(CGROUP_TEXT, sizeof CGROUP_TEXT - 1, fd);
}
