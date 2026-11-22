<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia** · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

<p align="center"><img src="../../docs/assets/rainbow.svg" width="100%" alt=""/></p>

**Firmware Android klasik — HTC Sense, TouchWiz, MIUI, AOSP — di ponsel modern. Tanpa root, tanpa PC.**

AEmulator Pro mem-boot sistem Android 1.0–17+ asli langsung dari file firmware: ZIP recovery, arsip Odin, atau factory image Google. Kode ARM lama diterjemahkan oleh QEMU yang dimodifikasi, binder kernel diemulasikan, grafis memakai GPU ponsel, dan suara lewat sistem audio Android — semuanya di dalam aplikasi biasa.

## Fitur

- Impor hampir semua format: ZIP CWM/TWRP, Odin `.tar.md5` Samsung, factory image Google `.tgz`, `system.img`, OTA `system.new.dat.br`
- Tampilan pabrikan berjalan apa adanya: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafis hardware lewat jembatan GL, suara, sentuh dan multisentuh, jaringan dengan proxy TLS modern
- Folder kartu memori bersama untuk APK, musik, dan foto
- Antarmuka Material 3 Expressive dalam 18 bahasa
- Gratis dan sumber terbuka (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Mulai cepat

1. Unduh APK dari [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) lalu pasang.
2. Buka AEmulator Pro → **Tambah firmware** lalu pilih file. Impor butuh beberapa menit.
3. Tekan **Mulai**. Boot pertama lebih lambat: sistem mengoptimalkan aplikasi.
4. Menu  untuk volume, tombol daya, dan log;  membuka setelan dan bahasa.

## Persyaratan

- Android 8.0+ di ponsel ARM 64-bit (arm64-v8a)
- Sekitar 1–3 GB ruang kosong per firmware
- Disarankan Snapdragon / Dimensity / Tensor terbaru

## Cara kerja

Setiap proses tamu berjalan di QEMU mode pengguna yang dimodifikasi. Daemon binder menggantikan driver kernel, jembatan GL meneruskan panggilan OpenGL ES ke GPU, dan pustaka tamu kecil (HAL audio, pembungkus audio policy, shim LD_PRELOAD) menyesuaikan kode pabrikan dengan emulator. Importir membaca firmware, menemukan skrip init di image boot, dan menyusun rencana mulai layanan.

## Membangun dari sumber

Build rilis aplikasi yang diuji memakai JDK 21 dan Android SDK 36. Lihat [petunjuk build/sumber](../build-source.md), termasuk detail toolchain native dan penandatanganan. **Binari mesin bawaan warisan belum memiliki asal-usul kode sumber/build yang terverifikasi lengkap**; lihat [audit](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Kredit

AEmulator Pro tumbuh dari emulator HTC Desire HD dan HTC One M7 karya [pembuat asli](https://t.me/istratiit_ech) — tanpa mesinnya proyek ini tidak akan ada.

Ini adalah fork yang dimodifikasi dari [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), yang sendiri merupakan fork yang dimodifikasi dari [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## Tautan

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Fork saya: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Asli: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Pembuat: [somerandomusername2](https://github.com/somerandomusernam2)
- Pembuat asli: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Lisensi

GPL-3.0. Android, merek dagang, dan firmware milik pemiliknya.

Lihat [pemberitahuan modifikasi bertanggal](../../NOTICE.md) dan [audit sumber/lisensi yang belum selesai](../license-audit.md). Label GPL bukan sertifikasi bahwa setiap binari bawaan memiliki kode sumber yang cocok dan lengkap.
