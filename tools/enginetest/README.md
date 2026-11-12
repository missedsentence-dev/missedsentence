# 엔진 시나리오 검사

멈춤 규칙을 담은 `core/LoadEngine.kt` 는 Android에 기대지 않는 순수 Kotlin 코드입니다.
그래서 에뮬레이터 없이 PC의 JVM에서 주행 장면을 0.2초 간격으로 흘려 넣어 결정이 맞는지 볼 수 있습니다.

## 실행
```bash
C=app/src/main/java/com/missedsentence/car/core
kotlinc $C/LoadEngine.kt $C/NavModel.kt $C/DemoScenario.kt $C/SignalReader.kt \
  tools/enginetest/EngineScenarios.kt -include-runtime -d engine-test.jar
java -jar engine-test.jar
```
마지막 줄에 `엔진 시나리오 검사: 통과 N, 실패 0` 이 나오면 됩니다. `tools/check.sh` 가 이 검사와 파일 문법 확인을 한 번에 합니다.

## 검사하는 장면
1. 좌회전 10초 전 미룸, 신호 대기 5초 뒤 다시 재생, 브레이크를 떼는 순간 끊음, 회전 뒤 재생
2. 우회전 6초 전 미룸, 회전 뒤 조향 안정 2초
3. 어린이보호구역 진입 전 미룸과 진입 순간 끊음, 벗어날 때만 재생
4. 차로 변경 예산 30분에 2번과 간격 90초, 방향지시등을 켠 속도로 회전과 차로 변경 가르기
5. 급제동 끊음과 3초 뒤 재생
6. 다음 멈춤이 30초 안이면 합치기
7. 민감도 낮음: 8초 전, 예산 없음
8. 기본 모드: 길안내 없이 방향지시등과 속도만으로 판단
9. 움직인 시간 60초 상한, 기어 P에서 풀기
10. 시연 출근길 네 장면이 실제 이벤트와 같은 통로로 들어가 같은 결정을 내는지
11. 회전 안내 원문과 주의 지점 원문 읽기, 어린이보호구역 판정 순서
12. Vehicle SDK 값에서 방향지시등, 브레이크 페달, 조향각 읽기
