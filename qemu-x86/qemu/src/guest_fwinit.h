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
// Changed: 2026-10-03 … 2026-10-04

/*
 * guest_fwinit.h — «ядро для init»: транслятор изображает ядро настоящему
 * /init прошивки (режим «init прошивки»). Включается GUEST_FWINIT=1; без
 * переменной ни одна функция здесь не меняет поведения. Подробности — в
 * guest_fwinit.c и в шапках патчей 0075…0083.
 */
#ifndef GUEST_FWINIT_H
#define GUEST_FWINIT_H

#include <stdbool.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>

 
 
 
 
bool guest_fwinit(void);

 
 
bool guest_fwinit_node(mode_t mode);
 
int guest_fwinit_mknod(const char *host, mode_t mode, dev_t dev);

 
 
 
 
 
const char *guest_fwinit_proc_text(const char *path, char *buf, size_t cap);

 
 
 
 
 
bool guest_fwinit_emmc(char **data, size_t *len);

 
 
void guest_fwinit_program(const char *host_path);
 
void guest_fwinit_comm(void);

 
 
void guest_fwinit_start_log(const char *argv0);

 
 
bool guest_fwinit_prop_path(const void *sockaddr, socklen_t len);
 
void guest_fwinit_prop_listener(int fd);
 
void guest_fwinit_prop_accepted(int lfd, int newfd);
 
void guest_fwinit_prop_data(int fd, const void *buf, size_t n);
 
void guest_fwinit_prop_iov(int fd, const struct iovec *iov, size_t cnt,
                           size_t total);
 
void guest_fwinit_prop_closed(int fd);

 
 
void guest_fwinit_reboot(unsigned cmd, unsigned long arg);

 
 
bool guest_fwinit_keep(const char *guest_path);

 
 
bool guest_fwinit_kmsg(const char *guest_path);

#endif
