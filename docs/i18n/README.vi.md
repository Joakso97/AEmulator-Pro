<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**Giả lập gần như mọi firmware Android — HTC Sense, TouchWiz, MIUI, AOSP — trên một chiếc điện thoại hiện đại.** Một bản fork của [AEmulator](https://github.com/uxazu/AEmulator) với nhiều tính năng và bản sửa hơn.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · **🇻🇳 Tiếng Việt** · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro khởi động một image hệ thống Android 1.0–17 thật ngay từ tệp firmware bạn đã có. Nó dịch mã ARM cũ bằng QEMU đã vá, giả lập binder của nhân, vẽ bằng GPU của điện thoại và phát âm thanh qua ngăn xếp âm thanh của Android — tất cả chạy trong một ứng dụng bình thường. Dự án này được thực hiện gần như hoàn toàn bằng Claude Sonnet 5.5 (và trình giả lập gốc bằng Opus 5.5).

## Tính năng

- Nhập gần như mọi định dạng firmware: Android-x86 `.iso`, ZIP CWM/TWRP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, image sparse, image EDL, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` và nhiều định dạng khác
- Giao diện của hãng chạy nguyên bản nếu tương thích: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Đồ họa phần cứng qua cầu GL, âm thanh, cảm ứng và đa điểm, mạng với proxy TLS hiện đại
- Thư mục thẻ nhớ dùng chung cho APK, nhạc và ảnh
- **Chủ đề màn hình VM và trình sửa chủ đề**: thanh điều hướng và khung quanh màn hình khách theo một chủ đề — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x hoặc Lollipop 5.x — hoặc chủ đề của riêng bạn, tạo trong trình sửa có xem trước trực tiếp (hình dạng và cỡ phím, màu thanh/phím/nhấn/khung, phát sáng và quầng, thanh nổi hoặc phẳng, ảnh khung, góc màn hình bo tròn). Chủ đề có thể chia sẻ dưới dạng tệp `.aetheme`. Cài đặt ứng dụng › Giao diện › Chủ đề màn hình VM.
- **Sắp xếp thư viện**: nhấn giữ một ROM và kéo lên hoặc xuống để đổi thứ tự (danh sách cổ điển và lưới 0.0.0.3), và nhóm các ROM vào thư mục có tên đóng mở được — thả ROM vào thư mục, hoặc dùng menu ⋮ › Chuyển vào thư mục
- Giao diện Material 3 Expressive với 19 ngôn ngữ
- Miễn phí và mã nguồn mở (GPL-3.0)

## Bắt đầu nhanh

1. Tải APK từ [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) và cài đặt.
2. Mở AEmulator Pro → **Thêm firmware** và chọn tệp. Việc nhập mất vài phút.
3. Nhấn **Bắt đầu**. Lần khởi động đầu chậm hơn: hệ thống tối ưu hóa các ứng dụng của nó.
4. Dùng menu ⋮ cho âm lượng, nút nguồn và nhật ký; nút ⚙️ mở cài đặt và ngôn ngữ.

## Yêu cầu

- Android 8.0+ trên điện thoại ARM 64-bit (arm64-v8a)
- Khoảng 1–3 GB dung lượng trống cho mỗi firmware
- Khuyến nghị SoC Snapdragon, do một số vấn đề với GPU Mali

## Biên dịch từ mã nguồn

Bản build release đã kiểm thử của ứng dụng dùng JDK 21 và Android SDK 36. Xem [hướng dẫn build/mã nguồn](../build-source.md), gồm chi tiết toolchain native và ký.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## Ghi công

AEmulator Pro lớn lên từ các trình giả lập HTC Desire HD và HTC One M7 của [tác giả gốc](https://t.me/istratiit_ech) — engine của họ đã làm nên dự án này.

Đây là bản fork sửa đổi của [uxazu/AEmulator](https://github.com/uxazu/AEmulator), đồng thời dùng nhiều tính năng từ [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset).

## Liên kết

- Fork của tôi: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset (ghi công lớn cho nhiều tính năng): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Tác giả: [somerandomusername2](https://github.com/somerandomusernam2)
- Tác giả gốc: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## Giấy phép

GPL-3.0. Android, nhãn hiệu và firmware thuộc về chủ sở hữu của chúng.

Xem [các thông báo sửa đổi có ngày](../../NOTICE.md) và [bản kiểm toán nguồn/giấy phép còn tồn đọng](../license-audit.md). Nhãn GPL không phải chứng nhận rằng mọi prebuilt đi kèm đều có nguồn đầy đủ tương ứng.
