# 루멘 (Lumen) — 안드로이드 영상·음악 플레이어

디자인 시안 A "루멘"(Material You 톤의 다크 청록)을 바탕으로 만든 플레이어입니다.
디자인 캔버스: https://claude.ai/artifact/7BpG1oZZHtSywPpDAGzm3a

## 기능

| 영역 | 내용 |
| --- | --- |
| 재생 엔진 | Media3 ExoPlayer + Jellyfin FFmpeg 오디오 디코더(DTS, AC-3, E-AC-3, TrueHD). MKV, MP4, WebM, AVI, MOV, TS/M2TS, FLV, OGG, HLS, DASH, FLAC, MP3, Opus 등 |
| 불러오기 | 내 기기(MediaStore), SMB 2/3, FTP/FTPS, WebDAV(HTTP/HTTPS), NFSv3, 클라우드(Google Drive·OneDrive·Dropbox·Nextcloud 앱의 문서 폴더, SAF), 다른 앱의 "열기" |
| 이어보기 | 3초마다, 일시정지할 때, 항목이 바뀔 때 위치를 저장. 95% 넘게 본 항목은 목록에서 빠짐. 배속과 고른 자막도 함께 기억 |
| 재생목록 | 만들기·이름 바꾸기·삭제, 순서 바꾸기, 전체/무작위 재생, 폴더째 추가, 재생 대기열 편집 |
| 재생 속도 | 0.25–4.0×, 0.05 단위. 기본 속도를 저장할 수 있음 |
| 백그라운드 | MediaSessionService 알림·잠금화면·블루투스 리모컨. 앱을 나가면 영상 디코딩을 끄고 소리만 재생. 화면 속 화면(PiP) |
| 제스처 | 두 번 탭으로 앞뒤 이동, 좌우로 끌어 탐색, 왼쪽 위아래는 밝기, 오른쪽은 볼륨, 화면 잠금 |
| 자막 형식 | SRT, WebVTT, SAMI(.smi, CP949 자동 판별, 다국어 클래스), ASS/SSA, 내장 자막 트랙 |
| 자막 스타일 | 글꼴(내 글꼴 파일 포함), 크기, 굵기, 줄 간격, 글자 색, 외곽선 두께·색, 그림자, 배경 상자 불투명도·색·여백 |
| 자막 위치 | 영상 안 위/아래, **영상 밖** 위/아래(영상을 줄여 자막 띠를 확보), 손가락으로 끌어 놓기, 싱크 ±0.1초/±1초 |
| 자막 검색 | OpenSubtitles(파일 해시 일치 우선) + SubDL, 선호 언어 순 정렬 |
| AI 자막 | 오디오를 16kHz 모노로 뽑아 조용한 지점에서 약 10분 단위로 나눈 뒤 음성 인식. 조각이 끝날 때마다 자막을 바로 보여 줌 |

### AI 자막 엔진

| 엔진 | 요금 | 모델 | 비고 |
| --- | --- | --- | --- |
| Groq | 무료 등급 | whisper-large-v3 / -turbo | 기본값. 429 응답이면 Retry-After를 지켜 다시 보냄 |
| 내 서버 | 무료 | large-v3 등 | faster-whisper-server, whisper.cpp server 같은 OpenAI 호환 서버 |
| OpenAI | 유료 | whisper-1 | 구간 타임스탬프가 있는 모델 |
| Deepgram | 유료(가입 크레딧) | nova-3 | 발화 단위, 용어집을 keyterm으로 전달 |
| ElevenLabs | 유료 | scribe_v1 | 단어 단위 타임스탬프 |

정확도를 높이는 처리:
- 언어 지정, 용어집(인물·고유명사)과 직전 조각의 마지막 대사를 Whisper 프롬프트로 전달
- `no_speech_prob`/`avg_logprob`로 무음 구간에서 지어낸 문장을 거르고, "시청해 주셔서 감사합니다" 같은 흔한 환각 문구를 제거
- 단어 시간이 있으면 그 지점에서 자막을 나누고, 줄마다 최대 글자 수(기본 32자)·두 줄·7초를 넘지 않게 다듬음

## 구조

```
app/src/main/java/com/lumen/player
├── LumenApp.kt              수동 DI(AppContainer)
├── MainActivity.kt          라이브러리·탐색·재생목록·설정
├── data/                    Room(이어보기, 재생목록, 서버), DataStore 설정, Keystore 암호화(SecretBox)
├── remote/                  SMB·FTP·WebDAV·NFS·SAF 파일 시스템 (lumen://<서버id>/<경로>)
├── player/                  PlaybackService, 데이터 소스 라우팅, 재생 화면·재생바·시트
├── subtitle/                파서, 저장소, 오버레이, online/(검색), ai/(추출·STT·정리)
└── ui/                      화면과 공용 컴포넌트, 루멘 테마
```

- 화면은 `PlayerConnection`이 로컬 바인더로 받은 ExoPlayer를 직접 다루고, 알림과 외부 기기는 MediaSession으로 붙습니다.
- 외부·AI 자막은 앱이 직접 파싱해 그립니다. 그래서 싱크 조절, 영상 밖 배치, 생성 중 자막 표시가 가능합니다. 내장 자막 트랙은 Media3 cue를 같은 스타일로 그립니다.
- 서버 비밀번호와 API 키는 Android Keystore AES-GCM 키로 암호화해 저장합니다.

## 빌드

Android Studio(Ladybug 이상) 또는 명령줄:

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 자막 파서·AI 자막 정리·STT 요청 테스트
```

AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 26(Android 8.0).

## 처음 쓸 때 넣어야 하는 키

- 설정 › AI 자막 생성: Groq(https://console.groq.com) 등 공급자 API 키
- 설정 › 온라인 자막 검색: OpenSubtitles API 키(선택: 계정), SubDL API 키

## 현재 한계

- 영상 코덱은 기기의 하드웨어/소프트웨어 디코더에 따릅니다. WMV/RMVB처럼 MediaCodec이 못 여는 형식은 재생되지 않습니다(다음 단계: libVLC 대체 경로).
- SMB는 공유 이름을 직접 입력해야 합니다(공유 목록 자동 탐색 없음).
- Android 11 이상에서 내 기기 영상 옆의 자막 파일은 자동으로 찾지 못할 수 있어, 자막 메뉴의 "파일에서 불러오기"를 씁니다.
- AI 자막의 오디오 추출은 기기의 MediaCodec을 씁니다. 기기가 DTS 등을 해독하지 못하면 추출이 실패합니다.
- 기기 안에서 돌리는 Whisper(whisper.cpp JNI)는 아직 없습니다. 무료로 쓰려면 Groq 무료 등급이나 내 서버를 쓰세요.
