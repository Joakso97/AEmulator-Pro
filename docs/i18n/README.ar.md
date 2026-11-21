<div dir="rtl">

<div align="center">

<img src="../../docs/assets/logo-rounded.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**حاكِ تقريبًا أي برنامج ثابت لأندرويد — HTC Sense، TouchWiz، MIUI، AOSP — على هاتف حديث.** نسخة متفرعة من [AEmulator](https://github.com/uxazu/AEmulator) مع مزيد من الميزات والإصلاحات.

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية** · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

يقلع AEmulator Pro بصورة نظام أندرويد 1.0–17 حقيقية مباشرة من ملف برنامج ثابت لديك بالفعل. يترجم شفرة ARM القديمة عبر QEMU معدّل، ويحاكي binder النواة، ويرسم بمعالج رسومات هاتفك ويشغّل الصوت عبر نظام صوت أندرويد — كل ذلك داخل تطبيق عادي. صُنع هذا المشروع بالكامل تقريبًا باستخدام Claude Sonnet 5.5 (والمحاكي الأصلي باستخدام Opus 5.5).

## الميزات

- استيراد تقريبًا أي صيغة برنامج ثابت: Android-x86 `.iso`، CWM/TWRP ZIP، Samsung Odin `.tar.md5`، Google factory `.tgz`، `system.img`، OTA `system.new.dat.br`، Motorola `.sbf`، صور sparse، صور EDL، Spreadtrum `.pac`، HTC `.nbh`، HTC `RUU.exe` وغيرها الكثير
- واجهات الشركات المصنّعة تعمل كما هي إن كانت متوافقة: HTC Sense، Samsung TouchWiz، MIUI، AOSP
- رسوميات عتادية عبر جسر GL، وصوت، ولمس ولمس متعدد، وشبكة عبر وكيل TLS حديث
- مجلد بطاقة ذاكرة مشترك لملفات APK والموسيقى والصور
- **سمات شاشة الجهاز الافتراضي ومحرر السمات**: يتبع شريط التنقل والإطار حول شاشة الضيف سمةً — Original أو Sunset أو Gingerbread 2.x أو Honeycomb 3.x أو Holo 4.x أو Lollipop 5.x — أو سمتك الخاصة المصنوعة في محرر بمعاينة حية (أشكال المفاتيح وحجمها، ألوان الشريط/المفاتيح/الضغط/الإطار، التوهج والهالة، شريط عائم أو مسطح، صورة الإطار، زوايا شاشة مستديرة). يمكن مشاركة السمات كملفات `.aetheme`. إعدادات التطبيق <img src="../assets/chevron.svg" height="14" valign="middle"> المظهر <img src="../assets/chevron.svg" height="14" valign="middle"> سمة شاشة الجهاز الافتراضي.
- **رتّب مكتبتك**: اضغط مطولًا على ROM واسحبه لأعلى أو لأسفل لإعادة ترتيبه (القائمة الكلاسيكية وشبكة 0.0.0.3)، واجمع ملفات ROM في مجلدات مسماة تفتح وتغلق — أفلت ROM على مجلد أو استخدم قائمة <img src="../assets/menu.svg" height="14" valign="middle"> <img src="../assets/chevron.svg" height="14" valign="middle"> نقل إلى مجلد
- واجهة Material 3 Expressive بـ 19 لغة
- مجاني ومفتوح المصدر (GPL-3.0)

## البداية السريعة

1. نزّل ملف APK من [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) وثبّته.
2. افتح AEmulator Pro ← **إضافة برنامج ثابت** واختر الملف. يستغرق الاستيراد بضع دقائق.
3. اضغط **بدء**. الإقلاع الأول أبطأ: النظام يحسّن تطبيقاته.
4. استخدم قائمة <img src="../assets/menu.svg" height="14" valign="middle"> للصوت وزر الطاقة والسجلات؛ زر <img src="../assets/settings.svg" height="14" valign="middle"> يفتح الإعدادات واللغة.

## المتطلبات

- أندرويد 8.0+ على هاتف ARM ببنية 64 بت (arm64-v8a)
- نحو 1–3 غيغابايت من المساحة الحرة لكل برنامج ثابت
- يوصى بمعالج Snapdragon بسبب عدة مشكلات مع معالجات رسوميات Mali

## البناء من المصدر

بناء الإصدار المختبر للتطبيق يستخدم JDK 21 وAndroid SDK 36. راجع [تعليمات البناء/المصدر](../build-source.md)، بما في ذلك تفاصيل سلسلة الأدوات الأصلية والتوقيع.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## الشكر

نشأ AEmulator Pro من محاكيات HTC Desire HD وHTC One M7 التي صنعها [المطوّر الأصلي](https://t.me/istratiit_ech) — محركه جعل هذا المشروع ممكنًا.

هذه نسخة متفرعة معدلة من [uxazu/AEmulator](https://github.com/uxazu/AEmulator)، تستخدم أيضًا العديد من ميزات [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## روابط

- نسختي المتفرعة: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- المنبع: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (شكر جزيل على العديد من الميزات): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- المؤلف: [somerandomusername2](https://github.com/somerandomusernam2)
- المؤلف الأصلي: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## الترخيص

GPL-3.0. أندرويد والعلامات التجارية والبرامج الثابتة ملك لأصحابها.

راجع [إشعارات التعديل المؤرخة](../../NOTICE.md) و[تدقيق المصدر/التراخيص المعلّق](../license-audit.md). شعار GPL ليس شهادة بأن كل ملف ثنائي مرفق له مصدر مطابق كامل.

</div>
