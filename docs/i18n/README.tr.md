<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Klasik Android yazılımları — HTC Sense, TouchWiz, MIUI, AOSP — modern bir telefonda. Root ve PC gerekmez.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe** · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro gerçek bir Android 1.0–17+ sistemini doğrudan yazılım dosyasından başlatır: recovery ZIP, Odin arşivi veya Google fabrika imajı. Eski ARM kodu değiştirilmiş QEMU ile çevrilir, çekirdeğin binder’ı taklit edilir, grafikler telefonun GPU’sunu, ses Android’in ses altyapısını kullanır — hepsi sıradan bir uygulamanın içinde.

## Özellikler

- Neredeyse her biçimi içe aktarma: CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google fabrika `.tgz`, `system.img`, OTA `system.new.dat.br`
- Üretici arayüzleri olduğu gibi çalışır: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- GL köprüsüyle donanım grafikleri, ses, dokunma ve çoklu dokunma, modern TLS vekilli ağ
- APK, müzik ve fotoğraflar için ortak hafıza kartı klasörü
- 18 dilde Material 3 Expressive arayüz
- Ücretsiz ve açık kaynak (GPL-3.0)
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## Hızlı başlangıç

1. APK’yı [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) sayfasından indirip kurun.
2. AEmulator Pro’ı açın → **Yazılım ekle** ve dosyayı seçin. İçe aktarma birkaç dakika sürer.
3. **Başlat**’a basın. İlk açılış daha uzundur: sistem uygulamaları optimize eder.
4.  menüsü: ses, güç düğmesi ve günlük;  ayarları ve dili açar.

## Gereksinimler

- 64 bit ARM telefonda Android 8.0+ (arm64-v8a)
- Yazılım başına yaklaşık 1–3 GB boş alan
- Önerilen: yeni bir Snapdragon / Dimensity / Tensor

## Nasıl çalışır

Her misafir süreç değiştirilmiş kullanıcı kipi QEMU altında çalışır. Bir binder arka plan programı çekirdek sürücüsünün yerini alır, GL köprüsü OpenGL ES çağrılarını GPU’ya iletir, küçük misafir kütüphaneleri (ses HAL’i, audio policy sarmalayıcısı, LD_PRELOAD katmanı) üretici kodunu emülatöre uyarlar. İçe aktarıcı yazılımı okur, boot imajındaki init betiklerini bulur ve servislerin başlama planını kurar.

## Kaynaktan derleme

Uygulamanın test edilen sürüm derlemesi JDK 21 ve Android SDK 36 kullanır. Yerel araç zinciri ve imzalama ayrıntıları dahil [derleme/kaynak kodu yönergelerine](../build-source.md) bakın. **Devralınan motor ikili dosyalarının kaynak kodu ve derleme kökeni henüz tam olarak doğrulanmamıştır**; bkz. [denetim](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Teşekkürler

AEmulator Pro, [ilk geliştiricinin](https://t.me/istratiit_ech) HTC Desire HD ve HTC One M7 emülatörlerinden doğdu — onun motoru olmadan bu proje olmazdı.

Bu, kendisi de [uxazu/AEmulator](https://github.com/uxazu/AEmulator) deposunun değiştirilmiş bir çatalı olan [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) deposunun değiştirilmiş bir çatalıdır.

## Bağlantılar

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Çatalım: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Üst proje: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Orijinal: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Geliştirici: [somerandomusername2](https://github.com/somerandomusernam2)
- İlk geliştirici: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Lisans

GPL-3.0. Android, markalar ve yazılımlar sahiplerine aittir.

[Tarihli değişiklik bildirimlerine](../../NOTICE.md) ve [açık kalan kaynak/lisans denetimine](../license-audit.md) bakın. GPL etiketi, pakete dahil her ikili dosyanın eksiksiz ve eşleşen kaynak koduna sahip olduğunun belgesi değildir.
