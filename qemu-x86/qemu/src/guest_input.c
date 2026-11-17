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
// Changed: 2026-08-29 … 2026-10-11

// guest_input.c — поддельный сенсорный экран гостя: /dev/input/event0.
//
// ★ Форк glue_input.c из более ранней нашей работы, где ввод уже
// отработан на этом же телефоне. Отличий ровно три, и все — от того, что здесь
// гость 2.3 и 32-битный, а шим живёт ВНУТРИ qemu, а не в гостевом процессе:
//
//   1. запись `struct input_event` у 32-битного гостя — 16 БАЙТ (две 32-битные
//      половины времени, u16 тип, u16 код, s32 значение), а не 24;
//   2. протокол мультитача — «A» (пакет пальца завершается SYN_MT_REPORT), а не
//      «B» (слоты). ABS_MT_SLOT в 2.3 не знает НИКТО: слоты появились в ядре
//      2.6.38 и в Android 4.0. Объявив слот, мы получили бы устройство, все
//      события которого InputReader молча выбрасывает;
//   3. ioctl приходит с ГОСТЕВЫМ адресом (abi_ulong), его надо переводить.
//
// Устройство. У гостя в /dev/input лежит пустой файл event0 — он нужен только
// затем, чтобы EventHub нашёл устройство при обходе каталога (scan_dir). Само
// открытие перехватываем здесь: гостю отдаётся конец пары сокетов, а второй
// конец кормит поток-переносчик, читающий сокет приложения (путь в GUEST_INPUT).
// Всё, что приложение туда напишет, гость читает как поток событий с экрана.
//
// ★ Сеансы и переносчики — guest_input_pump.h (Р-119): переносчик живёт, пока жив
// гостевой конец пары (закрытие любым путём, а не только close), и сразу после
// подключения шлёт приложению приветствие со знаком сеанса — приложение различает
// клиентов по сеансу, а не по процессу.
//
// ⚠️ Пара сокетов нужна именно ради ГРАНИЦ записей, и здесь это важнее, чем у
// донора: EventHub 2.3 читает по ОДНОМУ событию за раз (`read(fd, &iev,
// sizeof(iev))`). Отдай мы гостю прямое соединение с приложением — любое
// дробление потока привело бы к чтению неполного события, а «лишние» байты
// EventHub выбрасывает вместе с жалобой «could not get event (wrong size)».
// Поток сдвинулся бы на несколько байт НАВСЕГДА. Переносчик копит байты и
// отдаёт гостю только целые события.
//
// ⚠️ BTN_TOUCH в наборе возможностей объявлен, но НЕ ШЛЁТСЯ. В 2.3
// MultiTouchInputMapper::process разбирает только EV_ABS и EV_SYN, а EV_KEY на
// том же устройстве достаётся KeyboardInputMapper — и BTN_TOUCH (код 0x14a) ни
// в одном .kl прошивки не описан, так что каждое касание превращалось бы в
// пару «неизвестных клавиш».
//
// ★ Клавиши идут ТЕМ ЖЕ устройством: коды из раскладки прошивки (BACK 158,
// HOME 172, MENU 139, SEARCH 217, громкость 114/115, питание 116). Устройство
// от этого становится в глазах EventHub ещё и клавиатурой — это нормально и
// так устроены настоящие телефоны. ⚠️ Букв в наборе НЕТ намеренно: EventHub
// проверяет наличие скан-кода клавиши «Q» и, найдя его, объявляет устройство
// АЛФАВИТНОЙ клавиатурой — а тогда framework считает, что у телефона есть
// физическая клавиатура, и не показывает экранную.

#include "qemu/osdep.h"
#include "qemu.h"
#include "user-internals.h"

#include <pthread.h>
#include <poll.h>
#include <linux/input.h>

#include "guest_input.h"
#include "guest_input_pump.h"

 
 
 
 
 
 
 
 
 
 
 
 
 
#ifndef _IOC_NR
#define _IOC_NR(n)   (((n) >> 0) & 0xff)
#endif
#ifndef _IOC_SIZE
#define _IOC_SIZE(n) (((n) >> 16) & 0x3fff)
#endif

#ifndef BUS_VIRTUAL
#define BUS_VIRTUAL 0x06
#endif
#ifndef INPUT_PROP_DIRECT
#define INPUT_PROP_DIRECT 0x01
#endif

 
 
 
 
 
 
 
 
 
 
 
static int in_geom_done;
static int in_w = 480, in_h = 800;

static int in_env(const char *a, const char *b, int dflt)
{
    const char *s = getenv(a);
    if (!s || !*s) {
        s = getenv(b);
    }
    if (!s || !*s) {
        return dflt;
    }
    int v = atoi(s);
    return v > 0 ? v : dflt;
}

static void in_geom_init(void)
{
    if (in_geom_done) {
        return;
    }
    in_geom_done = 1;
    in_w = in_env("GUEST_IN_W", "GUEST_FB_W", in_w);
    in_h = in_env("GUEST_IN_H", "GUEST_FB_H", in_h);
}

#define IN_W (in_geom_init(), in_w)
#define IN_H (in_geom_init(), in_h)

 
struct guest_ev32 {
    int32_t sec;
    int32_t usec;
    uint16_t type;
    uint16_t code;
    int32_t value;
};
QEMU_BUILD_BUG_ON(sizeof(struct guest_ev32) != 16);

 
QEMU_BUILD_BUG_ON(GI_EVSZ != sizeof(struct guest_ev32));

static void in_path_init(void)
{
    static int done;

    if (done) {
        return;
    }
    done = 1;
    const char *e = getenv("GUEST_INPUT");
    if (e && *e) {
        gi_set_path(e);
    }
}

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
static int in_single(void)
{
    static int val = -1;

    if (val < 0) {
        const char *e = getenv("GUEST_INPUT_ST");
        val = (e && *e && *e != '0') ? 1 : 0;
    }
    return val;
}

 
 
 
 
 
 
int guest_input_try_open(const char *path, int *fd)
{
    if (!path || strcmp(path, "/dev/input/event0") != 0) {
        return 0;
    }
    in_path_init();
    if (!g_gi_path[0]) {
         
 
        errno = ENOENT;
        *fd = -1;
        return 1;
    }
     
 
    if (gi_open(fd) < 0) {
        *fd = -1;
    }
    return 1;
}

static void bit_set(uint8_t *b, size_t cap, unsigned n)
{
    if (n / 8 < cap) {
        b[n / 8] |= (uint8_t)(1u << (n % 8));
    }
}

 
 
 
 
 
 
 
static const unsigned in_keys[] = {
    KEY_BACK,           
     
 
 
 
 
 
 
 
 
    KEY_HOME,           
    KEY_HOMEPAGE,       
    KEY_MENU,           
    KEY_SEARCH,         
    KEY_VOLUMEDOWN,     
    KEY_VOLUMEUP,       
    KEY_POWER,          
};

 
int guest_input_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret)
{
     
 
    if (!gi_owns(fd)) {
        return 0;
    }
    unsigned nr = _IOC_NR(req);
    size_t sz = _IOC_SIZE(req);
    void *host = arg ? g2h_untagged(arg) : NULL;

    *ret = 0;

     
 
    if (nr == _IOC_NR(EVIOCGVERSION)) {
        if (host) {
            int v = EV_VERSION;
            memcpy(host, &v, sizeof v < sz ? sizeof v : sz);
        }
        return 1;
    }
     
    if (nr == _IOC_NR(EVIOCGID)) {
        if (host) {
            struct input_id id = { .bustype = BUS_VIRTUAL, .vendor = 0x0bb4,
                                   .product = 0x0c02, .version = 1 };
            memcpy(host, &id, sizeof id < sz ? sizeof id : sz);
        }
        return 1;
    }
     
 
 
 
 
 
 
 
    if (nr == _IOC_NR(EVIOCGNAME(0))) {
        const char *nm = "guest-touch";
        size_t n = strlen(nm) + 1;

        if (host && sz) {
            if (n > sz) {
                n = sz;
            }
            memcpy(host, nm, n);
        }
        *ret = (abi_long)n;
        return 1;
    }
    if (nr == _IOC_NR(EVIOCGPHYS(0))) {
        const char *nm = "guestvm/input0";
        size_t n = strlen(nm) + 1;

        if (host && sz) {
            if (n > sz) {
                n = sz;
            }
            memcpy(host, nm, n);
        }
        *ret = (abi_long)n;
        return 1;
    }
     
    if (nr == _IOC_NR(EVIOCGUNIQ(0))) {
        errno = ENOENT;
        *ret = -1;
        return 1;
    }
     
 
 
 
 
    if (nr == _IOC_NR(EVIOCGPROP(0))) {
        if (host && sz) {
            memset(host, 0, sz);
            bit_set((uint8_t *)host, sz, INPUT_PROP_DIRECT);
        }
        *ret = (abi_long)sz;
        return 1;
    }
     
    if (nr >= _IOC_NR(EVIOCGBIT(0, 0)) && nr <= _IOC_NR(EVIOCGBIT(EV_MAX, 0))) {
        unsigned ev = nr - _IOC_NR(EVIOCGBIT(0, 0));

         
 
 
 
 
 
 
 
 
 
 
 
 
 
        size_t len;
        switch (ev) {
        case 0:       len = (EV_MAX + 8) / 8;  break;
        case EV_KEY:  len = (KEY_MAX + 8) / 8; break;
        case EV_REL:  len = (REL_MAX + 8) / 8; break;
        case EV_ABS:  len = (ABS_MAX + 8) / 8; break;
        case EV_MSC:  len = (MSC_MAX + 8) / 8; break;
        case EV_SW:   len = (SW_MAX + 8) / 8;  break;
        case EV_LED:  len = (LED_MAX + 8) / 8; break;
        case EV_SND:  len = (SND_MAX + 8) / 8; break;
        case EV_REP:  len = (REP_MAX + 8) / 8; break;
        default:      len = 0;                 break;
        }
        if (sz > len) {
            sz = len;
        }
        if (host && sz) {
            uint8_t *b = (uint8_t *)host;

            memset(b, 0, sz);
            if (ev == 0) {                     
                bit_set(b, sz, EV_SYN);
                bit_set(b, sz, EV_KEY);
                bit_set(b, sz, EV_ABS);
            } else if (ev == EV_KEY) {
                bit_set(b, sz, BTN_TOUCH);     
                for (unsigned i = 0; i < ARRAY_SIZE(in_keys); i++) {
                    bit_set(b, sz, in_keys[i]);
                }
            } else if (ev == EV_ABS && in_single()) {
                 
 
                bit_set(b, sz, ABS_X);
                bit_set(b, sz, ABS_Y);
                bit_set(b, sz, ABS_PRESSURE);
            } else if (ev == EV_ABS) {
                 
 
                bit_set(b, sz, ABS_MT_POSITION_X);
                bit_set(b, sz, ABS_MT_POSITION_Y);
                bit_set(b, sz, ABS_MT_TOUCH_MAJOR);
                 
 
 
 
                bit_set(b, sz, ABS_MT_WIDTH_MAJOR);
                bit_set(b, sz, ABS_MT_PRESSURE);
                bit_set(b, sz, ABS_MT_TRACKING_ID);
            }
        }
        *ret = (abi_long)sz;
        return 1;
    }
     
 
 
 
 
 
 
 
 
 
 
 
 
 
    if (nr >= _IOC_NR(EVIOCGABS(0)) && nr <= _IOC_NR(EVIOCGABS(ABS_MAX))) {
        unsigned ax = nr - _IOC_NR(EVIOCGABS(0));
        struct input_absinfo ai;

        memset(&ai, 0, sizeof ai);
        switch (ax) {
        case ABS_X:
        case ABS_MT_POSITION_X:
            ai.maximum = IN_W - 1;
            break;
        case ABS_Y:
        case ABS_MT_POSITION_Y:
            ai.maximum = IN_H - 1;
            break;
        case ABS_PRESSURE:
            ai.maximum = 255;
            break;
        case ABS_MT_TOUCH_MAJOR:
        case ABS_MT_WIDTH_MAJOR:
        case ABS_MT_PRESSURE:
            ai.maximum = 255;
            break;
        case ABS_MT_TRACKING_ID:
            ai.maximum = 31;       
            break;
        default:
            break;                 
        }
        if (host) {
            memcpy(host, &ai, sizeof ai < sz ? sizeof ai : sz);
        }
        return 1;
    }
     
 
 
    if (nr == _IOC_NR(EVIOCGKEY(0)) || nr == _IOC_NR(EVIOCGSW(0)) ||
        nr == _IOC_NR(EVIOCGLED(0))) {
        if (host && sz) {
            memset(host, 0, sz);
        }
        *ret = (abi_long)sz;
        return 1;
    }
     
    if (nr == _IOC_NR(EVIOCGRAB)) {
        return 1;
    }
     
    errno = EINVAL;
    *ret = -1;
    return 1;
}

void guest_input_close(int fd)
{
     
 
    gi_close(fd);
}
