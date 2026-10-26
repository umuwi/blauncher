#!/usr/bin/env bash
# Linux-сборка BLauncher: app-image jpackage + Linux-JavaFX + свой рантайм, упакованные в tar.gz.
#
#   ./build-linux.sh                 скомпилировать src/ и собрать пакет
#   ./build-linux.sh --jar FILE      взять готовый BLauncher.jar (тот, что собрал build.ps1 и что
#                                    раздаёт автообновление) вместо компиляции
#
# Запускается в Linux (или WSL) с JDK 21: JAVA_HOME, иначе java на PATH, иначе ~/bl-port/jdk.
#
# Почему отдельный скрипт, а не ветка build.ps1. BLauncher.jar от ОС не зависит: нативный код
# есть только у JavaFX, а она лежит отдельными jar рядом с ним. Поэтому Linux-сборке нужны
# свои javafx-*-linux.jar и свой рантайм, а BLauncher.jar — тот же самый, и автообновление
# (оно меняет ровно один BLauncher.jar, см. UpdateService) у Linux-сборки общее с Windows.
# Сам jpackage Linux-образ собирает только на Linux.
#
# Манифестов обновления и загрузки на сайт здесь нет: это шаг релиза, он остаётся в build.ps1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/dist-linux"
WORK="$OUT/work"
FX_VERSION=21
JAR_ARG=""
if [[ "${1:-}" == "--jar" ]]; then JAR_ARG="$(realpath "$2")"; fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then JDK="$JAVA_HOME"
elif command -v jpackage >/dev/null; then JDK="$(dirname "$(dirname "$(readlink -f "$(command -v jpackage)")")")"
elif [[ -x "$HOME/bl-port/jdk/bin/jpackage" ]]; then JDK="$HOME/bl-port/jdk"
else echo "Нужен JDK 21 (javac, jlink, jpackage): задайте JAVA_HOME" >&2; exit 1; fi
"$JDK/bin/java" -version 2>&1 | head -1

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) FX_CLASSIFIER=linux; PKG_ARCH=x64 ;;
  aarch64) FX_CLASSIFIER=linux-aarch64; PKG_ARCH=arm64 ;;
  *) echo "Архитектура $ARCH не поддерживается JavaFX" >&2; exit 1 ;;
esac

rm -rf "$WORK"
mkdir -p "$WORK/input" "$OUT/cache"

# --- зависимости -----------------------------------------------------------------------------
# Всё, кроме JavaFX, берём из app/ (его наполняет fetch-libs.ps1): эти jar от ОС не зависят.
# JavaFX — linux-классификатор с Maven Central, в кэш, чтобы не качать на каждой сборке.
shopt -s nullglob
for j in "$ROOT"/app/*.jar; do
  name="$(basename "$j")"
  [[ "$name" == BLauncher.jar || "$name" == javafx-* ]] && continue
  cp "$j" "$WORK/input/"
done
for m in base controls fxml graphics media swing; do
  f="javafx-$m-$FX_VERSION-$FX_CLASSIFIER.jar"
  if [[ ! -s "$OUT/cache/$f" ]]; then
    echo "[build] $f"
    curl -sSfL --retry 5 --retry-all-errors --connect-timeout 20 -o "$OUT/cache/$f" "https://repo1.maven.org/maven2/org/openjfx/javafx-$m/$FX_VERSION/$f"
  fi
  # Имя без классификатора — то же, что у Windows-сборки в app/.
  cp "$OUT/cache/$f" "$WORK/input/javafx-$m-$FX_VERSION.jar"
done
if [[ -z "$(ls "$WORK/input"/gson-*.jar 2>/dev/null)" ]]; then
  echo "В app/ нет зависимостей — сначала fetch-libs.ps1 (или скопируйте jar из Windows-сборки)" >&2
  exit 1
fi

# --- BLauncher.jar ---------------------------------------------------------------------------
if [[ -n "$JAR_ARG" ]]; then
  cp "$JAR_ARG" "$WORK/input/BLauncher.jar"
else
  STAGE="$WORK/classes"
  mkdir -p "$STAGE"
  CP="$(ls "$WORK/input"/*.jar "$ROOT"/lib/*.jar 2>/dev/null | tr '\n' ':')"
  find "$ROOT/src/main/java" -name '*.java' > "$WORK/sources.txt"
  # --release 21 — как в build.ps1: рантайм закреплён на 21.
  "$JDK/bin/javac" -nowarn --release 21 -encoding UTF-8 -cp "$CP" -d "$STAGE" @"$WORK/sources.txt"
  cp -r "$ROOT/src/main/resources/." "$STAGE/"
  # Номер версии — из BL_VERSION, иначе из Windows-сборки в app/ (его впечатал build.ps1):
  # без version.properties лаунчер считал бы себя старым и всё время предлагал обновление.
  VERSION="${BL_VERSION:-}"
  if [[ -z "$VERSION" && -f "$ROOT/app/BLauncher.jar" ]]; then
    VERSION="$(cd "$WORK" && "$JDK/bin/jar" xf "$ROOT/app/BLauncher.jar" version.properties && sed -n 's/^version=//p' version.properties | tr -d '\r')"
    rm -f "$WORK/version.properties"
  fi
  printf '# Создаётся build-linux.sh.\nversion=%s\n' "${VERSION:-dev}" > "$STAGE/version.properties"
  # lib/*.jar — внутрь, как в build.ps1 (ImageIO WebP, JSch). SPI-файлы склеиваются, подписи
  # и module-info выкидываются.
  for v in "$ROOT"/lib/*.jar; do
    tmp="$WORK/unz"; rm -rf "$tmp"; mkdir -p "$tmp"
    (cd "$tmp" && "$JDK/bin/jar" xf "$v")
    rm -rf "$tmp/META-INF/maven" "$tmp/META-INF/MANIFEST.MF" "$tmp/module-info.class"
    find "$tmp/META-INF" -maxdepth 1 \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' \) -delete 2>/dev/null || true
    if [[ -d "$tmp/META-INF/services" ]]; then
      mkdir -p "$STAGE/META-INF/services"
      for svc in "$tmp/META-INF/services"/*; do
        cat "$svc" >> "$STAGE/META-INF/services/$(basename "$svc")"; echo >> "$STAGE/META-INF/services/$(basename "$svc")"
      done
      rm -rf "$tmp/META-INF/services"
    fi
    cp -r "$tmp/." "$STAGE/"
  done
  for svc in "$STAGE/META-INF/services"/*; do
    [[ -f "$svc" ]] && grep -v '^\s*#' "$svc" | sed 's/^\s*//;s/\s*$//' | grep -v '^$' | awk '!seen[$0]++' > "$svc.tmp" && mv "$svc.tmp" "$svc"
  done
  printf 'Manifest-Version: 1.0\nMulti-Release: true\n' > "$WORK/manifest.mf"
  "$JDK/bin/jar" cfm "$WORK/input/BLauncher.jar" "$WORK/manifest.mf" -C "$STAGE" .
fi

# --- рантайм ---------------------------------------------------------------------------------
# Тот же набор модулей, что в Windows-рантайме (dist/BLauncher/runtime/release), минус
# jdk.crypto.mscapi — он существует только в Windows.
MODULES="java.base,java.compiler,java.datatransfer,java.xml,java.prefs,java.desktop,java.instrument,java.logging,java.management,java.security.sasl,java.naming,java.rmi,java.management.rmi,java.net.http,java.scripting,java.security.jgss,java.smartcardio,java.transaction.xa,java.sql,java.sql.rowset,java.xml.crypto,jdk.accessibility,jdk.internal.jvmstat,jdk.attach,jdk.charsets,jdk.internal.opt,jdk.zipfs,jdk.compiler,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.dynalink,jdk.internal.ed,jdk.editpad,jdk.httpserver,jdk.incubator.vector,jdk.internal.le,jdk.jartool,jdk.javadoc,jdk.management,jdk.management.agent,jdk.jconsole,jdk.jdeps,jdk.jdwp.agent,jdk.jdi,jdk.jfr,jdk.jlink,jdk.jpackage,jdk.jshell,jdk.jsobject,jdk.jstatd,jdk.localedata,jdk.management.jfr,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.random,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.unsupported.desktop,jdk.xml.dom"
"$JDK/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
  --output "$WORK/runtime"

# --- app-image -------------------------------------------------------------------------------
# Все jar из --input jpackage сам кладёт в classpath (BLauncher.cfg), как в Windows-сборке.
"$JDK/bin/jpackage" --type app-image --dest "$WORK/image" --name BLauncher \
  --input "$WORK/input" --main-jar BLauncher.jar --main-class com.example.launcher.AppLauncher \
  --runtime-image "$WORK/runtime" --icon "$ROOT/src/main/resources/images/logo.png" \
  --java-options '-Dfile.encoding=UTF-8' --app-version 1.0
IMG="$WORK/image/BLauncher"
cp "$ROOT/src/main/resources/images/logo.png" "$IMG/blauncher.png"
cp "$ROOT/linux/blauncher" "$ROOT/linux/install.sh" "$ROOT/linux/README.txt" "$IMG/"
chmod +x "$IMG/blauncher" "$IMG/install.sh"
# Номер версии рядом с install.sh — его отметки установки в статистике берут версию отсюда (21.10.2026).
(cd "$WORK" && "$JDK/bin/jar" xf "$WORK/input/BLauncher.jar" version.properties \
  && sed -n 's/^version=//p' version.properties | tr -d '\r' > "$IMG/VERSION"; rm -f version.properties) || true

PKG="$OUT/BLauncher-linux-$PKG_ARCH.tar.gz"
tar -C "$WORK/image" -czf "$PKG" BLauncher
echo "[build] $PKG ($(du -h "$PKG" | cut -f1))"
