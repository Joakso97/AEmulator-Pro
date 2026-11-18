<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emula quasi qualsiasi firmware Android — HTC Sense, TouchWiz, MIUI, AOSP — su un telefono moderno.** Un fork di [AEmulator](https://github.com/uxazu/AEmulator) con più funzioni e correzioni.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · **🇮🇹 Italiano** · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro avvia una vera immagine di sistema Android 1.0–17 direttamente da un file firmware che hai già. Traduce il vecchio codice ARM con un QEMU modificato, emula il binder del kernel, disegna con la GPU del telefono e riproduce l'audio tramite lo stack audio di Android — tutto dentro una normale app. Questo progetto è stato realizzato quasi interamente con Claude Sonnet 5.5 (e l'emulatore originale con Opus 5.5).

## Funzionalità

- Importa quasi ogni formato di firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, immagini sparse, immagini EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` e molti altri
- Le interfacce dei produttori funzionano così come sono, se compatibili: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafica hardware tramite il bridge GL, audio, touch e multitouch, rete con un proxy TLS moderno
- Cartella condivisa della scheda di memoria per APK, musica e foto
- **Temi dello schermo della VM ed editor dei temi**: la barra di navigazione e la cornice attorno allo schermo guest seguono un tema — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x o Lollipop 5.x — oppure il tuo, creato in un editor con anteprima dal vivo (forme e dimensione dei tasti, colori di barra/tasti/pressione/cornice, bagliore e alone, barra flottante o piatta, immagine della cornice, angoli dello schermo arrotondati). I temi si possono condividere come file `.aetheme`. Impostazioni dell'app › Aspetto › Tema dello schermo della VM.
- **Organizza la libreria**: tieni premuto un ROM e trascinalo su o giù per riordinarlo (elenco classico e griglia 0.0.0.3), e raggruppa i ROM in cartelle con nome che si aprono e chiudono — rilascia un ROM su una cartella o usa il menu ⋮ › Sposta in cartella
- Interfaccia Material 3 Expressive in 19 lingue
- Gratuito e open source (GPL-3.0)

## Avvio rapido

1. Scarica l'APK da [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) e installalo.
2. Apri AEmulator Pro → **Aggiungi firmware** e scegli il file. L'importazione richiede qualche minuto.
3. Premi **Avvia**. Il primo avvio è più lento: il sistema ottimizza le sue app.
4. Usa il menu ⋮ per volume, pulsante di accensione e log; il pulsante ⚙️ apre impostazioni e lingua.

## Requisiti

- Android 8.0+ su un telefono ARM a 64 bit (arm64-v8a)
- Circa 1–3 GB di spazio libero per firmware
- È consigliato un SoC Snapdragon, per vari problemi con le GPU Mali

## Compilare dai sorgenti

La build release testata dell'app usa JDK 21 e Android SDK 36. Vedi le [istruzioni di build/sorgenti](../build-source.md), inclusi i dettagli della toolchain nativa e della firma.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Ringraziamenti

AEmulator Pro nasce dagli emulatori HTC Desire HD e HTC One M7 dell'[autore originale](https://t.me/istratiit_ech) — il suo motore ha reso possibile questo progetto.

Questo è un fork modificato di [uxazu/AEmulator](https://github.com/uxazu/AEmulator), che utilizza inoltre molte funzioni di [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Link

- Il mio fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (grandi ringraziamenti per molte funzioni): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Autore: [somerandomusername2](https://github.com/somerandomusernam2)
- Autore originale: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licenza

GPL-3.0. Android, i marchi e i firmware appartengono ai rispettivi proprietari.

Vedi le [note di modifica datate](../../NOTICE.md) e l'[audit sorgenti/licenze in sospeso](../license-audit.md). L'etichetta GPL non certifica che ogni binario incluso abbia i sorgenti completi corrispondenti.
