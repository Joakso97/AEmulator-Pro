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
// Changed: 2026-08-29 … 2026-09-21

/* guest_net.h — подставной netlink для гостевых служб (netd, vold). */
#ifndef GUEST_NET_H
#define GUEST_NET_H

 
 
 
 
int guest_net_socket_fallback(int domain, int type, int protocol, int *fd);

 
 
 
 
 
int guest_net_bind_fallback(int sockfd);

 
int guest_net_owns(int fd);

 
void guest_net_close(int fd);

 
 
 
 
 
 
int guest_net_answer(int fd, const void *msg, size_t len);

 
 
 
 
void guest_net_bound(int fd, const void *addr, socklen_t len);

 
 
 
 
 
int guest_net_sockname(int fd, void *addr, socklen_t cap, socklen_t *len);

 
 
 
 
 
int guest_net_from_kernel(int fd, void *addr, socklen_t cap, socklen_t *len);

 
 
 
 
 
int guest_net_adopt(int fd, const void *msg, size_t len);


 
void guest_http_redirect(int fd, void *addr, socklen_t addrlen);

 
 
 
 
void guest_dns_unredirect(int fd, void *addr, socklen_t len);

#endif
