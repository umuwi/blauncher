# Скачивает сторонние библиотеки с Maven Central в app/ и lib/ — те же версии, что в релизах.
# Сами jar в репозиторий не кладутся (~40 МБ бинарников, в основном JavaFX), поэтому после
# git clone нужно один раз запустить этот скрипт, а потом build.ps1.
#
#   app/  — classpath лаунчера (javac собирает против них, jpackage кладёт рядом с BLauncher.jar)
#   lib/  — распаковываются ВНУТРЬ BLauncher.jar при сборке (см. build.ps1 и раздел «Сборка» в README)
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$central = 'https://repo1.maven.org/maven2'

# group, artifact, version, classifier, папка
$libs = @(
    @('org.ow2.asm',                'asm',                  '9.6',    '',    'app'),
    @('org.ow2.asm',                'asm-commons',          '9.6',    '',    'app'),
    @('org.bouncycastle',           'bcpkix-jdk18on',       '1.78.1', '',    'app'),
    @('org.bouncycastle',           'bcprov-jdk18on',       '1.78.1', '',    'app'),
    @('com.google.code.gson',       'gson',                 '2.10.1', '',    'app'),
    @('org.kordamp.ikonli',         'ikonli-core',          '12.3.1', '',    'app'),
    @('org.kordamp.ikonli',         'ikonli-feather-pack',  '12.3.1', '',    'app'),
    @('org.kordamp.ikonli',         'ikonli-javafx',        '12.3.1', '',    'app'),
    @('org.openjfx',                'javafx-base',          '21',     'win', 'app'),
    @('org.openjfx',                'javafx-controls',      '21',     'win', 'app'),
    @('org.openjfx',                'javafx-fxml',          '21',     'win', 'app'),
    @('org.openjfx',                'javafx-graphics',      '21',     'win', 'app'),
    @('org.openjfx',                'javafx-media',         '21',     'win', 'app'),
    @('org.openjfx',                'javafx-swing',         '21',     'win', 'app'),
    @('com.twelvemonkeys.common',   'common-image',         '3.12.0', '',    'lib'),
    @('com.twelvemonkeys.common',   'common-io',            '3.12.0', '',    'lib'),
    @('com.twelvemonkeys.common',   'common-lang',          '3.12.0', '',    'lib'),
    @('com.twelvemonkeys.imageio',  'imageio-core',         '3.12.0', '',    'lib'),
    @('com.twelvemonkeys.imageio',  'imageio-metadata',     '3.12.0', '',    'lib'),
    @('com.twelvemonkeys.imageio',  'imageio-webp',         '3.12.0', '',    'lib'),
    @('com.github.mwiede',          'jsch',                 '2.28.7', '',    'lib')
)

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
foreach ($l in $libs) {
    $group, $artifact, $version, $classifier, $dir = $l
    $dest = Join-Path $root $dir
    New-Item -ItemType Directory -Force $dest | Out-Null
    # Имя без classifier — так их называет BLauncher.cfg (app.classpath=$APPDIR\javafx-base-21.jar).
    $file = Join-Path $dest "$artifact-$version.jar"
    if ((Test-Path $file) -and -not $Force) { Write-Host "есть  $artifact-$version"; continue }
    $suffix = if ($classifier) { "-$classifier" } else { '' }
    $url = "$central/$($group.Replace('.', '/'))/$artifact/$version/$artifact-$version$suffix.jar"
    Write-Host "качаю $artifact-$version$suffix"
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
    # Контрольная сумма из самого Maven Central: оборванная загрузка не должна тихо попасть в сборку.
    $expected = (Invoke-WebRequest -Uri "$url.sha1" -UseBasicParsing).Content.ToString().Trim().Split(' ')[0]
    $actual = (Get-FileHash $file -Algorithm SHA1).Hash.ToLower()
    if ($actual -ne $expected.ToLower()) {
        Remove-Item $file
        throw "sha1 не совпал для $artifact-$version ($actual вместо $expected)"
    }
}
Write-Host 'Готово.'
