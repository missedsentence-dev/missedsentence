# 에뮬레이터에서 앱이 어떻게 보이고 차량 값이 어떻게 읽히는지 확인 자료를 모읍니다.
# 화면 캡처와 기록 파일을 모아 프로젝트 폴더에 missed_check.zip 으로 묶습니다.
# 위치와 계정 정보는 담기지 않습니다.
# 실행: Android Studio의 Terminal에서
#       powershell -ExecutionPolicy Bypass -File scripts\collect-info.ps1
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
. "$PSScriptRoot\adb-common.ps1"
$out = Join-Path $root "check_result"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Mark([string]$name) {
    & $adb shell log -t MissedCheck $name 2>&1 | Out-Null
}
function Shot([string]$name) {
    & $adb shell screencap -p /data/local/tmp/ms_shot.png 2>&1 | Out-Null
    & $adb pull /data/local/tmp/ms_shot.png (Join-Path $out "$name.png") 2>&1 | Out-Null
    Write-Host "    화면 저장: $name.png"
}
function Dump([string]$name, [string[]]$cmd) {
    (& $adb @cmd 2>&1 | Out-String) | Out-File -Encoding utf8 (Join-Path $out "$name.txt")
}
function Ask([string]$step, [string]$msg) {
    Write-Host ""
    Write-Host $msg -ForegroundColor Cyan
    Read-Host "    다 했으면 이 창을 한 번 클릭하고 Enter" | Out-Null
    Mark "STEP_$step"
}

Write-Host "확인 자료를 모읍니다. 안내가 나올 때마다 그대로 하고 Enter를 누르세요. 모두 일곱 번입니다."
Write-Host "재생은 한 번 켜면 끝날 때까지 멈추지 마세요." -ForegroundColor Yellow

# 차량 기록이 금방 밀려나지 않도록 기록을 처음부터 끝까지 파일로 받아 둡니다.
& $adb logcat -G 16M 2>&1 | Out-Null
& $adb logcat -c 2>&1 | Out-Null
$stream = $null
try {
    $stream = Start-Process -FilePath $adb -ArgumentList @("logcat", "-v", "time") -RedirectStandardOutput (Join-Path $out "logcat_stream.txt") -WindowStyle Hidden -PassThru -ErrorAction Stop
} catch {
    Write-Host "    기록을 실시간으로 받지 못해 마지막에 한꺼번에 받습니다."
}

& $adb shell am start -n com.missedsentence.car/.ui.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 2
Mark "START"

Ask "1" "[1/7] 크롬의 차량 조작 화면에서 기어가 P인지 확인하세요. 가상 차량 화면의 놓친문장에서 '지금 듣는 것' 칸의 초록색 '재생'을 누르세요. 영어 소리가 나면 Enter."
Shot "1a_P_playing"
Dump "1_media_session" @("shell", "dumpsys", "media_session")
Start-Sleep -Seconds 8
Shot "1b_P_playing_8s"

Ask "2" "[2/7] 재생은 그대로 두고, 크롬에서 방향지시등을 Blink Left 로 켜세요. 5초 동안 소리가 어떻게 되는지 들은 뒤 Enter."
Shot "2_turn_left"

Ask "3" "[3/7] 방향지시등을 Off 로 끄세요. 5초 동안 소리가 다시 나는지 들은 뒤 Enter."
Shot "3_turn_off"

Ask "4" "[4/7] 크롬에서 기어를 D로 바꾸세요. 10초 동안 소리가 계속 나는지 들은 뒤 Enter. 그다음 20초쯤 차량 화면이 저절로 바뀌니 건드리지 마세요."
Shot "4a_D_app"
Dump "4_activity_D" @("shell", "dumpsys", "activity", "activities")
# 핸들의 이전 버튼
Mark "KEY_PREVIOUS"
& $adb shell input keyevent 88 2>&1 | Out-Null
Start-Sleep -Seconds 4
# 홈 화면 왼쪽 아래의 미디어 칸을 눌러 봅니다.
Mark "TAP_HOME_MEDIA"
& $adb shell input tap 250 1210 2>&1 | Out-Null
Start-Sleep -Seconds 4
Shot "4b_D_after_tap_media"
# 차량 기본 미디어 화면을 열어 봅니다.
Mark "MEDIA_TEMPLATE"
(& $adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE 2>&1 | Out-String) | Out-File -Encoding utf8 (Join-Path $out "4_media_template_result.txt")
Start-Sleep -Seconds 4
Shot "4c_D_media_template"
Dump "4_media_session_D" @("shell", "dumpsys", "media_session")
Mark "BACK_TO_APP"
& $adb shell am start -n com.missedsentence.car/.ui.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 3
Shot "4d_D_app_again"

Ask "5" "[5/7] 기어 D 그대로, ACCEL을 눌러 속도를 40 넘게 올리세요. 그 속도에서 방향지시등 Blink Left 를 5초 켰다가 Off 로 끄세요. 그다음 BRAKE로 속도를 0까지 내린 뒤 Enter."
Shot "5_after_drive"

Ask "6" "[6/7] BRAKE를 누른 채 기어를 P로 바꾸세요. 안 바뀌면 N을 먼저 눌렀다가 P를 누르세요. 그래도 안 바뀌면 그대로 Enter."
& $adb shell am start -n com.missedsentence.car/.ui.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 3
Shot "6_P_again"

Ask "7" "[7/7] 놓친문장 화면 오른쪽 '시연 도구'의 장면에서 '좌회전과 신호 대기'를 누르고 곧바로 Enter. 그다음 1분 동안 화면과 소리를 지켜보세요. 기어가 P가 아니라서 버튼이 안 보이면 그냥 Enter."
for ($i = 1; $i -le 14; $i++) {
    Shot ("7_scene_{0:d2}" -f $i)
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "기록을 모으는 중입니다."
Mark "END"
Dump "media_session_end" @("shell", "dumpsys", "media_session")
Dump "car_media" @("shell", "dumpsys", "car_service", "--services", "CarMediaService")
Dump "car_state" @("shell", "dumpsys", "car_service", "--services", "CarDrivingStateService", "CarUxRestrictionsManagerService", "CarPackageManagerService")
Dump "audio" @("shell", "dumpsys", "audio")
Dump "permissions" @("shell", "dumpsys", "package", "com.missedsentence.car")
Start-Sleep -Seconds 1
if ($null -ne $stream -and -not $stream.HasExited) { Stop-Process -Id $stream.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
$streamFile = Join-Path $out "logcat_stream.txt"
if (-not (Test-Path $streamFile) -or (Get-Item $streamFile).Length -lt 2000) {
    # 실시간으로 받지 못했으면 차량에 남아 있는 기록이라도 받습니다.
    & $adb logcat -d -v time 2>&1 | Out-File -Encoding utf8 $streamFile
}
if (Test-Path $streamFile) {
    Select-String -Path $streamFile -Pattern "MissedSentence|MissedCheck|AndroidRuntime" -Encoding UTF8 | ForEach-Object { $_.Line } | Out-File -Encoding utf8 (Join-Path $out "app_log.txt")
}

$zip = Join-Path $root "missed_check.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
Write-Host ""
Write-Host "끝났습니다. 아래 파일을 채팅창에 끌어다 놓아 보내 주세요." -ForegroundColor Green
Write-Host "    $zip"
