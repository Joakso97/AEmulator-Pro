<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Firmware Android cổ điển — HTC Sense, TouchWiz, MIUI, AOSP — trên điện thoại hiện đại. Không root, không cần PC.**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt** · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語](README.ja.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro khởi động một hệ thống Android 1.0–17+ thật trực tiếp từ tệp firmware: ZIP recovery, gói Odin hoặc factory image của Google. Mã ARM cũ được dịch bằng QEMU đã chỉnh sửa, binder của nhân được giả lập, đồ họa dùng GPU điện thoại và âm thanh đi qua hệ thống âm thanh Android — tất cả trong một ứng dụng bình thường.

## ✨ Tính năng

- Nhập gần như mọi định dạng: ZIP CWM/TWRP, Odin `.tar.md5` của Samsung, factory `.tgz` của Google, `system.img`, OTA `system.new.dat.br`
- Giao diện hãng chạy nguyên bản: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Đồ họa phần cứng qua cầu GL, âm thanh, cảm ứng và đa điểm, mạng với proxy TLS hiện đại
- Thư mục thẻ nhớ dùng chung cho APK, nhạc và ảnh
- Giao diện Material 3 Expressive với 18 ngôn ngữ
- Miễn phí và mã nguồn mở (GPL-3.0)
- Guest activity launcher: search installed packages in the ⋮ menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## 🚀 Bắt đầu nhanh

1. Tải APK từ [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) và cài đặt.
2. Mở AEmulator Pro → **Thêm firmware** và chọn tệp. Việc nhập mất vài phút.
3. Nhấn **Chạy**. Lần khởi động đầu chậm hơn: hệ thống tối ưu ứng dụng.
4. Menu ⋮ để chỉnh âm lượng, nút nguồn và nhật ký; ⚙️ mở cài đặt và ngôn ngữ.

## 📋 Yêu cầu

- Android 8.0+ trên điện thoại ARM 64-bit (arm64-v8a)
- Khoảng 1–3 GB trống cho mỗi firmware
- Nên dùng Snapdragon / Dimensity / Tensor đời mới

## ⚙️ Cách hoạt động

Mỗi tiến trình khách chạy dưới QEMU chế độ người dùng đã chỉnh sửa. Một daemon binder thay cho driver nhân, cầu GL chuyển lệnh OpenGL ES tới GPU và các thư viện khách nhỏ (HAL âm thanh, lớp bọc audio policy, shim LD_PRELOAD) điều chỉnh mã của hãng cho hợp với trình giả lập. Trình nhập đọc firmware, tìm script init trong image boot và lập kế hoạch khởi động dịch vụ.

## 🛠️ Biên dịch từ mã nguồn

Bản dựng phát hành đã kiểm thử của ứng dụng dùng JDK 21 và Android SDK 36. Xem [hướng dẫn dựng/mã nguồn](../build-source.md), gồm chi tiết về toolchain native và ký ứng dụng. **Các tệp nhị phân dựng sẵn của engine được kế thừa vẫn chưa có nguồn gốc mã nguồn/bản dựng đầy đủ được xác minh**; xem [bản kiểm toán](../license-audit.md).

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 🙏 Ghi công

AEmulator Pro phát triển từ trình giả lập HTC Desire HD và HTC One M7 của [tác giả gốc](https://t.me/istratiit_ech) — không có engine của anh ấy thì không có dự án này.

Đây là bản fork đã chỉnh sửa của [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), vốn cũng là bản fork đã chỉnh sửa của [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Liên kết

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- Fork của tôi: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Bản gốc: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Tác giả: [somerandomusername2](https://github.com/somerandomusernam2)
- Tác giả gốc: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Giấy phép

GPL-3.0. Android, nhãn hiệu và firmware thuộc về chủ sở hữu.

Xem [thông báo sửa đổi có ghi ngày](../../NOTICE.md) và [bản kiểm toán mã nguồn/giấy phép còn tồn đọng](../license-audit.md). Nhãn GPL không phải là xác nhận rằng mọi tệp nhị phân dựng sẵn đều có mã nguồn khớp đầy đủ.
