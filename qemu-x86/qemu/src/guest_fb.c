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

/* guest_fb.c — ioctl кадрового буфера для гостя.
 *
 * Зачем. За гостевым /dev/graphics/fb0 у нас стоит обычный файл: гость и
 * приложение отображают его вместе, и путь до экрана телефона на этом уже
 * проверен (см. guest/fbpaint.c, GuestView.kt). Но обычный файл не отвечает на
 * ioctl — а gralloc.default и SurfaceFlinger первым делом спрашивают у драйвера
 * геометрию: FBIOGET_VSCREENINFO и FBIOGET_FSCREENINFO. Без ответов SF не
 * поднимается вовсе.
 *
 * Отвечаем сами: умолчание 480x800, **16 бит RGB565**. Настоящие числа задаёт
 * профиль прошивки — на её родном разрешении и формате её ресурсы встают без
 * сдвигов.
 *
 * ★★ Почему именно 565, а не 8888. gralloc.default в 2.3 объявляет формат
 * кадрового буфера ЖЁСТКО: `dev->device.format = HAL_PIXEL_FORMAT_RGB_565`,
 * что бы ни ответил драйвер (в 4.x это стало условным). Мы сначала отвечали
 * 32 бита — и получили в буфере загрузочный экран изготовителя, нарисованный ДВАЖДЫ
 * рядом и в половину высоты: гость писал по 2 байта на точку в наши 4. Ровно
 * та картинка, по которой формат и опознаётся.
 *
 * ★ Одна страница на весь экран, БЕЗ двойной буферизации: `yres_virtual =
 * yres`. Тогда gralloc.default не делает переключения страниц вовсе (fb_post
 * просто копирует кадр в отображённую область), и приложению не нужно знать,
 * какая половина буфера сейчас показывается. Ценой возможного разрыва картинки
 * — зато без рассинхрона, который иначе пришлось бы отлаживать на живом экране.
 *
 * ★★ Р-144: ДВЕ СТРАНИЦЫ — только по просьбе стенда, переменной GUEST_FB_FLIP.
 * Её стенд ставит, когда композитор гостя рисует программно ПРЯМО в fb0 и
 * кадр показывает вид гостя (путь «кадровый буфер», у 1.x: загрузчик EGL до
 * моста не доходит). Композитор 1.x просит `yres_virtual = 2·yres` и, получив
 * отказ, пишет «page flipping not supported» и рисует в запасной буфер, а на
 * каждом кадре копирует его в fb0 целиком (300 КБ при 320x480) — копия под
 * трансляцией и разрыв, если вид гостя читает посреди копии. С переменной:
 *   * FBIOGET/FBIOPUT_VSCREENINFO отвечают `yres_virtual = 2·yres`, yoffset —
 *     показываемая страница; smem_len — обе страницы (файл стенд и так заводит
 *     на два кадра, Vm.FB_BYTES);
 *   * FBIOPUT_VSCREENINFO и FBIOPAN_DISPLAY с yoffset 0 или yres переключают
 *     показываемую страницу (1.x листает именно FBIOPUT_VSCREENINFO с
 *     FB_ACTIVATE_VBL, gralloc 2.3+ — FBIOPAN_DISPLAY);
 *   * какая страница показана, транслятор пишет в файл листания стенда — путь
 *     в самой переменной (хозяйский, вне дерева гостя): 16 байт, четыре слова
 *     little-endian {знак FB_FLIP_MAGIC, страница, номер листания, страниц}.
 *     Номер листания растёт на каждое переключение — по нему вид гостя
 *     (GuestView.kt) берёт новый кадр без сверки отпечатка, из той половины,
 *     которую гость велел показать. Файл общий для всех процессов гостя:
 *     номер читается из него (pread) и пишется назад, а описатель на миг —
 *     открыл, прочёл, записал, закрыл: гость наших описателей не видит.
 * Без переменной (мост, 2.3+) всё как было: одна страница, `yres_virtual =
 * yres`, FBIOPAN_DISPLAY ничего не делает.
 *
 * ⚠️ Вертикальной развёртки нет: FB_ACTIVATE_VBL здесь не ждёт её, а
 * переключает сразу. Окно разрыва остаётся — вид гостя копирует показанную
 * страницу (доли миллисекунды), а композитор как раз начинает рисовать в неё
 * следующий кадр, — но оно на порядки уже копии целого кадра в одну страницу.
 *
 * ⚠️ Формат задаётся ЗДЕСЬ, и приложение обязано показывать буфер тем же
 * форматом (Bitmap.Config.RGB_565). Разойдётся — картинка сдвоится или цвета
 * перевернутся.
 */
#include "qemu/osdep.h"
#include <stdlib.h>
#include "qemu.h"
#include "user-internals.h"

#include <linux/fb.h>

#include "guest_fb.h"

 
 
 
 
 
 
 
#define GUEST_FBIO_WAITFORVSYNC 0x40044620u

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
struct target_fb_fix_screeninfo {
    char id[16];
    uint32_t smem_start;
    uint32_t smem_len;
    uint32_t type;
    uint32_t type_aux;
    uint32_t visual;
    uint16_t xpanstep;
    uint16_t ypanstep;
    uint16_t ywrapstep;
    uint32_t line_length;
    uint32_t mmio_start;
    uint32_t mmio_len;
    uint32_t accel;
    uint16_t reserved[3];
};
 
QEMU_BUILD_BUG_ON(sizeof(struct target_fb_fix_screeninfo) != 68);

 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define FB_BPP    16
static int fb_geom_done;
static int fb_w = 480, fb_h = 800, fb_mm_w = 56, fb_mm_h = 94;

 
 
 
 
 
 
#define FB_FLIP_MAGIC 0x50494c46u       
static const char *fb_flip;
static int fb_pages = 1;
static int fb_page;

static int env_int(const char *name, int dflt)
{
    const char *s = getenv(name);
    if (!s || !*s) {
        return dflt;
    }
    int v = atoi(s);
    return v > 0 ? v : dflt;
}

static void fb_geom_init(void)
{
    if (fb_geom_done) {
        return;
    }
    fb_geom_done = 1;
    fb_w    = env_int("GUEST_FB_W", fb_w);
    fb_h    = env_int("GUEST_FB_H", fb_h);
     
 
 
 
    fb_mm_w = env_int("GUEST_FB_MM_W", fb_w * 254 / 2170);
    fb_mm_h = env_int("GUEST_FB_MM_H", fb_h * 254 / 2170);
    const char *flip = getenv("GUEST_FB_FLIP");
    if (flip && flip[0] == '/') {
        fb_flip = flip;
        fb_pages = 2;
    }
}

 
 
 
 
 
 
 
 
static void fb_show_page(int page)
{
    uint32_t w[4] = { 0, 0, 0, 0 };

    fb_page = page;
    if (!fb_flip) {
        return;
    }
    int f = open(fb_flip, O_RDWR | O_CLOEXEC);
    if (f < 0) {
        return;
    }
    if (pread(f, w, sizeof w, 0) != (ssize_t)sizeof w || w[0] != FB_FLIP_MAGIC) {
        w[2] = 0;                       
    }
    w[0] = FB_FLIP_MAGIC;
    w[1] = (uint32_t)page;
    w[2] += 1;
    w[3] = (uint32_t)fb_pages;
    if (pwrite(f, w, sizeof w, 0) != (ssize_t)sizeof w) {
         
    }
    close(f);
}

 
 
 
 
 
 
static int fb_page_of(abi_ulong arg)
{
    struct fb_var_screeninfo v;

    memcpy(&v, g2h_untagged(arg), sizeof v);
    if (v.yoffset == 0) {
        return 0;
    }
    if (fb_pages == 2 && v.yoffset == (uint32_t)fb_h) {
        return 1;
    }
    return -1;
}

#define FB_W      fb_w
#define FB_H      fb_h
#define FB_MM_W   fb_mm_w
#define FB_MM_H   fb_mm_h

 
 
 
 
 
#define FB_MAXFD 8
static int fb_yes[FB_MAXFD], fb_no[FB_MAXFD];

static int fd_in(const int *tbl, int fd)
{
    for (int i = 0; i < FB_MAXFD; i++) {
        if (tbl[i] == fd + 1) {
            return 1;
        }
    }
    return 0;
}

static void fd_add(int *tbl, int fd)
{
    for (int i = 0; i < FB_MAXFD; i++) {
        if (!tbl[i]) {
            tbl[i] = fd + 1;
            return;
        }
    }
}

static int is_fb_fd(int fd)
{
    char link[64], target[PATH_MAX];

    if (fd < 0) {
        return 0;
    }
    if (fd_in(fb_yes, fd)) {
        return 1;
    }
    if (fd_in(fb_no, fd)) {
        return 0;
    }
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, target, sizeof target - 1);
    if (n <= 0) {
        return 0;                       
    }
    target[n] = 0;
     
 
 
 
    size_t tlen = strlen(target);
    static const char tail[] = "dev/graphics/fb0";
    int ours = tlen >= sizeof tail - 1 &&
        !strcmp(target + tlen - (sizeof tail - 1), tail);
    fd_add(ours ? fb_yes : fb_no, fd);
    return ours;
}

void guest_fb_forget(int fd)
{
    for (int i = 0; i < FB_MAXFD; i++) {
        if (fb_yes[i] == fd + 1) {
            fb_yes[i] = 0;
        }
        if (fb_no[i] == fd + 1) {
            fb_no[i] = 0;
        }
    }
}

static void fill_var(struct fb_var_screeninfo *v)
{
    fb_geom_init();
    memset(v, 0, sizeof *v);
    v->xres = FB_W;
    v->yres = FB_H;
    v->xres_virtual = FB_W;
     
    v->yres_virtual = FB_H * fb_pages;
    v->yoffset = (uint32_t)(fb_page * FB_H);
    v->bits_per_pixel = FB_BPP;
     
    v->red.offset = 11; v->red.length = 5;
    v->green.offset = 5; v->green.length = 6;
    v->blue.offset = 0; v->blue.length = 5;
    v->transp.offset = 0; v->transp.length = 0;
    v->activate = FB_ACTIVATE_NOW;
    v->width = FB_MM_W;
    v->height = FB_MM_H;
    v->vmode = FB_VMODE_NONINTERLACED;
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
     
 
 
 
 
 
    {
        int hz = 0;
        const char *e = getenv("GUEST_FB_HZ");
        if (e) hz = atoi(e);
        if (hz < 10 || hz > 120) hz = 120;
         
 
 
 
 
 
 
        unsigned long total = (unsigned long)(FB_H + 24) * (unsigned long)(FB_W + 24);
        v->pixclock = (unsigned)(1000000000000ULL / (total * (unsigned long)hz));
    }
    v->left_margin = 8;
    v->right_margin = 8;
    v->upper_margin = 8;
    v->lower_margin = 8;
    v->hsync_len = 8;
    v->vsync_len = 8;
}

static void fill_fix(struct target_fb_fix_screeninfo *f)
{
    fb_geom_init();
    memset(f, 0, sizeof *f);
    snprintf(f->id, sizeof f->id, "guestfb");
    f->smem_start = 0;                  
    f->smem_len = FB_W * FB_H * (FB_BPP / 8) * fb_pages;
    f->ypanstep = fb_pages > 1 ? 1 : 0;    
    f->type = FB_TYPE_PACKED_PIXELS;
    f->visual = FB_VISUAL_TRUECOLOR;
    f->line_length = FB_W * (FB_BPP / 8);
    f->accel = FB_ACCEL_NONE;
}

int guest_fb_ioctl(int fd, unsigned long req, abi_ulong arg, abi_long *ret)
{
    switch (req) {
    case FBIOGET_VSCREENINFO:
    case FBIOPUT_VSCREENINFO:
    case FBIOGET_FSCREENINFO:
    case FBIOPAN_DISPLAY:
    case GUEST_FBIO_WAITFORVSYNC:
    case FBIOBLANK:
        break;
    default:
        return 0;                       
    }
    if (!is_fb_fd(fd)) {
        return 0;                       
    }

    *ret = 0;
    switch (req) {
    case FBIOGET_VSCREENINFO: {
        struct fb_var_screeninfo v;
        fill_var(&v);
        memcpy(g2h_untagged(arg), &v, sizeof v);
        return 1;
    }
    case FBIOGET_FSCREENINFO: {
        struct target_fb_fix_screeninfo f;
        fill_fix(&f);
        memcpy(g2h_untagged(arg), &f, sizeof f);
        return 1;
    }
    case FBIOPUT_VSCREENINFO: {
         
 
 
 
 
 
 
 
 
        fb_geom_init();
        if (fb_pages == 2) {
            int page = fb_page_of(arg);
            if (page >= 0) {
                fb_show_page(page);
            }
        }
        struct fb_var_screeninfo v;
        fill_var(&v);
        memcpy(g2h_untagged(arg), &v, sizeof v);
        return 1;
    }
    case FBIOPAN_DISPLAY:
         
        fb_geom_init();
        if (fb_pages == 2) {
            int page = fb_page_of(arg);
            if (page < 0) {
                errno = EINVAL;         
                *ret = -1;
                return 1;
            }
            fb_show_page(page);
        }
        return 1;
    case GUEST_FBIO_WAITFORVSYNC:
         
 
 
 
        return 1;
    case FBIOBLANK:
        return 1;
    }
    return 0;
}
