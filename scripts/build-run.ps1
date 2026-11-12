# 빌드, 설치, 실행, Virtual Console 연결까지 한 번에 합니다.
# 가상 차량(Pleos Connect 에뮬레이터)을 먼저 켜 두세요.
# 실행: Android Studio의 Terminal에서
#       powershell -ExecutionPolicy Bypass -File scripts\build-run.ps1
#       다시 빌드하지 않고 설치와 실행만 하려면 끝에 -SkipBuild 를 붙입니다.
param([switch]$SkipBuild)
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
. "$PSScriptRoot\adb-common.ps1"

function Stop-Here([string]$msg) {
    Write-Host ""
    Write-Host "멈춤: $msg" -ForegroundColor Yellow
    exit 1
}

# 1. Gradle이 쓸 JDK 찾기. Gradle 8.10.2는 JDK 25에서 돌지 않아 17이나 21이 필요합니다.
$jdk = $null
$jdkRoot = Join-Path $env:USERPROFILE ".jdks"
if (Test-Path $jdkRoot) {
    $jdk = Get-ChildItem $jdkRoot -Directory | Where-Object { $_.Name -match '-(21|17)(\.|$)' } | Sort-Object Name -Descending | Select-Object -First 1
}
if ($null -eq $jdk) {
    Stop-Here "JDK 21을 찾지 못했습니다. Android Studio의 Settings > Build Tools > Gradle > Gradle JDK > Download JDK 에서 21을 받아 주세요."
}
$env:JAVA_HOME = $jdk.FullName
Write-Host "[1/5] JDK: $($jdk.FullName)"

# Android Studio에서 이 폴더를 연 적이 없으면 SDK 위치 파일이 없으므로 기본 위치를 알려 줍니다.
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path (Join-Path $root "local.properties")) -and (Test-Path $sdk)) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
}

# 2. 빌드
if (-not $SkipBuild) {
    Write-Host "[2/5] 빌드합니다. 처음에는 10분 넘게 걸릴 수 있습니다. 자세한 기록은 build_log.txt 에 남습니다."
    & .\gradlew.bat assembleDebug --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $root "build_log.txt") | ForEach-Object {
        $line = "$_"
        if ($line -match '^> Task .*(compile|package|assemble)' -or $line -match 'BUILD|FAILED|^e: |error:|What went wrong') { Write-Host "    $line" }
    }
    if ($LASTEXITCODE -ne 0) { Stop-Here "빌드가 실패했습니다. 프로젝트 폴더의 build_log.txt 파일을 보내 주세요." }
} else {
    Write-Host "[2/5] 빌드는 건너뜁니다."
}
$appApk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$navApk = Join-Path $root "navprompt\build\outputs\apk\debug\navprompt-debug.apk"
if (-not (Test-Path $appApk)) { Stop-Here "빌드 결과 파일이 없습니다: $appApk" }

# 3. 가상 차량이 켜져 있는지
$devices = (& $adb devices 2>&1 | Out-String) -split "`n" | Where-Object { $_ -match '\sdevice\s*$' }
if (-not $devices) { Stop-Here "켜져 있는 가상 차량이 없습니다. Device Manager에서 Pleos Connect v2.1.0 을 켜고, 차량 화면이 다 뜬 뒤에 다시 실행해 주세요. 다시 실행할 때는 끝에 -SkipBuild 를 붙이면 빠릅니다." }
Write-Host "[3/5] 가상 차량 연결 확인"

# 4. 설치
Write-Host "[4/5] 앱을 설치합니다."
$r1 = (& $adb install -r $appApk 2>&1 | Out-String)
if ($r1 -notmatch "Success") { Write-Host $r1; Stop-Here "놓친문장 앱 설치가 실패했습니다. 위에 나온 글자를 복사해 보내 주세요." }
if (Test-Path $navApk) {
    $r2 = (& $adb install -r $navApk 2>&1 | Out-String)
    if ($r2 -notmatch "Success") { Write-Host $r2; Write-Host "    시연용 안내 음성 앱은 설치하지 못했습니다. 놓친문장 앱은 그대로 쓸 수 있습니다." }
}
# 권한 창을 일일이 누르지 않도록 미리 허용합니다. 실패해도 앱에서 다시 묻습니다.
# 차량은 운전자 프로필 사용자로 돌아가므로, 기본 사용자가 아니라 지금 쓰는 사용자에게 허용해야 합니다.
$user = ((& $adb shell am get-current-user 2>&1 | Out-String).Trim())
if ($user -notmatch '^[0-9]+$') { $user = "0" }
foreach ($perm in @("android.permission.POST_NOTIFICATIONS", "android.car.permission.CAR_SPEED")) {
    & $adb shell pm grant --user $user com.missedsentence.car $perm 2>&1 | Out-Null
}

# 5. 실행과 Virtual Console 연결. 새로 설치한 앱으로 깨끗하게 다시 띄웁니다.
& $adb shell am force-stop com.missedsentence.car 2>&1 | Out-Null
& $adb shell am start -n com.missedsentence.car/.ui.MainActivity 2>&1 | Out-Null
& $adb forward tcp:8088 tcp:8088 2>&1 | Out-Null
Write-Host "[5/5] 실행했습니다."
Write-Host ""
Write-Host "끝났습니다. 가상 차량 창에 놓친문장이 떠 있어야 합니다." -ForegroundColor Green
Write-Host "차량 조작 화면은 크롬에서 http://127.0.0.1:8088 입니다."
