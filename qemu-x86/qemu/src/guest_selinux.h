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
 * guest_selinux.h — SELinux «всё разрешено» для прошивок 5.0–7.0 в режиме
 * «init прошивки». Включается GUEST_FWINIT=1 И GUEST_SELINUX=1; без любой из
 * двух переменных ни одна функция здесь не меняет поведения. Подробности — в
 * guest_selinux.c и в шапках патчей 0084…
 */
#ifndef GUEST_SELINUX_H
#define GUEST_SELINUX_H

#include <stdbool.h>
#include <stddef.h>
#include <sys/types.h>
#include <sys/vfs.h>

 
bool guest_selinux(void);

 
 
 
 
 
void guest_selinux_start(void);
 
bool guest_selinux_statfs(const char *guest_path, struct statfs *st);
 
void guest_selinux_fstatfs(int fd, struct statfs *st);


 
 
 
 
 
 
 
bool guest_selinux_try_open(const char *guest_path, int flags, int *fd);
 
bool guest_selinux_read(int fd, void *buf, size_t n, abi_long *ret);
 
bool guest_selinux_write(int fd, const void *buf, size_t n, abi_long *ret);
 
void guest_selinux_close(int fd);

 
 
 
 
 
 
int guest_selinux_child_env(char *buf, size_t cap);
bool guest_selinux_is_child_env(const char *entry);

 
 
 
 
 
 
 
 
 
enum {
    GX_GET, GX_LGET, GX_FGET, GX_SET, GX_LSET, GX_FSET,
    GX_LIST, GX_LLIST, GX_FLIST, GX_REMOVE, GX_LREMOVE, GX_FREMOVE,
};
abi_long guest_selinux_xattr(int op, abi_ulong a1, abi_ulong a2, abi_ulong a3,
                             abi_ulong a4, abi_ulong a5);

 
 
 
 
 
 
bool guest_selinux_peersec(int sockfd, char *buf, socklen_t *len, abi_long *ret);

#endif
