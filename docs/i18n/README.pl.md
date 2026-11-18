<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emuluj prawie dowolny firmware Androida — HTC Sense, TouchWiz, MIUI, AOSP — na nowoczesnym telefonie.** Fork [AEmulator](https://github.com/uxazu/AEmulator) z większą liczbą funkcji i poprawek.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · **🇵🇱 Polski** · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro uruchamia prawdziwy obraz systemu Android 1.0–17 prosto z pliku firmware, który już masz. Tłumaczy stary kod ARM zmodyfikowanym QEMU, emuluje binder jądra, rysuje GPU telefonu i odtwarza dźwięk przez stos audio Androida — wszystko w zwykłej aplikacji. Ten projekt powstał niemal w całości przy użyciu Claude Sonnet 5.5 (a oryginalny emulator — Opus 5.5).

## Funkcje

- Import prawie każdego formatu firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, obrazy sparse, obrazy EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` i wiele innych
- Nakładki producentów działają bez zmian, jeśli są zgodne: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafika sprzętowa przez most GL, dźwięk, dotyk i multitouch, sieć z nowoczesnym proxy TLS
- Wspólny folder karty pamięci na APK, muzykę i zdjęcia
- **Motywy ekranu VM i edytor motywów**: pasek nawigacji i ramka wokół ekranu gościa podążają za motywem — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x lub Lollipop 5.x — albo twoim własnym, zrobionym w edytorze z podglądem na żywo (kształty i rozmiar klawiszy, kolory paska/klawiszy/naciśnięcia/ramki, poświata i halo, pasek pływający lub płaski, obraz ramki, zaokrąglone rogi ekranu). Motywy można udostępniać jako pliki `.aetheme`. Ustawienia aplikacji › Wygląd › Motyw ekranu VM.
- **Uporządkuj bibliotekę**: przytrzymaj ROM i przeciągnij go w górę lub w dół, aby zmienić kolejność (klasyczna lista i siatka 0.0.0.3), i grupuj ROM-y w nazwane foldery, które się otwierają i zamykają — upuść ROM na folder albo użyj menu ⋮ › Przenieś do folderu
- Interfejs Material 3 Expressive w 19 językach
- Darmowy i open source (GPL-3.0)

## Szybki start

1. Pobierz APK z [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) i zainstaluj.
2. Otwórz AEmulator Pro → **Dodaj firmware** i wybierz plik. Import trwa kilka minut.
3. Naciśnij **Start**. Pierwsze uruchomienie jest wolniejsze: system optymalizuje swoje aplikacje.
4. Menu ⋮ obsługuje głośność, przycisk zasilania i logi; przycisk ⚙️ otwiera ustawienia i język.

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
