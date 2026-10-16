# BLauncher

Лаунчер Minecraft: Java Edition для Windows на JavaFX. Сайт и загрузка: [blauncher.ru](https://blauncher.ru).

## Возможности

- **Сборки**: отдельные профили со своими модами, ресурс-паками, шейдерами и датапаками;
  Fabric, Forge, NeoForge и OptiFine ставятся в один клик, старые версии Forge
  (1.7.10–1.16.5) тоже.
- **Моды с Modrinth**: поиск и установка с фильтром по версии и загрузчику, проверка конфликтов,
  обмен списком модов кодом.
- **Вход через Microsoft** и офлайн-ник. Нужная версия Java скачивается сама.
- **Свой сервер**: локальный сервер в один клик с консолью и списком игроков, доступ друзьям через
  ZeroTier или playit.gg; управление сервером на VPS по SSH (установка, консоль, бэкапы, плагины).
- **Друзья по коду**: список друзей, их статус и приглашения в комнату.
- **Инструменты**: редактор скина и плаща, студия предметов и блоков с 3D-превью.
- **Оформление**: темы и собственная палитра, фоны, масштаб шрифта, русский и английский интерфейс.

## Сборка

Нужны Windows, [JDK 21](https://adoptium.net/) и PowerShell. Системы сборки вроде Maven или Gradle
нет: всё делают два скрипта.

```powershell
git clone https://github.com/umuwi/blauncher.git
cd blauncher
.\fetch-libs.ps1                              # сторонние jar с Maven Central → app\ и lib\
.\build.ps1 -Version v0.0.0-dev -SkipManifest # → app\BLauncher.jar
java -cp "app\*" com.example.launcher.AppLauncher
```

- `app\` — classpath лаунчера (JavaFX, Gson, BouncyCastle, ASM, Ikonli).
- `lib\` — библиотеки, которые `build.ps1` распаковывает **внутрь** `BLauncher.jar`
  (декодер WebP, JSch для SSH). Автообновление заменяет только `BLauncher.jar`, поэтому новые
  зависимости добавляются сюда, а не в `app\`.
- Данные лаунчер пишет относительно рабочей папки: `launcher_root\` (сборки, моды, миры, серверы)
  и `config\`. Для экспериментов запускайте его из пустой временной папки.
- Без `-SkipManifest` `build.ps1` пишет манифесты автообновления — это шаг релиза.
- `build-installer.ps1` собирает установщик (`installer\`, C# 5, `csc` из .NET Framework); ему нужен
  app-image от `jpackage` в `dist\BLauncher`. Подробности — в [installer/README.md](installer/README.md).
- `blcore\` — мод-компаньон для Fabric (значки и плащи игроков BLauncher), собирается Gradle;
  готовый jar лежит в `src/main/resources/blcore/`.

## Устройство

```
src/main/java/com/example/launcher/
  AppLauncher, MainApp     точка входа
  controller/              UI: MainController (основной экран) и вынесенные панели
  service/                 логика без JavaFX: запуск игры и сервера, загрузчики, Modrinth,
                           версии, Java, обновления, ZeroTier, playit
  service/vps/             сервер на VPS по SSH
  agent/                   клиент старого VPS-агента
  model/                   модели для Gson
  ui/                      компоненты: анимации, редактор скина, студия предметов
src/main/resources/
  fxml/main.fxml           разметка
  css/theme-template.css   тема; цвета подставляет PaletteService из палитры
  i18n/                    строки интерфейса
```

Серверная часть blauncher.ru (обновления, друзья, статистика) в этот репозиторий не входит.

## Сторонние компоненты

Шрифты Inter и JetBrains Mono распространяются по SIL Open Font License 1.1. Библиотеки
скачиваются `fetch-libs.ps1` и остаются под своими лицензиями (JavaFX — GPLv2 с Classpath
Exception, остальные — Apache 2.0, MIT, BSD). Minecraft — товарный знак Mojang; BLauncher
не связан с Mojang и Microsoft.
