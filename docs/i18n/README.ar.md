<div dir="rtl">

<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**شغّل برامج أندرويد الكلاسيكية — HTC Sense وTouchWiz وMIUI وAOSP — على هاتف حديث. بلا روت وبلا حاسوب.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية** · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

يقلع AEmulator Pro نظام أندرويد 1.0–17+ حقيقيًا مباشرة من ملف البرنامج الثابت: ملف ZIP للريكفري أو أرشيف Odin أو صورة مصنع Google. تُترجم شفرة ARM القديمة عبر QEMU معدّل، ويُحاكى binder النواة، وتُرسم الواجهة بمعالج رسومات الهاتف ويمر الصوت عبر نظام صوت أندرويد — كل ذلك داخل تطبيق عادي.

## ✨ المزايا

- استيراد كل الصيغ تقريبًا: ‏ZIP لـ CWM/TWRP، و‏Odin ‏`.tar.md5` من سامسونج، وصورة مصنع Google ‏`.tgz`، و`system.img`، وOTA ‏`system.new.dat.br`
- واجهات الشركات تعمل كما هي: HTC Sense وSamsung TouchWiz وMIUI وAOSP
- رسوميات عتادية عبر جسر GL، وصوت، ولمس ولمس متعدد، وشبكة مع وكيل TLS حديث
- مجلد بطاقة ذاكرة مشترك لملفات APK والموسيقى والصور
- واجهة Material 3 Expressive بـ 18 لغة
- مجاني ومفتوح المصدر (GPL-3.0)
- Guest activity launcher: search installed packages in the ⋮ menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## 🚀 البدء السريع

1. نزّل ملف APK من [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) وثبّته.
2. افتح AEmulator Pro ← **إضافة برنامج ثابت** واختر الملف. يستغرق الاستيراد بضع دقائق.
3. اضغط **تشغيل**. الإقلاع الأول أبطأ: النظام يحسّن التطبيقات.
4. القائمة ⋮ للصوت وزر التشغيل والسجل؛ وزر ⚙️ للإعدادات واللغة.

## 📋 المتطلبات

- أندرويد 8.0+ على هاتف ARM بمعمارية 64 بت (arm64-v8a)
- نحو 1–3 غيغابايت لكل برنامج ثابت
- يُنصح بمعالج Snapdragon / Dimensity / Tensor حديث

## ⚙️ كيف يعمل

تعمل كل عملية ضيف تحت QEMU معدّل بوضع المستخدم. يحل خادم binder محل برنامج تشغيل النواة، وينقل جسر GL استدعاءات OpenGL ES إلى معالج الرسومات، وتكيّف مكتبات صغيرة (HAL للصوت، وغلاف audio policy، وطبقة LD_PRELOAD) شفرة الشركات مع المحاكي. يقرأ المستورد البرنامج الثابت ويجد سكربتات init في صورة boot ويبني خطة تشغيل الخدمات.

## 🛠️ البناء من المصدر

يستخدم إصدار التطبيق المُختبَر JDK 21 وAndroid SDK 36. راجع [تعليمات البناء والمصدر](../build-source.md) لمعرفة تفاصيل سلسلة الأدوات الأصلية والتوقيع. **الملفات الثنائية الجاهزة للمحرك الموروثة لا تملك بعدُ أصلاً مُتحقَّقاً منه للشيفرة المصدرية وطريقة البناء**؛ راجع [التدقيق](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 🙏 شكر وتقدير

نشأ AEmulator Pro من محاكيات HTC Desire HD وHTC One M7 التي صنعها [المطوّر الأصلي](https://t.me/istratiit_ech)، ولولا محركه ما وُجد هذا المشروع.

هذا فرع معدَّل من [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)، وهو بدوره فرع معدَّل من [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 روابط

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- فرعي: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- المستودع الأم: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- الأصل: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- المطوّر: [somerandomusername2](https://github.com/somerandomusernam2)
- المطوّر الأصلي: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 الترخيص

GPL-3.0. أندرويد والعلامات التجارية والبرامج الثابتة ملك لأصحابها.

راجع [إشعارات التعديل المؤرخة](../../NOTICE.md) و[تدقيق المصدر والتراخيص المعلَّق](../license-audit.md). وسم GPL ليس شهادة بأن لكل ملف ثنائي مضمَّن شيفرته المصدرية الكاملة المطابقة.

</div>
