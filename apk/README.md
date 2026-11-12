# APK

빌드한 설치 파일을 두는 자리입니다.

| 파일 | 내용 |
|---|---|
| `missedsentence-release.apk` | 놓친문장 앱 |
| `navprompt-release.apk` | 시연용 안내 음성 앱 |

만드는 법은 저장소 첫 화면의 README에 있습니다. `.\gradlew.bat assembleRelease` 로 빌드한 뒤 `.\scripts\collect-apk.ps1` 을 실행하면 이 폴더에 모입니다.
