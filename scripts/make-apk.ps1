# 제출용 설치 파일(APK) 두 개를 만들어 apk 폴더에 모읍니다. 가상 차량이 꺼져 있어도 됩니다.
# 실행: 프로젝트 폴더에서
#       powershell -ExecutionPolicy Bypass -File scripts\make-apk.ps1
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

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
Write-Host "[1/3] JDK: $($jdk.FullName)"

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path (Join-Path $root "local.properties")) -and (Test-Path $sdk)) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
}

# 2. 빌드
Write-Host "[2/3] 제출용으로 빌드합니다. 몇 분 걸립니다. 자세한 기록은 build_log.txt 에 남습니다."
& .\gradlew.bat assembleRelease --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $root "build_log.txt") | ForEach-Object {
    $line = "$_"
    if ($line -match '^> Task .*(compile|package|assemble)' -or $line -match 'BUILD|FAILED|^e: |error:|What went wrong') { Write-Host "    $line" }
}
if ($LASTEXITCODE -ne 0) { Stop-Here "빌드가 실패했습니다. 프로젝트 폴더의 build_log.txt 파일을 보내 주세요." }

# 3. apk 폴더로 모으기
Write-Host "[3/3] apk 폴더로 모읍니다."
& "$PSScriptRoot\collect-apk.ps1"
$main = Join-Path $root "apk\missedsentence-release.apk"
if (-not (Test-Path $main)) { Stop-Here "apk 폴더에 missedsentence-release.apk 가 없습니다. 위에 나온 글자를 복사해 보내 주세요." }
Write-Host ""
Write-Host "끝났습니다. apk 폴더에 설치 파일이 들어 있습니다." -ForegroundColor Green
Get-ChildItem (Join-Path $root "apk") -Filter *.apk | ForEach-Object { Write-Host ("    {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
