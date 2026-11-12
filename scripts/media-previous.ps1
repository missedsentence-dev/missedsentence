# 핸들의 '이전' 버튼을 누른 것과 같은 미디어 키를 보냅니다. 놓친문장은 이 키를 '한 문장 다시'로 받습니다.
# 실행: PowerShell에서  .\scripts\media-previous.ps1
# 실행이 막히면 먼저:  Set-ExecutionPolicy -Scope Process Bypass
. "$PSScriptRoot\adb-common.ps1"
& $adb shell input keyevent 88   # KEYCODE_MEDIA_PREVIOUS
Write-Host "이전 버튼 키를 보냈습니다. 앱의 시연 띠에 '핸들의 이전 버튼'이 찍히는지 보세요."
