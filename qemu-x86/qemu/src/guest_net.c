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
// Changed: 2026-08-29 … 2026-10-05

/* guest_net.c — подставной netlink для гостевых служб.
 *
 * Зачем. netd и vold прошивки первым делом заводят сокет событий ядра:
 *
 *   int NetlinkManager::start() {
 *       mSock = socket(PF_NETLINK, SOCK_DGRAM, NETLINK_KOBJECT_UEVENT);
 *       setsockopt(mSock, SOL_SOCKET, SO_RCVBUFFORCE, …);
 *       bind(mSock, …);
 *       mHandler->start();
 *   }
 *
 * Процессу приложения на Android этого не дают — политика SELinux не пускает
 * untrusted_app создавать netlink_kobject_uevent_socket:
 *
 *   I Netd  Netd 1.0 starting
 *   E Netd  Unable to create uevent socket: Permission denied
 *   E Netd  Unable to start NetlinkManager (Permission denied)
 *   · netd завершился, код 1
 *
 * И это не мелочь: без netd загрузка встаёт НАВСЕГДА.
 * NetworkManagementService.create ждёт его соединения на
 * mConnectedSignal.await(), а в логе видно лишь бесконечное
 * «NetdConnector: Communications error».
 *
 * Что делаем. Ровно то же, что с ashmem и binder: даём безвредную замену там,
 * где хозяйское ядро отказывает. Гость получает обычный AF_UNIX SOCK_DGRAM —
 * несвязанный и никому не известный. Читать из него можно, и чтение честно
 * блокируется навсегда: событий ядра у нас и не будет. Поток-слушатель внутри
 * netd спит на нём, а всё остальное — приём команд от system_server — работает.
 *
 * ★ Подменяем ТОЛЬКО когда настоящий вызов отказал по правам. Там, где netlink
 * доступен (например на обычном Linux), гость получит настоящий сокет и настоящие
 * события. Подмена не должна прятать работающую возможность.
 *
 * ⚠️ bind и setsockopt на подставном дескрипторе отвечают успехом, не делая
 * ничего: гость шлёт им sockaddr_nl и SO_RCVBUFFORCE, которые для AF_UNIX
 * бессмысленны, а отказ на любом из них netd считает смертельным.
 *
 * ★★★★★ 14 сентября: МОЛЧАНИЕ ПОДСТАВНОГО СОКЕТА ВЕШАЛО ВЕСЬ ВЕБ.
 *
 * «Читать можно, чтение блокируется навсегда» — для netd безобидно (его поток
 * событий и должен спать), а для браузера оказалось смертельно. Сетевая часть
 * Движок браузера прошивки (libbrowsercore → libbrowsernet) при создании
 * КАЖДОЙ страницы делает так:
 *
 *   BrowserFrame.nativeCreateFrame
 *     → net::NetworkChangeNotifier::Create()
 *       → NetworkChangeNotifierLinux()
 *         → base::Thread::StartWithOptions()   ← ЖДЁТ, пока поток отчитается
 *
 * а сам поток ПЕРЕД тем как отчитаться просит у ядра список адресов
 * (RTM_GETADDR, дамп) и читает ответ ПОДРЯД, пока не придёт NLMSG_DONE.
 * Ответа нет — поток не отчитывается — StartWithOptions стоит вечно —
 * nativeCreateFrame не возвращается — страница не рисуется НИКОГДА. Снято на
 * живом стенде: поток висит в recvfrom(fd, …, 4096), wchan =
 * __skb_wait_for_more_packets, а его создатель — в WaitableEvent::Wait.
 * Отсюда и «полоса встала на 35 %, содержимое белое» — даже для file://.
 *
 * Поэтому подставной сокет теперь не немой. Вместо одинокого AF_UNIX берём
 * ПАРУ: один конец отдаём гостю, второй держим у себя и отвечаем на запросы
 * дампов сами — одним интерфейсом (поднят, не петля) с адресом, и NLMSG_DONE.
 * Событий по-прежнему нет: ждущий их поток netd спит, как и спал.
 *
 * ⚠️ Почему «есть интерфейс», а не «пусто»: у движка браузера пустой список линков
 * означает CONNECTION_NONE, то есть «сети нет вовсе», и он начинает отказывать
 * запросам сам, не доходя до сокета. А сеть у гостя как раз ЕСТЬ — она идёт
 * через хозяйское ядро (см. guest_http_redirect ниже). Так что один поднятый
 * интерфейс — не выдумка, а самое близкое к правде, что можно сказать в
 * терминах netlink.
 */
#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"

#include <linux/netlink.h>
#include <linux/rtnetlink.h>

#include "guest_net.h"

 
#define GUEST_NET_MAX 16

 
 
 
 
static int fds[GUEST_NET_MAX];
static int peers[GUEST_NET_MAX];
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static uint32_t nlpid[GUEST_NET_MAX];
static uint32_t nlgrp[GUEST_NET_MAX];
static int nfds;

 
 
 
 
 
#define GUEST_NET_FDBITS 4096
static uint32_t owned[GUEST_NET_FDBITS / 32];

static void own_set(int fd, int on)
{
    if (fd < 0 || fd >= GUEST_NET_FDBITS) {
        return;
    }
    if (on) {
        qatomic_or(&owned[fd / 32], 1u << (fd % 32));
    } else {
        qatomic_and(&owned[fd / 32], ~(1u << (fd % 32)));
    }
}
static QemuMutex lock;
static bool inited;

 
static void dns_forget(int fd);

static void net_init(void)
{
    if (!inited) {
        qemu_mutex_init(&lock);
        inited = true;
    }
}

 
 
 
 
 
 
 
 
 
 
static FILE *g_nlog;

static void nlog_init(void)
{
    static int done;
    const char *path;

    if (done) {
        return;
    }
    done = 1;
    path = getenv("GUEST_NET_LOG");
    if (!path || !*path) {
        return;
    }
    g_nlog = (path[0] == '-' && !path[1]) ? stderr : fopen(path, "ae");
}

static void nlog(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void nlog(const char *fmt, ...)
{
    va_list ap;

    nlog_init();
    if (!g_nlog) {
        return;
    }
    fprintf(g_nlog, "[pid=%d] ", getpid());
    va_start(ap, fmt);
    vfprintf(g_nlog, fmt, ap);
    va_end(ap);
    fputc('\n', g_nlog);
    fflush(g_nlog);
}

int guest_net_socket_fallback(int domain, int type, int protocol, int *fd)
{
    int s, i, sv[2];

    if (domain != PF_NETLINK) {
        return 0;
    }
     
 
 
 
    if (errno != EACCES && errno != EPERM) {
        return 0;
    }

     
    if (socketpair(AF_UNIX, SOCK_DGRAM | (type & ~0xf  ),
                   0, sv) < 0) {
        return 0;
    }
    s = sv[0];

    net_init();
    qemu_mutex_lock(&lock);
    if (nfds >= GUEST_NET_MAX) {
        qemu_mutex_unlock(&lock);
        close(sv[0]);
        close(sv[1]);
        return 0;
    }
    for (i = 0; i < nfds; i++) {
        if (fds[i] == s) {           
            break;
        }
    }
    if (i == nfds) {
        nfds++;
    } else {
        close(peers[i]);             
    }
    fds[i] = s;
    peers[i] = sv[1];
    nlpid[i] = 0;                    
    nlgrp[i] = 0;
    own_set(s, 1);
    qemu_mutex_unlock(&lock);

    nlog("socket: netlink(%d) запрещён хозяином — подставлена пара AF_UNIX, "
         "дескриптор %d", protocol, s);
    *fd = s;
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int netlink_take_over(int sockfd, const char *why)
{
    int dom = 0, i, sv[2], fl, fdfl;
    socklen_t len = sizeof(dom);
    struct sockaddr_nl was;
    socklen_t waslen = sizeof(was);
    uint32_t keep_pid = 0, keep_grp = 0;

    if (sockfd < 0) {
        return 0;
    }
     
    if (getsockopt(sockfd, SOL_SOCKET, SO_DOMAIN, &dom, &len) < 0 ||
        dom != PF_NETLINK) {
        return 0;
    }

     
 
 
 
 
 
 
 
 
 
    memset(&was, 0, sizeof(was));
    if (getsockname(sockfd, (struct sockaddr *)&was, &waslen) == 0 &&
        waslen >= sizeof(was) && was.nl_family == AF_NETLINK) {
        keep_pid = was.nl_pid;
        keep_grp = was.nl_groups;
    }

     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    fl = fcntl(sockfd, F_GETFL, 0);
    fdfl = fcntl(sockfd, F_GETFD, 0);

    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sv) < 0) {
        return 0;
    }
    if (dup2(sv[0], sockfd) < 0) {
        close(sv[0]);
        close(sv[1]);
        return 0;
    }
    close(sv[0]);
    if (fl >= 0) {
        fcntl(sockfd, F_SETFL, fl);
    }
    if (fdfl >= 0) {
        fcntl(sockfd, F_SETFD, fdfl);
    }

    net_init();
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == sockfd) {
            break;
        }
    }
    if (i == nfds) {
        if (nfds >= GUEST_NET_MAX) {
            qemu_mutex_unlock(&lock);
            close(sv[1]);
            return 0;
        }
        nfds++;
    } else {
        close(peers[i]);             
    }
    fds[i] = sockfd;
    peers[i] = sv[1];
    nlpid[i] = keep_pid;
    nlgrp[i] = keep_grp;
    own_set(sockfd, 1);
    qemu_mutex_unlock(&lock);

    nlog("%s — за дескриптором %d теперь пара AF_UNIX "
         "(флаги файла 0x%x сохранены, номер порта %u, группы 0x%x)",
         why, sockfd, fl, keep_pid, keep_grp);
    return 1;
}

int guest_net_bind_fallback(int sockfd)
{
     
    if (errno != EACCES && errno != EPERM) {
        return 0;
    }
    return netlink_take_over(sockfd, "bind: netlink запрещён хозяином");
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
int guest_net_owns(int fd)
{
    if (!inited || fd < 0 || fd >= GUEST_NET_FDBITS) {
        return 0;
    }
    return (qatomic_read(&owned[fd / 32]) >> (fd % 32)) & 1;
}

void guest_net_close(int fd)
{
    int i, peer = -1;

    if (!inited || fd < 0) {
        return;
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == fd) {
            peer = peers[i];
            own_set(fd, 0);
            --nfds;
            fds[i] = fds[nfds];
            peers[i] = peers[nfds];
            nlpid[i] = nlpid[nfds];
            nlgrp[i] = nlgrp[nfds];
            break;
        }
    }
    qemu_mutex_unlock(&lock);
     
    if (peer >= 0) {
        close(peer);
    }
    dns_forget(fd);
}

 
 
 
 
 
 
 
 
 
 

 
#define GUEST_IFINDEX 1
#define GUEST_IFNAME  "guest0"
 
 
 
 
 
#define GUEST_IFADDR  0x0f02000au

 
 
 
 
 
 
 
 
#define GUEST_IFF (0x00001u | 0x00002u | 0x00040u | 0x01000u | 0x10000u)

 
static int peer_of(int fd)
{
    int i, peer = -1;

    if (!inited || fd < 0) {
        return -1;
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == fd) {
            peer = peers[i];
            break;
        }
    }
    qemu_mutex_unlock(&lock);
    return peer;
}

 
 
 
 
static uint32_t nl_port(int fd)
{
    int i;
    uint32_t port = 0;

    if (!inited || fd < 0) {
        return (uint32_t)getpid();
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == fd) {
            if (!nlpid[i]) {
                nlpid[i] = (uint32_t)getpid();
            }
            port = nlpid[i];
            break;
        }
    }
    qemu_mutex_unlock(&lock);
    return port ? port : (uint32_t)getpid();
}

 
static uint32_t nl_groups(int fd)
{
    int i;
    uint32_t g = 0;

    if (!inited || fd < 0) {
        return 0;
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == fd) {
            g = nlgrp[i];
            break;
        }
    }
    qemu_mutex_unlock(&lock);
    return g;
}

void guest_net_bound(int fd, const void *addr, socklen_t len)
{
    const struct sockaddr_nl *nl = addr;
    uint32_t port, grp;
    int i;

    if (!inited || fd < 0 || !addr || len < sizeof(*nl) ||
        nl->nl_family != AF_NETLINK) {
        return;
    }
     
 
 
 
 
    port = nl->nl_pid ? nl->nl_pid : (uint32_t)getpid();
    grp = nl->nl_groups;
    qemu_mutex_lock(&lock);
    for (i = 0; i < nfds; i++) {
        if (fds[i] == fd) {
            nlpid[i] = port;
            nlgrp[i] = grp;
            break;
        }
    }
    qemu_mutex_unlock(&lock);
    nlog("дескриптор %d привязан: номер порта %u, группы 0x%x", fd, port, grp);
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
int guest_net_sockname(int fd, void *addr, socklen_t cap, socklen_t *len)
{
    struct sockaddr_nl nl;

    if (!guest_net_owns(fd) || !addr || !len) {
        return 0;
    }
    memset(&nl, 0, sizeof(nl));
    nl.nl_family = AF_NETLINK;
    nl.nl_pid = nl_port(fd);
    nl.nl_groups = nl_groups(fd);
    memcpy(addr, &nl, cap < sizeof(nl) ? (size_t)cap : sizeof(nl));
     
    *len = sizeof(nl);
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
int guest_net_from_kernel(int fd, void *addr, socklen_t cap, socklen_t *len)
{
    struct sockaddr_nl nl;

    if (!guest_net_owns(fd) || !addr || !len) {
        return 0;
    }
    memset(&nl, 0, sizeof(nl));
    nl.nl_family = AF_NETLINK;       
    memcpy(addr, &nl, cap < sizeof(nl) ? (size_t)cap : sizeof(nl));
    *len = sizeof(nl);
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static void put_attr(struct nlmsghdr *h, int type, const void *val, size_t n)
{
    struct rtattr *a = (struct rtattr *)((char *)h + NLMSG_ALIGN(h->nlmsg_len));
    size_t pad;

    a->rta_type = type;
    a->rta_len = RTA_LENGTH(n);
    memcpy(RTA_DATA(a), val, n);
    pad = RTA_ALIGN(a->rta_len) - a->rta_len;
    if (pad) {
        memset((char *)RTA_DATA(a) + n, 0, pad);
    }
    h->nlmsg_len = NLMSG_ALIGN(h->nlmsg_len) + RTA_ALIGN(a->rta_len);
}

 
 
 
 
 
 
 
 
 
static void put_head(struct nlmsghdr *h, int type, int flags,
                     const struct nlmsghdr *req, uint32_t port, size_t payload)
{
    h->nlmsg_len = NLMSG_LENGTH(payload);
    h->nlmsg_type = type;
    h->nlmsg_flags = flags;
    h->nlmsg_seq = req->nlmsg_seq;
    h->nlmsg_pid = port;
}

 
static size_t make_link(char *buf, const struct nlmsghdr *req, uint32_t port)
{
    struct nlmsghdr *h = (struct nlmsghdr *)buf;
    struct ifinfomsg *ifi;

    put_head(h, RTM_NEWLINK, NLM_F_MULTI, req, port, sizeof(*ifi));
    ifi = (struct ifinfomsg *)NLMSG_DATA(h);
    ifi->ifi_family = AF_UNSPEC;
    ifi->ifi_type = 1;                   
    ifi->ifi_index = GUEST_IFINDEX;
    ifi->ifi_flags = GUEST_IFF;
    ifi->ifi_change = 0xffffffffu;
    put_attr(h, IFLA_IFNAME, GUEST_IFNAME, sizeof(GUEST_IFNAME));
    return NLMSG_ALIGN(h->nlmsg_len);
}

 
static size_t make_addr(char *buf, const struct nlmsghdr *req, uint32_t port)
{
    struct nlmsghdr *h = (struct nlmsghdr *)buf;
    struct ifaddrmsg *ifa;
    uint32_t ip = GUEST_IFADDR;

    put_head(h, RTM_NEWADDR, NLM_F_MULTI, req, port, sizeof(*ifa));
    ifa = (struct ifaddrmsg *)NLMSG_DATA(h);
    ifa->ifa_family = AF_INET;
    ifa->ifa_prefixlen = 24;
    ifa->ifa_flags = 0x80;               
    ifa->ifa_scope = 0;                  
    ifa->ifa_index = GUEST_IFINDEX;
    put_attr(h, IFA_ADDRESS, &ip, sizeof(ip));
    put_attr(h, IFA_LOCAL, &ip, sizeof(ip));
    put_attr(h, IFA_LABEL, GUEST_IFNAME, sizeof(GUEST_IFNAME));
    return NLMSG_ALIGN(h->nlmsg_len);
}

 
static size_t make_done(char *buf, const struct nlmsghdr *req, uint32_t port)
{
    struct nlmsghdr *h = (struct nlmsghdr *)buf;
    int zero = 0;

    put_head(h, NLMSG_DONE, NLM_F_MULTI, req, port, sizeof(zero));
    memcpy(NLMSG_DATA(h), &zero, sizeof(zero));
    return NLMSG_ALIGN(h->nlmsg_len);
}

 
static size_t make_ack(char *buf, const struct nlmsghdr *req, uint32_t port)
{
    struct nlmsghdr *h = (struct nlmsghdr *)buf;
    struct nlmsgerr *e;

    put_head(h, NLMSG_ERROR, 0, req, port, sizeof(*e));
    e = (struct nlmsgerr *)NLMSG_DATA(h);
    e->error = 0;
    e->msg = *req;
    return NLMSG_ALIGN(h->nlmsg_len);
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
int guest_net_adopt(int fd, const void *msg, size_t len)
{
    const struct nlmsghdr *h = msg;

    if (!msg || fd < 0 || len < sizeof(*h)) {
        return 0;
    }
    if (h->nlmsg_len < sizeof(*h) || h->nlmsg_len > len) {
        return 0;
    }
    if (!(h->nlmsg_flags & NLM_F_REQUEST)) {
        return 0;
    }
    if (guest_net_owns(fd)) {
        return 1;
    }
    nlog_init();
    if (!netlink_take_over(fd, "send: настоящий netlink забран себе")) {
        nlog("★ дескриптор %d НЕ наш, а посылка похожа на netlink "
             "(тип %u, флаги 0x%x) — забрать не вышло",
             fd, h->nlmsg_type, h->nlmsg_flags);
        return 0;
    }
    return 1;
}

int guest_net_answer(int fd, const void *msg, size_t len)
{
    const struct nlmsghdr *req = msg;
    char buf[512];
    size_t n = 0;
    uint32_t port;
    int peer = peer_of(fd);

    if (peer < 0) {
        nlog("дескриптор %d наш, а пары к нему нет — отвечать нечем", fd);
        return 0;
    }
    if (len < sizeof(*req)) {
        nlog("дескриптор %d: посылка %zu Б — не netlink, молчим", fd, len);
        return 0;
    }
     
    if (req->nlmsg_len < sizeof(*req) || req->nlmsg_len > len) {
        nlog("дескриптор %d: длина %u при посылке %zu Б — не netlink, молчим",
             fd, req->nlmsg_len, len);
        return 0;
    }
    if (!(req->nlmsg_flags & NLM_F_REQUEST)) {
        nlog("дескриптор %d: тип %u без NLM_F_REQUEST — молчим",
             fd, req->nlmsg_type);
        return 0;
    }

    port = nl_port(fd);
    memset(buf, 0, sizeof(buf));
    if ((req->nlmsg_flags & NLM_F_DUMP) == NLM_F_DUMP) {
        if (req->nlmsg_type == RTM_GETLINK) {
            n += make_link(buf + n, req, port);
        } else if (req->nlmsg_type == RTM_GETADDR) {
            n += make_addr(buf + n, req, port);
        }
         
 
 
 
 
 
 
        n += make_done(buf + n, req, port);
    } else if (req->nlmsg_flags & NLM_F_ACK) {
        n = make_ack(buf, req, port);
    } else {
        nlog("дескриптор %d: тип %u, флаги 0x%x — ни дамп, ни ack, молчим",
             fd, req->nlmsg_type, req->nlmsg_flags);
        return 0;
    }

     
 
 
 
    if (send(peer, buf, n, MSG_DONTWAIT) < 0) {
        nlog("ответ на дескриптор %d НЕ УШЁЛ: %s", fd, strerror(errno));
        return 0;
    }
    nlog("дескриптор %d: запрос типа %u (флаги 0x%x, номер %u) — ответили %zu Б "
         "от ядра на порт %u", fd, req->nlmsg_type, req->nlmsg_flags,
         req->nlmsg_seq, n, port);
    return 1;
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define GUEST_DNS_MAX 64

struct dns_aim {
    int fd;
    socklen_t len;
    struct sockaddr_storage sa;
};

static struct dns_aim dnsmap[GUEST_DNS_MAX];
static int ndnsmap;

 
static void dns_remember(int fd, const void *orig, socklen_t len)
{
    int i;

    if (fd < 0 || !orig || len == 0 || len > sizeof(((struct dns_aim *)0)->sa)) {
        return;
    }
    net_init();
    qemu_mutex_lock(&lock);
    for (i = 0; i < ndnsmap; i++) {
        if (dnsmap[i].fd == fd) {
            break;
        }
    }
    if (i == ndnsmap) {
        if (ndnsmap >= GUEST_DNS_MAX) {
             
            i = 0;
            ndnsmap = 1;
        } else {
            ndnsmap++;
        }
    }
    dnsmap[i].fd = fd;
    dnsmap[i].len = len;
    memcpy(&dnsmap[i].sa, orig, len);
    qemu_mutex_unlock(&lock);
}

void guest_dns_unredirect(int fd, void *addr, socklen_t len)
{
    int i;

    if (!inited || fd < 0 || !addr || len == 0) {
        return;
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < ndnsmap; i++) {
        if (dnsmap[i].fd == fd) {
            memcpy(addr, &dnsmap[i].sa, MIN(len, dnsmap[i].len));
            break;
        }
    }
    qemu_mutex_unlock(&lock);
}

static void dns_forget(int fd)
{
    int i;

    if (!inited || fd < 0) {
        return;
    }
    qemu_mutex_lock(&lock);
    for (i = 0; i < ndnsmap; i++) {
        if (dnsmap[i].fd == fd) {
            dnsmap[i] = dnsmap[--ndnsmap];
            break;
        }
    }
    qemu_mutex_unlock(&lock);
}

void guest_http_redirect(int fd, void *addr, socklen_t addrlen)
{
    static int http = -1, https = -1, dns = -1;
    struct sockaddr_in *in = (struct sockaddr_in *)addr;
    int want, oport;

    if (http == -1) {
        const char *h = getenv("GUEST_HTTP80");
        const char *t = getenv("GUEST_HTTPS443");
        const char *d = getenv("GUEST_DNS53");

        http = h ? atoi(h) : 0;
        https = t ? atoi(t) : 0;
        dns = d ? atoi(d) : 0;
    }
    if (!addr) {
        return;
    }
     
 
 
 
 
 
 
 
 
 
    if (addrlen >= (socklen_t)sizeof(struct sockaddr_in6) &&
        ((struct sockaddr_in6 *)addr)->sin6_family == AF_INET6) {
        struct sockaddr_in6 *in6 = (struct sockaddr_in6 *)addr;
        int p6 = ntohs(in6->sin6_port);
        int want6 = 0;

        if (http == -1) {
            const char *h = getenv("GUEST_HTTP80");
            const char *t = getenv("GUEST_HTTPS443");
            const char *d = getenv("GUEST_DNS53");

            http = h ? atoi(h) : 0;
            https = t ? atoi(t) : 0;
            dns = d ? atoi(d) : 0;
        }
        if (p6 == 80) {
            want6 = http;
        } else if (p6 == 443) {
            want6 = https;
        } else if (p6 == 53) {
            want6 = dns;
        }
        if (want6 > 0) {
            static const uint8_t v4mapped[16] = {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1
            };
            const uint8_t *a6 = (const uint8_t *)&in6->sin6_addr;
             
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
            int keep = (p6 == 443 &&
                        !memcmp(a6, v4mapped, 12) && a6[12] == 127 &&
                        !(a6[13] == 0 && a6[14] == 0 && a6[15] == 1));

            if (p6 == 53) {
                dns_remember(fd, in6, addrlen);
            }
            if (!keep) {
                memcpy(&in6->sin6_addr, v4mapped, sizeof(v4mapped));
            }
            in6->sin6_port = htons(want6);
        }
        return;
    }
    if (addrlen < (socklen_t)sizeof(*in) || in->sin_family != AF_INET) {
        return;
    }
    oport = ntohs(in->sin_port);
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (oport == 0) {
        if (dns > 0 && (ntohl(in->sin_addr.s_addr) >> 24) != 127) {
            in->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        }
        return;
    }
    switch (oport) {
    case 80:
        want = http;
        break;
     
 
 
 
 
 
    case 443:
        want = https;
        break;
     
 
 
 
 
 
 
    case 53:
        want = dns;
        break;
    default:
        return;
    }
    if (want <= 0) {
        return;
    }
     
 
 
 
    if (oport == 53) {
        dns_remember(fd, in, addrlen);
    }
    in->sin_port = htons(want);
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (oport == 80 || (ntohl(in->sin_addr.s_addr) >> 24) != 127) {
        in->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    }
}
