# Release 빌드는 난독화를 끈 상태입니다(isMinifyEnabled = false).
# 난독화를 켜는 경우 Pleos SDK를 리플렉션으로 조회하므로 아래 규칙이 필요합니다.
-keep class ai.pleos.playground.** { *; }
