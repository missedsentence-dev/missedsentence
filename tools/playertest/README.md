# 재생기 검사와 앱 흐름 검사

에뮬레이터 없이 PC의 JVM에서 실제 소스를 돌려 보는 검사입니다.
`fake` 폴더에는 이름만 같은 가짜 Android 클래스가, `fakecar` 폴더에는 차량 값과 내비 연결을 대신하는 가짜 클래스가 들어 있습니다.
가짜 시계가 시간을 대신 흘려보내므로 3분짜리 장면도 바로 끝납니다. 이 폴더는 Gradle 빌드에 들어가지 않습니다.

## 실행
```bash
tools/check.sh
```
따로 돌리려면 다음과 같이 합니다.
```bash
J=app/src/main/java/com/missedsentence/car
kotlinc $J/player/SentencePlayer.kt $J/content/Pack.kt tools/playertest/fake/*.kt \
  tools/playertest/PlayerScenarios.kt -include-runtime -d player-test.jar
java -jar player-test.jar

kotlinc $J/Controller.kt $J/core/*.kt $J/player/SentencePlayer.kt $J/content/Pack.kt \
  tools/playertest/fake/*.kt tools/playertest/fakecar/*.kt \
  tools/playertest/ControllerScenarios.kt -include-runtime -d controller-test.jar
java -Dassets=app/src/main/assets -jar controller-test.jar
```

## 재생기 검사가 보는 것
1. 문장을 차례로 재생하고 마지막 문장에서 멈춤
2. 문장이 끝나는 자리에서 미룸, 붙잡는 동안 오디오 포커스 유지, 풀 때 소리가 1초에 걸쳐 커짐
3. 문장이 길면 끊고, 풀리면 그 문장 처음부터
4. 신호 대기 중 다시 재생, 출발 순간 끊음
5. 포커스를 잠시 잃으면 멈추고 돌아오면 그 문장 처음부터, 다른 앱이 재생을 시작하면 멈춤
6. 핸들 이전 버튼은 한 문장 다시
7. 다시 들을 문장부터 이어 듣기
8. 앞뒤 문장과 함께 다시 듣기
9. 처음부터 다시 듣기, 녹음이 없는 묶음의 음성 합성과 소리 없는 진행
10. 예상보다 문장이 길면 약속한 시간에 끊음
11. 붙잡힌 동안의 멈춤과 재생

## 앱 흐름 검사가 보는 것
1. 실제 pack.json 읽기와 시작 상태, 지원 확인 결과
2. 장면 좌회전과 신호 대기: 미룸, 대기 중 재생, 출발 순간 끊음, 회전 뒤 재생, 오늘 멈춘 이유 집계
3. 같은 장면에서 브레이크를 밟고 있다가 뗌
4. 규칙 끔 비교
5. 장면 어린이보호구역과 우회전
6. 기본 모드: 가상 속도 20과 60, 차로 변경 예산, 다시 들을 문장, 출발할 때 이어 듣기, 기어 P에서 풀기
7. 실제 내비 이벤트가 들어오는 통로로 넣은 회전 안내
8. 이야기 묶음의 앞뒤 문장 듣기, 다시 켰을 때 남아 있는 값, 다시 찍기
9. 장면 출근길 전체

## 이 검사가 보지 못하는 것
소리가 실제로 나는지, 화면 배치, Pleos SDK의 실제 값 표기, 에뮬레이터의 오디오 포커스 동작은 에뮬레이터에서 확인해야 합니다.
