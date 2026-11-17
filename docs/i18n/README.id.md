<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emulasikan hampir semua firmware Android — HTC Sense, TouchWiz, MIUI, AOSP — di ponsel modern.** Fork dari [AEmulator](https://github.com/uxazu/AEmulator) dengan lebih banyak fitur dan perbaikan.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · **🇮🇩 Indonesia** · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro mem-boot image sistem Android 1.0–17 asli langsung dari file firmware yang sudah Anda punya. Ia menerjemahkan kode ARM lama dengan QEMU yang ditambal, mengemulasikan binder kernel, menggambar dengan GPU ponsel Anda, dan memutar suara melalui stack audio Android — semuanya berjalan di dalam aplikasi biasa. Proyek ini dibuat hampir sepenuhnya dengan Claude Sonnet 5.5 (dan emulator aslinya dengan Opus 5.5).

## Fitur

- Impor hampir semua format firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, image sparse, image EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe`, dan banyak lainnya
- Skin vendor berjalan apa adanya jika kompatibel: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafis perangkat keras lewat jembatan GL, suara, sentuhan dan multitouch, jaringan dengan proksi TLS modern
- Folder kartu memori bersama untuk APK, musik, dan foto
- **Tema layar VM dan editor tema**: bilah navigasi dan bingkai di sekeliling layar tamu mengikuti tema — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x, atau Lollipop 5.x — atau tema Anda sendiri, dibuat di editor dengan pratinjau langsung (bentuk dan ukuran tombol, warna bilah/tombol/tekanan/bingkai, cahaya dan halo, bilah mengambang atau datar, gambar bingkai, sudut layar membulat). Tema dapat dibagikan sebagai file `.aetheme`. Pengaturan aplikasi › Tampilan › Tema layar VM.
- **Atur pustaka Anda**: tekan dan tahan ROM lalu seret ke atas atau bawah untuk mengurutkannya (daftar klasik dan kisi 0.0.0.3), dan kelompokkan ROM ke folder bernama yang bisa dibuka-tutup — jatuhkan ROM ke folder, atau gunakan menu ⋮ › Pindahkan ke folder
- Antarmuka Material 3 Expressive dalam 19 bahasa
- Gratis dan sumber terbuka (GPL-3.0)

## Mulai cepat

1. Unduh APK dari [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) lalu pasang.
2. Buka AEmulator Pro → **Tambah firmware** dan pilih file. Impor memakan waktu beberapa menit.
3. Tekan **Mulai**. Boot pertama lebih lambat: sistem mengoptimalkan aplikasi-aplikasinya.
4. Gunakan menu ⋮ untuk volume, tombol daya, dan log; tombol ⚙️ membuka pengaturan dan bahasa.

## Persyaratan

- Android 8.0+ di ponsel ARM 64-bit (arm64-v8a)
- Sekitar 1–3 GB ruang kosong per firmware
- SoC Snapdragon disarankan, karena beberapa masalah dengan GPU Mali

## Bangun dari sumber

Build release aplikasi yang teruji memakai JDK 21 dan Android SDK 36. Lihat [instruksi build/sumber](../build-source.md), termasuk detail toolchain native dan penandatanganan.

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## Kredit

AEmulator Pro tumbuh dari emulator HTC Desire HD dan HTC One M7 karya [penulis asli](https://t.me/istratiit_ech) — mesin merekalah yang memungkinkan proyek ini.

Ini adalah fork modifikasi dari [uxazu/AEmulator](https://github.com/uxazu/AEmulator), yang juga memakai banyak fitur dari [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Tautan

- Fork saya: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (kredit besar untuk banyak fitur): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Penulis: [somerandomusername2](https://github.com/somerandomusernam2)
- Penulis asli: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Lisensi

GPL-3.0. Android, merek dagang, dan firmware adalah milik pemiliknya masing-masing.

Lihat [pemberitahuan modifikasi bertanggal](../../NOTICE.md) dan [audit sumber/lisensi yang belum selesai](../license-audit.md). Label GPL bukan sertifikasi bahwa setiap prebuilt yang dibundel memiliki sumber lengkap yang cocok.
