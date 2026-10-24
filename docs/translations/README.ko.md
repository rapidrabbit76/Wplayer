<div align="center">

<img src="../icon.svg" width="96" alt="Wplayer" />

# Wplayer

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84)
[![GPL v3+](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](../../LICENSE)

[English](../../README.md) · 한국어

</div>

libmpv 기반 Android 동영상 플레이어입니다. 기기에 저장된 영상이나 NAS의 영상을 재생할 수 있습니다.
SMB, NFS, FTP, WebDAV를 지원하며 폰, 태블릿, Android TV에서 사용할 수 있습니다.

## 다운로드

[GitHub Releases](https://github.com/rapidrabbit76/Wplayer/releases/latest)에서 APK를 받으세요.
Android 10 이상, 64비트·32비트 ARM 기기를 지원합니다.

Android TV에서는 `adb install`이나 사이드로딩 앱으로 설치할 수 있습니다.
설치 후에는 설정 → 업데이트에서 새 버전을 확인할 수 있습니다.

## 스크린샷

<img src="../screenshots/player.jpg" width="100%" alt="동영상 재생" />

<table>
  <tr>
    <td width="50%"><img src="../screenshots/browser.jpg" alt="폴더 탐색" /></td>
    <td width="50%"><img src="../screenshots/settings.jpg" alt="설정" /></td>
  </tr>
</table>

영상: [사이버펑크: 엣지러너 공식 예고편](https://www.youtube.com/watch?v=ax5YUmkWf_Y), Netflix.

## 기능

- libmpv, FFmpeg, dav1d를 통한 하드웨어·소프트웨어 디코딩
- SMB 2/3, NFSv3, FTP/FTPS, WebDAV, 기기 저장소
- 폴더 목록·격자·다중 열 보기, 파일 이름 검색
- 이어보기, 재생목록, 무작위 재생, 반복, A-B 반복
- 스와이프로 탐색·밝기·볼륨 조절, 더블탭으로 건너뛰기, 길게 눌러 배속 재생
- 탐색 중 프레임 미리보기
- ASS·SRT·SMI 자막, 인코딩 감지, 자막 모양과 싱크 조절
- whisper.cpp를 이용한 기기 내 자막 생성
- HDR 톤 매핑, FSR·RAVU·FSRCNNX·Anime4K 업스케일링
- 백그라운드 오디오, 화면 속 화면, 헤드셋 버튼, TV 리모컨
- 네트워크 캐시, 다운로드 이어받기, 기기 인증으로 폴더 잠금
- 한국어·영어, 다크·완전한 검정·배경화면 색상 테마

## 빌드

JDK 21, Android SDK 플랫폼 37, NDK `30.0.16248370`, stable Rust, `cargo-ndk`가 필요합니다.
Rust 타깃은 `aarch64-linux-android`와 `armv7-linux-androideabi`를 추가하세요.

네이티브 라이브러리 빌드에는 `meson`, `ninja`, `cmake`, `nasm`, `pkg-config`, `git`, `libclang`이 필요합니다.
Ubuntu에서는 `sudo apt-get install ninja-build nasm pkg-config cmake libclang-dev`와 `pipx install meson`으로 설치합니다.

```sh
source ./env.sh
native/build.sh
./gradlew :app:assembleDebug
```

`env.sh`의 SDK·JDK 경로를 환경에 맞게 설정하세요.
Rust 코어 빌드와 Kotlin 바인딩 생성은 Gradle이 처리합니다.
네이티브 라이브러리 버전은 [`native/versions.env`](../../native/versions.env)에서 관리합니다.

릴리스 서명은 저장소 루트의 `keystore.properties`에 `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`를 설정합니다. 이 파일은 커밋하지 않습니다. 파일이 없으면 디버그 키로 서명합니다.

## 기여

개발, 테스트, 번역 방법은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 참고하세요.
버그는 [Issues](https://github.com/rapidrabbit76/Wplayer/issues)에,
보안 문제는 [SECURITY.md](../../SECURITY.md)에 안내된 방법으로 알려 주세요.

## 라이선스

[GPL-3.0-or-later](../../LICENSE). 외부 라이브러리 라이선스는
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)와 설정 → 정보에서 확인할 수 있습니다.
