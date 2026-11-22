<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Firmwares clássicos do Android — HTC Sense, TouchWiz, MIUI, AOSP — em um celular moderno. Sem root, sem PC.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português** · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

<p align="center"><img src="../../docs/assets/rainbow.svg" width="100%" alt=""/></p>

O AEmulator Pro inicia um sistema Android 1.0–17+ real direto de um arquivo de firmware: ZIP de recovery, pacote do Odin ou imagem de fábrica do Google. O código ARM antigo é traduzido por um QEMU modificado, o binder do kernel é emulado, os gráficos usam a GPU do celular e o som passa pelo áudio do Android — tudo dentro de um app comum.

## Recursos

- Importa quase qualquer formato: ZIP CWM/TWRP, Odin `.tar.md5` da Samsung, imagem de fábrica `.tgz` do Google, `system.img`, OTA `system.new.dat.br`
- Interfaces das fabricantes funcionam como vieram: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware pela ponte GL, som, toque e multitoque, rede com proxy TLS moderno
- Pasta compartilhada de cartão de memória para APKs, músicas e fotos
- Interface Material 3 Expressive em 18 idiomas
- Gratuito e de código aberto (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Início rápido

1. Baixe o APK em [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) e instale.
2. Abra o AEmulator Pro → **Adicionar firmware** e escolha o arquivo. A importação leva alguns minutos.
3. Toque em **Iniciar**. A primeira inicialização é mais lenta: o sistema otimiza os apps.
4. Menu  para volume, botão liga/desliga e registro;  abre configurações e idioma.

## Requisitos

- Android 8.0+ em celular ARM de 64 bits (arm64-v8a)
- Cerca de 1–3 GB livres por firmware
- Recomendado: Snapdragon / Dimensity / Tensor recente

## Como funciona

Cada processo convidado roda num QEMU de modo usuário modificado. Um daemon binder substitui o driver do kernel, uma ponte GL envia as chamadas OpenGL ES para a GPU e pequenas bibliotecas (HAL de áudio, wrapper de audio policy, shim LD_PRELOAD) adaptam o código das fabricantes ao emulador. O importador lê o firmware, acha os scripts init na imagem boot e monta o plano de início dos serviços.

## Compilar do código-fonte

A build de lançamento testada do app usa JDK 21 e Android SDK 36. Veja as [instruções de build e código-fonte](../build-source.md), com detalhes da toolchain nativa e da assinatura. **Os binários do motor herdados ainda não têm origem completa verificada do código-fonte e da build**; veja [a auditoria](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Créditos

O AEmulator Pro nasceu dos emuladores de HTC Desire HD e HTC One M7 do [autor original](https://t.me/istratiit_ech) — sem o motor dele este projeto não existiria.

Este é um fork modificado de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), que por sua vez é um fork modificado de [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## Links

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Meu fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licença

GPL-3.0. Android, marcas e firmwares pertencem aos seus donos.

Veja os [avisos de modificação datados](../../NOTICE.md) e a [auditoria pendente de código-fonte e licenças](../license-audit.md). O selo GPL não certifica que cada binário incluído tenha o código-fonte correspondente completo.
