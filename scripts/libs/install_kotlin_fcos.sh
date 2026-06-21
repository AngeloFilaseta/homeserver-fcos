#!/usr/bin/env bash
set -euo pipefail

KOTLIN_VERSION="2.4.0"
JAVA_MAJOR_VERSION="21"

detect_arch() {
  case "$(uname -m)" in
    x86_64) echo "x64" ;;
    aarch64|arm64) echo "aarch64" ;;
    *)
      echo "unsupported"
      ;;
  esac
}

ensure_java() {
  echo "🔎 Verifica presenza Java..."
  if command -v java >/dev/null 2>&1; then
    local current_java_ver
    current_java_ver=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
    
    if [ "$current_java_ver" = "$JAVA_MAJOR_VERSION" ]; then
      echo "✅ Java $JAVA_MAJOR_VERSION già presente e corrispondente. Salto installazione."
      return 0
    else
      echo "⚠️  Trovata versione Java differente ($current_java_ver). Procedo con installazione della versione $JAVA_MAJOR_VERSION..."
    fi
  fi

  echo "📥 Installazione JRE Temurin in /opt/jre..."

  local arch
  arch="$(detect_arch)"
  if [ "$arch" = "unsupported" ]; then
    echo "❌ Architettura non supportata per installazione Java: $(uname -m)"
    exit 1
  fi

  local tmp_java java_tgz java_url
  tmp_java="$(mktemp -d)"
  java_tgz="$tmp_java/jre.tar.gz"
  java_url="https://api.adoptium.net/v3/binary/latest/${JAVA_MAJOR_VERSION}/ga/linux/${arch}/jre/hotspot/normal/eclipse"

  curl -fL "$java_url" -o "$java_tgz"
  sudo rm -rf /opt/jre
  sudo mkdir -p /opt/jre
  sudo tar -xzf "$java_tgz" -C /opt/jre --strip-components=1
  rm -rf "$tmp_java"

  if [ ! -x /opt/jre/bin/java ]; then
    echo "❌ Installazione Java fallita: /opt/jre/bin/java non trovato"
    exit 1
  fi

  sudo ln -sf /opt/jre/bin/java /usr/local/bin/java
  echo "✅ Java installato: $(/opt/jre/bin/java -version 2>&1 | head -n 1)"
}

ensure_kotlin() {
  echo "🔎 Verifica presenza Kotlin..."
  if command -v kotlin >/dev/null 2>&1; then
    local current_kot_ver
    current_kot_ver=$(kotlin -version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || echo "unknown")

    if [ "$current_kot_ver" = "$KOTLIN_VERSION" ]; then
      echo "✅ Kotlin $KOTLIN_VERSION è già installato. Salto installazione."
      return 0
    else
      echo "⚠️  Trovata versione Kotlin differente ($current_kot_ver). Procedo all'installazione della $KOTLIN_VERSION..."
    fi
  fi

  echo "📥 Installazione manuale Kotlin compiler in /opt/kotlinc..."

  TMP_DIR="$(mktemp -d)"
  ZIP_PATH="$TMP_DIR/kotlinc.zip"
  URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"

  cleanup() {
    rm -rf "$TMP_DIR"
  }
  trap cleanup EXIT

  curl -fL "$URL" -o "$ZIP_PATH"

  sudo rm -rf /opt/kotlinc

  if command -v unzip >/dev/null 2>&1; then
    sudo unzip -oq "$ZIP_PATH" -d /opt
  elif command -v bsdtar >/dev/null 2>&1; then
    sudo bsdtar -xf "$ZIP_PATH" -C /opt
  else
    python3 - "$ZIP_PATH" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
z.extractall('/tmp/kotlinc_extract')
PY
    sudo rm -rf /opt/kotlinc
    sudo mv /tmp/kotlinc_extract/kotlinc /opt/kotlinc
    sudo rm -rf /tmp/kotlinc_extract
  fi

  if [ ! -x /opt/kotlinc/bin/kotlin ]; then
    echo "❌ Installazione fallita: /opt/kotlinc/bin/kotlin non trovato"
    exit 1
  fi

  sudo ln -sf /opt/kotlinc/bin/kotlin /usr/local/bin/kotlin
  sudo ln -sf /opt/kotlinc/bin/kotlinc /usr/local/bin/kotlinc

  echo ""
  echo "✅ Kotlin installato con successo."
  echo "   Versione: $(/opt/kotlinc/bin/kotlin -version 2>&1 | head -n 1)"
  echo "   Binari: /usr/local/bin/kotlin e /usr/local/bin/kotlinc"
}

# Run execution
ensure_java
ensure_kotlin