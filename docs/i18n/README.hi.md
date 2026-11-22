<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी** · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

**पुराने Android फ़र्मवेयर — HTC Sense, TouchWiz, MIUI, AOSP — आधुनिक फ़ोन पर। बिना root, बिना PC।**

</div>

<p align="center"><img src="../../docs/assets/rainbow.svg" width="100%" alt=""/></p>

AEmulator Pro सीधे फ़र्मवेयर फ़ाइल से असली Android 1.0–17+ सिस्टम बूट करता है: रिकवरी ZIP, Odin आर्काइव या Google फ़ैक्टरी इमेज। पुराना ARM कोड संशोधित QEMU से चलता है, कर्नेल का binder एमुलेट होता है, ग्राफ़िक्स फ़ोन के GPU से और आवाज़ Android के ऑडियो सिस्टम से चलती है — सब कुछ एक सामान्य ऐप के भीतर।

## विशेषताएँ

- लगभग हर फ़ॉर्मेट आयात करें: CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google फ़ैक्टरी `.tgz`, `system.img`, OTA `system.new.dat.br`
- कंपनियों के स्किन जैसे हैं वैसे चलते हैं: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- GL ब्रिज से हार्डवेयर ग्राफ़िक्स, आवाज़, टच और मल्टीटच, आधुनिक TLS प्रॉक्सी के साथ नेटवर्क
- APK, संगीत और फ़ोटो के लिए साझा मेमोरी कार्ड फ़ोल्डर
- 18 भाषाओं में Material 3 Expressive इंटरफ़ेस
- मुफ़्त और ओपन सोर्स (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## जल्दी शुरुआत

1. [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) से APK डाउनलोड करके इंस्टॉल करें।
2. AEmulator Pro खोलें → **फ़र्मवेयर जोड़ें** और फ़ाइल चुनें। आयात में कुछ मिनट लगते हैं।
3. **चलाएँ** दबाएँ। पहला बूट धीमा होता है: सिस्टम ऐप्स ऑप्टिमाइज़ करता है।
4.  मेनू में वॉल्यूम, पावर बटन और लॉग;  से सेटिंग्स और भाषा।

## आवश्यकताएँ

- 64-बिट ARM फ़ोन (arm64-v8a) पर Android 8.0+
- प्रति फ़र्मवेयर लगभग 1–3 GB खाली जगह
- नया Snapdragon / Dimensity / Tensor सुझाया जाता है

## यह कैसे काम करता है

हर गेस्ट प्रोसेस संशोधित यूज़र-मोड QEMU में चलता है। एक binder डेमन कर्नेल ड्राइवर की जगह लेता है, GL ब्रिज OpenGL ES कॉल GPU तक भेजता है, और छोटी गेस्ट लाइब्रेरी (ऑडियो HAL, audio policy रैपर, LD_PRELOAD शिम) निर्माताओं के कोड को एमुलेटर के अनुकूल बनाती हैं। इम्पोर्टर फ़र्मवेयर पढ़ता है, boot इमेज में init स्क्रिप्ट ढूँढता है और सेवाओं की शुरुआत की योजना बनाता है।

## सोर्स से बनाएँ

ऐप का परीक्षित रिलीज़ बिल्ड JDK 21 और Android SDK 36 का उपयोग करता है। नेटिव टूलचेन और साइनिंग के विवरण के साथ [बिल्ड/सोर्स निर्देश](../build-source.md) देखें। **विरासत में मिले इंजन प्रीबिल्ट बाइनरी के सोर्स और बिल्ड की पूरी उत्पत्ति अभी सत्यापित नहीं है**; [ऑडिट](../license-audit.md) देखें।

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## आभार

AEmulator Pro [मूल लेखक](https://t.me/istratiit_ech) के HTC Desire HD और HTC One M7 एमुलेटर से विकसित हुआ — उनके इंजन के बिना यह प्रोजेक्ट नहीं होता।

यह [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) का संशोधित फ़ोर्क है, जो स्वयं [uxazu/AEmulator](https://github.com/uxazu/AEmulator) का संशोधित फ़ोर्क है।

## लिंक

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- मेरा फ़ोर्क: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- अपस्ट्रीम: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- मूल: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- लेखक: [somerandomusername2](https://github.com/somerandomusernam2)
- मूल लेखक: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## लाइसेंस

GPL-3.0। Android, ट्रेडमार्क और फ़र्मवेयर उनके स्वामियों के हैं।

[दिनांकित संशोधन सूचनाएँ](../../NOTICE.md) और [लंबित सोर्स/लाइसेंस ऑडिट](../license-audit.md) देखें। GPL लेबल इस बात का प्रमाणपत्र नहीं है कि हर शामिल प्रीबिल्ट बाइनरी का पूरा मेल खाता सोर्स मौजूद है।
