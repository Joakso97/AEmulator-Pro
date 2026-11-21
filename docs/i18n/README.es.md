<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Firmware clásico de Android — HTC Sense, TouchWiz, MIUI, AOSP — en un teléfono moderno. Sin root, sin PC.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español** · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro arranca un sistema Android 1.0–17+ real directamente desde un archivo de firmware: ZIP de recovery, archivo de Odin o imagen de fábrica de Google. El código ARM antiguo se traduce con un QEMU modificado, el binder del kernel se emula, los gráficos usan la GPU del teléfono y el sonido pasa por la pila de audio de Android — todo dentro de una app normal.

## ✨ Características

- Importa casi cualquier formato: ZIP CWM/TWRP, Odin `.tar.md5` de Samsung, imagen de fábrica `.tgz` de Google, `system.img`, OTA `system.new.dat.br`
- Las capas de los fabricantes funcionan tal cual: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware con el puente GL, sonido, táctil y multitáctil, red con proxy TLS moderno
- Carpeta compartida de tarjeta de memoria para APK, música y fotos
- Interfaz Material 3 Expressive en 18 idiomas
- Gratis y de código abierto (GPL-3.0)
- Guest activity launcher: search installed packages in the ⋮ menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## 🚀 Inicio rápido

1. Descarga el APK desde [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) e instálalo.
2. Abre AEmulator Pro → **Añadir firmware** y elige el archivo. La importación tarda unos minutos.
3. Pulsa **Iniciar**. El primer arranque es más lento: el sistema optimiza las apps.
4. Menú ⋮ para volumen, botón de encendido y registro; ⚙️ abre ajustes e idioma.

## 📋 Requisitos

- Android 8.0+ en un teléfono ARM de 64 bits (arm64-v8a)
- Unos 1–3 GB libres por firmware
- Se recomienda un Snapdragon / Dimensity / Tensor reciente

## ⚙️ Cómo funciona

Cada proceso invitado se ejecuta con un QEMU de modo usuario modificado. Un demonio binder sustituye al controlador del kernel, un puente GL envía las llamadas OpenGL ES a la GPU y pequeñas bibliotecas invitadas (HAL de audio, envoltorio de audio policy, shim LD_PRELOAD) adaptan el código del fabricante al emulador. El importador lee el firmware, encuentra los scripts init en la imagen boot y crea el plan de arranque de los servicios.

## 🛠️ Compilar desde el código

La compilación de lanzamiento probada de la app usa JDK 21 y Android SDK 36. Consulta las [instrucciones de compilación y código fuente](../build-source.md), con detalles de la cadena de herramientas nativa y la firma. **Los binarios del motor heredados aún no tienen una procedencia completa verificada del código fuente y la compilación**; consulta [la auditoría](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 🙏 Créditos

AEmulator Pro nació de los emuladores de HTC Desire HD y HTC One M7 del [autor original](https://t.me/istratiit_ech); sin su motor este proyecto no existiría.

Este es un fork modificado de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), que a su vez es un fork modificado de [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Enlaces

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Mi fork: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Licencia

GPL-3.0. Android, las marcas y el firmware pertenecen a sus dueños.

Consulta los [avisos de modificación fechados](../../NOTICE.md) y la [auditoría pendiente de código fuente y licencias](../license-audit.md). La etiqueta GPL no certifica que cada binario incluido tenga el código fuente completo correspondiente.
