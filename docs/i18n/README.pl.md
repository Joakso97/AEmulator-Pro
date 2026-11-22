<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Klasyczne firmware Androida — HTC Sense, TouchWiz, MIUI, AOSP — na nowoczesnym telefonie. Bez roota, bez PC.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski** · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

<p align="center"><img src="../../docs/assets/rainbow.svg" width="100%" alt=""/></p>

AEmulator Pro uruchamia prawdziwy system Android 1.0–17+ prosto z pliku firmware: ZIP do recovery, archiwum Odin lub obraz fabryczny Google. Stary kod ARM tłumaczy zmodyfikowane QEMU, binder jądra jest emulowany, grafika korzysta z GPU telefonu, a dźwięk z systemu audio Androida — wszystko w zwykłej aplikacji.

## Funkcje

- Import niemal każdego formatu: ZIP CWM/TWRP, Odin `.tar.md5` Samsunga, obraz fabryczny Google `.tgz`, `system.img`, OTA `system.new.dat.br`
- Nakładki producentów działają bez zmian: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Sprzętowa grafika przez mostek GL, dźwięk, dotyk i multitouch, sieć z nowoczesnym proxy TLS
- Wspólny folder karty pamięci na APK, muzykę i zdjęcia
- Interfejs Material 3 Expressive w 18 językach
- Za darmo i open source (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Szybki start

1. Pobierz APK z [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) i zainstaluj.
2. Otwórz AEmulator Pro → **Dodaj firmware** i wybierz plik. Import trwa kilka minut.
3. Naciśnij **Uruchom**. Pierwszy start trwa dłużej: system optymalizuje aplikacje.
4. Menu  — głośność, przycisk zasilania i dziennik;  — ustawienia i język.

## Wymagania

- Android 8.0+ na 64-bitowym telefonie ARM (arm64-v8a)
- Około 1–3 GB wolnego miejsca na firmware
- Zalecany nowy Snapdragon / Dimensity / Tensor

## Jak to działa

Każdy proces gościa działa pod zmodyfikowanym QEMU w trybie użytkownika. Demon binder zastępuje sterownik jądra, mostek GL przekazuje wywołania OpenGL ES do GPU, a małe biblioteki gościa (HAL audio, nakładka audio policy, shim LD_PRELOAD) dopasowują kod producentów do emulatora. Importer czyta firmware, znajduje skrypty init w obrazie boot i tworzy plan startu usług.

## Budowanie ze źródeł

Przetestowana wersja release aplikacji używa JDK 21 i Android SDK 36. Zobacz [instrukcję budowania i kodu źródłowego](../build-source.md) wraz ze szczegółami natywnego toolchainu i podpisywania. **Odziedziczone gotowe pliki binarne silnika nie mają jeszcze zweryfikowanego, pełnego pochodzenia kodu źródłowego i buildu**; zobacz [audyt](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Podziękowania

AEmulator Pro wyrósł z emulatorów HTC Desire HD i HTC One M7 [pierwotnego autora](https://t.me/istratiit_ech) — bez jego silnika tego projektu by nie było.

To zmodyfikowany fork [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), który sam jest zmodyfikowanym forkiem [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## Linki

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Mój fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Oryginał: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Pierwotny autor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licencja

GPL-3.0. Android, znaki towarowe i firmware należą do ich właścicieli.

Zobacz [datowane informacje o modyfikacjach](../../NOTICE.md) oraz [otwarty audyt kodu źródłowego i licencji](../license-audit.md). Oznaczenie GPL nie jest zapewnieniem, że każdy dołączony plik binarny ma kompletny, pasujący kod źródłowy.
