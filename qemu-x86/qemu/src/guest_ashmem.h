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
// Changed: 2026-08-29 … 2026-10-09

/* guest_ashmem.h — /dev/ashmem для гостя (регион = memfd). */
#ifndef GUEST_ASHMEM_H
#define GUEST_ASHMEM_H

int guest_ashmem_try_open(const char *path, int *fd);
int guest_ashmem_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret);
void guest_ashmem_close(int fd);
 
int guest_ashmem_mmap_prep(int fd, int prot, int host_flags, int *rfd);
 
 
 
 
 
void guest_ashmem_range_note(abi_ulong start, abi_ulong len);
void guest_ashmem_range_forget(abi_ulong start, abi_ulong len);
int guest_ashmem_range_covers(abi_ulong start, abi_ulong len);
void guest_ashmem_range_moved(abi_ulong old, abi_ulong oldlen, abi_ulong newaddr, abi_ulong keeplen, int cov);
int guest_ashmem_mprotect_denied(abi_ulong start, abi_ulong len, int prot);

#endif
