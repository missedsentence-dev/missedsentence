# Android Studio를 켜지 않고 가상 차량(Pleos Connect 에뮬레이터)만 켭니다.
# Android Studio가 꺼져 있으면 PC가 덜 느려져 시연 영상을 찍기 좋습니다.
# 실행: 시작 메뉴에서 Windows PowerShell을 열고 프로젝트 폴더로 간 뒤
#       powershell -ExecutionPolicy Bypass -File scripts\start-car.ps1
. "$PSScriptRoot\adb-common.ps1"

function Stop-Here([string]$msg) {
    Write-Host ""
    Write-Host "멈춤: $msg" -ForegroundColor Yellow
    exit 1
}

$emu = Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe"
if (-not (Test-Path $emu)) { Stop-Here "에뮬레이터 프로그램을 찾지 못했습니다: $emu" }

# 이미 켜져 있으면 다시 켜지 않습니다.
$running = (& $adb devices 2>&1 | Out-String) -split "`n" | Where-Object { $_ -match '\sdevice\s*$' }
if ($running) {
    Write-Host "[1/2] 가상 차량이 이미 켜져 있습니다."
} else {
    # 만들어 둔 가상 기기 목록에서 Pleos Connect 를 찾습니다.
    $all = @((& $emu -list-avds 2>&1 | Out-String) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" -and $_ -notmatch '\s' })
    $avd = @($all | Where-Object { $_ -match 'pleos|connect' }) | Select-Object -First 1
    if (-not $avd -and $all.Count -eq 1) { $avd = $all[0] }
    if (-not $avd) {
        Write-Host "찾은 가상 기기 목록:"
        $all | ForEach-Object { Write-Host "    $_" }
        Stop-Here "어느 가상 차량을 켤지 정하지 못했습니다. 위 목록을 복사해 보내 주세요."
    }
    Write-Host "[1/2] 가상 차량을 켭니다: $avd"
    Start-Process -FilePath $emu -ArgumentList @("-avd", "$avd")
}

Write-Host "[2/2] 차량 화면이 다 뜰 때까지 기다립니다. 몇 분 걸릴 수 있습니다."
$ok = $false
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 5
    $boot = ((& $adb shell getprop sys.boot_completed 2>&1 | Out-String).Trim())
    if ($boot -eq "1") { $ok = $true; break }
    if (($i % 12) -eq 11) { Write-Host "    기다리는 중입니다. $(($i + 1) / 12)분 지남" }
}
if (-not $ok) { Stop-Here "10분이 지나도 차량 화면이 뜨지 않았습니다. 가상 차량 창을 닫고 다시 실행해 주세요." }

& $adb forward tcp:8088 tcp:8088 2>&1 | Out-Null
Write-Host ""
Write-Host "가상 차량이 켜졌습니다." -ForegroundColor Green
Write-Host "이어서 아래 줄을 실행하면 놓친문장이 설치되고 실행됩니다."
Write-Host "    powershell -ExecutionPolicy Bypass -File scripts\build-run.ps1 -SkipBuild"
Write-Host "차량 조작 화면은 크롬에서 http://127.0.0.1:8088 입니다."
