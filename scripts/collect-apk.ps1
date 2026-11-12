# 빌드한 APK 두 개를 apk 폴더로 모읍니다. 제출용 저장소에 올릴 때 씁니다.
# 먼저  .\gradlew.bat assembleRelease  로 빌드하세요.
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root "apk"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$items = @(
    @{ From = "app\build\outputs\apk\release\app-release.apk"; To = "missedsentence-release.apk" },
    @{ From = "navprompt\build\outputs\apk\release\navprompt-release.apk"; To = "navprompt-release.apk" }
)
foreach ($i in $items) {
    $src = Join-Path $root $i.From
    if (Test-Path $src) {
        Copy-Item $src (Join-Path $out $i.To) -Force
        Write-Host "복사: apk\$($i.To)"
    } else {
        Write-Host "없음: $($i.From)  먼저 assembleRelease 를 실행하세요."
    }
}
