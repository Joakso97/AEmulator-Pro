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
// Changed: 2026-09-24

/* guest_oom.h — важность процессов гостя для ядра телефона (см. guest_oom.c). */
#ifndef GUEST_OOM_H
#define GUEST_OOM_H

 
 
void guest_oom_opened(const char *path, abi_long flags, abi_long fd);

 
int guest_oom_write(abi_long fd, const void *buf, abi_long len, abi_long *ret);

 
void guest_oom_close(abi_long fd);

#endif
