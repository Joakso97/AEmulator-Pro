<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**在现代手机上模拟几乎任何 Android 固件 — HTC Sense、TouchWiz、MIUI、AOSP。** [AEmulator](https://github.com/uxazu/AEmulator) 的一个分支，带来更多功能和修复。

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · **🇨🇳 简体中文** · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Pro 直接从你已有的固件文件启动真正的 Android 1.0–17 系统镜像。它用修补过的 QEMU 翻译旧 ARM 代码，模拟内核的 binder，用你手机的 GPU 绘图，并通过 Android 的音频栈播放声音 — 一切都在一个普通应用里运行。本项目几乎完全由 Claude Sonnet 5.5 完成（原版模拟器则由 Opus 5.5 完成）。

## 功能

- 导入几乎任何固件格式：Android-x86 `.iso`、CWM/TWRP ZIP、三星 Odin `.tar.md5`、Google 工厂 `.tgz`、`system.img`、OTA `system.new.dat.br`、摩托罗拉 `.sbf`、sparse 镜像、EDL 镜像、展讯 `.pac`、HTC `.nbh`、HTC `RUU.exe` 以及更多格式
- 厂商界面在兼容时开箱即用：HTC Sense、三星 TouchWiz、MIUI、AOSP
- 通过 GL 桥接的硬件图形、声音、触摸与多点触控、带现代 TLS 代理的网络
- 用于 APK、音乐和照片的共享存储卡文件夹
- **虚拟机屏幕主题与主题编辑器**：导航栏和客体屏幕周围的边框跟随主题 — Original、Sunset、Gingerbread 2.x、Honeycomb 3.x、Holo 4.x 或 Lollipop 5.x — 或你自己在带实时预览的编辑器中制作的主题（按键形状与大小、栏/按键/按下/边框颜色、辉光与光晕、悬浮或扁平栏、边框图片、屏幕圆角）。主题可作为 `.aetheme` 文件分享。应用设置 › 外观 › 虚拟机屏幕主题。
- **整理你的库**：长按 ROM 并上下拖动来重新排序（经典列表和 0.0.0.3 网格），并把 ROM 分组到可开合的命名文件夹 — 把 ROM 拖到文件夹上，或使用其 ⋮ 菜单 › 移动到文件夹
- Material 3 Expressive 界面，支持 19 种语言
- 免费且开源（GPL-3.0）

## 快速开始

1. 从 [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) 下载 APK 并安装。
2. 打开 AEmulator Pro → **添加固件**并选择文件。导入需要几分钟。
3. 按 **启动**。首次开机较慢：系统会优化其应用。
4. ⋮ 菜单提供音量、电源键和日志；⚙️ 按钮打开应用设置和语言。

## 要求

- 64 位 ARM 手机上的 Android 8.0+（arm64-v8a）
- 每个固件约需 1–3 GB 可用空间
- 推荐使用骁龙 SoC，因为 Mali GPU 存在若干问题

## 从源码构建

经过测试的应用 release 构建使用 JDK 21 和 Android SDK 36。参见[构建/源码说明](../build-source.md)，包括原生工具链细节与签名。

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 致谢

AEmulator Pro 源自[原作者](https://t.me/istratiit_ech)的 HTC Desire HD 和 HTC One M7 模拟器 — 他们的引擎让本项目成为可能。

这是 [uxazu/AEmulator](https://github.com/uxazu/AEmulator) 的修改分支，另外还使用了 [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) 的许多功能。

## 链接

- 我的分支: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- 上游: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset（众多功能，特别致谢）: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- 作者: [somerandomusername2](https://github.com/somerandomusernam2)
- 原作者: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 许可证

GPL-3.0。Android、商标和固件归其各自所有者所有。

参见[按日期记录的修改声明](../../NOTICE.md)和[未完成的源码/许可审计](../license-audit.md)。GPL 标签并不保证每个捆绑的预构建文件都有完整对应的源码。
