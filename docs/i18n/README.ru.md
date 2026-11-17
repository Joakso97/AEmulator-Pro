<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Эмулируйте почти любую прошивку Android — HTC Sense, TouchWiz, MIUI, AOSP — на современном телефоне.** Форк [AEmulator](https://github.com/uxazu/AEmulator) с новыми возможностями и исправлениями.

[🇬🇧 English](../../README.md) · **🇷🇺 Русский** · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro загружает настоящий образ системы Android 1.0–17 прямо из файла прошивки, который у вас уже есть. Старый ARM-код транслируется патченным QEMU, binder ядра эмулируется, графика рисуется GPU вашего телефона, а звук идёт через аудиостек Android — всё внутри обычного приложения. Этот проект почти целиком сделан с помощью Claude Sonnet 5.5 (а оригинальный эмулятор — с помощью Opus 5.5).

## Возможности

- Импорт почти любых форматов прошивок: Android-x86 `.iso`, CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, sparse-образы, EDL-образы, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` и многие другие
- Фирменные оболочки работают как есть, если совместимы: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Аппаратная графика через GL-мост, звук, касания и мультитач, сеть с современным TLS-прокси
- Общая папка карты памяти для APK, музыки и фото
- **Темы экрана ВМ и редактор тем**: панель навигации и рамка вокруг экрана гостя следуют теме — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x или Lollipop 5.x — или вашей собственной, созданной в редакторе с живым предпросмотром (форма и размер клавиш, цвета панели/клавиш/нажатия/рамки, свечение и ореол, плавающая или плоская панель, картинка рамки, скруглённые углы экрана). Темами можно делиться как файлами `.aetheme`. Настройки приложения › Оформление › Тема экрана ВМ.
- **Упорядочьте библиотеку**: нажмите и удерживайте ROM и перетащите его вверх или вниз (классический список и сетка 0.0.0.3), группируйте ROM в именованные папки, которые открываются и закрываются — бросьте ROM на папку или используйте меню ⋮ › Переместить в папку
- Интерфейс Material 3 Expressive на 19 языках
- Бесплатно и с открытым кодом (GPL-3.0)

## Быстрый старт

1. Скачайте APK из [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) и установите.
2. Откройте AEmulator Pro → **Добавить прошивку** и выберите файл. Импорт занимает несколько минут.
3. Нажмите **Старт**. Первая загрузка медленнее: система оптимизирует приложения.
4. Меню ⋮ — громкость, кнопка питания и логи; кнопка ⚙️ открывает настройки и язык.

## Требования

- Android 8.0+ на 64-битном ARM-телефоне (arm64-v8a)
- Около 1–3 ГБ свободного места на каждую прошивку
- Рекомендуется SoC Snapdragon из-за ряда проблем с GPU Mali

## Сборка из исходников

Проверенная release-сборка приложения использует JDK 21 и Android SDK 36. См. [инструкции по сборке](../build-source.md), включая детали нативного тулчейна и подписи.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Благодарности

AEmulator Pro вырос из эмуляторов HTC Desire HD и HTC One M7 [оригинального автора](https://t.me/istratiit_ech) — его движок сделал этот проект возможным.

Это модифицированный форк [uxazu/AEmulator](https://github.com/uxazu/AEmulator), который также использует множество возможностей из [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Ссылки

- Мой форк: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Апстрим: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (огромная благодарность за множество функций): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Автор: [somerandomusername2](https://github.com/somerandomusernam2)
- Оригинальный автор: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Лицензия

GPL-3.0. Android, товарные знаки и прошивки принадлежат их владельцам.

См. [датированные уведомления об изменениях](../../NOTICE.md) и [незавершённый аудит исходников/лицензий](../license-audit.md). Метка GPL не гарантирует, что для каждого встроенного бинарника есть полный соответствующий исходный код.
