#!/bin/bash

# Interrompe subito se c'è un errore
set -e

# ==========================================
# ♻️ REMOTE RESTORE (SMART MERGE)
# ==========================================

# 1. Configurazione Percorsi
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Configurazione Server
SERVER_HOST="fcos-ha"
REMOTE_USER="core"
LOCAL_BACKUP_DIR="$PROJECT_ROOT/backups"

# File Critici da forzare post-restore
LOCAL_CADDYFILE="$PROJECT_ROOT/Caddyfile"

# 2. Selezione del Backup
if [ -n "$1" ]; then
    BACKUP_FILE="$1"
else
    BACKUP_FILE=$(ls -t "$LOCAL_BACKUP_DIR"/ha_backup_*.tar.gz 2>/dev/null | head -n 1)
fi

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ Errore: Nessun backup trovato in $LOCAL_BACKUP_DIR."
    exit 1
fi

FILENAME=$(basename "$BACKUP_FILE")

echo "🚀 Inizio procedura di ripristino su $SERVER_HOST..."
echo "📂 Backup selezionato: $FILENAME"
echo "⚠️  ATTENZIONE: Verranno ripristinati i DATI dal backup, ma la CONFIGURAZIONE verrà forzata dal PC locale."
# read -p "Premi INVIO per continuare..."

# 3. Upload Backup
echo "⬆️  Caricamento backup..."
scp "$BACKUP_FILE" "$REMOTE_USER@$SERVER_HOST:~/$FILENAME"

# 4. Estrazione Dati (Via SSH)
echo "📦 Estrazione archivio e pulizia..."
ssh "$REMOTE_USER@$SERVER_HOST" "bash -s" <<EOF
    # Stop servizi (HA è di sistema, Caddy è utente)
    sudo systemctl stop homeassistant.service 2>/dev/null || true
    systemctl --user stop caddy.service 2>/dev/null || true
    
    # Rimuovi vecchia cartella (SUDO necessario per i file creati dal container rootful)
    sudo rm -rf homeassistant/

    # Estrai il backup come utente 'core' per normalizzare i permessi
    tar -xzf "$FILENAME"
    rm "$FILENAME"
    
    # Fix permessi SELinux (se necessario)
    if command -v restorecon &> /dev/null; then
        sudo restorecon -R homeassistant/
    fi
EOF

# 5. SOVRASCRITTURA CONFIGURAZIONE (La parte fondamentale)
echo ""
echo "--- 🔧 Allineamento Configurazione (Local -> Remote) ---"

# 5.1 Caddyfile
if [ -f "$LOCAL_CADDYFILE" ]; then
    echo "✅ Aggiorno Caddyfile..."
    scp "$LOCAL_CADDYFILE" "$REMOTE_USER@$SERVER_HOST:~/homeassistant/Caddyfile"
else
    echo "⚠️  ATTENZIONE: Caddyfile locale non trovato! Userò quello del backup (se esiste)."
fi

# 5.2 Secrets
if [ -f "$LOCAL_SECRETS" ]; then
    echo "✅ Aggiorno secrets.env..."
    scp "$LOCAL_SECRETS" "$REMOTE_USER@$SERVER_HOST:~/homeassistant/secrets.env"
else
    echo "❌ ERRORE: secrets.env locale mancante! Il deploy potrebbe fallire."
fi

# 5.3 Services & Scripts
echo "✅ Aggiorno Services e Scripts..."
scp -r "$PROJECT_ROOT/services/" "$REMOTE_USER@$SERVER_HOST:~/"
scp -r "$PROJECT_ROOT/scripts/" "$REMOTE_USER@$SERVER_HOST:~/"

# 6. Deploy
echo ""
echo "🚀 Avvio Deploy..."
ssh "$REMOTE_USER@$SERVER_HOST" "chmod +x scripts/deploy_app.sh && ./scripts/deploy_app.sh"

echo "✅ Ripristino completato con successo!"