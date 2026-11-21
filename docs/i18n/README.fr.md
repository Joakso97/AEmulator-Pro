<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Les anciennes ROM Android — HTC Sense, TouchWiz, MIUI, AOSP — sur un téléphone moderne. Sans root, sans PC.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français** · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro démarre un vrai système Android 1.0–17+ directement depuis un fichier de ROM : ZIP recovery, archive Odin ou image d’usine Google. L’ancien code ARM passe par un QEMU modifié, le binder du noyau est émulé, les graphismes utilisent le GPU du téléphone et le son passe par la pile audio d’Android — le tout dans une application ordinaire.

## Fonctionnalités

- Import de presque tous les formats : ZIP CWM/TWRP, Odin `.tar.md5` de Samsung, image d’usine Google `.tgz`, `system.img`, OTA `system.new.dat.br`
- Les surcouches constructeur fonctionnent telles quelles : HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Graphismes matériels via le pont GL, son, tactile et multitouch, réseau avec proxy TLS moderne
- Dossier de carte mémoire partagé pour les APK, la musique et les photos
- Interface Material 3 Expressive en 18 langues
- Gratuit et open source (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Démarrage rapide

1. Téléchargez l’APK depuis [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) et installez-le.
2. Ouvrez AEmulator Pro → **Ajouter une ROM** et choisissez le fichier. L’import prend quelques minutes.
3. Appuyez sur **Démarrer**. Le premier démarrage est plus long : le système optimise les applis.
4. Menu  pour le volume, le bouton marche et le journal ;  ouvre les paramètres et la langue.

## Configuration requise

- Android 8.0+ sur un téléphone ARM 64 bits (arm64-v8a)
- Environ 1–3 Go libres par ROM
- Puce récente conseillée : Snapdragon / Dimensity / Tensor

## Fonctionnement

Chaque processus invité tourne sous un QEMU en mode utilisateur modifié. Un démon binder remplace le pilote du noyau, un pont GL transmet les appels OpenGL ES au GPU, et de petites bibliothèques invitées (HAL audio, enveloppe audio policy, shim LD_PRELOAD) adaptent le code constructeur à l’émulateur. L’importateur lit la ROM, trouve les scripts init dans l’image boot et construit le plan de démarrage des services.

## Compiler depuis les sources

Le build de release testé de l’application utilise JDK 21 et Android SDK 36. Voir les [instructions de compilation et de sources](../build-source.md), avec les détails de la chaîne d’outils native et de la signature. **Les binaires du moteur hérités n’ont pas encore de provenance complète vérifiée pour les sources et la compilation** ; voir [l’audit](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Remerciements

AEmulator Pro est né des émulateurs HTC Desire HD et HTC One M7 de [l’auteur d’origine](https://t.me/istratiit_ech) — sans son moteur, ce projet n’existerait pas.

Ceci est un fork modifié de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), lui-même un fork modifié de [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## Liens

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Mon fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Amont: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Auteur: [somerandomusername2](https://github.com/somerandomusernam2)
- Auteur d’origine: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licence

GPL-3.0. Android, les marques et les ROM appartiennent à leurs propriétaires.

Voir les [avis de modification datés](../../NOTICE.md) et [l’audit des sources et des licences, encore en cours](../license-audit.md). L’étiquette GPL ne certifie pas que chaque binaire fourni dispose de l’intégralité des sources correspondantes.
