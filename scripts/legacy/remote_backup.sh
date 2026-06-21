#!/bin/bash

# Interrompe in caso di errori
set -e

# ==========================================
# 📦 REMOTE BACKUP (HA + SECRETS + DATA)
# ==========================================

# Configurazione
SERVER_HOST="fcos-ha"
REMOTE_USER="core"
REMOTE_DIR="homeassistant"        
LOCAL_BACKUP_DIR="./backups"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILENAME="ha_backup_${TIMESTAMP}.tar.gz"

# 1. Preparazione Locale
mkdir -p "$LOCAL_BACKUP_DIR"

echo "🚀 Inizio backup da $SERVER_HOST..."

# 2. Verifica Esistenza
if ! ssh "$REMOTE_USER@$SERVER_HOST" "[ -d $REMOTE_DIR ]"; then
    echo "❌ ERRORE: La cartella $REMOTE_DIR non esiste sul server!"
    exit 1
fi

# 3. Stop Servizi & Compressione
echo "⏸️  Arresto servizi e compressione..."
# Rimosso il flag -t per evitare il warning del pseudo-terminal
ssh "$REMOTE_USER@$SERVER_HOST" "bash -s" <<EOF
    # Stop di HA (servizio di sistema) e Caddy (servizio utente)
    sudo systemctl stop homeassistant.service
    systemctl --user stop caddy.service || true
    
    # Compressione (eseguita con SUDO per leggere i file creati dal container rootful)
    sudo tar -czf "$BACKUP_FILENAME" "$REMOTE_DIR" || [ \$? -eq 1 ]

    # Cambia il proprietario dell'archivio a "core" per permettere il download
    sudo chown $REMOTE_USER:$REMOTE_USER "$BACKUP_FILENAME"

    # Riavvio
    echo "▶️  Riavvio servizi..."
    systemctl --user start caddy.service || true
    sudo systemctl start homeassistant.service
EOF

# 4. Download
echo "⬇️  Download backup..."
scp "$REMOTE_USER@$SERVER_HOST:~/$BACKUP_FILENAME" "$LOCAL_BACKUP_DIR/$BACKUP_FILENAME"

# 5. Pulizia Remota
echo "🧹 Pulizia file temporaneo..."
ssh "$REMOTE_USER@$SERVER_HOST" "rm ~/$BACKUP_FILENAME"

echo "✅ Backup salvato in: $LOCAL_BACKUP_DIR/$BACKUP_FILENAME"