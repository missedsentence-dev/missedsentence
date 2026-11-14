# Android Studio를 켜지 않고 가상 차량(Pleos Connect 에뮬레이터)만 켭니다.
# Android Studio가 꺼져 있으면 PC가 덜 느려져 시연 영상을 찍기 좋습니다.
# 실행: 시작 메뉴에서 Windows PowerShell을 열고 프로젝트 폴더로 간 뒤
#       powershell -ExecutionPolicy Bypass -File scripts\start-car.ps1
# 화면을 그래픽 카드로 그리도록 켭니다. 내장 그래픽의 드라이버가 오래되면 에뮬레이터가 스스로
# 소프트웨어 그리기를 골라 매우 느려지기 때문입니다. 화면이 검게만 나오거나 바로 꺼지면
# 끝에 -Software 를 붙여 예전 방식으로 켭니다.
param([switch]$Software)
. "$PSScriptRoot\adb-common.ps1"

function Stop-Here([string]$msg) {
    Write-Host ""
    Write-Host "멈춤: $msg" -ForegroundColor Yellow
    exit 1
}

$emu = Join-Path $env:LOCALAPPDATA "Android\Sdk\emulator\emulator.exe"
if (-not (Test-Path $emu)) { Stop-Here "에뮬레이터 프로그램을 찾지 못했습니다: $emu" }

function Get-CarDevices {
    return @((& $adb devices 2>&1 | Out-String) -split "`n" | Where-Object { $_ -match '\sdevice\s*$' })
}
# 가상 차량 창이 화면에 떠 있는지
function Test-CarWindow {
    & "$PSScriptRoot\fix-window.ps1" -Check -Quiet | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# 이미 켜져 있으면 다시 켜지 않습니다.
$running = Get-CarDevices
# Android Studio 안에서 켠 가상 차량은 따로 뜨는 창이 없으므로 아래 확인에서 뺍니다.
$inStudio = @(Get-Process | Where-Object { $_.ProcessName -like "qemu-system*headless*" }).Count -gt 0
if ($running -and -not $inStudio -and -not (Test-CarWindow)) {
    # 창을 닫은 직후에는 가상 차량이 마무리하느라 잠시 더 남아 있습니다. 다 꺼진 뒤에 새로 켭니다.
    Write-Host "가상 차량이 꺼지는 중입니다. 다 꺼질 때까지 기다립니다."
    for ($k = 0; $k -lt 24; $k++) {
        Start-Sleep -Seconds 5
        $running = Get-CarDevices
        if (-not $running) { break }
        if (Test-CarWindow) { break }
    }
    if ($running -and -not (Test-CarWindow)) {
        # 2분이 지나도 창 없이 남아 있으면 남은 프로세스를 끝냅니다.
        Get-Process | Where-Object { $_.ProcessName -like "qemu-system*" } | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
        $running = Get-CarDevices
    }
}
if ($running) {
    Write-Host "[1/2] 가상 차량이 이미 켜져 있습니다. 그래픽 방식을 바꾸려면 가상 차량을 끈 뒤 다시 실행해 주세요."
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

    # 가상 차량 창 크기를 이 PC 화면에 맞춥니다. 에뮬레이터는 차량 화면(2560x1440)을 PC 화면보다
    # 크게 띄우는 일이 있습니다. 창 위치는 켜진 뒤에 fix-window.ps1 이 옮깁니다.
    $avdDir = Join-Path $env:USERPROFILE ".android\avd\$avd.avd"
    if (Test-Path $avdDir) {
        $scale = 0.5
        try {
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            $wa = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
            $g = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
            $dpi = [double]$g.DpiX
            $g.Dispose()
            if ($dpi -lt 96) { $dpi = 96.0 }
            # 화면 배율(125% 등)을 뺀 화면 크기. 에뮬레이터의 창 배율은 이 크기를 기준으로 합니다.
            $screenW = $wa.Width * 96.0 / $dpi
            $screenH = $wa.Height * 96.0 / $dpi
            $lcdW = 2560.0
            $lcdH = 1440.0
            $cfg = Join-Path $avdDir "config.ini"
            if (Test-Path $cfg) {
                foreach ($line in Get-Content $cfg) {
                    if ($line -match '^\s*hw\.lcd\.width\s*=\s*(\d+)') { $lcdW = [double]$Matches[1] }
                    if ($line -match '^\s*hw\.lcd\.height\s*=\s*(\d+)') { $lcdH = [double]$Matches[1] }
                }
            }
            # 오른쪽 도구 막대, 창 제목 줄, 여백을 빼고 화면 안에 들어가는 가장 큰 배율
            $s = [Math]::Min(($screenW - 110) / $lcdW, ($screenH - 60) / $lcdH)
            $s = [Math]::Floor($s * 100) / 100
            if ($s -gt 1) { $s = 1.0 }
            if ($s -ge 0.2) { $scale = $s }
        } catch { }
        $scaleText = ([double]$scale).ToString("0.##", [System.Globalization.CultureInfo]::InvariantCulture)
        $ini = Join-Path $avdDir "emulator-user.ini"
        $keep = @()
        if (Test-Path $ini) { $keep = @(Get-Content $ini | Where-Object { $_ -notmatch '^\s*window\.(x|y|scale)\s*=' }) }
        $lines = @("window.x = 10", "window.y = 10", "window.scale = $scaleText") + $keep
        Set-Content -Path $ini -Value $lines -Encoding ASCII
    }

    # 켤 때마다 처음부터 부팅하고, 끌 때 상태를 저장하지 않습니다. 끄는 데 시간이 걸리지 않고 매번 같은 상태로 켜집니다.
    $emuArgs = @("-avd", "$avd", "-no-snapshot")
    if ($Software) {
        Write-Host "[1/2] 가상 차량을 켭니다(소프트웨어 그리기): $avd"
    } else {
        $emuArgs += @("-gpu", "host")
        Write-Host "[1/2] 가상 차량을 켭니다(그래픽 카드 사용): $avd"
    }
    Start-Process -FilePath $emu -ArgumentList $emuArgs
}

Write-Host "[2/2] 차량 화면이 다 뜰 때까지 기다립니다. 몇 분 걸릴 수 있습니다."
$ok = $false
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 5
    $boot = ((& $adb shell getprop sys.boot_completed 2>&1 | Out-String).Trim())
    if ($boot -eq "1") { $ok = $true; break }
    # 창이 뜨면 기다리는 동안에도 화면 안으로 옮겨 둡니다.
    if ($i -eq 1 -or $i -eq 4) { & "$PSScriptRoot\fix-window.ps1" -Quiet }
    if (($i % 12) -eq 11) { Write-Host "    기다리는 중입니다. $(($i + 1) / 12)분 지남" }
}
if (-not $ok) { Stop-Here "10분이 지나도 차량 화면이 뜨지 않았습니다. 가상 차량 창을 닫고, 끝에 -Software 를 붙여 다시 실행해 주세요." }

& $adb forward tcp:8088 tcp:8088 2>&1 | Out-Null
Write-Host ""
& "$PSScriptRoot\fix-window.ps1"
Write-Host "가상 차량이 켜졌습니다." -ForegroundColor Green
Write-Host "이어서 아래 줄을 실행하면 놓친문장이 설치되고 실행됩니다."
# 이 폴더에서 한 번도 빌드하지 않았으면 설치할 파일이 없으므로 빌드까지 하는 줄을 알려 줍니다.
$built = Test-Path (Join-Path (Split-Path $PSScriptRoot -Parent) "app\build\outputs\apk\debug\app-debug.apk")
if ($built) {
    Write-Host "    powershell -ExecutionPolicy Bypass -File scripts\build-run.ps1 -SkipBuild"
} else {
    Write-Host "    powershell -ExecutionPolicy Bypass -File scripts\build-run.ps1"
    Write-Host "    (이 폴더에서는 처음이라 빌드부터 합니다. 몇 분 걸립니다.)"
}
Write-Host "차량 조작 화면은 크롬에서 http://127.0.0.1:8088 입니다."
