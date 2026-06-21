#!/bin/bash

# ==========================================
# 💿 FCOS CUSTOM ISO BUILDER (Auto-Regenerate)
# ==========================================

# --- CONFIGURAZIONE CARTELLE ---
CACHE_DIR="cache"
BUILD_DIR="build"
ISO_FILENAME="fedora-coreos-stream.iso"   # Nome file nella cache
OUTPUT_FILENAME="fcos-autoinstall.iso"    # Nome file finale

# Percorsi completi
INPUT_ISO="$CACHE_DIR/$ISO_FILENAME"
OUTPUT_ISO="$BUILD_DIR/$OUTPUT_FILENAME"
IGNITION_FILE="$BUILD_DIR/config.ign"     # Ora è dentro build/
TARGET_DISK="/dev/sda"                    # ⚠️ VERIFICA: /dev/sda o /dev/nvme0n1

# --- 0. PREPARAZIONE AMBIENTE ---
mkdir -p "$CACHE_DIR"
mkdir -p "$BUILD_DIR"

# Rilevamento Engine
if command -v podman &> /dev/null; then
    CMD="podman"
    ARGS="--security-opt label=disable"
    echo "🐳 Usando engine: Podman"
elif command -v docker &> /dev/null; then
    CMD="docker"
    ARGS=""
    echo "🐳 Usando engine: Docker"
else
    echo "❌ Errore: Docker o Podman non trovati."
    exit 1
fi

# --- 1. RIGENERAZIONE AUTOMATICA IGNITION ---
echo "🔄 Eseguo la rigenerazione della configurazione..."
./scripts/generate_ignition.sh

# Verifica se la generazione è andata a buon fine
if [ $? -ne 0 ] || [ ! -f "$IGNITION_FILE" ]; then
    echo "❌ Errore Critico: Impossibile generare il file Ignition."
    exit 1
fi

# --- 2. GESTIONE CACHE ISO ---
# Sposta vecchi file root nella cache se presenti
if [ -f "$ISO_FILENAME" ]; then mv "$ISO_FILENAME" "$INPUT_ISO"; fi

if [ -f "$INPUT_ISO" ]; then
    echo "✅ ISO Cache trovata: $INPUT_ISO"
else
    echo "⬇️  ISO non trovata in $CACHE_DIR. Avvio il download..."
    
    $CMD run $ARGS --rm \
        -v "$PWD":/data -w "/data/$CACHE_DIR" \
        quay.io/coreos/coreos-installer:release \
        download -s stable -p metal -f iso
    
    # Trova e rinomina
    DOWNLOADED_FILE=$(find "$CACHE_DIR" -name "fedora-coreos-*.iso" -type f | head -n 1)
    if [ -n "$DOWNLOADED_FILE" ]; then
        mv "$DOWNLOADED_FILE" "$INPUT_ISO"
    else
        echo "❌ Errore: Download fallito."
        exit 1
    fi
fi

# --- 3. CREAZIONE ISO CUSTOMIZZATA ---
echo "🔨 Creazione ISO Autoinstallante in $BUILD_DIR..."
echo "   Source:      $INPUT_ISO"
echo "   Target Disk: $TARGET_DISK"
echo "   Ignition:    $IGNITION_FILE"

# Nota: Passiamo i percorsi relativi alla root del progetto
$CMD run $ARGS --rm \
    -v "$PWD":/data -w /data \
    quay.io/coreos/coreos-installer:release \
    iso customize \
    --dest-device "$TARGET_DISK" \
    --dest-ignition "$IGNITION_FILE" \
    -o "$OUTPUT_ISO" \
    "$INPUT_ISO"

if [ $? -eq 0 ]; then
    echo ""
    echo "🎉 SUCCESS! ISO Pronta: $OUTPUT_ISO"
    echo "👉 Flasha questo file sulla USB."
else
    echo "❌ Errore creazione ISO."
    exit 1
fi