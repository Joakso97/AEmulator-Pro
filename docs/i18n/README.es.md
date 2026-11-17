<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Emula casi cualquier firmware de Android — HTC Sense, TouchWiz, MIUI, AOSP — en un teléfono moderno.** Un fork de [AEmulator](https://github.com/uxazu/AEmulator) con más funciones y correcciones.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · **🇪🇸 Español** · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro arranca una imagen real del sistema Android 1.0–17 directamente desde un archivo de firmware que ya tienes. Traduce el código ARM antiguo con un QEMU modificado, emula el binder del kernel, dibuja con la GPU de tu teléfono y reproduce el sonido a través de la pila de audio de Android — todo dentro de una app normal. Este proyecto se hizo casi por completo con Claude Sonnet 5.5 (y el emulador original con Opus 5.5).

## Funciones

- Importa casi cualquier formato de firmware: Android-x86 `.iso`, ZIP de CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, imágenes sparse, imágenes EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` y muchos más
- Las capas de los fabricantes funcionan tal cual si son compatibles: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware mediante el puente GL, sonido, táctil y multitáctil, red con un proxy TLS moderno
- Carpeta compartida de tarjeta de memoria para APKs, música y fotos
- **Temas de pantalla de la VM y editor de temas**: la barra de navegación y el marco alrededor de la pantalla invitada siguen un tema — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x o Lollipop 5.x — o el tuyo propio, creado en un editor con vista previa en vivo (formas y tamaño de teclas, colores de barra/teclas/pulsación/marco, brillo y halo, barra flotante o plana, imagen de marco, esquinas de pantalla redondeadas). Los temas se pueden compartir como archivos `.aetheme`. Ajustes de la app › Apariencia › Tema de pantalla de la VM.
- **Organiza tu biblioteca**: mantén pulsado un ROM y arrástralo hacia arriba o abajo para reordenarlo (lista clásica y cuadrícula 0.0.0.3), y agrupa los ROMs en carpetas con nombre que se abren y cierran — suelta un ROM sobre una carpeta o usa su menú ⋮ › Mover a carpeta
- Interfaz Material 3 Expressive en 19 idiomas
- Gratis y de código abierto (GPL-3.0)

## Inicio rápido

1. Descarga el APK desde [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) e instálalo.
2. Abre AEmulator Pro → **Añadir firmware** y elige el archivo. La importación tarda unos minutos.
3. Pulsa **Iniciar**. El primer arranque es más lento: el sistema optimiza sus apps.
4. Usa el menú ⋮ para volumen, botón de encendido y registros; el botón ⚙️ abre los ajustes y el idioma.

## Requisitos

- Android 8.0+ en un teléfono ARM de 64 bits (arm64-v8a)
- Entre 1–3 GB de espacio libre por firmware
- Se recomienda un SoC Snapdragon, debido a varios problemas con las GPU Mali

## Compilar desde el código fuente

La build release probada de la app usa JDK 21 y Android SDK 36. Consulta las [instrucciones de compilación/fuentes](../build-source.md), incluidos los detalles de la toolchain nativa y la firma.

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## Créditos

AEmulator Pro nació de los emuladores de HTC Desire HD y HTC One M7 del [autor original](https://t.me/istratiit_ech) — su motor hizo posible este proyecto.

Este es un fork modificado de [uxazu/AEmulator](https://github.com/uxazu/AEmulator), que además usa muchas funciones de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Enlaces

- Mi fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (enormes créditos por muchas funciones): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Licencia

GPL-3.0. Android, las marcas y el firmware pertenecen a sus propietarios.

Consulta los [avisos de modificación fechados](../../NOTICE.md) y la [auditoría pendiente de fuentes/licencias](../license-audit.md). La etiqueta GPL no certifica que cada binario incluido tenga su código fuente completo correspondiente.
