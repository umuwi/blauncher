# Собирает агент BL Skins в src/main/resources/blskins/blskins-agent.jar (его вшивает build.ps1).
# Запускать вручную после правки skinagent/src. --release 8: агент работает и в игре на Java 8 (до 1.16).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = "C:\Program Files\Java\jdk-21.0.10\bin"
$out = Join-Path $env:TEMP "blskins-build"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
$sources = Get-ChildItem -Path (Join-Path $here "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\javac.exe" --release 8 -encoding UTF-8 -nowarn -Xlint:-options -d $out $sources
$manifest = Join-Path $env:TEMP "blskins-manifest.txt"
"Premain-Class: blskins.Agent`r`n" | Set-Content -Encoding ascii $manifest
$dest = Join-Path $here "..\src\main\resources\blskins"
New-Item -ItemType Directory -Force $dest | Out-Null
& "$jdk\jar.exe" cfm (Join-Path $dest "blskins-agent.jar") $manifest -C $out .
Write-Host "[skinagent] готово"
