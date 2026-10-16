# Разовая проверка вида установщика: запускает BLauncherSetup.exe, ждёт окно и снимает его в PNG.
param(
    [string]$Exe = (Join-Path $PSScriptRoot '..\build\BLauncherSetup.exe'),
    [string]$Out = "$env:TEMP\blauncher-setup-shot.png",
    [int]$WaitSeconds = 20,
    [string]$TitleMatch = 'BLauncher',
    [switch]$KeepOpen
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing
if (-not ('WinCap2' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WinCap2 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
}

$proc = Start-Process -FilePath $Exe -PassThru
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$window = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    $window = Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match $TitleMatch } |
        Select-Object -First 1
    if ($window) { break }
}
if (-not $window) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    throw "окно '$TitleMatch' не появилось"
}

Start-Sleep -Seconds 3
[void][WinCap2]::SetForegroundWindow($window.MainWindowHandle)
Start-Sleep -Milliseconds 700

$rect = New-Object WinCap2+RECT
[void][WinCap2]::GetWindowRect($window.MainWindowHandle, [ref]$rect)
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
$graphics.Dispose()
$bitmap.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()
Write-Host "[shot] ${width}x${height} -> $Out"

if (-not $KeepOpen) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Write-Host "[shot] закрыт pid $($proc.Id)"
} else {
    Write-Host "[shot] оставлен открытым pid $($proc.Id)"
}
