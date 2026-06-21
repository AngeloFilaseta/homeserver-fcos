#!/bin/bash

# ==========================================
# ⚡ USB FLASHER (Mac & Linux)
# ==========================================
# Scrive l'ISO generata sulla chiavetta USB in modo sicuro.

# Configurazione
BUILD_DIR="build"
ISO_FILENAME="fcos-autoinstall.iso"
ISO_PATH="$BUILD_DIR/$ISO_FILENAME"

# 1. CONTROLLO ISO
if [ ! -f "$ISO_PATH" ]; then
    echo "❌ Errore: ISO non trovata in $ISO_PATH"
    echo "   Esegui prima: ./scripts/build_custom_iso.sh"
    exit 1
fi

echo "💿 File ISO pronto: $ISO_FILENAME"
echo "   Dimensione: $(du -h "$ISO_PATH" | cut -f1)"
echo ""

# 2. RILEVAMENTO DISCHI ESTERNI
OS="$(uname)"
if [ "$OS" == "Darwin" ]; then
    # --- MACOS ---
    echo "🔍 Dischi ESTERNI rilevati (macOS):"
    diskutil list external
    echo ""
    echo "👉 Inserisci il device node della chiavetta (es. /dev/disk2):"
    read -r DISK_ID
else
    # --- LINUX ---
    echo "🔍 Dischi USB rilevati (Linux):"
    lsblk -o NAME,MODEL,SIZE,TRAN,TYPE | grep "usb"
    echo ""
    echo "👉 Inserisci il device node della chiavetta (es. /dev/sdb):"
    read -r DISK_ID
fi

# 3. VERIFICHE DI SICUREZZA
if [ -z "$DISK_ID" ]; then
    echo "❌ Operazione annullata. Nessun disco selezionato."
    exit 1
fi

if [ ! -e "$DISK_ID" ]; then
    echo "❌ Errore: Il dispositivo $DISK_ID non esiste."
    exit 1
fi

# Protezione extra: Impedisce di scrivere su disk0 o disk1 (solitamente sistema) su Mac
if [[ "$OS" == "Darwin" && ("$DISK_ID" == "/dev/disk0" || "$DISK_ID" == "/dev/disk1") ]]; then
    echo "⛔ BLOCCATO: Per sicurezza non ti lascio scrivere su disk0 o disk1."
    exit 1
fi

echo ""
echo "⚠️  ATTENZIONE ESTREMA ⚠️"
echo "Stai per CANCELLARE COMPLETAMENTE $DISK_ID per scriverci l'ISO."
echo "I dati non saranno recuperabili."
echo ""
read -p "Sei sicuro al 100%? Scrivi 'SI' per procedere: " CONFIRM

if [ "$CONFIRM" != "SI" ]; then
    echo "❌ Annullato."
    exit 1
fi

# 4. PREPARAZIONE E SCRITTURA
echo ""
echo "🔌 Smontaggio volumi..."
if [ "$OS" == "Darwin" ]; then
    diskutil unmountDisk "$DISK_ID"
else
    sudo umount "${DISK_ID}"* 2>/dev/null
fi

echo "🔥 Inizio scrittura (potrebbe volerci qualche minuto)..."
echo "   Non rimuovere la chiavetta!"

if [ "$OS" == "Darwin" ]; then
    # Su Mac usiamo /dev/rdiskN invece di /dev/diskN perché è 10x più veloce
    # Sostituiamo 'disk' con 'rdisk' nella stringa
    RDISK_ID="${DISK_ID/disk/rdisk}"
    
    # pv non è standard su mac, usiamo dd semplice
    sudo dd if="$ISO_PATH" of="$RDISK_ID" bs=4m
else
    # Su Linux usiamo status=progress
    sudo dd if="$ISO_PATH" of="$DISK_ID" bs=4M status=progress oflag=sync
fi

# 5. FINE
echo ""
if [ $? -eq 0 ]; then
    echo "✅ Scrittura Completata!"
    if [ "$OS" == "Darwin" ]; then
        diskutil eject "$DISK_ID"
    else
        sudo eject "$DISK_ID"
    fi
    echo "👉 Chiavetta pronta. Inseriscila nel Mini PC e avvia!"
else
    echo "❌ Errore durante la scrittura."
    exit 1
fi