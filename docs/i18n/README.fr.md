<div align="center">

<img src="../../docs/assets/logo-rounded.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Émulez presque n'importe quel firmware Android — HTC Sense, TouchWiz, MIUI, AOSP — sur un téléphone moderne.** Un fork d'[AEmulator](https://github.com/uxazu/AEmulator) avec plus de fonctionnalités et de correctifs.

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français** · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro démarre une véritable image système Android 1.0–17 directement depuis un fichier de firmware que vous possédez déjà. Il traduit l'ancien code ARM avec un QEMU modifié, émule le binder du noyau, affiche avec le GPU de votre téléphone et joue le son via la pile audio d'Android — le tout dans une application normale. Ce projet a été réalisé presque entièrement avec Claude Sonnet 5.5 (et l'émulateur original avec Opus 5.5).

## Fonctionnalités

- Import de presque tous les formats de firmware : Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, images sparse, images EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` et bien d'autres
- Les surcouches constructeur fonctionnent telles quelles si compatibles : HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Graphismes matériels via le pont GL, son, tactile et multitouch, réseau avec un proxy TLS moderne
- Dossier de carte mémoire partagé pour APK, musique et photos
- **Thèmes d'écran de la VM et éditeur de thèmes** : la barre de navigation et le cadre autour de l'écran invité suivent un thème — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x ou Lollipop 5.x — ou le vôtre, créé dans un éditeur avec aperçu en direct (formes et taille des touches, couleurs barre/touches/appui/cadre, lueur et halo, barre flottante ou plate, image de cadre, coins d'écran arrondis). Les thèmes se partagent sous forme de fichiers `.aetheme`. Réglages de l'app <img src="../assets/chevron.svg" height="14" valign="middle"> Apparence <img src="../assets/chevron.svg" height="14" valign="middle"> Thème d'écran de la VM.
- **Organisez votre bibliothèque** : maintenez un ROM appuyé et faites-le glisser vers le haut ou le bas pour le réordonner (liste classique et grille 0.0.0.3), et regroupez les ROMs dans des dossiers nommés qui s'ouvrent et se ferment — déposez un ROM sur un dossier, ou utilisez son menu <img src="../assets/menu.svg" height="14" valign="middle"> <img src="../assets/chevron.svg" height="14" valign="middle"> Déplacer vers un dossier
- Interface Material 3 Expressive en 19 langues
- Gratuit et open source (GPL-3.0)

## Démarrage rapide

1. Téléchargez l'APK depuis [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) et installez-le.
2. Ouvrez AEmulator Pro <img src="../assets/chevron.svg" height="14" valign="middle"> **Ajouter un firmware** et choisissez le fichier. L'import prend quelques minutes.
3. Appuyez sur **Démarrer**. Le premier démarrage est plus lent : le système optimise ses applications.
4. Le menu <img src="../assets/menu.svg" height="14" valign="middle"> gère le volume, le bouton d'alimentation et les journaux ; le bouton <img src="../assets/settings.svg" height="14" valign="middle"> ouvre les réglages et la langue.

## Prérequis

- Android 8.0+ sur un téléphone ARM 64 bits (arm64-v8a)
- Environ 1–3 Go d'espace libre par firmware
- Un SoC Snapdragon est recommandé, en raison de plusieurs problèmes avec les GPU Mali

## Compiler depuis les sources

La build release testée de l'app utilise JDK 21 et Android SDK 36. Voir les [instructions de build/sources](../build-source.md), y compris la toolchain native et la signature.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Remerciements

AEmulator Pro est né des émulateurs HTC Desire HD et HTC One M7 de [l'auteur original](https://t.me/istratiit_ech) — son moteur a rendu ce projet possible.

Ceci est un fork modifié d'[uxazu/AEmulator](https://github.com/uxazu/AEmulator), qui utilise en plus de nombreuses fonctionnalités de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Liens

- Mon fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (immenses remerciements pour de nombreuses fonctionnalités): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Auteur: [somerandomusername2](https://github.com/somerandomusernam2)
- Auteur original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licence

GPL-3.0. Android, les marques et les firmwares appartiennent à leurs propriétaires.

Voir les [avis de modification datés](../../NOTICE.md) et l'[audit sources/licences en cours](../license-audit.md). Le label GPL ne certifie pas que chaque binaire fourni dispose de sources complètes correspondantes.
