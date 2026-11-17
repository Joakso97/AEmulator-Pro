<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emule quase qualquer firmware Android — HTC Sense, TouchWiz, MIUI, AOSP — em um celular moderno.** Um fork do [AEmulator](https://github.com/uxazu/AEmulator) com mais recursos e correções.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · **🇧🇷 Português** · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

O AEmulator Pro inicializa uma imagem real do sistema Android 1.0–17 direto de um arquivo de firmware que você já tem. Ele traduz o código ARM antigo com um QEMU modificado, emula o binder do kernel, desenha com a GPU do seu celular e reproduz som pela pilha de áudio do Android — tudo dentro de um app normal. Este projeto foi feito quase inteiramente com Claude Sonnet 5.5 (e o emulador original com Opus 5.5).

## Recursos

- Importa quase qualquer formato de firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, imagens sparse, imagens EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` e muitos outros
- As interfaces dos fabricantes funcionam como são, se compatíveis: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware pela ponte GL, som, toque e multitoque, rede com um proxy TLS moderno
- Pasta compartilhada de cartão de memória para APKs, músicas e fotos
- **Temas de tela da VM e editor de temas**: a barra de navegação e a moldura ao redor da tela convidada seguem um tema — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x ou Lollipop 5.x — ou o seu próprio, feito em um editor com pré-visualização ao vivo (formas e tamanho das teclas, cores de barra/teclas/pressão/moldura, brilho e halo, barra flutuante ou plana, imagem de moldura, cantos de tela arredondados). Temas podem ser compartilhados como arquivos `.aetheme`. Configurações do app › Aparência › Tema de tela da VM.
- **Organize sua biblioteca**: pressione e segure um ROM e arraste-o para cima ou para baixo para reordená-lo (lista clássica e grade 0.0.0.3), e agrupe ROMs em pastas nomeadas que abrem e fecham — solte um ROM sobre uma pasta ou use o menu ⋮ › Mover para pasta
- Interface Material 3 Expressive em 19 idiomas
- Gratuito e de código aberto (GPL-3.0)

## Início rápido

1. Baixe o APK em [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) e instale.
2. Abra o AEmulator Pro → **Adicionar firmware** e escolha o arquivo. A importação leva alguns minutos.
3. Pressione **Iniciar**. A primeira inicialização é mais lenta: o sistema otimiza seus apps.
4. Use o menu ⋮ para volume, botão de energia e logs; o botão ⚙️ abre as configurações e o idioma.

## Requisitos

- Android 8.0+ em um celular ARM de 64 bits (arm64-v8a)
- Cerca de 1–3 GB de espaço livre por firmware
- Recomenda-se um SoC Snapdragon, devido a vários problemas com GPUs Mali

## Compilar do código-fonte

A build release testada do app usa JDK 21 e Android SDK 36. Veja as [instruções de build/fontes](../build-source.md), incluindo detalhes da toolchain nativa e da assinatura.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Créditos

O AEmulator Pro nasceu dos emuladores de HTC Desire HD e HTC One M7 do [autor original](https://t.me/istratiit_ech) — o motor dele tornou este projeto possível.

Este é um fork modificado do [uxazu/AEmulator](https://github.com/uxazu/AEmulator), que também usa muitos recursos do [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Links

- Meu fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (enormes créditos por muitos recursos): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licença

GPL-3.0. Android, marcas e firmwares pertencem aos seus donos.

Veja os [avisos de modificação datados](../../NOTICE.md) e a [auditoria pendente de fontes/licenças](../license-audit.md). O selo GPL não certifica que cada binário incluído tenha o código-fonte completo correspondente.
