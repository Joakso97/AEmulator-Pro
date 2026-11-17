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
// Changed: 2026-09-15 … 2026-10-10

/* guest_pagelog.c — слежка за правами ИСПОЛНЯЕМЫХ страниц гостя.
 *
 * Зачем. Браузер прошивки с включённым JavaScript падает прыжком в память,
 * которой гостю уже не выдавали:
 *
 *   Fatal signal 11 (SIGSEGV) at 0x3a0cf960, thread (WebViewCoreThre)
 *   PC=0x3a0cf960 [гость: страница не выдавалась] в 33a19000-3c000000 ---p
 *   LR=0x3c78d1b1 [гость: rw-]                    в 3c700000-3c800000 rw-p
 *   след по стеку: libjs.so ×5, дальше libbrowsercore.so ×7
 *
 * Права в квадратных скобках — это ПАМЯТЬ ГЛАЗАМИ ТРАНСЛЯТОРА (page_get_flags).
 * Транслятор НЕ переводит код со страницы без PAGE_EXEC — значит страница
 * когда-то была исполняемой и права потеряла. Вопрос ровно один: каким вызовом
 * движок её отобрал и сбросил ли транслятор при этом свои переводы.
 *
 * Ответ должен быть один из трёх, и журнал их различает:
 *
 *   1. Строка про эту страницу есть, «переводы=сброшены» — дыра не здесь,
 *      смотреть дальше (кто прыгнул).
 *   2. Строка есть, «переводы=ОСТАЛИСЬ» — вот она, дыра.
 *   3. Строк про эту страницу нет вовсе — движок отбирает её путём, который
 *      мимо page_set_flags, и надо смотреть madvise (он тут тоже есть).
 *
 * ⚠️ Почему слежка именно в page_set_flags: это ЕДИНСТВЕННАЯ воронка, через
 * которую в qemu проходят и mmap, и munmap, и mprotect, и mremap. Ловить
 * каждый вызов по отдельности незачем, а различить их можно по виду записи:
 * «заново=да» — это mmap (PAGE_RESET), пустые новые права — munmap, всё
 * остальное — mprotect.
 *
 * ⚠️ Почему только страницы с PAGE_EXEC: иначе сюда попадёт КАЖДЫЙ mmap гостя,
 * а их за загрузку сотни тысяч. Исполняемых же — единицы на библиотеку.
 *
 * ⚠️ Кроме процесса с личностью READ_IMPLIES_EXEC (Р-141, патч 0098: программа
 * без PT_GNU_STACK — у ключевых программ 1.5/1.6; сплошного обхода не было):
 * у него КАЖДОЕ читаемое отображение исполнимо, и в журнал идёт каждый
 * читаемый mmap/mprotect/munmap.
 * Журнал у такого процесса во много раз больше, а «область кода» расширяется
 * по PAGE_EXEC до потолка 2 МБ — включать слежку там только на короткий прогон.
 *
 * Включается переменной GUEST_PAGE_LOG (путь к файлу), как GUEST_NET_LOG и
 * GUEST_ASHMEM_LOG. Без неё ни одной лишней команды на горячем пути нет:
 * guest_plog_on() проверяет указатель.
 */
#include "qemu/osdep.h"
#include "qemu.h"
 
#include "exec/exec-all.h"

#include "guest_pagelog.h"

static FILE *g_plog;
static int plog_tried;

int guest_plog_on(void)
{
    if (!plog_tried) {
        const char *path = getenv("GUEST_PAGE_LOG");

        plog_tried = 1;
        if (path && *path) {
            g_plog = fopen(path, "ae");
        }
    }
    return g_plog != NULL;
}

void guest_plog(const char *fmt, ...)
{
    va_list ap;

    if (!guest_plog_on()) {
        return;
    }
     
 
 
 
 
    fprintf(g_plog, "[pid=%d] ", (int)getpid());
    va_start(ap, fmt);
    vfprintf(g_plog, fmt, ap);
    va_end(ap);
    fputc('\n', g_plog);
    fflush(g_plog);
}

const char *guest_plog_prot(int flags, char *buf)
{
    if (!(flags & PAGE_VALID)) {
        return "нет";
    }
    buf[0] = (flags & PAGE_READ)  ? 'r' : '-';
    buf[1] = (flags & PAGE_WRITE) ? 'w' : '-';
    buf[2] = (flags & PAGE_EXEC)  ? 'x' : '-';
     
 
 
 
 
    buf[3] = (flags & PAGE_PASSTHROUGH) ? 'p' : '.';
    buf[4] = 0;
    return buf;
}

 
#define GUEST_TRACE_N 32

static __thread uint64_t trace_pc[GUEST_TRACE_N];
static __thread unsigned trace_sz[GUEST_TRACE_N];
static __thread unsigned trace_i;

void guest_trace_push(uint64_t pc, unsigned size)
{
    unsigned i = trace_i++ & (GUEST_TRACE_N - 1);

    trace_pc[i] = pc;
    trace_sz[i] = size;
     
    guest_watch_tick();
}

int guest_trace_get(int back, uint64_t *pc, unsigned *size)
{
    unsigned i;

    if (back < 0 || back >= GUEST_TRACE_N || (unsigned)back >= trace_i) {
        return 0;
    }
    i = (trace_i - 1 - back) & (GUEST_TRACE_N - 1);
    *pc = trace_pc[i];
    *size = trace_sz[i];
    return 1;
}

int guest_plog_smc(void)
{
    static int smc = -1;

    if (smc < 0) {
        const char *v = getenv("GUEST_PAGE_LOG_SMC");

        smc = (v && *v && *v != '0') ? 1 : 0;
    }
    return smc && guest_plog_on();
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static void plog_where(const char *tag, uint64_t a, unsigned size)
{
    FILE *m = fopen("/proc/self/maps", "r");
    char line[512], pb[8];

    if (!m) {
        guest_plog("%s=0x%08lx (%u б) [%s]", tag, (unsigned long)a, size,
                 guest_plog_prot(page_get_flags(a), pb));
        return;
    }
    while (fgets(line, sizeof line, m)) {
        unsigned long lo, hi;
        char *nl;

        if (sscanf(line, "%lx-%lx", &lo, &hi) != 2 || a < lo || a >= hi) {
            continue;
        }
        nl = strchr(line, '\n');
        if (nl) {
            *nl = 0;
        }
        guest_plog("%s=0x%08lx (%u б, +0x%lx) [%s] в %s", tag, (unsigned long)a,
                 size, (unsigned long)(a - lo),
                 guest_plog_prot(page_get_flags(a), pb), line);
        fclose(m);
        return;
    }
    fclose(m);
    guest_plog("%s=0x%08lx (%u б) [%s] — вне отображений", tag, (unsigned long)a,
             size, guest_plog_prot(page_get_flags(a), pb));
}

 
 
 
 
 
 
 
 
 
 
 
 
static void dump_code_page(uint64_t pc)
{
    static int done;
    const char *path = getenv("GUEST_PAGE_LOG");
    uint64_t base = pc & ~0xfffULL;
    char name[512];
    FILE *f;

    if (done || !path || !*path) {
        return;
    }
    done = 1;
    if (!(page_get_flags(base) & PAGE_READ)) {
        guest_plog("   страницу 0x%08lx снять нельзя: читать её не дают",
                 (unsigned long)base);
        return;
    }
    snprintf(name, sizeof name, "%s.dump", path);
    f = fopen(name, "we");
    if (!f) {
        return;
    }
    fwrite(g2h_untagged((abi_ulong)base), 1, 4096, f);
    fclose(f);
    guest_plog("   ★ страница кода 0x%08lx снята в %s (4096 б)",
             (unsigned long)base, name);
}

 
static uint64_t seen_lo, seen_hi;
static unsigned long seen_n;
static int watch_n;             
static int watch_old(uint64_t addr, uint32_t *val);
static int watch_old(uint64_t addr, uint32_t *val);

 
 
 
 
 
 
 
 
 
 
 
 
 
static void dump_code_range(uint64_t from, uint64_t to)
{
    static int done;
    const char *path = getenv("GUEST_PAGE_LOG");
    char name[512];
    FILE *f;
    uint64_t a, lo, hi;

    if (done || !path || !*path) {
        return;
    }
    done = 1;
    lo = (from > 0x400 ? from - 0x400 : 0) & ~3ULL;
    hi = (to + 0x800) & ~3ULL;
    snprintf(name, sizeof name, "%s.obj", path);
    f = fopen(name, "we");
    if (!f) {
        return;
    }
    for (a = lo; a < hi; a += 4) {
        uint32_t w = 0;

        if (page_get_flags(a) & PAGE_READ) {
            w = *(const uint32_t *)g2h_untagged((abi_ulong)a);
        }
        fwrite(&w, 4, 1, f);
    }
    fclose(f);
    guest_plog("   ★ объект кода 0x%08lx-0x%08lx снят в %s (%lu б)",
             (unsigned long)lo, (unsigned long)hi, name,
             (unsigned long)(hi - lo));
}

void guest_trace_dump(void)
{
    uint64_t pc;
    unsigned size;
    int k;

    if (!guest_plog_on()) {
        return;
    }
     
 
 
 
    if (guest_trace_get(0, &pc, &size)) {
        guest_plog("   просмотров кода: %lu, последняя область 0x%08lx-0x%08lx, "
                 "наблюдаемых слов %d; блок-0 %s этой области",
                 seen_n, (unsigned long)seen_lo, (unsigned long)seen_hi,
                 watch_n,
                 (pc >= seen_lo && pc < seen_hi) ? "ВНУТРИ" : "СНАРУЖИ");
    }
     
 
 
 
 
 
 
 
    if (guest_trace_get(0, &pc, &size) && size >= 4) {
        uint64_t w = pc + size - 4;
        uint32_t was;

        if (watch_old(w, &was)) {
            uint32_t now = (page_get_flags(w) & PAGE_READ)
                ? *(const uint32_t *)g2h_untagged((abi_ulong)w) : 0;

            guest_plog("   ★★★★★ слово 0x%08lx: БЫЛО %08x, СТАЛО %08x",
                     (unsigned long)w, was, now);
        } else {
            guest_plog("   слова 0x%08lx среди наблюдаемых нет (в списке %d)",
                     (unsigned long)w, watch_n);
        }
    }
    for (k = 0; k < 12 && guest_trace_get(k, &pc, &size); k++) {
        char tag[32];

        snprintf(tag, sizeof tag, "   блок-%d", k);
        plog_where(tag, pc, size);
    }
     
 
 
 
    if (guest_trace_get(0, &pc, &size) && size && (page_get_flags(pc) & PAGE_READ)) {
        uint64_t from = (pc + size) & ~1ULL;
        char buf[128];
        int n = 0;
        uint64_t i;

        if (from >= 16) {
            from -= 16;
        }
        for (i = from; i <= (uint64_t)((pc + size) & ~1ULL) && n < 110; i += 2) {
            n += snprintf(buf + n, sizeof buf - n, " %04x",
                          *(const uint16_t *)g2h_untagged((abi_ulong)i));
        }
        guest_plog("   байты хвоста блока-0 (0x%08lx-0x%08lx):%s",
                 (unsigned long)from, (unsigned long)((pc + size) & ~1ULL), buf);
        dump_code_page(pc);
    }
}

 
 
 
 
 
 
 
 
 
static int dvm_known;
static unsigned long dvm_lo, dvm_hi;

static int from_dalvik(uint64_t lr)
{
    if (!dvm_known) {
        FILE *m = fopen("/proc/self/maps", "r");
        char line[512];

        dvm_known = 1;
        if (m) {
            while (fgets(line, sizeof line, m)) {
                unsigned long lo, hi;

                if (sscanf(line, "%lx-%lx", &lo, &hi) == 2 &&
                    strstr(line, "libdvm.so")) {
                    if (!dvm_lo || lo < dvm_lo) {
                        dvm_lo = lo;
                    }
                    if (hi > dvm_hi) {
                        dvm_hi = hi;
                    }
                }
            }
            fclose(m);
        }
    }
    return dvm_lo && lr >= dvm_lo && lr < dvm_hi;
}

 
 
 
 
 
 
 
 
 
 
 
 
static uint64_t bad_branch_in(uint64_t from, uint64_t to, uint32_t *word,
                              uint32_t *target)
{
    uint64_t a;

    for (a = (from & ~3ULL) + 4; a + 4 <= to; a += 4) {
        uint32_t w, tgt;
        int32_t imm;

        if (!(page_get_flags(a) & PAGE_READ)) {
            a = (a | 0xfff) - 3;
            continue;
        }
         
 
 
 
 
 
 
 
        if (!(page_get_flags(a - 4) & PAGE_READ) ||
            *(const uint32_t *)g2h_untagged((abi_ulong)(a - 4)) != 0xe12fff3cu) {
            continue;
        }
        w = *(const uint32_t *)g2h_untagged((abi_ulong)a);
        if (((w >> 25) & 7) != 5) {       
            continue;
        }
        imm = (int32_t)(w << 8) >> 6;     
        tgt = (uint32_t)(a + 8 + imm);
         
 
 
 
 
 
 
 
 
 
        if (page_get_flags(tgt) & PAGE_EXEC) {
            continue;
        }
        *word = w;
        *target = tgt;
        return a;
    }
    return 0;
}

 
static void watch_fill(uint64_t lo, uint64_t hi);
static int watch_said;
static unsigned long watch_fills;

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
void guest_check_code(uint64_t start, uint64_t end, uint64_t lr, uint64_t pc)
{
     
    const uint64_t kMax = 256 * 1024;
    static uint64_t prev_lr, prev_start, prev_end;
    static unsigned long prev_n;
    static int said;
    uint64_t a, lo, hi;
    uint32_t w = 0, tgt = 0;

    if (!guest_plog_on()) {
        return;
    }
     
 
 
 
    if (end <= start || end - start > kMax) {
        guest_plog("сброс ПРОПУЩЕН (величина) 0x%08lx-0x%08lx, возврат 0x%08lx",
                 (unsigned long)start, (unsigned long)end, (unsigned long)lr);
        return;
    }
    if (from_dalvik(lr)) {
        return;
    }

     
 
 
 
 
    lo = start & ~0xfffULL;
    hi = (end + 0xfff) & ~0xfffULL;
    while (lo > 0x1000 && hi - lo < 2 * 1024 * 1024 &&
           (page_get_flags(lo - 0x1000) & PAGE_EXEC)) {
        lo -= 0x1000;
    }
    while (hi - lo < 2 * 1024 * 1024 && (page_get_flags(hi) & PAGE_EXEC)) {
        hi += 0x1000;
    }

    a = bad_branch_in(lo, hi, &w, &tgt);
    if (!a) {
         
 
 
 
 
        prev_lr = lr;
        prev_start = start;
        prev_end = end;
        prev_n++;
        seen_lo = lo;
        seen_hi = hi;
        seen_n++;
         
 
 
 
 
 
        guest_plog("сброс кэша 0x%08lx-0x%08lx (область 0x%08lx-0x%08lx), "
                 "возврат 0x%08lx",
                 (unsigned long)start, (unsigned long)end,
                 (unsigned long)lo, (unsigned long)hi, (unsigned long)lr);
         
 
 
 
 
        if (!watch_said && hi - lo >= 256 * 1024) {
             
 
 
 
            watch_fills++;
            watch_fill(lo, hi);
        }
         
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
        return;
    }
    if (said) {
        return;          
    }
    said = 1;
    guest_plog("   предыдущий ЧИСТЫЙ сброс был №%lu: 0x%08lx-0x%08lx, "
             "возврат 0x%08lx", prev_n, (unsigned long)prev_start,
             (unsigned long)prev_end, (unsigned long)prev_lr);
    plog_where("   предыдущий возврат", prev_lr, 0);
    guest_plog("★★ НЕГОДНЫЙ ПЕРЕХОД В КОДЕ: 0x%08lx = %08x -> 0x%08lx "
             "(память не отображена); область 0x%08lx-0x%08lx, "
             "сброс кэша 0x%08lx-0x%08lx",
             (unsigned long)a, w, (unsigned long)tgt,
             (unsigned long)lo, (unsigned long)hi,
             (unsigned long)start, (unsigned long)end);
    guest_plog("   негодное слово на +0x%lx от начала сброса "
             "(сброс 0x%08lx-0x%08lx, длина 0x%lx)",
             (unsigned long)(a - start), (unsigned long)start,
             (unsigned long)end, (unsigned long)(end - start));
    plog_where("   вернуться", lr, 0);
    plog_where("   исполнялось", pc, 0);
    dump_code_range(start, end);
}

 
 
 
 
 
 
 
 
 
int guest_no_smc(void)
{
#ifndef TARGET_ARM
     
 
 
 
 
 
 
    return 0;
#else
    static int v = -1;

    if (v < 0) {
        const char *s = getenv("GUEST_NO_SMC");

        v = (s && *s && *s != '0') ? 1 : 0;
    }
    return v;
#endif
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define WATCH_MAX 65536

static struct { uint32_t addr; uint32_t val; } watch[WATCH_MAX];
static unsigned long watch_tick;

static void watch_fill(uint64_t lo, uint64_t hi)
{
    uint64_t a;

    watch_n = 0;
    for (a = lo + 4; a + 4 <= hi && watch_n < WATCH_MAX; a += 4) {
        if (!(page_get_flags(a) & PAGE_READ) ||
            !(page_get_flags(a - 4) & PAGE_READ)) {
            a = (a | 0xfff) - 3;
            continue;
        }
        if (*(const uint32_t *)g2h_untagged((abi_ulong)(a - 4)) != 0xe12fff3cu) {
            continue;
        }
        watch[watch_n].addr = (uint32_t)a;
        watch[watch_n].val = *(const uint32_t *)g2h_untagged((abi_ulong)a);
        watch_n++;
    }
}

 
static int watch_old(uint64_t addr, uint32_t *val)
{
    int i;

    for (i = 0; i < watch_n; i++) {
        if (watch[i].addr == (uint32_t)addr) {
            *val = watch[i].val;
            return 1;
        }
    }
    return 0;
}

 
void guest_watch_tick(void)
{
    int i;

    if (watch_said || watch_n == 0 || (++watch_tick & 0x1fff) != 0) {
        return;
    }
    for (i = 0; i < watch_n; i++) {
        uint32_t v, tgt;
        int32_t imm;

        if (!(page_get_flags(watch[i].addr) & PAGE_READ)) {
            continue;
        }
        v = *(const uint32_t *)g2h_untagged((abi_ulong)watch[i].addr);
        if (v == watch[i].val) {
            continue;
        }
        if (((v >> 25) & 7) != 5) {          
            watch[i].val = v;
            continue;
        }
        imm = (int32_t)(v << 8) >> 6;
        tgt = watch[i].addr + 8 + imm;
        if (page_get_flags(tgt) & PAGE_EXEC) {    
            watch[i].val = v;
            continue;
        }
        watch_said = 1;
        guest_plog("★★★★★ ВОТ ОНА: слово 0x%08lx БЫЛО %08x, СТАЛО %08x "
                 "(переход в 0x%08lx — памяти нет)",
                 (unsigned long)watch[i].addr, watch[i].val, v,
                 (unsigned long)tgt);
        {
             
            uint64_t a0 = (uint64_t)watch[i].addr - 16;
            char buf[160];
            int n = 0;
            uint64_t a;

            for (a = a0; a <= (uint64_t)watch[i].addr + 16; a += 4) {
                if (!(page_get_flags(a) & PAGE_READ)) {
                    break;
                }
                n += snprintf(buf + n, sizeof buf - n, " %08x",
                              *(const uint32_t *)g2h_untagged((abi_ulong)a));
            }
            guest_plog("   соседи 0x%08lx..:%s", (unsigned long)a0, buf);
        }
        guest_trace_dump();
        return;
    }
}

 
 
 
 
 
 
 
 
 
int guest_no_tcgopt(void)
{
    static int v = -1;

    if (v < 0) {
        const char *s = getenv("GUEST_NO_TCGOPT");

        v = (s && *s && *s != '0') ? 1 : 0;
    }
    return v;
}
