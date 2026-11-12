# GitHub에 올릴 파일만 골라 깨끗한 폴더로 복사합니다.
# 빌드하다 생긴 파일과 이 PC의 경로가 적힌 파일은 빼고 복사합니다.
# 먼저 scripts\make-apk.ps1 로 설치 파일을 만들어 두세요.
# 실행: 프로젝트 폴더에서
#       powershell -ExecutionPolicy Bypass -File scripts\make-upload.ps1
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path (Split-Path $root -Parent) "missedsentence_upload"

if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
& robocopy $root $dest /E /XD build .gradle .idea .kotlin .git captures check_result __pycache__ /XF local.properties build_log.txt missed_check.zip *.iml *.keystore /NFL /NDL /NJH /NJS | Out-Null
if ($LASTEXITCODE -ge 8) {
    Write-Host "멈춤: 복사하지 못했습니다. 이 창의 글자를 복사해 보내 주세요." -ForegroundColor Yellow
    exit 1
}

$files = @(Get-ChildItem $dest -Recurse -File)
$apks = @(Get-ChildItem (Join-Path $dest "apk") -Filter *.apk -ErrorAction SilentlyContinue)
Write-Host ""
Write-Host "올릴 폴더: $dest" -ForegroundColor Green
Write-Host "파일 수: $($files.Count)"
if ($apks.Count -eq 0) {
    Write-Host "주의: apk 폴더에 설치 파일이 없습니다. scripts\make-apk.ps1 을 먼저 실행한 뒤 이 스크립트를 다시 실행해 주세요." -ForegroundColor Yellow
} else {
    $apks | ForEach-Object { Write-Host ("설치 파일: {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
}
Write-Host "탐색기에서 이 폴더를 열어 두세요. GitHub에는 이 폴더 안의 내용만 올립니다."
