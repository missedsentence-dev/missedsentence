# 놓친문장 화면을 띄워 둔 채로 시연용 안내 음성 앱의 안내음을 냅니다.
# 시연용 안내 음성 앱이 설치돼 있어야 합니다.
# 실행: PowerShell에서  .\scripts\nav-prompt.ps1
#       낮추기를 허락하지 않는 요청으로 내려면  .\scripts\nav-prompt.ps1 -Exclusive
param([switch]$Exclusive)
. "$PSScriptRoot\adb-common.ps1"
if ($Exclusive) {
    & $adb shell am broadcast --include-stopped-packages -n com.missedsentence.navprompt/.PromptReceiver --ez exclusive true | Out-Null
} else {
    & $adb shell am broadcast --include-stopped-packages -n com.missedsentence.navprompt/.PromptReceiver | Out-Null
}
Write-Host "안내음을 요청했습니다. 4초 동안 놓친문장이 멈췄다가 그 문장 처음부터 다시 나오는지 보세요."
