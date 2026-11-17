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
// Changed: 2026-10-03

/* guest_script.h — что лежит в начале исполняемого файла гостя: ELF, сценарий
 * `#!` или ни то ни другое. Решение — как у ядра Linux (fs/binfmt_elf.c,
 * fs/binfmt_script.c), чтобы гостевой execve отвечал ровно то, что ответило бы
 * ядро (см. guest_script.c и патч 0071).
 *
 * ★ Файл не зависит от qemu: только libc POSIX. Поэтому его разбор гоняется
 * хостовым cc на Маке (native/test/test_script.c), а не только на телефоне. */
#ifndef GUEST_SCRIPT_H
#define GUEST_SCRIPT_H

#include <stddef.h>

 
#define GUEST_SCRIPT_BUF   256    
 
 
 
 
#define GUEST_SCRIPT_DEPTH 4
enum { GUEST_EXEC_ELF = 0, GUEST_EXEC_SCRIPT = 1 };

 
 
int guest_script_parse(const char *buf, size_t len, char *interp, size_t interp_sz, char *arg, size_t arg_sz);

 
 
int guest_script_probe(const char *host_path, char *interp, size_t interp_sz, char *arg, size_t arg_sz);

#endif
