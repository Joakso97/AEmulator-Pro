<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Pro logo"/>

# AEmulator Pro

**거의 모든 Android 펌웨어 — HTC Sense, TouchWiz, MIUI, AOSP — 를 최신 휴대폰에서 에뮬레이트하세요.** 더 많은 기능과 수정이 포함된 [AEmulator](https://github.com/uxazu/AEmulator)의 포크입니다.

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · **🇰🇷 한국어**

</div>

---

AEmulator Pro는 이미 가지고 있는 펌웨어 파일에서 진짜 Android 1.0–17 시스템 이미지를 바로 부팅합니다. 패치된 QEMU로 오래된 ARM 코드를 변환하고, 커널의 binder를 에뮬레이트하며, 휴대폰의 GPU로 그리고 Android의 오디오 스택으로 소리를 재생합니다 — 이 모든 것이 일반 앱 안에서 실행됩니다. 이 프로젝트는 거의 전적으로 Claude Sonnet 5.5로 만들어졌습니다(원본 에뮬레이터는 Opus 5.5로).

## 기능

- 거의 모든 펌웨어 형식 가져오기: Android-x86 `.iso`, CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google factory `.tgz`, `system.img`, OTA `system.new.dat.br`, Motorola `.sbf`, sparse 이미지, EDL 이미지, Spreadtrum `.pac`, HTC `.nbh`, HTC `RUU.exe` 등 다수
- 제조사 스킨은 호환되면 그대로 작동: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- GL 브리지 기반 하드웨어 그래픽, 사운드, 터치와 멀티터치, 최신 TLS 프록시가 있는 네트워크
- APK, 음악, 사진을 위한 공유 메모리 카드 폴더
- **VM 화면 테마와 테마 편집기**: 내비게이션 바와 게스트 화면을 둘러싼 프레임이 테마를 따릅니다 — Original, Sunset, Gingerbread 2.x, Honeycomb 3.x, Holo 4.x 또는 Lollipop 5.x — 또는 실시간 미리보기가 있는 편집기에서 만든 나만의 테마(키 모양과 크기, 바/키/눌림/프레임 색상, 글로우와 헤일로, 떠 있는 또는 평평한 바, 프레임 그림, 둥근 화면 모서리). 테마는 `.aetheme` 파일로 공유할 수 있습니다. 앱 설정 › 모양 › VM 화면 테마.
- **라이브러리 정리**: ROM을 길게 누르고 위아래로 드래그해 순서를 바꾸고(클래식 목록과 0.0.0.3 그리드), 열고 닫을 수 있는 이름 있는 폴더로 ROM을 묶으세요 — ROM을 폴더에 놓거나 ⋮ 메뉴 › 폴더로 이동 사용
- 19개 언어의 Material 3 Expressive 인터페이스
- 무료 및 오픈 소스(GPL-3.0)

## 빠른 시작

1. [Releases](https://github.com/Joakso97/AEmulator-Pro/releases)에서 APK를 다운로드해 설치하세요.
2. AEmulator Pro 열기 → **펌웨어 추가**에서 파일을 선택하세요. 가져오기는 몇 분 걸립니다.
3. **시작**을 누르세요. 첫 부팅은 더 느립니다: 시스템이 앱을 최적화합니다.
4. ⋮ 메뉴로 볼륨, 전원 버튼, 로그를 조작하고 ⚙️ 버튼으로 앱 설정과 언어를 엽니다.

## 요구 사항

- 64비트 ARM 휴대폰의 Android 8.0+(arm64-v8a)
- 펌웨어당 약 1–3GB의 여유 공간
- Mali GPU의 여러 문제로 인해 Snapdragon SoC를 권장

## 소스에서 빌드

테스트된 앱 release 빌드는 JDK 21과 Android SDK 36을 사용합니다. 네이티브 툴체인과 서명 세부 사항을 포함한 [빌드/소스 지침](../build-source.md)을 참고하세요.

```bash
git clone https://github.com/Joakso97/AEmulator-Pro.git
cd AEmulator-Pro
./gradlew copyReleaseApks
```

## 크레딧

AEmulator Pro는 [원작자](https://t.me/istratiit_ech)의 HTC Desire HD 및 HTC One M7 에뮬레이터에서 자라났습니다 — 그 엔진이 이 프로젝트를 가능하게 했습니다.

이것은 [uxazu/AEmulator](https://github.com/uxazu/AEmulator)의 수정 포크이며, 추가로 [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)의 많은 기능을 사용합니다.

## 링크

- 내 포크: [Joakso97/AEmulator-Pro](https://github.com/Joakso97/AEmulator-Pro)
- 업스트림: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- AEmulator-Sunset(많은 기능에 큰 감사): [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- 작성자: [somerandomusername2](https://github.com/somerandomusernam2)
- 원작자: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 라이선스

GPL-3.0. Android, 상표, 펌웨어는 각 소유자에게 귀속됩니다.

[날짜별 수정 고지](../../NOTICE.md)와 [미완료 소스/라이선스 감사](../license-audit.md)를 참고하세요. GPL 표시는 번들된 모든 프리빌트에 완전한 대응 소스가 있다는 인증이 아닙니다.
