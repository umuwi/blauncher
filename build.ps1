# Builds BLauncher.jar from src/ and (re)generates the auto-update manifests.
#
#   pwsh -File build.ps1 -Version v1.12.3 -Changelog "Что нового..." [-SkipManifest]
#
# What it does:
#   1. javac every source under src/main/java against the jars in app/ (no build tool in this
#      project - the packaged app image is a plain jpackage app-image whose app/BLauncher.jar is
#      exactly this jar, which is also what auto-update replaces).
#   2. Copy src/main/resources into the same staging dir, unpack the vendored lib/*.jar into it
#      (the ImageIO WebP plugin - see the note at that step), jar it up.
#   3. Back up + install the result into app/, dist/BLauncher/app/ and build/BLauncher-current.jar.
#   4. Hash the jar it just produced and write manifest.json / manifest_pc.json from THAT hash.
#      The manifest sha256 must match the exact bytes uploaded to https://blauncher.ru/update/,
#      otherwise every client rejects the download as a checksum mismatch - so never hand-edit it,
#      and never re-zip the jar after this script has hashed it.
param(
    [string]$Version = "v1.12.4",
    [string]$Changelog = "",
    [string]$ApkVersion = "",
    [string]$ApkUrl = "",
    [string]$ApkSha256 = "",
    [switch]$SkipManifest,
    # Сознательно опубликовать манифест без архива полной сборки (релиз только с обновлением
    # jar). Без этого ключа такая публикация — ошибка: см. проверку ниже.
    [switch]$AllowNoZip,
    [switch]$Zip,
    # Показать игрокам окно с изменениями и «Обновить/Позже». Без ключа (с 1.14.30) лаунчер
    # качает обновление молча и ставит при закрытии — окно оставлено для крупных выпусков.
    [switch]$Notify
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# javac/jar write informational notes to stderr ("uses a deprecated API"), which under
# ErrorActionPreference=Stop would abort the script even though the build succeeded - so native
# tools are run with the preference relaxed and judged solely by their exit code.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Resolve a real JDK: javac on PATH is usually the "javapath" shim directory, which has no
# jar.exe next to it, so the JDK home is taken from java itself, then from the usual install roots.
function Resolve-JdkHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { return $env:JAVA_HOME }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
        $match = [regex]::Match($settings, 'java\.home\s*=\s*(.+)')
        if ($match.Success) {
            $detected = $match.Groups[1].Value.Trim()
            if (Test-Path (Join-Path $detected 'bin\javac.exe')) { return $detected }
        }
    } catch { } finally {
        $ErrorActionPreference = $previous
    }

    $roots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Amazon Corretto',
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium')
    )
    $found = @()
    foreach ($rootPath in $roots) {
        if (-not (Test-Path $rootPath)) { continue }
        $found += Get-ChildItem $rootPath -Directory -ErrorAction SilentlyContinue |
                  Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') }
    }
    if ($found.Count -eq 0) {
        throw "JDK not found (need javac and jar). Set JAVA_HOME to a JDK 21 install."
    }

    # Версии сравниваются ЧИСЛАМИ, а не строками.
    #
    # Прежний Sort-Object Name -Descending сортировал имена каталогов лексикографически, и
    # "jdk-8u422" или "jdk-9" оказывались старше "jdk-21.0.10" — потому что "9" > "2". Пока
    # сборка идёт на этой машине, ветку обычно закорачивает проба java.home выше, так что
    # промах не виден; но на машине, где java не на PATH, выбрался бы JDK 8, и javac упал бы
    # с невнятной ошибкой вместо осмысленной.
    $candidates = @()
    foreach ($dir in $found) {
        $ver = Get-JdkVersion $dir
        if ($ver) { $candidates += [pscustomobject]@{ Path = $dir.FullName; Version = $ver } }
    }
    $usable = @($candidates | Where-Object { $_.Version.Major -ge 21 } | Sort-Object Version -Descending)
    if ($usable.Count -gt 0) { return $usable[0].Path }

    $listing = ($candidates | ForEach-Object { "$($_.Path) (Java $($_.Version))" }) -join '; '
    throw ("Найден только JDK старше 21: $listing. Нужен JDK 21 или новее " +
           "(сборка идёт с --release 21). Укажи его в JAVA_HOME.")
}

# Версия JDK по имени каталога, с проверкой через сам java.exe, если имя непонятное.
# Понимает jdk-21.0.10, jdk-17.0.12+7, temurin-21, jdk-9 и старый jdk1.8.0_422.
function Get-JdkVersion {
    param([System.IO.DirectoryInfo] $Dir)

    $name = $Dir.Name
    if ($name -match '(?<![\d.])1\.(\d+)\.0') {
        return [version]::new([int]$matches[1], 0, 0)
    }
    if ($name -match '(\d+)(?:\.(\d+))?(?:\.(\d+))?') {
        $major = [int]$matches[1]
        $minor = 0
        if ($matches[2]) { $minor = [int]$matches[2] }
        $patch = 0
        if ($matches[3]) { $patch = [int]$matches[3] }
        if ($major -gt 0) { return [version]::new($major, $minor, $patch) }
    }

    # Имя ничего не говорит (например, каталог называется "jdk") — спрашиваем сам java.
    $javaExe = Join-Path $Dir.FullName 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return $null }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& $javaExe -version 2>&1 | Out-String)
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($out -match 'version "1\.(\d+)\.') { return [version]::new([int]$matches[1], 0, 0) }
    if ($out -match 'version "(\d+)(?:\.(\d+))?(?:\.(\d+))?') {
        $minor = 0
        if ($matches[2]) { $minor = [int]$matches[2] }
        $patch = 0
        if ($matches[3]) { $patch = [int]$matches[3] }
        return [version]::new([int]$matches[1], $minor, $patch)
    }
    return $null
}

$jdkHome = Resolve-JdkHome
$javac = Join-Path $jdkHome 'bin\javac.exe'
$jar = Join-Path $jdkHome 'bin\jar.exe'
Write-Host "[build] jdk $jdkHome"

$staging = Join-Path $root 'build\classes-jar'
$outJar = Join-Path $root 'build\BLauncher.jar'

Write-Host "[build] version $Version"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

# lib/*.jar — тоже в classpath компиляции: они вшиваются в BLauncher.jar (см. ниже), и наш код
# может их вызывать напрямую (JSch — панель VPS). Раньше там лежали только ImageIO-плагины,
# которые находятся через ServiceLoader и при компиляции не нужны.
$deps = @(Get-ChildItem (Join-Path $root 'app') -Filter *.jar |
        Where-Object { $_.Name -ne 'BLauncher.jar' } |
        ForEach-Object { $_.FullName }) +
        @(Get-ChildItem (Join-Path $root 'lib') -Filter *.jar -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName })
$cp = $deps -join ';'
$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName }
Write-Host "[build] compiling $($sources.Count) sources against $($deps.Count) jars"
# --release 21 ОБЯЗАТЕЛЕН. В dist\BLauncher\runtime закреплена Java 21.0.10, а javac берётся из
# того JDK, на который сейчас указывает javapath, — и установщики Oracle переставляют его сами.
# В день, когда появится JDK 22+, сборка без этого флага выдала бы класс-файлы версии 66 и выше,
# посчитала хеш и опубликовала без возражений (UpdateService проверяет только sha256). У всех
# клиентов app-image jar заменился бы на классы, которые закреплённая Java 21 загрузить не может:
# UnsupportedClassVersionError при старте и никакого пути к самопочинке. Флаг делает это
# невозможным: javac откажется компилировать то, что не пойдёт на 21.
$exit = Invoke-Native $javac (@('-nowarn', '--release', '21', '-encoding', 'UTF-8', '-cp', $cp, '-d', $staging) + $sources)
if ($exit -ne 0) { throw "javac failed with exit code $exit" }

$resources = Join-Path $root 'src\main\resources'
if (Test-Path $resources) {
    Copy-Item (Join-Path $resources '*') $staging -Recurse -Force
}

# Версия ВПЕЧАТЫВАЕТСЯ в сборку, а не дублируется руками в коде.
#
# Раньше -Version влиял только на манифест, а сам лаунчер сообщал о себе
# жёстко прописанной в MainController строкой. Разойтись они могли двумя способами, и оба
# были молчаливыми: забыл поправить код при бампе - isNewer вечно истинно, и человек
# обновляется, перезапускается и снова видит окно обновления; забыл передать -Version -
# публикуется старый номер при новом jar, и обновление не предлагается никому.
# Теперь номер один и тот же, потому что источник один.
#
# MainController читает этот файл из класспатха, а зашитую в коде строку использует только
# как фолбэк для запуска из IDE и через BLauncher.bat.
$versionFile = Join-Path $staging 'version.properties'
$utf8NoBomRes = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    $versionFile,
    ("# Создаётся build.ps1 при каждой сборке. Править руками бессмысленно.`r`nversion=" + $Version + "`r`n"),
    $utf8NoBomRes)
Write-Host "[build] stamped version.properties = $Version"

# Исходники ВНУТРЬ jar, под META-INF/src/ — их оттуда достаёт UpdateService
# (SRC_PREFIX) при обновлении .bat-установки.
#
# Зачем. Такая установка — это распакованный исходный zip: BLauncher.bat компилирует src\ в
# build\classes, когда классов нет, в том числе по явной команде `BLauncher.bat rebuild`.
# Автообновление заменяет классы, а src\ оставалось тем, что было в исходном архиве человека —
# и первый же rebuild молча возвращал старую версию. Код в UpdateService для этого есть и
# описан в javadoc, но сборка ничего под META-INF/src не кладёт, то есть ветка мёртвая.
#
# Кладём только ТЕКСТОВЫЕ исходники: java плюс fxml/css/i18n/palette. Бинарные ресурсы
# (videos ~12 МБ, images, fonts) сознательно не дублируем — иначе jar вырос бы с 18 до 36 МБ,
# и это уезжало бы в каждое автообновление. Они и так лежат в jar на верхнем уровне, откуда
# попадают прямо в build\classes, а в src\ остаются прежними: эти файлы меняются редко,
# и rebuild соберёт с ними корректную сборку.
$srcInJar = Join-Path $staging 'META-INF\src'
New-Item -ItemType Directory -Force -Path (Join-Path $srcInJar 'main\java') | Out-Null
Copy-Item (Join-Path $root 'src\main\java\*') (Join-Path $srcInJar 'main\java') -Recurse -Force
$textResourceDirs = @('fxml', 'css', 'i18n', 'palette')
foreach ($dir in $textResourceDirs) {
    $from = Join-Path $root "src\main\resources\$dir"
    if (-not (Test-Path $from)) { continue }
    $to = Join-Path $srcInJar "main\resources\$dir"
    New-Item -ItemType Directory -Force -Path $to | Out-Null
    Copy-Item (Join-Path $from '*') $to -Recurse -Force
}
$srcBytes = (Get-ChildItem $srcInJar -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("[build] embedded sources under META-INF/src: {0:N1} MB" -f ($srcBytes / 1MB))

# Unpack lib/*.jar INTO the jar. These are the TwelveMonkeys ImageIO plugins (notably the WebP
# reader) that ui/IconLoader depends on: Modrinth serves most mod icons as WebP, JavaFX's own
# Image loader cannot decode it, and java.desktop's ImageIO has no built-in WebP reader either -
# without this, those icons silently fall back to the placeholder. They are unpacked rather than
# left as separate jars next to BLauncher.jar because auto-update replaces exactly ONE file
# (app\BLauncher.jar - see UpdateService.replaceJar), so anything that is not inside this jar
# would never reach an installed copy of the launcher.
#
# META-INF/services/** MUST survive (that is how ImageIO's ServiceLoader finds the reader) and is
# MERGED across jars rather than overwritten - see the comment at the extraction itself;
# signatures, the vendor manifest and the maven metadata are dropped as noise.
$vendorJars = Get-ChildItem (Join-Path $root 'lib') -Filter *.jar -ErrorAction SilentlyContinue
if ($vendorJars) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $unpacked = 0
    foreach ($vendor in $vendorJars) {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($vendor.FullName)
        try {
            foreach ($entry in $archive.Entries) {
                $name = $entry.FullName
                if ($name.EndsWith('/')) { continue }   # directory entry
                if ($name -eq 'META-INF/MANIFEST.MF' -or
                    $name -like 'META-INF/maven/*' -or
                    $name -eq 'module-info.class' -or
                    $name -match '^META-INF/.*\.(SF|RSA|DSA)$') { continue }
                $target = Join-Path $staging $name
                $targetDir = Split-Path $target -Parent
                if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }

                if ($name -like 'META-INF/services/*') {
                    # Файлы SPI ОБЪЕДИНЯЮТСЯ, а не перезаписываются.
                    #
                    # ExtractToFile(..., $true) затирает, и порядок распаковки — алфавитный по
                    # имени jar. Сегодняшние шесть jar не конфликтуют (два разных имени SPI),
                    # но как только появится второй ридер под тем же ImageReaderSpi — а это
                    # любой imageio-* из TwelveMonkeys, — регистрация одного из них исчезнет.
                    # Симптом был бы ровно тот откат на иконку-заглушку, против которого вся
                    # эта распаковка и написана, и заметить его в сборке нечем.
                    $stream = $entry.Open()
                    try {
                        $readerSpi = New-Object System.IO.StreamReader($stream)
                        try { $incoming = $readerSpi.ReadToEnd() } finally { $readerSpi.Dispose() }
                    } finally {
                        $stream.Dispose()
                    }
                    $lines = @()
                    if (Test-Path $target) {
                        $lines += (Get-Content $target -Encoding UTF8 -ErrorAction SilentlyContinue)
                    }
                    $before = @($lines | Where-Object { $_ -and $_.Trim() -ne '' -and -not $_.TrimStart().StartsWith('#') }).Count
                    $lines += ($incoming -split "`r?`n")
                    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
                    $merged = @()
                    foreach ($line in $lines) {
                        if (-not $line) { continue }
                        $trimmed = $line.Trim()
                        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
                        if ($seen.Add($trimmed)) { $merged += $trimmed }
                    }
                    [System.IO.File]::WriteAllLines($target, [string[]]$merged, (New-Object System.Text.UTF8Encoding($false)))
                    if ($before -gt 0) {
                        Write-Host "[build] merged SPI $name -> $($merged.Count) providers"
                    }
                } else {
                    if (Test-Path $target) {
                        # Один и тот же путь в двух jar: победит последний по алфавиту. Молча это
                        # делать нельзя — именно так из сборки пропадают классы.
                        $sameSize = ((Get-Item $target).Length -eq $entry.Length)
                        if (-not $sameSize) {
                            Write-Warning "[build] конфликт при распаковке lib: $name из $($vendor.Name) перезаписывает другой файл"
                        }
                    }
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                }
                $unpacked++
            }
        } finally {
            $archive.Dispose()
        }
        Write-Host "[build] unpacked $($vendor.Name)"
    }
    Write-Host "[build] $unpacked entries unpacked from $($vendorJars.Count) lib jars"
}

# No Main-Class: the jpackage app image names com.example.launcher.AppLauncher in BLauncher.cfg.
#
# Multi-Release: true — с 07.10.2026, ради JSch (lib/jsch-*.jar, панель VPS). Это multi-release
# jar: поддержка ed25519 через JDK лежит в META-INF/versions/15+, и распаковка выше её переносит,
# но без этого атрибута Java читает только базовые классы, где ed25519 нет — подключение к VPS
# с ключом ed25519 падало бы только в собранном лаунчере, а при запуске из IDE работало бы.
# Наши собственные классы в META-INF/versions не лежат, на них атрибут не влияет.
$manifestFile = Join-Path $env:TEMP 'blauncher-jar-manifest.mf'
"Manifest-Version: 1.0`r`nMulti-Release: true`r`n" | Set-Content -Path $manifestFile -Encoding ASCII
$exit = Invoke-Native $jar @('cfm', $outJar, $manifestFile, '-C', $staging, '.')
if ($exit -ne 0) { throw "jar failed with exit code $exit" }

foreach ($target in @(
        (Join-Path $root 'app\BLauncher.jar'),
        (Join-Path $root 'dist\BLauncher\app\BLauncher.jar'),
        (Join-Path $root 'build\BLauncher-current.jar'))) {
    $targetDir = Split-Path $target -Parent
    if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }
    if (Test-Path $target) {
        # .prev — бэкап «на один шаг назад», и его мало: две сборки подряд уничтожали последний
        # известно-хороший jar (сборка N делает .prev = N-1, сборка N+1 перезаписывает .prev
        # версией N). Поэтому рядом кладём ещё и копию с номером версии по тому же соглашению
        # .bak-vX.Y.Z, что принято на сервере, и её никто не перезаписывает.
        Copy-Item $target "$target.prev" -Force
        $versioned = "$target.bak-$Version"
        if (-not (Test-Path $versioned)) {
            Copy-Item $target $versioned -Force
            Write-Host "[build] backup -> $versioned"
        }
    }
    Copy-Item $outJar $target -Force
    Write-Host "[build] installed -> $target"
}

$hash = (Get-FileHash $outJar -Algorithm SHA256).Hash
Write-Host "[build] sha256 $hash"
Write-Host "[build] size   $((Get-Item $outJar).Length) bytes"

if ($Zip) {
    # Portable Windows distribution: the jpackage app image exactly as it ships - BLauncher.exe,
    # app/ (the launcher jar plus its libraries) and the bundled runtime/. Everything that is not
    # part of a running install is left out on purpose: *.bak* / *.prev jar backups, launcher_root
    # (the player's own game files), config, logs, build/, out/, src/, .research/ and the temp files
    # that accumulate in this working copy.
    $imageRoot = Join-Path $root 'dist\BLauncher'
    if (-not (Test-Path (Join-Path $imageRoot 'BLauncher.exe'))) {
        throw "app image not found at $imageRoot - run jpackage first"
    }
    $zipStaging = Join-Path $root 'build\zip-staging'
    $zipPath = Join-Path $root ("build\BLauncher_{0}.zip" -f $Version.TrimStart('v'))
    if (Test-Path $zipStaging) { Remove-Item $zipStaging -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $zipStaging | Out-Null

    Copy-Item (Join-Path $imageRoot 'BLauncher.exe') $zipStaging -Force
    foreach ($dir in @('app', 'runtime')) {
        $from = Join-Path $imageRoot $dir
        if (-not (Test-Path $from)) { continue }
        $to = Join-Path $zipStaging $dir
        New-Item -ItemType Directory -Force -Path $to | Out-Null
        $junk = '*.bak', '*.bak-*', '*.bak[0-9]*', '*.prev', '*.tmp', '*.log', 'Thumbs.db', 'desktop.ini'
        # '*.bak-*' добавлен 2026-10-04: версионные бэкапы называются BLauncher.jar.bak-v1.13.0,
        # и ни '*.bak' (имя на .bak не кончается), ни '*.bak[0-9]*' (после .bak дефис, не цифра)
        # их не отсекали. В итоге в архив v1.13.0 уехал ЛИШНИЙ jar прошлой версии — 19 МБ,
        # которые качал бы каждый. Поймано на размере архива: 57 МБ вместо ожидаемых 38.
        Get-ChildItem $from -Recurse -File | Where-Object {
            $name = $_.Name
            -not ($junk | Where-Object { $name -like $_ })
        } | ForEach-Object {
            $relative = $_.FullName.Substring($from.Length).TrimStart('\')
            $destPath = Join-Path $to $relative
            $destDir = Split-Path $destPath -Parent
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
            Copy-Item $_.FullName $destPath -Force
        }
    }

    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $zipStaging, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)

    # Хеш уезжает в манифест как sha256Zip - без него установщик отказывается ставить
    # полную сборку (VerifyHash в installer/BLauncherSetup.cs теперь строгий: отсутствие
    # хеша - это отказ, а не повод довериться). Поэтому zip собирается ДО записи манифестов.
    $script:zipHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash
    $script:zipFile = $zipPath
    Write-Host "[build] zip    $zipPath"
    Write-Host "[build] zip sha256 $script:zipHash"
    Write-Host "[build] zip size   $((Get-Item $zipPath).Length) bytes"
    Write-Host "[build] upload the zip to https://blauncher.ru/downloads/ and point the site link at it"

    # ВТОРОЙ архив: то же самое, но БЕЗ runtime\ — только BLauncher.exe и app\.
    #
    # Именно его качает установщик. Java в установщик вшита (см. build-installer.ps1), потому что
    # репутация exe у SmartScreen копится по хешу файла, а Java не меняется годами — в отличие от
    # BLauncher.jar, который меняется каждым выпуском. Пока было наоборот, хеш установщика
    # сбрасывался с каждым релизом, и предупреждение «файл скачивается редко» получал каждый
    # новый человек.
    #
    # Поэтому выпуск новой версии — это выложить ЭТОТ архив, а установщик не трогать.
    # Публикуется под двумя именами: с версией (для истории и бэкапов) и без версии
    # (blauncher-launcher.zip), чтобы в exe не приходилось вшивать номер выпуска — любое вшитое
    # число меняет байты файла и тем самым обнуляет ту самую репутацию.
    $launcherZip = Join-Path $root ("build\BLauncher_launcher_{0}.zip" -f $Version.TrimStart('v'))
    $launcherStaging = Join-Path $root 'build\launcher-staging'
    if (Test-Path $launcherStaging) { Remove-Item $launcherStaging -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $launcherStaging | Out-Null

    Copy-Item (Join-Path $zipStaging 'BLauncher.exe') $launcherStaging -Force
    Copy-Item (Join-Path $zipStaging 'app') $launcherStaging -Recurse -Force

    if (Test-Path $launcherZip) { Remove-Item $launcherZip -Force }
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $launcherStaging, $launcherZip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Remove-Item $launcherStaging -Recurse -Force

    $script:launcherHash = (Get-FileHash $launcherZip -Algorithm SHA256).Hash
    $script:launcherFile = $launcherZip
    Write-Host "[build] launcher zip    $launcherZip"
    Write-Host "[build] launcher sha256 $script:launcherHash"
    Write-Host "[build] launcher size   $((Get-Item $launcherZip).Length) bytes"
    Write-Host "[build] upload it to https://blauncher.ru/downloads/ TWICE: under this name AND as blauncher-launcher.zip"
}

# Если zip не собирали в этом запуске, пробуем взять уже лежащий рядом архив этой же версии -
# манифест должен нести sha256Zip, иначе установщик не сможет поставить полную сборку.
if (-not $script:zipHash) {
    $existingZip = Join-Path $root ("build\BLauncher_{0}.zip" -f $Version.TrimStart('v'))
    if (Test-Path $existingZip) {
        $script:zipHash = (Get-FileHash $existingZip -Algorithm SHA256).Hash
        $script:zipFile = $existingZip
        Write-Host "[build] zip (уже был) $existingZip"
        Write-Host "[build] zip sha256 $script:zipHash"
    }
}
if (-not $script:launcherHash) {
    $existingLauncher = Join-Path $root ("build\BLauncher_launcher_{0}.zip" -f $Version.TrimStart('v'))
    if (Test-Path $existingLauncher) {
        $script:launcherHash = (Get-FileHash $existingLauncher -Algorithm SHA256).Hash
        $script:launcherFile = $existingLauncher
        Write-Host "[build] launcher zip (уже был) $existingLauncher"
        Write-Host "[build] launcher sha256 $script:launcherHash"
    }
}

if (-not $SkipManifest) {
    if ([string]::IsNullOrWhiteSpace($Changelog)) { throw "-Changelog is required when writing manifests" }

    $downloadUrl = 'https://blauncher.ru/update/BLauncher.jar'
    $zipUrl = 'https://blauncher.ru/downloads/BLauncher_{0}.zip' -f $Version.TrimStart('v')
    # Адрес БЕЗ версии: установщик спрашивает манифест, но запасным путём идёт по этому
    # постоянному имени, чтобы в exe не было вшитого номера выпуска.
    $launcherUrl = 'https://blauncher.ru/downloads/blauncher-launcher.zip'

    # Старый sha256Zip из предыдущего манифеста не наследуем: он относится к ДРУГОМУ архиву,
    # и установщик отверг бы по нему новую сборку как повреждённую.
    $zipHashValue = if ($script:zipHash) { $script:zipHash } else { '' }
    if (-not $zipHashValue) {
        # Отказ, а не предупреждение. Установщик теперь проверяет хеш архива СТРОГО: пустой
        # sha256Zip означает, что путь «поставить полную сборку» у него вообще не работает
        # (остаётся только через runtime-архив). Публиковать манифест с номером версии, для
        # которой архива нет, — это тихо сломанный путь установки, а раньше об этом сообщалось
        # лишь строкой предупреждения, которую легко проскроллить.
        if (-not $AllowNoZip) {
            throw ("Манифест публикует {0}, но архива build\BLauncher_{1}.zip нет. " -f $Version, $Version.TrimStart('v')) +
                  "Добавьте -Zip, чтобы собрать его, либо -AllowNoZip, если это сознательно " +
                  "релиз только с обновлением jar (тогда установка полной сборки работать не будет)."
        }
        Write-Host ""
        Write-Warning ("-AllowNoZip: публикую {0} без архива. Установщик не сможет поставить полную сборку." -f $Version)
        Write-Host ""
    }

    # Хеш архива лаунчера — обязателен по той же логике, что sha256Zip: установщик проверяет
    # СТРОГО, и пустой хеш означает, что основной путь установки у него не работает (останется
    # запасной — полная сборка на 93 МБ вместо 38).
    $launcherHashValue = if ($script:launcherHash) { $script:launcherHash } else { '' }
    if (-not $launcherHashValue -and -not $AllowNoZip) {
        throw ("Манифест публикует {0}, но архива build\BLauncher_launcher_{1}.zip нет. " -f $Version, $Version.TrimStart('v')) +
              "Добавьте -Zip, чтобы собрать его: именно этот архив качает установщик."
    }

    $pc = [ordered]@{
        latestVersion       = $Version
        changelog           = $Changelog
        downloadUrl         = $downloadUrl
        downloadUrlJar      = $downloadUrl
        sha256              = $hash
        sha256Jar           = $hash
        downloadUrlZip      = $zipUrl
        sha256Zip           = $zipHashValue
        downloadUrlLauncher = $launcherUrl
        sha256Launcher      = $launcherHashValue
        notify              = [bool]$Notify
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    # Манифест перезаписывался на месте без бэкапа: неудачный запуск с неверным -Version или
    # -Changelog терял предыдущий опубликованный манифест безвозвратно.
    $pcPath = Join-Path $root 'manifest_pc.json'
    if (Test-Path $pcPath) {
        Copy-Item $pcPath "$pcPath.bak-$(Get-Date -Format 'yyyyMMdd-HHmm')" -Force
    }
    [System.IO.File]::WriteAllText($pcPath, ($pc | ConvertTo-Json -Depth 4), $utf8NoBom)
    Write-Host "[build] wrote manifest_pc.json ($Version)"

    # manifest.json этот скрипт БОЛЬШЕ НЕ ПИШЕТ — и это важно.
    #
    # На сервере манифесты разделены (1 октября): /update/manifest_pc.json — ПК, а
    # /update/manifest.json — АНДРОИД (latestVersionApk / downloadUrlApk / sha256Apk), рядом
    # лежит его копия manifest_android.json и бэкап manifest.json.bak-pc-mixed-*. Скрипт же
    # писал старый смешанный формат: залей такой manifest.json как раньше — и андроидный
    # манифест будет перезаписан ПК-данными, то есть обновление на телефонах сломается.
    #
    # Вторая причина: APK-поля раньше ПРОТЯГИВАЛИСЬ из предыдущего манифеста, если их не
    # передали. Пересобрал и выложил APK без -ApkSha256 — и в манифесте остаётся хеш старого
    # файла, после чего Android-клиент отвергает каждую загрузку как несовпадение контрольной
    # суммы, при внешне исправном манифесте.
    if ($ApkVersion -or $ApkUrl -or $ApkSha256) {
        if (-not ($ApkVersion -and $ApkUrl -and $ApkSha256)) {
            throw "Для андроидного манифеста нужны ВСЕ три: -ApkVersion, -ApkUrl, -ApkSha256. " +
                  "Хеш не наследуется от прежней версии намеренно: он относился бы к другому файлу."
        }
        $android = [ordered]@{
            latestVersionApk = $ApkVersion
            changelog        = $Changelog
            downloadUrlApk   = $ApkUrl
            sha256Apk        = $ApkSha256
        }
        $androidPath = Join-Path $root 'manifest_android.json'
        [System.IO.File]::WriteAllText($androidPath, ($android | ConvertTo-Json -Depth 4), $utf8NoBom)
        Write-Host "[build] wrote manifest_android.json ($ApkVersion)"
        Write-Host "[build] НА СЕРВЕРЕ его надо положить И как update/manifest_android.json, И как update/manifest.json"
    } else {
        Write-Host "[build] андроидный манифест не трогаю (APK-параметры не переданы)"
    }

    Write-Host "[build] upload build\BLauncher.jar to https://blauncher.ru/update/BLauncher.jar"
    Write-Host "[build] upload manifest_pc.json to https://blauncher.ru/update/ (НЕ как manifest.json!)"
}
