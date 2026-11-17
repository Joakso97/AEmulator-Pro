<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emuliere fast jede Android-Firmware — HTC Sense, TouchWiz, MIUI, AOSP — auf einem modernen Handy.** Ein Fork von [AEmulator](https://github.com/uxazu/AEmulator) mit mehr Funktionen und Fehlerbehebungen.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · **🇩🇪 Deutsch** · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro bootet ein echtes Android-1.0–17-System-Image direkt aus einer Firmware-Datei, die du bereits hast. Der alte ARM-Code läuft über ein angepasstes QEMU, der Binder des Kernels wird emuliert, die Grafik läuft über die GPU des Handys und der Ton über Androids Audiosystem — alles in einer normalen App. Dieses Projekt entstand fast vollständig mit Claude Sonnet 5.5 (und der ursprüngliche Emulator mit Opus 5.5).

## Funktionen

- Import fast aller Firmware-Formate: Android-x86 `.iso`, CWM/TWRP-ZIP, Samsung Odin `.tar.md5`, Google Factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, Sparse-Images, EDL-Images, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` und viele mehr
- Hersteller-Oberflächen laufen unverändert, sofern kompatibel: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Hardwaregrafik über die GL-Brücke, Ton, Touch und Multitouch, Netzwerk mit modernem TLS-Proxy
- Gemeinsamer Speicherkarten-Ordner für APKs, Musik und Fotos
- **VM-Bildschirmthemen und Themen-Editor**: Navigationsleiste und Rahmen um den Gast-Bildschirm folgen einem Thema — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x oder Lollipop 5.x — oder deinem eigenen, im Editor mit Live-Vorschau erstellt (Tastenformen und -größe, Farben für Leiste/Tasten/Druck/Rahmen, Leuchten und Halo, schwebende oder flache Leiste, Rahmenbild, abgerundete Bildschirmecken). Themen lassen sich als `.aetheme`-Dateien teilen. App-Einstellungen › Darstellung › VM-Bildschirmthema.
- **Bibliothek ordnen**: ROM gedrückt halten und nach oben oder unten ziehen, um es umzusortieren (klassische Liste und 0.0.0.3-Raster), und ROMs in benannte Ordner gruppieren, die sich öffnen und schließen lassen — ROM auf einen Ordner ziehen oder das ⋮-Menü › In Ordner verschieben nutzen
- Material-3-Expressive-Oberfläche in 19 Sprachen
- Kostenlos und quelloffen (GPL-3.0)

## Schnellstart

1. APK unter [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) herunterladen und installieren.
2. AEmulator Pro öffnen → **Firmware hinzufügen** und die Datei wählen. Der Import dauert einige Minuten.
3. **Starten** drücken. Der erste Start dauert länger: Das System optimiert seine Apps.
4. Menü ⋮ für Lautstärke, Ein/Aus und Protokolle; die ⚙️-Schaltfläche öffnet Einstellungen und Sprache.

## Voraussetzungen

- Android 8.0+ auf einem 64-Bit-ARM-Handy (arm64-v8a)
- Etwa 1–3 GB freier Speicher pro Firmware
- Ein Snapdragon-SoC wird empfohlen, wegen einiger Probleme mit Mali-GPUs

## Aus dem Quellcode bauen

Der getestete Release-Build der App verwendet JDK 21 und Android SDK 36. Siehe die [Build-/Quellcode-Anleitung](../build-source.md) mit Details zur nativen Toolchain und zum Signieren.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Dank

AEmulator Pro ist aus den Emulatoren für HTC Desire HD und HTC One M7 [des Originalautors](https://t.me/istratiit_ech) hervorgegangen — seine Engine machte dieses Projekt möglich.

Dies ist ein modifizierter Fork von [uxazu/AEmulator](https://github.com/uxazu/AEmulator), der zusätzlich viele Funktionen aus [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) nutzt.

## Links

- Mein Fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (großer Dank für viele Funktionen): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Originalautor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Lizenz

GPL-3.0. Android, Marken und Firmware gehören ihren Inhabern.

Siehe die [datierten Änderungshinweise](../../NOTICE.md) und das [offene Quellcode-/Lizenz-Audit](../license-audit.md). Das GPL-Label ist keine Bestätigung, dass für jede mitgelieferte Binärdatei der vollständige passende Quellcode vorliegt.
