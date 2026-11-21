<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Старые прошивки Android — HTC Sense, TouchWiz, MIUI, AOSP — на современном телефоне. Без root и без ПК.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский** · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro загружает настоящую систему Android 1.0–17+ прямо из файла прошивки: ZIP для рекавери, архива Odin или factory-образа Google. Старый ARM-код транслирует доработанный QEMU, binder ядра эмулируется, графика идёт через GPU телефона, звук — через аудиосистему Android. Всё работает внутри обычного приложения.

## ✨ Возможности

- Импорт почти любых форматов: ZIP для CWM/TWRP, Odin `.tar.md5` от Samsung, factory `.tgz` от Google, `system.img`, OTA `system.new.dat.br`
- Оболочки производителей работают как есть: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Аппаратная графика через GL-мост, звук, касания и мультитач, сеть с современным TLS-прокси
- Общая папка «карты памяти» для APK, музыки и фото
- Интерфейс Material 3 Expressive на 18 языках
- Бесплатно и с открытым кодом (GPL-3.0)
- Guest activity launcher: search installed packages in the ⋮ menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## 🚀 Быстрый старт

1. Скачайте APK из [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) и установите.
2. Откройте AEmulator Pro → **Добавить прошивку** и выберите файл. Импорт занимает несколько минут.
3. Нажмите **Запустить**. Первая загрузка дольше: система оптимизирует приложения.
4. Меню ⋮ — громкость, кнопка питания и журнал; кнопка ⚙️ — настройки приложения и язык.

## 📋 Требования

- Android 8.0+ на 64-битном ARM-телефоне (arm64-v8a)
- Около 1–3 ГБ свободного места на прошивку
- Рекомендуется современный Snapdragon / Dimensity / Tensor

## ⚙️ Как это устроено

Каждый процесс гостя работает под доработанным QEMU в режиме пользователя. Демон binder заменяет драйвер ядра, GL-мост передаёт вызовы OpenGL ES на GPU телефона, а небольшие гостевые библиотеки (звуковой HAL, обёртка audio policy, прослойка LD_PRELOAD) подгоняют код производителей под эмулятор. Импортёр читает прошивку, находит init-скрипты в boot-образе и строит план запуска системных служб.

## 🛠️ Сборка из исходников

Проверенная сборка релизного приложения использует JDK 21 и Android SDK 36. См. [инструкцию по сборке и исходникам](../build-source.md) — там же детали нативного тулчейна и подписи. **У унаследованных готовых бинарников движка пока нет подтверждённого полного происхождения исходников и сборки**; см. [аудит](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 🙏 Благодарности

AEmulator Pro вырос из эмуляторов HTC Desire HD и HTC One M7 [первого автора](https://t.me/istratiit_ech) — без его движка проекта бы не было.

Это модифицированный форк [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), который, в свою очередь, является модифицированным форком [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Ссылки

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Мой форк: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Апстрим: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Оригинал: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Автор: [somerandomusername2](https://github.com/somerandomusernam2)
- Первый автор: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Лицензия

GPL-3.0. Android, товарные знаки и прошивки принадлежат их владельцам.

См. [датированные уведомления об изменениях](../../NOTICE.md) и [незакрытый аудит исходников и лицензий](../license-audit.md). Метка GPL не гарантирует, что у каждого встроенного готового бинарника есть полные соответствующие исходники.
