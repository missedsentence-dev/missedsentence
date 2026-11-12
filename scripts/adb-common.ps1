# 다른 스크립트가 함께 쓰는 부분: adb 위치 찾기
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
