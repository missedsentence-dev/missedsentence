# 에뮬레이터의 실제 차량 속도와 기어 값을 바꿉니다. 앱의 가상 속도 대신 실제 값으로 기본 모드를 보여 줄 때 씁니다.
# Virtual Console의 가속 페달 대신 정해진 속도 값을 바로 넣고 싶을 때 씁니다.
# 판매용 차와 같은 잠금 상태의 에뮬레이터 이미지에서는 막혀서 동작하지 않을 수 있습니다.
#
# 실행: PowerShell에서  .\scripts\real-speed.ps1 -Kmh 60      속도를 시속 60으로
#                       .\scripts\real-speed.ps1 -Kmh 0 -Gear P  멈추고 기어 P
# 실행이 막히면 먼저:  Set-ExecutionPolicy -Scope Process Bypass
# 앱에서는 가상 속도를 '실제 값'으로 두세요.
param([double]$Kmh = 0, [string]$Gear = "")
. "$PSScriptRoot\adb-common.ps1"

$SPEED = 291504647   # PERF_VEHICLE_SPEED, 단위 m/s
$GEARP = 289408000   # GEAR_SELECTION, P=4 R=2 N=1 D=8
$VHAL  = "android.hardware.automotive.vehicle.IVehicle/default"

function Set-Prop([int]$prop, [string]$type, [string]$value) {
    $out = (& $adb shell dumpsys $VHAL --set $prop $type $value 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $out -match "(?i)error|fail|unknown|invalid|can't find|no such|not found") {
        # 직접 설정이 안 되면 변경 알림만 넣습니다. 기어는 반영되고 속도는 반영되지 않을 수 있습니다.
        $hex = "0x{0:X}" -f $prop
        & $adb shell cmd car_service inject-vhal-event $hex $value | Out-Null
        Write-Host "직접 설정이 안 돼서 이벤트 주입으로 넣었습니다."
    }
}

if ($Gear -ne "") {
    $code = @{ "P" = 4; "R" = 2; "N" = 1; "D" = 8 }[$Gear.ToUpper()]
    if ($null -eq $code) { Write-Host "기어는 P, R, N, D 가운데 하나로 적어 주세요."; exit 1 }
    Set-Prop $GEARP "-i" ([string]$code)
    Write-Host "기어 $($Gear.ToUpper())"
}
$ms = [math]::Round($Kmh / 3.6, 2)
Set-Prop $SPEED "-f" ([string]$ms)
Write-Host "속도 시속 $Kmh"
