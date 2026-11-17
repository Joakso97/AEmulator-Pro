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

/* guest_script.c — начало исполняемого файла гостя: ELF, сценарий `#!` или
 * «не исполняется» (задача 7 части А1, патч 0071).
 *
 * ★★★ Зачем. Гостевой execve пересобирается на повторный вход в трансляцию
 * (патч 0005), и до этого файла он не смотрел, ЧТО запускает: повторный вход
 * удавался всегда, а настоящий отказ ребёнок-qemu печатал уже сам и выходил с
 * кодом 1. Вызывающий не получал ни ENOENT, ни ENOEXEC:
 *
 *  - execvp из bionic перебирает каталоги PATH именно по ENOENT; первая же
 *    попытка «удавалась» и умирала, остальные каталоги не пробовались;
 *  - сценарии БЕЗ строки `#!` (у 4.x это /system/bin/am, pm, svc, input,
 *    monkey: файл начинается с комментария) ядро отвергает с ENOEXEC, и sh /
 *    execvp запускают их оболочкой сами. У нас этого не происходило;
 *  - сценарии С `#!` не исполнялись вовсе: загрузчик qemu понимает только ELF.
 *
 * Здесь — решение «что лежит в начале файла» ровно по правилам ядра Linux,
 * чтобы по нему же отвечать гостю. Сборка нового argv и повторный вход — в
 * linux-user/syscall.c (do_execv) и linux-user/main.c, здесь только разбор.
 *
 * ★ Файл не зависит от qemu — только libc POSIX, чтобы гоняться хостовым cc на
 * Маке (native/test/test_script.c). Ни одного системного вызова в разборе
 * (guest_script_parse); они — только в guest_script_probe.
 *
 * ⚠️ Правила разбора — дословно ядра 5.x (fs/binfmt_script.c), и каждое из них
 * проверено отдельным случаем:
 *  - `\r` в конце строки НЕ срезается: у ядра `#!/bin/sh\r` — это интерпретатор
 *    с `\r` в имени (и ENOENT), а не `/bin/sh`;
 *  - строка длиннее буфера (256 байт без перевода строки) — не «обрезаем и
 *    пробуем», а отказ: усечённое имя интерпретатора запустило бы не то;
 *  - внутренние пробелы довода сохраняются: ядро передаёт ВЕСЬ остаток одним
 *    доводом (`#!/bin/sh -x -e` → argv[1] = «-x -e»), а не разбивает его.
 *
 * ⚠️ Нулевой байт внутри строки обрывает её, как оборвал бы строку Си в ядре:
 * разбирать то, что за ним, значило бы разбирать мусор двоичного файла.
 */
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "guest_script.h"

static int is_blank(char c)
{
    return c == ' ' || c == '\t';
}

int guest_script_parse(const char *buf, size_t len, char *interp, size_t interp_sz,
                       char *arg, size_t arg_sz)
{
    size_t end, i, j, n;

     
    if (len > GUEST_SCRIPT_BUF) {
        len = GUEST_SCRIPT_BUF;
    }
    if (len >= 4 && memcmp(buf, "\177ELF", 4) == 0) {
        return GUEST_EXEC_ELF;
    }
    if (len < 2 || buf[0] != '#' || buf[1] != '!') {
        return -ENOEXEC;
    }

     
    for (end = 0; end < len && buf[end] != '\n' && buf[end] != '\0'; end++) {
    }
     
 
 
 
    if (end == len && len >= GUEST_SCRIPT_BUF) {
        return -ENOEXEC;
    }

     
    while (end > 2 && is_blank(buf[end - 1])) {
        end--;
    }

     
    for (i = 2; i < end && is_blank(buf[i]); i++) {
    }
    if (i == end) {
        return -ENOEXEC;
    }

     
    for (j = i; j < end && !is_blank(buf[j]); j++) {
    }
    n = j - i;
    if (n + 1 > interp_sz) {
        return -ENOEXEC;
    }
    memcpy(interp, buf + i, n);
    interp[n] = '\0';

     
    for (; j < end && is_blank(buf[j]); j++) {
    }
    n = end - j;
    if (n + 1 > arg_sz) {
        return -ENOEXEC;
    }
    memcpy(arg, buf + j, n);
    arg[n] = '\0';

    return GUEST_EXEC_SCRIPT;
}

int guest_script_probe(const char *host_path, char *interp, size_t interp_sz,
                       char *arg, size_t arg_sz)
{
    char buf[GUEST_SCRIPT_BUF];
    size_t have = 0;
    struct stat st;
    int fd, e;

    if (!host_path) {
        return -EINVAL;
    }
     
 
 
 
 
    fd = open(host_path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        return -errno;
    }
     
 
 
 
    if (fstat(fd, &st) < 0) {
        e = errno;
        close(fd);
        return -e;
    }
    if (!S_ISREG(st.st_mode)) {
        close(fd);
        return -EACCES;
    }
    while (have < sizeof buf) {
        ssize_t n = read(fd, buf + have, sizeof buf - have);

        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            e = errno;
            close(fd);
            return -e;
        }
        if (n == 0) {
            break;
        }
        have += (size_t)n;
    }
    close(fd);
    return guest_script_parse(buf, have, interp, interp_sz, arg, arg_sz);
}
