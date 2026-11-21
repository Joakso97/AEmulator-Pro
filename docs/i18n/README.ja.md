<div align="center">

<img src="../../docs/assets/icon-round.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**懐かしの Android ファームウェア — HTC Sense、TouchWiz、MIUI、AOSP — を最新スマホで。root も PC も不要。**

[<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="16" valign="middle"> English](../../README.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ru.svg" width="16" valign="middle"> Русский](README.ru.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ua.svg" width="16" valign="middle"> Українська](README.uk.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/de.svg" width="16" valign="middle"> Deutsch](README.de.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/fr.svg" width="16" valign="middle"> Français](README.fr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="16" valign="middle"> Español](README.es.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/br.svg" width="16" valign="middle"> Português](README.pt-BR.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/it.svg" width="16" valign="middle"> Italiano](README.it.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/pl.svg" width="16" valign="middle"> Polski](README.pl.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/tr.svg" width="16" valign="middle"> Türkçe](README.tr.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/sa.svg" width="16" valign="middle"> العربية](README.ar.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/ir.svg" width="16" valign="middle"> فارسی](README.fa.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/in.svg" width="16" valign="middle"> हिन्दी](README.hi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/id.svg" width="16" valign="middle"> Indonesia](README.id.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/vn.svg" width="16" valign="middle"> Tiếng Việt](README.vi.md) · [<img src="https://hatscripts.github.io/circle-flags/flags/cn.svg" width="16" valign="middle"> 简体中文](README.zh-CN.md) · **<img src="https://hatscripts.github.io/circle-flags/flags/jp.svg" width="16" valign="middle"> 日本語** · [<img src="https://hatscripts.github.io/circle-flags/flags/kr.svg" width="16" valign="middle"> 한국어](README.ko.md)

</div>

---

AEmulator Pro はファームウェアファイルから本物の Android 1.0〜17+ を直接起動します：リカバリー ZIP、Odin アーカイブ、Google ファクトリーイメージ。古い ARM コードは改良版 QEMU で変換し、カーネルの binder をエミュレートし、描画はスマホの GPU、音声は Android のオーディオを使います。すべて普通のアプリの中で動きます。

## 特長

- ほぼすべての形式をインポート：CWM/TWRP ZIP、Samsung Odin `.tar.md5`、Google ファクトリー `.tgz`、`system.img`、OTA `system.new.dat.br`
- メーカー独自 UI がそのまま動作：HTC Sense、Samsung TouchWiz、MIUI、AOSP
- GL ブリッジによるハードウェア描画、音声、タッチとマルチタッチ、最新 TLS プロキシ付きネットワーク
- APK・音楽・写真用の共有メモリーカードフォルダー
- 18 言語対応の Material 3 Expressive UI
- 無料・オープンソース（GPL-3.0）
- Guest activity launcher: search installed packages in the More menu and launch their main activity
- Import any partition into the QEMU / KitKat guest, including custom mount paths

## クイックスタート

1. [Releases](https://github.com/Joakso97/AEmulator-Pro/releases) から APK をダウンロードしてインストール。
2. AEmulator Pro を開き →**ファームウェアを追加**でファイルを選択。インポートには数分かかります。
3. **起動**をタップ。初回はアプリ最適化のため時間がかかります。
4.  メニューで音量・電源ボタン・ログ、 で設定と言語。

## 動作要件

- 64 ビット ARM 端末（arm64-v8a）の Android 8.0+
- ファームウェア 1 つにつき約 1〜3 GB の空き
- 最新の Snapdragon / Dimensity / Tensor を推奨

## 仕組み

各ゲストプロセスは改良版ユーザーモード QEMU で動きます。binder デーモンがカーネルドライバーの代わりを務め、GL ブリッジが OpenGL ES 呼び出しを GPU に転送し、小さなゲストライブラリ（オーディオ HAL、audio policy ラッパー、LD_PRELOAD シム）がメーカーのコードをエミュレーターに合わせます。インポーターはファームウェアを読み、boot イメージの init スクリプトからサービスの起動計画を作ります。

## ソースからビルド

動作確認済みのアプリのリリースビルドは JDK 21 と Android SDK 36 を使用します。ネイティブツールチェーンや署名の詳細は[ビルド／ソース手順](../build-source.md)をご覧ください。**継承したエンジンのビルド済みバイナリについては、ソースコードとビルドの出自がまだ完全には検証されていません**。[監査ドキュメント](../license-audit.md)を参照してください。

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## クレジット

AEmulator Pro は[オリジナル作者](https://t.me/istratiit_ech)の HTC Desire HD・HTC One M7 エミュレーターから生まれました。そのエンジンなしにこのプロジェクトはありません。

これは [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) の改変フォークであり、そのリポジトリ自体も [uxazu/AEmulator](https://github.com/uxazu/AEmulator) の改変フォークです。

## リンク

- AEmulator Plus: [GitHub](https://github.com/somerandomusernam2/AEmulator-Plus) · [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)
- 私のフォーク: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- アップストリーム: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- オリジナル: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- 作者: [somerandomusername2](https://github.com/somerandomusernam2)
- オリジナル作者: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## ライセンス

GPL-3.0。Android、商標、ファームウェアは各所有者に帰属します。

[日付入りの改変通知](../../NOTICE.md)と[未完了のソース／ライセンス監査](../license-audit.md)をご覧ください。GPL の表記は、同梱の各ビルド済みバイナリに対応する完全なソースがあることを保証するものではありません。
