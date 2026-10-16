# Собирает установщик лаунчера — build\BLauncherSetup.exe.
#
#   pwsh -File build-installer.ps1                 # собрать установщик
#   pwsh -File build-installer.ps1 -Upload         # собрать и залить на blauncher.ru
#   pwsh -File build-installer.ps1 -Version v1.12.6
#
# Что делает:
#   1. Определяет версию сборки, которую установщик будет качать с сервера: из локального
#      manifest_pc.json, если он есть, иначе — прямо из https://blauncher.ru/update/manifest_pc.json.
#      Версия вшивается в exe как значение по умолчанию (если сервер не ответит, установщик
#      попробует скачать именно её).
#   2. Генерирует installer\BuildInfo.g.cs.
#   3. Компилирует installer\BLauncherSetup.cs компилятором C# из .NET Framework (C# 5) в один
#      самодостаточный exe: иконка BLauncher.ico, манифест installer\app.manifest (asInvoker + DPI).
#      Никаких зависимостей, кроме .NET Framework 4.x, который есть в Windows 10/11 из коробки.
#   4. Печатает размер и sha256 — их стоит держать рядом с ссылкой на скачивание.
#
# Установщик НЕ содержит ни паролей, ни файлов лаунчера: сборку он берёт с https://blauncher.ru
# по HTTPS и только её распаковывает в выбранную папку.
param(
    [string]$Version = "",
    [string]$OutFile = "",
    [switch]$Upload,
    # Имя, а не IP: скрипт лежит в публичном репозитории (16.10.2026). Домен в Cloudflare
    # стоит в режиме «DNS only», так что имя ведёт прямо на сервер и SSH по нему работает.
    [string]$SshHost = "blauncher.ru",
    [string]$SshUser = "root",
    [string]$SshPassword = "",
    [string]$RemoteDir = "/var/www/blauncher.ru/downloads",
    [switch]$NoIcon,
    # Команда подписи кода. Шаблон PowerShell, в котором {0} — путь к подписываемому файлу.
    # Пусто (по умолчанию) — шаг подписи пропускается целиком, сборка идёт как раньше.
    #
    # Примеры:
    #   -SignCommand '& signtool.exe sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /sha1 <отпечаток> "{0}"'
    #   -SignCommand '& "C:\Certum\SimplySign\signtool.exe" sign /fd SHA256 /n "Имя Фамилия" "{0}"'
    #   -SignCommand '& java -jar CodeSignTool.jar sign -input_file_path="{0}" -override=true'
    [string]$SignCommand = ""
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [switch]$Quiet)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Exe @Arguments 2>&1
        $code = $LASTEXITCODE
        if (-not $Quiet) { $output | ForEach-Object { Write-Host $_ } }
        return @{ Code = $code; Output = ($output | Out-String) }
    } finally {
        $ErrorActionPreference = $previous
    }
}

# ---------------------------------------------------------------- версия сборки

if (-not $Version) {
    $localManifest = Join-Path $root 'manifest_pc.json'
    if (Test-Path $localManifest) {
        $Version = (Get-Content $localManifest -Raw | ConvertFrom-Json).latestVersion
        Write-Host "[setup] версия из manifest_pc.json: $Version"
    }
}
if (-not $Version) {
    try {
        $remote = Invoke-WebRequest 'https://blauncher.ru/update/manifest_pc.json' -UseBasicParsing -TimeoutSec 20
        $Version = ($remote.Content | ConvertFrom-Json).latestVersion
        Write-Host "[setup] версия с сервера: $Version"
    } catch {
        throw "Не удалось определить версию: manifest_pc.json недоступен. Передайте -Version vX.Y.Z."
    }
}
$Version = $Version.Trim()
if ($Version -notmatch '^v') { $Version = "v$Version" }

# Проверяем, что сборка этой версии реально лежит на сервере — иначе установщик соберётся,
# но качать ему будет нечего.
$zipUrl = "https://blauncher.ru/downloads/BLauncher_$($Version.TrimStart('v')).zip"
try {
    $head = Invoke-WebRequest $zipUrl -Method Head -UseBasicParsing -TimeoutSec 25
    Write-Host ("[setup] сборка на сервере: {0} ({1:N0} байт)" -f $zipUrl, [int64]$head.Headers['Content-Length'])
} catch {
    Write-Warning "Сборка $zipUrl сейчас недоступна: $($_.Exception.Message)"
    Write-Warning "Установщик всё равно соберётся — он умеет ставить и из локального ZIP."
}

# Установщик качает с сервера только лаунчер без Java — blauncher-launcher.zip
# (и BLauncher_launcher_<версия>.zip). Его здесь проверять незачем: его кладёт релиз.
#
# Тут раньше проверялся BLauncher_runtime_<версия>.zip и при его отсутствии печатался совет
# собрать его на сервере. Это осталось от схемы до 3 октября, когда Java качалась, а лаунчер был
# вшит. Сам установщик такой архив не запрашивает. 13.10.2026 по этому совету на сайте оказался
# пустой архив: пути в BLauncher_<версия>.zip записаны с «\», и выборка по 'runtime/*' не нашла ничего.

# ---------------------------------------------------------------- вшитая часть сборки

$imageRoot = Join-Path $root 'dist\BLauncher'
if (-not (Test-Path (Join-Path $imageRoot 'BLauncher.exe'))) {
    throw "Не найден образ сборки $imageRoot\BLauncher.exe — сначала соберите приложение (jpackage)."
}
# В exe вшивается ПАПКА runtime\, то есть Java. Лаунчер (BLauncher.exe + app\) наоборот
# качается с сервера.
#
# Раньше было наоборот, и это ломало главное свойство установщика: репутация файла у SmartScreen
# и браузеров копится по хешу, а в exe лежала самая изменчивая часть сборки — BLauncher.jar,
# который меняется каждым релизом. Хеш сбрасывался с каждым выпуском, и предупреждение «файл
# скачивается редко» получал каждый новый человек. Java же закреплена на 21.0.10 (см. --release 21
# в build.ps1) и не меняется годами, поэтому exe с ней внутри живёт от релиза к релизу.
#
# Объём для человека тот же: 55 МБ вшитых + 38 МБ скачанных вместо 38 вшитых + 55 скачанных.
# Заодно исчезает путь, по которому Java приезжала без сверки хеша — сервер его не публиковал.
$runtimeZip = Join-Path $root 'build\installer-runtime.zip'
$runtimeStaging = Join-Path $root 'build\installer-runtime'
if (Test-Path $runtimeStaging) { Remove-Item $runtimeStaging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $runtimeStaging | Out-Null

$runtimeFrom = Join-Path $imageRoot 'runtime'
if (-not (Test-Path $runtimeFrom)) {
    throw "Нет папки runtime в $imageRoot — установщику нечего вшивать. Соберите app-image через jpackage."
}
$runtimeTo = Join-Path $runtimeStaging 'runtime'
New-Item -ItemType Directory -Force -Path $runtimeTo | Out-Null
$junk = '*.bak', '*.bak-*', '*.bak[0-9]*', '*.prev', '*.tmp', '*.log', 'Thumbs.db', 'desktop.ini'
# '*.bak-*' добавлен 2026-10-04: версионные бэкапы называются BLauncher.jar.bak-v1.13.0,
# и ни '*.bak' (имя на .bak не кончается), ни '*.bak[0-9]*' (после .bak дефис, не цифра)
# их не отсекали. В итоге в архив v1.13.0 уехал ЛИШНИЙ jar прошлой версии — 19 МБ,
# которые качал бы каждый. Поймано на размере архива: 57 МБ вместо ожидаемых 38.
$runtimeFiles = Get-ChildItem $runtimeFrom -Recurse -File | Where-Object {
    $name = $_.Name
    -not ($junk | Where-Object { $name -like $_ })
}
foreach ($file in $runtimeFiles) {
    $relative = $file.FullName.Substring($runtimeFrom.Length).TrimStart('\')
    $destPath = Join-Path $runtimeTo $relative
    $destDir = Split-Path $destPath -Parent
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
    Copy-Item $file.FullName $destPath -Force
}

if (Test-Path $runtimeZip) { Remove-Item $runtimeZip -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $runtimeStaging, $runtimeZip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Remove-Item $runtimeStaging -Recurse -Force

$runtimeItem = Get-Item $runtimeZip
Write-Host ("[setup] встроенная часть: runtime\ ({0} файлов), {1:N0} байт в архиве" -f $runtimeFiles.Count, $runtimeItem.Length)

# ---------------------------------------------------------------- компилятор

function Resolve-Csc {
    $candidates = @(
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    throw "Не найден csc.exe из .NET Framework 4.x — он есть в любой Windows 10/11."
}

$csc = Resolve-Csc
Write-Host "[setup] компилятор $csc"

# Подпись кода. Пока сертификата нет, -SignCommand не передаётся и шаг молча пропускается.
#
# ЗАЧЕМ ЭТО ЗДЕСЬ ЗАРАНЕЕ. В порядке шагов есть ловушка, на которую легко наступить, когда
# сертификат наконец появится: подпись МЕНЯЕТ байты файла, а значит и его sha256. Подписать
# после того, как скрипт посчитал хеш, — значит опубликовать сумму, которая не сойдётся с
# файлом. Поэтому подпись вызывается до вычисления хеша, а маленький exe подписывается ДО
# того, как его вшивают ресурсом в большой, иначе внутри установщика окажется неподписанный
# uninstall.exe.
#
# Проверка результата обязательна: signtool умеет вернуть ноль и при этом не подписать
# (например, когда токен заблокирован), а молча опубликованный «подписанный» файл без
# подписи хуже честно упавшей сборки.
function Invoke-CodeSign {
    param([string] $FilePath, [string] $Label)

    if (-not $SignCommand) { return }
    if (-not (Test-Path $FilePath)) { throw "Нечего подписывать: $FilePath не найден" }

    Write-Host "[setup] подписываю $Label"
    $command = $SignCommand -f $FilePath
    $exit = Invoke-Native 'powershell' @('-NoProfile', '-NonInteractive', '-Command', $command)
    if ($exit -ne 0) { throw "Подпись $Label не удалась (код $exit)" }

    $sig = Get-AuthenticodeSignature $FilePath
    if ($sig.Status -ne 'Valid') {
        throw ("Подпись $Label не прошла проверку: статус $($sig.Status). " +
               "Файл НЕ публиковать.")
    }
    Write-Host "[setup] подпись ок: $($sig.SignerCertificate.Subject)"
}

# ---------------------------------------------------------------- генерация BuildInfo.g.cs

$buildInfoPath = Join-Path $root 'installer\BuildInfo.g.cs'
$buildInfo = @"
// Сгенерировано build-installer.ps1 — не править руками.
// Версия, которую установщик попробует скачать, если сервер не ответит на manifest_pc.json.
namespace BLauncherSetup
{
    internal static class BuildInfo
    {
        public const string DefaultVersion = "$Version";
    }
}
"@
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($buildInfoPath, $buildInfo, $utf8NoBom)
Write-Host "[setup] $buildInfoPath"

# ---------------------------------------------------------------- сборка

if (-not $OutFile) { $OutFile = Join-Path $root 'build\BLauncherSetup.exe' }
$outDir = Split-Path $OutFile -Parent
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

$sources = @(
    (Join-Path $root 'installer\BLauncherSetup.cs'),
    $buildInfoPath
)
$references = @(
    'System.dll',
    'System.Core.dll',
    'System.Drawing.dll',
    'System.Windows.Forms.dll',
    'System.IO.Compression.dll'
)

$arguments = @(
    '/noconfig', '/nologo', '/target:winexe', '/platform:anycpu', '/langversion:5',
    '/codepage:65001', '/optimize+', '/debug-', '/warn:4',
    # /deterministic здесь НЕ стоит, и это не забывчивость: старый csc из .NET Framework
    # (версия 4.8.9221.0, та самая, что лежит в Framework64\v4.0.30319) такого параметра не
    # знает — отвечает «fatal error CS2007: Нераспознанный параметр». Он появился только в
    # Roslyn, а тащить сюда отдельный компилятор ради одного флага значило бы завести в проекте
    # сборочную систему, которой у него нарочно нет.
    #
    # Последствие: пересборка из НЕИЗМЕНЁННЫХ исходников даёт другой хеш — csc пишет свежий MVID
    # и метку времени в PE-заголовок. Проверено: два прогона подряд на одном и том же .cs дали
    # разные sha256. А хеш — это и есть репутация файла у SmartScreen.
    #
    # Поэтому правило простое: установщик пересобирается, только когда меняется он сам или
    # версия Java. Выпуск новой версии лаунчера его НЕ требует — в нём нет ничего, что меняется
    # от релиза к релизу. Проверка ниже предупредит, если хеш всё-таки уехал.
    ('/out:' + $OutFile),
    ('/win32manifest:' + (Join-Path $root 'installer\app.manifest'))
)
if (-not $NoIcon) {
    $icon = Join-Path $root 'BLauncher.ico'
    if (Test-Path $icon) { $arguments += ('/win32icon:' + $icon) }
}
foreach ($reference in $references) { $arguments += ('/r:' + $reference) }
$arguments += $sources

# Проход 1 — маленький exe без вшитой сборки. Он же ложится в папку установки как uninstall.exe:
# для удаления вшитая сборка не нужна, а 38 МБ рядом с установленным лаунчером — лишний вес.
$tinyExe = Join-Path $root 'build\BLauncherSetup-uninstall.exe'
$tinyArguments = @()
foreach ($argument in $arguments) {
    if ($argument -like '/out:*') { $tinyArguments += ('/out:' + $tinyExe) } else { $tinyArguments += $argument }
}
Write-Host "[setup] проход 1: маленький exe для удаления"
$tinyResult = Invoke-Native $csc $tinyArguments
if ($tinyResult.Code -ne 0) { throw "csc (маленькая сборка) завершился с кодом $($tinyResult.Code)" }
Write-Host ("[setup] uninstall.exe: {0:N0} байт" -f (Get-Item $tinyExe).Length)
# Подписываем ДО вшивания: внутри установщика должен лежать уже подписанный uninstall.exe.
Invoke-CodeSign $tinyExe 'uninstall.exe'

# Проход 2 — сам установщик: вшитая Java (runtime\) и маленький exe внутри.
$arguments += ('/resource:' + $runtimeZip + ',BLauncherSetup.runtime.zip')
$arguments += ('/resource:' + $tinyExe + ',BLauncherSetup.uninstall.exe')

Write-Host "[setup] проход 2: установщик со вшитой сборкой ($($sources.Count) файлов)"
$result = Invoke-Native $csc $arguments
if ($result.Code -ne 0) { throw "csc завершился с кодом $($result.Code)" }

# Подпись ДО вычисления хеша: она меняет байты файла, и посчитанный раньше хеш стал бы
# неверным (см. Invoke-CodeSign).
Invoke-CodeSign $OutFile 'BLauncherSetup.exe'

$exe = Get-Item $OutFile
$hash = (Get-FileHash $OutFile -Algorithm SHA256).Hash
Write-Host ""
Write-Host "[setup] готово: $($exe.FullName)"
Write-Host ("[setup] размер {0:N0} байт, sha256 {1}" -f $exe.Length, $hash)

# Памятка о ВЫЛОЖЕННОМ хеше.
#
# Вопрос, на который она отвечает: отличается ли только что собранный файл от того, что лежит
# на сайте. Сравнивать с прошлой локальной сборкой бессмысленно — csc не поддерживает
# /deterministic (см. комментарий к аргументам выше), поэтому КАЖДАЯ пересборка даёт новый
# хеш, даже когда в исходниках не изменилось ни байта. Предупреждение на этом основании
# срабатывало бы всегда и превратилось бы в шум, который перестают читать.
#
# Поэтому в файле хранится хеш того, что опубликовано, и обновлять его нужно В МОМЕНТ ВЫКЛАДКИ,
# а не при сборке. Отсюда и совет ниже: если по сути ничего не менялось, выкладывать новый exe
# не надо — публикация обнуляет репутацию файла у SmartScreen, и предупреждение «файл
# скачивается редко» возвращается ко всем.
$hashMemo = Join-Path $root 'build\installer-sha256-published.txt'
Write-Host ""
if (Test-Path $hashMemo) {
    $published = (Get-Content $hashMemo -Raw).Trim()
    if ($published -eq $hash) {
        Write-Host "[setup] собралось байт-в-байт то, что уже выложено — публиковать нечего"
    } else {
        Write-Host "[setup] на сайте сейчас:  $published"
        Write-Host "[setup] собралось сейчас: $hash"
        Write-Host "[setup] это РАЗНЫЕ файлы. Публикуй только если менялся установщик или версия Java:"
        Write-Host "[setup] любая публикация обнуляет репутацию файла у SmartScreen."
        Write-Host "[setup] выложил — впиши новый хеш в $hashMemo"
    }
} else {
    Write-Host "[setup] памятки о выложенном хеше нет ($hashMemo)."
    Write-Host "[setup] после выкладки впиши туда хеш опубликованного файла."
}
Write-Host "[setup] версия по умолчанию внутри exe: $Version"
if (-not $SignCommand) {
    Write-Host "[setup] подпись кода: НЕТ (сертификата нет), SmartScreen покажет «неизвестный издатель»"
    Write-Host "[setup] когда сертификат появится — передать -SignCommand, остальное готово"
}

# ---------------------------------------------------------------- выкладка на сервер

if ($Upload) {
    # Переменную окружения теперь действительно читаем. Сообщение об ошибке её обещало, но
    # грепом по проекту она встречалась ровно один раз — в самом этом сообщении, то есть
    # задать её было бессмысленно: исключение срабатывало всё равно.
    if (-not $SshPassword -and $env:BLAUNCHER_SSH_PASSWORD) {
        $SshPassword = $env:BLAUNCHER_SSH_PASSWORD
        Write-Host "[setup] пароль взят из переменной окружения BLAUNCHER_SSH_PASSWORD"
    }
    if (-not $SshPassword) {
        throw "Для -Upload нужен -SshPassword или переменная окружения BLAUNCHER_SSH_PASSWORD."
    }
    # Пароль передаётся дальше как -pw в аргументах plink/pscp, то есть виден в командной строке
    # процесса любому локальному процессу на время заливки. Для рута продакшн-сервера это плохо;
    # нормальное решение — ключ SSH вместо пароля (тогда -pw не нужен вовсе).
    Write-Warning "Пароль уйдёт в командную строку plink/pscp и будет виден локальным процессам. Надёжнее настроить вход по ключу."
    $pscp = (Get-Command pscp -ErrorAction SilentlyContinue | Select-Object -First 1 -Expand Source)
    $plink = (Get-Command plink -ErrorAction SilentlyContinue | Select-Object -First 1 -Expand Source)
    if (-not $pscp -or -not $plink) { throw "Для -Upload нужны pscp.exe и plink.exe (PuTTY)." }

    $target = "$SshUser@$SshHost"
    Write-Host "[setup] заливаю на ${target}:$RemoteDir"

    # Первое подключение: принимаем host key (иначе plink/pscp откажутся работать).
    #
    # Через Invoke-Native, а НЕ напрямую с `2>&1`. В Windows PowerShell перенаправление stderr
    # нативной программы поднимает терминирующий NativeCommandError даже при коде выхода 0, а
    # при $ErrorActionPreference='Stop' (он выставлен на уровне скрипта) это обрывает весь
    # скрипт. plink же пишет в stderr при каждом подключении («Using username "root".») и
    # отпечаток ключа при первом — то есть -Upload умирал ровно здесь, на проверке связи,
    # до запуска pscp, и именно в том случае «первое подключение», который эта строка и
    # должна обслуживать. Хелпер Invoke-Native для этого и написан.
    $probe = Invoke-Native $plink @('-ssh', '-batch', '-pw', $SshPassword, $target, 'echo ok') -Quiet
    if ($probe.Output -notmatch 'ok') {
        Write-Warning "Проверка связи не вернула ok. Ответ: $($probe.Output)"
        Write-Warning "Если это первое подключение к серверу, host key нужно принять один раз вручную:"
        Write-Warning "  echo y | plink -ssh -pw <пароль> $target `"echo ok`""
        throw "plink не подтвердил связь (код $($probe.Code)) — заливка отменена."
    }

    $upload = Invoke-Native $pscp @('-pw', $SshPassword, '-batch', $OutFile, "${target}:$RemoteDir/BLauncherSetup.exe")
    if ($upload.Code -ne 0) { throw "pscp завершился с кодом $($upload.Code)" }

    $remoteCheck = Invoke-Native $plink @('-ssh', '-batch', '-pw', $SshPassword, $target,
        "ls -l $RemoteDir/BLauncherSetup.exe && sha256sum $RemoteDir/BLauncherSetup.exe && df -h / | tail -1")
    if ($remoteCheck.Code -ne 0) { throw "plink завершился с кодом $($remoteCheck.Code)" }

    Write-Host "[setup] ссылка: https://blauncher.ru/downloads/BLauncherSetup.exe"
}
