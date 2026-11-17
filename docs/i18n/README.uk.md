<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Емулюйте майже будь-яку прошивку Android — HTC Sense, TouchWiz, MIUI, AOSP — на сучасному телефоні.** Форк [AEmulator](https://github.com/uxazu/AEmulator) з новими можливостями та виправленнями.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · **🇺🇦 Українська** · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro завантажує справжній образ системи Android 1.0–17 прямо з файла прошивки, який у вас уже є. Старий ARM-код транслюється патченим QEMU, binder ядра емулюється, графіка малюється GPU вашого телефона, а звук іде через аудіостек Android — усе всередині звичайного застосунку. Цей проєкт майже повністю створено за допомогою Claude Sonnet 5.5 (а оригінальний емулятор — за допомогою Opus 5.5).

## Можливості

- Імпорт майже будь-яких форматів прошивок: Android-x86 `.iso`, CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, sparse-образи, EDL-образи, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` та багато інших
- Фірмові оболонки працюють як є, якщо сумісні: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Апаратна графіка через GL-міст, звук, дотики й мультитач, мережа із сучасним TLS-проксі
- Спільна папка карти пам'яті для APK, музики та фото
- **Теми екрана ВМ і редактор тем**: панель навігації та рамка навколо екрана гостя дотримуються теми — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x або Lollipop 5.x — чи вашої власної, створеної в редакторі з живим попереднім переглядом (форма й розмір клавіш, кольори панелі/клавіш/натискання/рамки, світіння та ореол, плаваюча чи пласка панель, зображення рамки, заокруглені кути екрана). Темами можна ділитися як файлами `.aetheme`. Налаштування застосунку › Оформлення › Тема екрана ВМ.
- **Упорядкуйте бібліотеку**: натисніть і утримуйте ROM та перетягніть його вгору або вниз (класичний список і сітка 0.0.0.3), групуйте ROM в іменовані папки, що відкриваються й закриваються — киньте ROM на папку або скористайтеся меню ⋮ › Перемістити до папки
- Інтерфейс Material 3 Expressive 19 мовами
- Безкоштовно та з відкритим кодом (GPL-3.0)

## Швидкий старт

1. Завантажте APK з [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) і встановіть.
2. Відкрийте AEmulator Pro → **Додати прошивку** та виберіть файл. Імпорт триває кілька хвилин.
3. Натисніть **Старт**. Перше завантаження повільніше: система оптимізує застосунки.
4. Меню ⋮ — гучність, кнопка живлення та логи; кнопка ⚙️ відкриває налаштування й мову.

## Вимоги

- Android 8.0+ на 64-бітному ARM-телефоні (arm64-v8a)
- Близько 1–3 ГБ вільного місця на кожну прошивку
- Рекомендується SoC Snapdragon через низку проблем із GPU Mali

## Збірка з вихідного коду

Перевірена release-збірка застосунку використовує JDK 21 і Android SDK 36. Див. [інструкції зі збірки](../build-source.md), включно з деталями нативного тулчейну та підпису.

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## Подяки

AEmulator Pro виріз з емуляторів HTC Desire HD і HTC One M7 [оригінального автора](https://t.me/istratiit_ech) — його рушій уможливив цей проєкт.

Це модифікований форк [uxazu/AEmulator](https://github.com/uxazu/AEmulator), який також використовує багато можливостей із [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Посилання

- Мій форк: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Апстрім: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (величезна подяка за багато функцій): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Автор: [somerandomusername2](https://github.com/somerandomusernam2)
- Оригінальний автор: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Ліцензія

GPL-3.0. Android, торговельні марки та прошивки належать їхнім власникам.

Див. [датовані повідомлення про зміни](../../NOTICE.md) та [незавершений аудит вихідного коду/ліцензій](../license-audit.md). Позначка GPL не гарантує, що для кожного вбудованого бінарника є повний відповідний вихідний код.
