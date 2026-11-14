# 가상 차량 창을 화면 왼쪽 위로 옮깁니다. 가상 차량이 켜져 있을 때 실행합니다.
# 실행: powershell -ExecutionPolicy Bypass -File scripts\fix-window.ps1
# 가상 차량 창은 테두리가 없어 마우스로 끌 수 없고, 화면 배율이 100%가 아닌 PC에서는
# 창의 왼쪽과 위쪽이 화면 밖으로 나간 채 뜨는 일이 있어 만든 스크립트입니다.
# 끝에 -Check 를 붙이면 창을 옮기지 않고, 가상 차량 창이 떠 있는지만 확인합니다(다른 스크립트가 씁니다).
param([int]$X = 10, [int]$Y = 10, [switch]$Quiet, [switch]$Check)

$code = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class MsWindowMover
{
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr hWnd, StringBuilder sb, int max);

    // Visible top-level windows of the given processes, console windows excluded.
    public static IntPtr[] FindWindows(int[] pids)
    {
        List<IntPtr> found = new List<IntPtr>();
        EnumWindows(delegate(IntPtr h, IntPtr l)
        {
            uint pid;
            GetWindowThreadProcessId(h, out pid);
            if (IsWindowVisible(h) && Array.IndexOf(pids, (int)pid) >= 0)
            {
                StringBuilder sb = new StringBuilder(256);
                GetClassName(h, sb, 256);
                if (sb.ToString().IndexOf("Console", StringComparison.OrdinalIgnoreCase) < 0) found.Add(h);
            }
            return true;
        }, IntPtr.Zero);
        return found.ToArray();
    }

    // left, top, width, height
    public static int[] Rect(IntPtr h)
    {
        RECT r;
        if (!GetWindowRect(h, out r)) return new int[] { 0, 0, 0, 0 };
        return new int[] { r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top };
    }

    // Move only: SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
    public static bool Move(IntPtr h, int x, int y)
    {
        return SetWindowPos(h, IntPtr.Zero, x, y, 0, 0, 0x0001 | 0x0004 | 0x0010);
    }
}
'@
if (-not ("MsWindowMover" -as [type])) { Add-Type -TypeDefinition $code }

$procs = @(Get-Process | Where-Object { $_.ProcessName -like "qemu-system*" -or $_.MainWindowTitle -like "Android Emulator*" })
if ($procs.Count -eq 0) {
    if (-not $Quiet) { Write-Host "켜져 있는 가상 차량을 찾지 못했습니다. 가상 차량을 먼저 켜 주세요." -ForegroundColor Yellow }
    exit 1
}
[int[]]$procIds = @($procs | ForEach-Object { $_.Id })
$wins = @([MsWindowMover]::FindWindows($procIds))
if ($wins.Count -eq 0) {
    if (-not $Quiet) { Write-Host "가상 차량 창을 찾지 못했습니다. 창이 뜬 뒤에 다시 실행해 주세요." -ForegroundColor Yellow }
    exit 1
}

if ($Check) { exit 0 }

# 가장 큰 창이 차량 화면입니다. 옆의 도구 막대는 차량 화면을 따라옵니다.
$before = @{}
$car = [IntPtr]::Zero
$carArea = [long]-1
foreach ($w in $wins) {
    $r = [MsWindowMover]::Rect($w)
    $before["$w"] = $r
    $area = [long]$r[2] * [long]$r[3]
    if ($area -gt $carArea) { $carArea = $area; $car = $w }
}
$r0 = $before["$car"]
[void][MsWindowMover]::Move($car, $X, $Y)
Start-Sleep -Milliseconds 700
$r1 = [MsWindowMover]::Rect($car)
$dx = $r1[0] - $r0[0]
$dy = $r1[1] - $r0[1]

# 따라오지 않은 창이 있으면 같은 만큼 옮깁니다.
if ($dx -ne 0 -or $dy -ne 0) {
    foreach ($w in $wins) {
        if ($w -eq $car) { continue }
        $b = $before["$w"]
        if ($b[2] -lt 20 -or $b[3] -lt 20) { continue }
        $a = [MsWindowMover]::Rect($w)
        if ([Math]::Abs($a[0] - $b[0]) -le 2 -and [Math]::Abs($a[1] - $b[1]) -le 2) {
            [void][MsWindowMover]::Move($w, $b[0] + $dx, $b[1] + $dy)
        }
    }
}

if (-not $Quiet) {
    Write-Host "가상 차량 창 위치: 왼쪽 $($r0[0]), 위 $($r0[1]) 에서 왼쪽 $($r1[0]), 위 $($r1[1]) 로 옮겼습니다. 창 크기 $($r1[2]) x $($r1[3]), 찾은 창 $($wins.Count)개"
    if ($r1[0] -lt 0 -or $r1[1] -lt 0) {
        Write-Host "창이 아직 화면 밖에 걸쳐 있습니다. 이 줄과 윗줄을 복사해 보내 주세요." -ForegroundColor Yellow
    }
}
