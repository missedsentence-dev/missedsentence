#!/usr/bin/env bash
# Gradle 없이 하는 점검: 엔진 시나리오 검사, 재생기 검사, 앱 흐름 검사, 전체 소스 컴파일, 리소스와 콘텐츠 파일 문법 확인.
# 쓰는 법: KOTLINC=/path/kotlinc ANDROID_JAR=/path/android.jar STUBS=/path/stubs tools/check.sh
#   STUBS 에는 Pleos Vehicle SDK, NaviHelper SDK, R 클래스의 빈 껍데기 소스가 들어 있어야 합니다.
#   STUBS 를 주지 않으면 전체 소스 컴파일만 건너뜁니다.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
# 전체 소스 컴파일은 컴파일러 기본 메모리로는 모자랄 수 있습니다.
export JAVA_OPTS="${JAVA_OPTS:--Xmx3g}"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$(mktemp -d)}"
C=app/src/main/java/com/missedsentence/car/core

echo "== 1. 엔진 시나리오 검사"
"$KOTLINC" $C/LoadEngine.kt $C/NavModel.kt $C/DemoScenario.kt $C/SignalReader.kt tools/enginetest/EngineScenarios.kt \
  -include-runtime -d "$OUT/engine-test.jar" 2>&1 | grep -E "error:" || true
java -jar "$OUT/engine-test.jar" 2>&1 | grep -v "^Picked up" | tail -1

J=app/src/main/java/com/missedsentence/car
F="tools/playertest/fake/*.kt"

echo "== 2. 재생기 검사 (실제 SentencePlayer.kt 를 가짜 Android 클래스와 함께)"
"$KOTLINC" $J/player/SentencePlayer.kt $J/content/Pack.kt $F tools/playertest/PlayerScenarios.kt \
  -include-runtime -d "$OUT/player-test.jar" 2>&1 | grep -E "error:" || true
java -jar "$OUT/player-test.jar" 2>&1 | grep -v "^Picked up" | grep -E "실패|검사:"

echo "== 3. 앱 흐름 검사 (실제 Controller.kt, 엔진, 재생기, 묶음 읽기를 가짜 차량 값으로)"
"$KOTLINC" $J/Controller.kt $C/*.kt $J/player/SentencePlayer.kt $J/content/Pack.kt $F tools/playertest/fakecar/*.kt \
  tools/playertest/ControllerScenarios.kt -include-runtime -d "$OUT/controller-test.jar" 2>&1 | grep -E "error:" || true
java -Dassets=app/src/main/assets -jar "$OUT/controller-test.jar" 2>&1 | grep -v "^Picked up" | grep -E "실패|검사:"

if [ -n "${STUBS:-}" ] && [ -n "${ANDROID_JAR:-}" ]; then
  echo "== 4. 전체 소스 컴파일 (app)"
  STDLIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib/kotlin-stdlib.jar"
  "$KOTLINC" -no-stdlib -no-reflect -cp "$ANDROID_JAR:$STDLIB" \
    $(find app/src/main/java -name '*.kt') $(find "$STUBS/pleos" "$STUBS/navi" -name '*.kt') "$STUBS/r-ms/com/missedsentence/car/R.java" \
    -jvm-target 17 -d "$OUT/app" 2>&1 | grep -E "error:|warning:" || true
  echo "app 클래스 $(find "$OUT/app/com/missedsentence" -name '*.class' | wc -l)개"
  echo "== 5. 전체 소스 컴파일 (navprompt)"
  "$KOTLINC" -no-stdlib -no-reflect -cp "$ANDROID_JAR:$STDLIB" $(find navprompt/src/main/java -name '*.kt') \
    -jvm-target 17 -d "$OUT/nav" 2>&1 | grep -E "error:|warning:" || true
  echo "navprompt 클래스 $(find "$OUT/nav" -name '*.class' | wc -l)개"
fi

echo "== 6. 리소스와 콘텐츠 파일"
python3 tools/check_files.py
