#!/bin/bash

# Interrompe se errori
set -e

echo "🚀 Inizio Deploy dell'infrastruttura..."

# Definizioni Percorsi
USER_CONFIG_DIR="$HOME/.config/containers/systemd"
SYS_CONFIG_DIR="/etc/containers/systemd"
HA_DIR="$HOME/homeassistant"
SERVICES_SRC="$HOME/services"
NAS_FSTAB_SRC="$SERVICES_SRC/nas.fstab"

# 1. Struttura Directory
echo "📂 Creazione struttura directory..."
mkdir -p "$USER_CONFIG_DIR"
sudo mkdir -p "$SYS_CONFIG_DIR"
mkdir -p "$HA_DIR"
mkdir -p "$HA_DIR/config"
mkdir -p "$HA_DIR/caddy/data"
mkdir -p "$HA_DIR/caddy/config"

# 2. Controllo Caddyfile
if [ ! -f "$HA_DIR/Caddyfile" ]; then
    echo "⚠️  ATTENZIONE: Caddyfile non trovato in $HA_DIR!"
    echo "    Caddy potrebbe non partire correttamente."
else
    echo "✅ Caddyfile rilevato in posizione corretta."
fi

# 3. Installazione Servizi (Smistamento Ibrido)
echo "🐳 Installazione definizioni Container..."
if [ -d "$SERVICES_SRC" ]; then
    for file in "$SERVICES_SRC"/*; do
        [ -f "$file" ] || continue
        filename=$(basename "$file")

        case "$filename" in
            *.container)
                if [ "$filename" == "homeassistant.container" ]; then
                    # Deploy Rootful (Sistema)
                    echo "   -> Copia $filename (System/Rootful)"
                    sudo cp "$file" "$SYS_CONFIG_DIR/"
                else
                    # Deploy Rootless (Utente)
                    echo "   -> Copia $filename (User/Rootless)"
                    cp "$file" "$USER_CONFIG_DIR/"
                fi
                ;;
            *.service)
                # Unità di sistema classiche (systemd)
                echo "   -> Copia $filename (Systemd di sistema)"
                sudo cp "$file" "/etc/systemd/system/"
                ;;
            *)
                # Ignora file non gestiti
                ;;
        esac
    done
else
    echo "❌ ERRORE: Cartella $SERVICES_SRC non trovata!"
    exit 1
fi

# 3.1 Gestione mount NAS da file unico fstab
if [ -f "$NAS_FSTAB_SRC" ]; then
    echo "📁 Aggiornamento mount NAS da nas.fstab..."
    START_MARKER="# >>> homeserver-fcos nas mounts >>>"
    END_MARKER="# <<< homeserver-fcos nas mounts <<<"

    TMP_CURRENT=$(mktemp)
    TMP_CLEAN=$(mktemp)
    TMP_FINAL=$(mktemp)

    if sudo test -f /etc/fstab; then
        sudo cat /etc/fstab > "$TMP_CURRENT"
    else
        echo "⚠️  /etc/fstab non trovato. Creo un nuovo file gestito."
        : > "$TMP_CURRENT"
    fi

    awk -v s="$START_MARKER" -v e="$END_MARKER" '
        $0 == s {skip=1; next}
        $0 == e {skip=0; next}
        !skip {print}
    ' "$TMP_CURRENT" > "$TMP_CLEAN"

    {
        cat "$TMP_CLEAN"
        echo ""
        echo "$START_MARKER"
        grep -Ev '^[[:space:]]*($|#)' "$NAS_FSTAB_SRC"
        echo "$END_MARKER"
    } > "$TMP_FINAL"

    sudo install -m 0644 "$TMP_FINAL" /etc/fstab

    # Crea automaticamente i mountpoint definiti nel nas.fstab
    while read -r fs_spec fs_file fs_vfstype fs_mntops fs_freq fs_passno; do
        [[ -z "$fs_spec" || "${fs_spec:0:1}" == "#" ]] && continue
        [ -n "$fs_file" ] && sudo mkdir -p "$fs_file"
    done < "$NAS_FSTAB_SRC"

    rm -f "$TMP_CURRENT" "$TMP_CLEAN" "$TMP_FINAL"
else
    echo "⚠️  nas.fstab non trovato in $NAS_FSTAB_SRC. Salto configurazione mount NAS."
fi

# 4. Reload Systemd (Utente + Sistema)
echo "🔄 Ricaricamento Systemd..."
systemctl --user daemon-reload
sudo systemctl daemon-reload
sudo systemctl restart remote-fs.target || true

# 5. Riavvio Servizi
echo "▶️  Riavvio servizi..."
# Riavvio servizi utente (ignoriamo errori se non esistono ancora)
systemctl --user restart caddy.service || true
systemctl --user restart duckdns.service || true
# Riavvio servizio di sistema
sudo systemctl restart homeassistant.service || true

# 5.1 Auto-Installazione HACS (Se mancante)
echo "🔍 Controllo presenza HACS..."

HACS_DIR="$HA_DIR/config/custom_components/hacs"

# Attendiamo che HA sia partito
sleep 10

if [ ! -d "$HACS_DIR" ]; then
    echo "⚠️  HACS non trovato. Avvio installazione automatica..."
    
    # Eseguiamo l'installazione dentro il container di sistema usando sudo
    if sudo podman exec -it homeassistant bash -c "wget -O - https://get.hacs.xyz | bash"; then
        echo "✅ HACS installato con successo!"
        echo "♻️  Riavvio Home Assistant per attivarlo..."
        sudo systemctl restart homeassistant.service
    else
        echo "❌ Errore durante l'installazione di HACS."
    fi
else
    echo "✅ HACS è già presente."
fi

# 6. Check Status
echo ""
echo "📊 Stato dei servizi:"
echo "---------------------"
systemctl --user status caddy.service --no-pager | grep "Active:" || echo "❌ Caddy non attivo"
systemctl --user status duckdns.service --no-pager | grep "Active:" || echo "❌ DuckDNS non attivo"
sudo systemctl status homeassistant.service --no-pager | grep "Active:" || echo "❌ Home Assistant non attivo"

echo ""
echo "📁 Stato automount NAS:"
sudo systemctl list-units --type=automount --all --no-pager | grep "mnt-nas" || echo "⚠️  Nessun automount NAS trovato"

echo ""
echo "✅ Deploy completato."
echo "   Log Utente: journalctl --user -f"
echo "   Log Sistema (HA): sudo journalctl -u homeassistant.service -f"