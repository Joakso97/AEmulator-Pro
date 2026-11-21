<div align="center">

<img src="../../docs/assets/logo-rounded.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emuluj prawie dowolny firmware Androida — HTC Sense, TouchWiz, MIUI, AOSP — na nowoczesnym telefonie.** Fork [AEmulator](https://github.com/uxazu/AEmulator) z większą liczbą funkcji i poprawek.

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski** · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro uruchamia prawdziwy obraz systemu Android 1.0–17 prosto z pliku firmware, który już masz. Tłumaczy stary kod ARM zmodyfikowanym QEMU, emuluje binder jądra, rysuje GPU telefonu i odtwarza dźwięk przez stos audio Androida — wszystko w zwykłej aplikacji. Ten projekt powstał niemal w całości przy użyciu Claude Sonnet 5.5 (a oryginalny emulator — Opus 5.5).

## Funkcje

- Import prawie każdego formatu firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, obrazy sparse, obrazy EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` i wiele innych
- Nakładki producentów działają bez zmian, jeśli są zgodne: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafika sprzętowa przez most GL, dźwięk, dotyk i multitouch, sieć z nowoczesnym proxy TLS
- Wspólny folder karty pamięci na APK, muzykę i zdjęcia
- **Motywy ekranu VM i edytor motywów**: pasek nawigacji i ramka wokół ekranu gościa podążają za motywem — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x lub Lollipop 5.x — albo twoim własnym, zrobionym w edytorze z podglądem na żywo (kształty i rozmiar klawiszy, kolory paska/klawiszy/naciśnięcia/ramki, poświata i halo, pasek pływający lub płaski, obraz ramki, zaokrąglone rogi ekranu). Motywy można udostępniać jako pliki `.aetheme`. Ustawienia aplikacji <img src="../assets/chevron.svg" height="14" valign="middle"> Wygląd <img src="../assets/chevron.svg" height="14" valign="middle"> Motyw ekranu VM.
- **Uporządkuj bibliotekę**: przytrzymaj ROM i przeciągnij go w górę lub w dół, aby zmienić kolejność (klasyczna lista i siatka 0.0.0.3), i grupuj ROM-y w nazwane foldery, które się otwierają i zamykają — upuść ROM na folder albo użyj menu <img src="../assets/menu.svg" height="14" valign="middle"> <img src="../assets/chevron.svg" height="14" valign="middle"> Przenieś do folderu
- Interfejs Material 3 Expressive w 19 językach
- Darmowy i open source (GPL-3.0)

## Szybki start

1. Pobierz APK z [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) i zainstaluj.
2. Otwórz AEmulator Pro <img src="../assets/chevron.svg" height="14" valign="middle"> **Dodaj firmware** i wybierz plik. Import trwa kilka minut.
3. Naciśnij **Start**. Pierwsze uruchomienie jest wolniejsze: system optymalizuje swoje aplikacje.
4. Menu <img src="../assets/menu.svg" height="14" valign="middle"> obsługuje głośność, przycisk zasilania i logi; przycisk <img src="../assets/settings.svg" height="14" valign="middle"> otwiera ustawienia i język.

## Wymagania

- Android 8.0+ na 64-bitowym telefonie ARM (arm64-v8a)
- Około 1–3 GB wolnego miejsca na każdy firmware
- Zalecany jest SoC Snapdragon ze względu na kilka problemów z GPU Mali

## Budowanie ze źródeł

Testowana wersja release aplikacji używa JDK 21 i Android SDK 36. Zobacz [instrukcje budowania/źródeł](../build-source.md), w tym szczegóły natywnego toolchainu i podpisywania.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Podziękowania

AEmulator Pro wyrósł z emulatorów HTC Desire HD i HTC One M7 [oryginalnego autora](https://t.me/istratiit_ech) — jego silnik umożliwił ten projekt.

To zmodyfikowany fork [uxazu/AEmulator](https://github.com/uxazu/AEmulator), który dodatkowo wykorzystuje wiele funkcji z [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Linki

- Mój fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (ogromne podziękowania za wiele funkcji): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Oryginalny autor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licencja

GPL-3.0. Android, znaki towarowe i firmware należą do ich właścicieli.

Zobacz [datowane informacje o modyfikacjach](../../NOTICE.md) i [otwarty audyt źródeł/licencji](../license-audit.md). Etykieta GPL nie potwierdza, że każda dołączona binarka ma kompletny odpowiadający jej kod źródłowy.
