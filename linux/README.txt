BLauncher для Linux (x86_64)

Запуск без установки:   ./blauncher
Установка в меню:       ./install.sh        (без sudo, в ~/.local/opt/BLauncher)
Удаление:               ./install.sh --remove

install.sh сообщает blauncher.ru, дошла ли установка до конца и на каком шаге остановилась
(номер установки, версия, дистрибутив, разрядность — без имён и путей). Отключить:
./install.sh --no-stats  или  BLAUNCHER_NOSTATS=1 ./install.sh

Сборки, миры и настройки хранятся в ~/.local/share/BLauncher (папку можно сменить
переменной BLAUNCHER_HOME). Java для лаунчера уже внутри, Java для игры лаунчер скачает сам.

Для самой игры нужны обычные библиотеки рабочего стола: OpenGL-драйвер видеокарты,
libX11/libXrandr (старые версии Minecraft), PulseAudio или PipeWire для звука.

Отличия от Windows:
 - Комнаты для игры с друзьями (ZeroTier) и playit.gg пока только в Windows. Сервер на
   этом ПК и панель VPS работают.
 - Порт для друзей лаунчер в брандмауэре не открывает: если включён ufw,
     sudo ufw allow 25565/tcp
 - Ключи панели VPS шифруются ключом из ~/.config/blauncher/secrets.key — без этого файла
   скопированная папка данных ключи не раскроет.
