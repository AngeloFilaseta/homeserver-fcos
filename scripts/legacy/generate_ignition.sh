#!/bin/bash

# Interrompe l'esecuzione se un comando fallisce
set -e

# ==========================================
# 🔥 IGNITION GENERATOR
# ==========================================

# --- 1. CONFIGURAZIONE PERCORSI ---
# Ottieni la directory dello script e la root del progetto
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Definizioni File e Cartelle
TEMPLATE_FILE="$PROJECT_ROOT/config.bu.template"
BUILD_DIR="$PROJECT_ROOT/build"
OUTPUT_FILE="$BUILD_DIR/config.ign"
REQUIRED_KEY="$HOME/.ssh/id_homeserver.pub"

# --- 2. RILEVAMENTO CONTAINER ENGINE ---
if command -v podman &> /dev/null; then
    ENGINE="podman"
    # Podman richiede --security-opt label=disable su alcuni sistemi per pipe stdin/stdout
    ARGS="--security-opt label=disable" 
    echo "🐳 Engine rilevato: Podman"
elif command -v docker &> /dev/null; then
    ENGINE="docker"
    ARGS=""
    echo "🐳 Engine rilevato: Docker"
else
    echo "❌ ERRORE: Nessun container engine trovato (Docker o Podman)."
    exit 1
fi

# --- 3. CONTROLLO CHIAVE SSH ---
if [ ! -f "$REQUIRED_KEY" ]; then
    echo "❌ ERRORE FATALE: Chiave SSH mancante!"
    echo "   Percorso atteso: $REQUIRED_KEY"
    echo "   Generala con: ssh-keygen -t ed25519 -C \"admin@homeserver\" -f ~/.ssh/id_homeserver"
    exit 1
fi

# Leggi contenuto chiave (rimuovendo spazi extra)
SSH_KEY_CONTENT=$(cat "$REQUIRED_KEY" | tr -d '\n')
echo "✅ Chiave trovata (${SSH_KEY_CONTENT:0:20}...)"

# --- 4. PREPARAZIONE BUILD ---
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "❌ ERRORE: Template $TEMPLATE_FILE non trovato."
    exit 1
fi

# Crea la cartella build se non esiste
mkdir -p "$BUILD_DIR"

echo "⚙️  Generazione file Ignition in: $OUTPUT_FILE"

# --- 5. ESECUZIONE (BUTANE) ---
# Sostituzione placeholder + Conversione Butane
sed "s|%%SSH_PUB_KEY%%|$SSH_KEY_CONTENT|g" "$TEMPLATE_FILE" | \
$ENGINE run $ARGS --interactive --rm \
       -v "$(pwd)":/cwd -w /cwd \
       quay.io/coreos/butane:release \
       --pretty --strict --files-dir /cwd > "$OUTPUT_FILE"

# Verifica risultato (ridondante con set -e, ma utile per feedback visivo)
if [ -f "$OUTPUT_FILE" ]; then
    echo "🎉 SUCCESSO! File generato correttamente."
else
    echo "❌ ERRORE: Il file di output non è stato creato."
    exit 1
fi