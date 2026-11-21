<div align="center">

<img src="../../docs/assets/logo-rounded.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**ほぼあらゆる Android ファームウェア — HTC Sense、TouchWiz、MIUI、AOSP — を現代のスマホでエミュレート。** [AEmulator](https://github.com/uxazu/AEmulator) のフォークで、機能追加と修正を多数収録。

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語** · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro は、手持ちのファームウェアファイルから本物の Android 1.0–17 システムイメージをそのまま起動します。古い ARM コードをパッチ済み QEMU で変換し、カーネルの binder をエミュレートし、スマホの GPU で描画し、Android のオーディオスタックで音を再生します — すべて普通のアプリの中で動きます。このプロジェクトはほぼ全体が Claude Sonnet 5.5 で作られました（オリジナルのエミュレーターは Opus 5.5 で）。

## 機能

- ほぼあらゆるファームウェア形式を取り込み：Android-x86 `.iso`、CWM/TWRP ZIP、Samsung Odin `.tar.md5`、Google factory `.tgz`、`system.img`、OTA `system.new.dat.br`、Motorola `.sbf`、sparse イメージ、EDL イメージ、Spreadtrum `.pac`、HTC `.nbh`、HTC `RUU.exe` など多数
- メーカー製スキンは互換性があればそのまま動作：HTC Sense、Samsung TouchWiz、MIUI、AOSP
- GL ブリッジによるハードウェアグラフィックス、サウンド、タッチとマルチタッチ、最新 TLS プロキシ付きネットワーク
- APK・音楽・写真用の共有メモリーカードフォルダー
- **VM 画面テーマとテーマエディター**：ナビゲーションバーとゲスト画面を囲むフレームがテーマに従います — Original、Sunset、Gingerbread 2.x、Honeycomb 3.x、Holo 4.x、Lollipop 5.x — あるいはライブプレビュー付きエディターで作る自作テーマ（キーの形とサイズ、バー/キー/押下/フレームの色、グローとハロー、フローティングまたはフラットなバー、フレーム画像、画面の角丸）。テーマは `.aetheme` ファイルとして共有できます。アプリ設定 <img src="../assets/chevron.svg" height="14" valign="middle"> 外観 <img src="../assets/chevron.svg" height="14" valign="middle"> VM 画面テーマ。
- **ライブラリを整理**：ROM を長押しして上下にドラッグで並べ替え（クラシックリストと 0.0.0.3 グリッド）、開閉できる名前付きフォルダーに ROM をグループ化 — ROM をフォルダーにドロップするか、<img src="../assets/menu.svg" height="14" valign="middle"> メニュー <img src="../assets/chevron.svg" height="14" valign="middle"> フォルダーへ移動を使用
- 19 言語対応の Material 3 Expressive インターフェース
- 無料・オープンソース（GPL-3.0）

## クイックスタート

1. [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) から APK をダウンロードしてインストール。
2. AEmulator Pro を開く <img src="../assets/chevron.svg" height="14" valign="middle"> **ファームウェアを追加** でファイルを選択。取り込みには数分かかります。
3. **起動** を押します。初回起動は遅めです：システムがアプリを最適化します。
4. <img src="../assets/menu.svg" height="14" valign="middle"> メニューで音量・電源ボタン・ログを操作。<img src="../assets/settings.svg" height="14" valign="middle"> ボタンでアプリ設定と言語を開きます。

## 要件

- 64 ビット ARM スマホの Android 8.0+（arm64-v8a）
- ファームウェアごとに約 1–3 GB の空き容量
- Mali GPU にいくつかの問題があるため、Snapdragon SoC を推奨

## ソースからビルド

テスト済みのアプリ release ビルドは JDK 21 と Android SDK 36 を使用します。ネイティブツールチェーンと署名の詳細を含む[ビルド/ソース手順](../build-source.md)を参照してください。

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## クレジット

AEmulator Pro は[原作者](https://t.me/istratiit_ech)の HTC Desire HD と HTC One M7 エミュレーターから生まれました — そのエンジンがこのプロジェクトを可能にしました。

これは [uxazu/AEmulator](https://github.com/uxazu/AEmulator) の改造フォークで、さらに [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) の多くの機能も利用しています。

## リンク

- 自分のフォーク: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- アップストリーム: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset（多くの機能に多大な感謝）: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- 作者: [somerandomusername2](https://github.com/somerandomusernam2)
- 原作者: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## ライセンス

GPL-3.0。Android、商標、ファームウェアは各所有者に帰属します。

[日付付き変更通知](../../NOTICE.md)と[未完了のソース/ライセンス監査](../license-audit.md)を参照。GPL の表示は、同梱のすべてのプリビルトに完全な対応ソースがあることの保証ではありません。
