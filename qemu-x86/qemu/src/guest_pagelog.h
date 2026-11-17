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
// Changed: 2026-09-15

/* guest_pagelog.h — слежка за правами исполняемых страниц гостя (см. .c). */
#ifndef GUEST_PAGELOG_H
#define GUEST_PAGELOG_H

 
int guest_plog_on(void);

 
void guest_plog(const char *fmt, ...) G_GNUC_PRINTF(1, 2);

 
const char *guest_plog_prot(int flags, char *buf);

 
 
 
 
 
 
 
 
 
 
 
 
void guest_trace_push(uint64_t pc, unsigned size);

 
int guest_trace_get(int back, uint64_t *pc, unsigned *size);

 
void guest_trace_dump(void);

 
void guest_check_code(uint64_t start, uint64_t end, uint64_t lr, uint64_t pc);

 
void guest_watch_tick(void);

 
int guest_no_tcgopt(void);

 
int guest_no_smc(void);

 
int guest_plog_smc(void);

#endif  
