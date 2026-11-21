<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Старі прошивки Android — HTC Sense, TouchWiz, MIUI, AOSP — на сучасному телефоні. Без root і без ПК.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська** · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro завантажує справжню систему Android 1.0–17+ прямо з файлу прошивки: ZIP для рекавері, архіву Odin або factory-образу Google. Старий ARM-код транслює доопрацьований QEMU, binder ядра емулюється, графіка йде через GPU телефона, звук — через аудіосистему Android. Усе працює всередині звичайного застосунку.

## ✨ Можливості

- Імпорт майже будь-яких форматів: ZIP для CWM/TWRP, Odin `.tar.md5` від Samsung, factory `.tgz` від Google, `system.img`, OTA `system.new.dat.br`
- Оболонки виробників працюють як є: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Апаратна графіка через GL-міст, звук, дотики й мультитач, мережа із сучасним TLS-проксі
- Спільна папка «карти пам’яті» для APK, музики й фото
- Інтерфейс Material 3 Expressive 18 мовами
- Безкоштовно й з відкритим кодом (GPL-3.0)
- Guest activity launcher: search installed packages in the ⋮ menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## 🚀 Швидкий старт

1. Завантажте APK з [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) і встановіть.
2. Відкрийте AEmulator Pro → **Додати прошивку** й виберіть файл. Імпорт триває кілька хвилин.
3. Натисніть **Запустити**. Перше завантаження довше: система оптимізує застосунки.
4. Меню ⋮ — гучність, кнопка живлення й журнал; кнопка ⚙️ — налаштування й мова.

## 📋 Вимоги

- Android 8.0+ на 64-бітному ARM-телефоні (arm64-v8a)
- Близько 1–3 ГБ вільного місця на прошивку
- Рекомендовано сучасний Snapdragon / Dimensity / Tensor

## ⚙️ Як це влаштовано

Кожен процес гостя працює під доопрацьованим QEMU в режимі користувача. Демон binder замінює драйвер ядра, GL-міст передає виклики OpenGL ES на GPU телефона, а невеликі гостьові бібліотеки (звуковий HAL, обгортка audio policy, прошарок LD_PRELOAD) підлаштовують код виробників під емулятор. Імпортер читає прошивку, знаходить init-скрипти в boot-образі й будує план запуску системних служб.

## 🛠️ Збирання з вихідного коду

Перевірена збірка релізного застосунку використовує JDK 21 та Android SDK 36. Див. [інструкцію зі збірки та вихідного коду](../build-source.md), зокрема подробиці про нативний тулчейн і підпис. **Для успадкованих готових бінарників рушія досі немає підтвердженого повного походження вихідного коду та збірки**; див. [аудит](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 🙏 Подяки

AEmulator Pro виріс з емуляторів HTC Desire HD та HTC One M7 [першого автора](https://t.me/istratiit_ech) — без його рушія проєкту б не було.

Це модифікований форк [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), який, своєю чергою, є модифікованим форком [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Посилання

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Мій форк: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Апстрим: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Оригінал: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Автор: [somerandomusername2](https://github.com/somerandomusernam2)
- Перший автор: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Ліцензія

GPL-3.0. Android, торговельні марки й прошивки належать їхнім власникам.

Див. [датовані повідомлення про зміни](../../NOTICE.md) та [відкритий аудит вихідного коду й ліцензій](../license-audit.md). Позначка GPL не гарантує, що для кожного вбудованого готового бінарника є повний відповідний вихідний код.
