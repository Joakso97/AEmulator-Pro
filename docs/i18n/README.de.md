<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch** · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

<p align="center"><img src="../../docs/assets/rainbow.svg" width="100%" alt=""/></p>

**Klassische Android-Firmware — HTC Sense, TouchWiz, MIUI, AOSP — auf einem modernen Handy. Ohne Root, ohne PC.**

AEmulator Pro startet ein echtes Android-1.0–17+-System direkt aus einer Firmware-Datei: Recovery-ZIP, Odin-Archiv oder Google-Factory-Image. Der alte ARM-Code läuft über ein angepasstes QEMU, der Binder des Kernels wird emuliert, die Grafik läuft über die GPU des Handys und der Ton über Androids Audiosystem — alles in einer normalen App.

## Funktionen

- Import fast aller Formate: CWM/TWRP-ZIP, Samsung Odin `.tar.md5`, Google Factory `.tgz`, `system.img`, OTA `system.new.dat.br`
- Hersteller-Oberflächen laufen unverändert: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Hardwaregrafik über die GL-Brücke, Ton, Touch und Multitouch, Netzwerk mit modernem TLS-Proxy
- Gemeinsamer Speicherkarten-Ordner für APKs, Musik und Fotos
- Material-3-Expressive-Oberfläche in 18 Sprachen
- Kostenlos und quelloffen (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Schnellstart

1. APK unter [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) herunterladen und installieren.
2. AEmulator Pro öffnen → **Firmware hinzufügen** und die Datei wählen. Der Import dauert einige Minuten.
3. **Starten** drücken. Der erste Start dauert länger: Das System optimiert Apps.
4. Menü  für Lautstärke, Ein/Aus und Protokoll;  öffnet Einstellungen und Sprache.

## Voraussetzungen

- Android 8.0+ auf einem 64-Bit-ARM-Handy (arm64-v8a)
- Etwa 1–3 GB freier Speicher pro Firmware
- Empfohlen: aktueller Snapdragon / Dimensity / Tensor

## So funktioniert es

Jeder Gastprozess läuft unter einem angepassten User-Mode-QEMU. Ein Binder-Daemon ersetzt den Kernel-Treiber, eine GL-Brücke leitet OpenGL-ES-Aufrufe an die GPU weiter, und kleine Gast-Bibliotheken (Audio-HAL, Audio-Policy-Wrapper, LD_PRELOAD-Shim) passen Herstellercode an den Emulator an. Der Importer liest die Firmware, findet die Init-Skripte im Boot-Image und erstellt einen Startplan für die Systemdienste.

## Aus dem Quellcode bauen

Der getestete Release-Build der App verwendet JDK 21 und Android SDK 36. Siehe die [Build-/Quellcode-Anleitung](../build-source.md) mit Details zur nativen Toolchain und zum Signieren. **Für die geerbten Engine-Binärdateien ist die vollständige Herkunft von Quellcode und Build noch nicht verifiziert**; siehe [das Audit](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Dank

AEmulator Pro ist aus den Emulatoren für HTC Desire HD und HTC One M7 [des Originalautors](https://t.me/istratiit_ech) hervorgegangen — ohne seine Engine gäbe es dieses Projekt nicht.

Dies ist ein modifizierter Fork von [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), das wiederum ein modifizierter Fork von [uxazu/AEmulator](https://github.com/uxazu/AEmulator) ist.

## Links

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Mein Fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Originalautor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Lizenz

GPL-3.0. Android, Marken und Firmware gehören ihren Inhabern.

Siehe die [datierten Änderungshinweise](../../NOTICE.md) und das [offene Quellcode-/Lizenz-Audit](../license-audit.md). Das GPL-Label ist keine Bestätigung, dass für jede mitgelieferte Binärdatei der vollständige passende Quellcode vorliegt.
