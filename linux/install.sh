#!/bin/sh
# Установка BLauncher для текущего пользователя, без sudo:
#   ./install.sh              — в ~/.local/opt/BLauncher, ярлык в меню, команда blauncher
#   ./install.sh --remove     — убрать программу (данные в ~/.local/share/BLauncher остаются)
#   ./install.sh --no-stats   — не сообщать blauncher.ru о ходе установки (или BLAUNCHER_NOSTATS=1)
#
# Папка установки должна быть доступна на запись: автообновление меняет в ней lib/app/BLauncher.jar.
set -e
SRC="$(dirname "$(readlink -f "$0")")"
DEST="$HOME/.local/opt/BLauncher"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
BIN="$HOME/.local/bin"

if [ "$1" = "--remove" ]; then
  rm -rf "$DEST" "$APPS/blauncher.desktop" "$BIN/blauncher"
  echo "BLauncher удалён. Сборки и настройки остались в ${XDG_DATA_HOME:-$HOME/.local/share}/BLauncher"
  exit 0
fi

# --- ход установки в статистику (21.10.2026) ------------------------------------------------
# Те же отметки, что шлёт установщик Windows (api/install.php): начало, шаг, готово/ошибка.
# 21.10 Linux-архив скачал человек, а лаунчер так ни разу и не вышел на связь — где он
# застрял, понять было не из чего. Уходит: случайный номер установки, версия, «linux» с
# названием дистрибутива, разрядность, шаг и код ошибки. Ни путей, ни имени пользователя.
STATS=1
for a in "$@"; do
  if [ "$a" = "--no-stats" ]; then STATS=0; fi
done
if [ "${BLAUNCHER_NOSTATS:-0}" = "1" ]; then STATS=0; fi
STATS_URL="${BLAUNCHER_STATS_URL:-https://blauncher.ru/api/install.php}"
ID="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
if [ ${#ID} -ne 32 ]; then STATS=0; fi
VER="$(tr -d ' \r\n' < "$SRC/VERSION" 2>/dev/null || true)"
OS_NAME="linux"
if [ -r /etc/os-release ]; then
  DISTRO="$(. /etc/os-release 2>/dev/null; printf '%s %s' "${ID:-}" "${VERSION_ID:-}")"
  DISTRO="$(printf '%s' "$DISTRO" | tr 'A-Z' 'a-z' | tr -cd 'a-z0-9. -' | cut -c1-30)"
  if [ -n "${DISTRO# }" ]; then OS_NAME="linux ${DISTRO% }"; fi
fi
case "$(uname -m 2>/dev/null)" in
  x86_64|amd64) ARCH=x64 ;; aarch64|arm64) ARCH=arm64 ;; i?86) ARCH=x86 ;; *) ARCH= ;;
esac
T0="$(date +%s)"
ms() { echo $(( ($(date +%s) - T0) * 1000 )); }
send() {
  [ "$STATS" = 1 ] || return 0
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 4 -H 'Content-Type: application/json' -d "$1" "$STATS_URL" >/dev/null 2>&1 || true
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 4 -O /dev/null --header='Content-Type: application/json' --post-data="$1" "$STATS_URL" 2>/dev/null || true
  fi
}
STAGE=start
DONE=0
REASON=""
on_exit() {
  code=$?
  if [ "$DONE" != 1 ] && [ $code -ne 0 ]; then
    send "{\"action\":\"fail\",\"id\":\"$ID\",\"stage\":\"$STAGE\",\"detail\":\"exit$code${REASON:+:$REASON}\",\"ms\":$(ms)}"
  fi
}
trap on_exit EXIT
trap 'send "{\"action\":\"cancel\",\"id\":\"$ID\",\"stage\":\"$STAGE\",\"detail\":\"signal\",\"ms\":$(ms)}"; DONE=1; exit 130' INT TERM
send "{\"action\":\"start\",\"id\":\"$ID\",\"v\":\"$VER\",\"os\":\"$OS_NAME\",\"arch\":\"$ARCH\"}"
stage() { STAGE="$1"; send "{\"action\":\"stage\",\"id\":\"$ID\",\"stage\":\"$1\"}"; }

# --- установка --------------------------------------------------------------------------------
mkdir -p "$HOME/.local/opt"
if [ "$SRC" != "$DEST" ]; then
  stage linux_copy
  # Место: копия целиком плюс запас — как проверка «space» у установщика Windows.
  NEED_KB="$(du -sk "$SRC" 2>/dev/null | cut -f1)"
  FREE_KB="$(df -Pk "$HOME/.local/opt" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [ -n "$NEED_KB" ] && [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt $((NEED_KB + 65536)) ]; then
    REASON=space
    echo "Не хватает места в $HOME/.local/opt: нужно около $(( (NEED_KB + 65536) / 1024 )) МБ" >&2
    exit 1
  fi
  rm -rf "$DEST.tmp"
  cp -a "$SRC" "$DEST.tmp"
  rm -rf "$DEST"
  mv "$DEST.tmp" "$DEST"
fi

stage linux_menu
mkdir -p "$APPS" "$BIN"
ln -sf "$DEST/blauncher" "$BIN/blauncher"
cat > "$APPS/blauncher.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=BLauncher
Comment=Minecraft launcher
Exec="$DEST/blauncher"
Icon=$DEST/blauncher.png
Terminal=false
Categories=Game;
EOF
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true

DONE=1
send "{\"action\":\"done\",\"id\":\"$ID\",\"ms\":$(ms)}"

echo "BLauncher установлен в $DEST"
echo "Запуск: из меню приложений или командой blauncher"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "(добавьте $BIN в PATH, чтобы работала команда blauncher)";; esac
