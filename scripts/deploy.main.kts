#!/usr/bin/env kotlin

import java.io.File
import java.nio.file.Paths

val SERVER_HOST = "fcos-ha"
val REMOTE_USER = "core"
val REMOTE_TARGET = "$REMOTE_USER@$SERVER_HOST"
// Percorsi sul NAS smarthome
val REMOTE_NAS_SMARTHOME = "/var/mnt/nas/smarthome"

val REMOTE_NAS_HA = "$REMOTE_NAS_SMARTHOME/homeassistant"

val scriptDir = Paths.get("").toAbsolutePath().toFile()
val projectRoot = scriptDir.parentFile ?: scriptDir

println("🚀 Inizio aggiornamento su: $SERVER_HOST")


println("📂 Project Root rilevata: ${projectRoot.absolutePath}\n")

fun runCommand(vararg command: String): Boolean {
    return ProcessBuilder(*command).inheritIO().start().waitFor() == 0
}

fun sshCommand(command: String): Boolean {
    return runCommand("ssh", REMOTE_TARGET, command)
}

fun scp(localPath: String, remoteDest: String) {
    println("✅ Invio $localPath...")
    val success = runCommand("scp", "-r", localPath, "$REMOTE_TARGET:$remoteDest")
    if (!success) {
        println("❌ Errore durante il trasferimento di $localPath")
        System.exit(1)
    }
}

println("--- 📦 Sincronizzazione File ---")

// 0. Attesa e verifica mount NAS (con accesso per triggare automount)
println("⏳ Attesa per il mount NAS...")
var mountReady = false

for (attempt in 1..30) {
    val checkMount = "ls $REMOTE_NAS_SMARTHOME 2>/dev/null"
    val process = ProcessBuilder("ssh", REMOTE_TARGET, checkMount)
        .redirectErrorStream(true)
        .start()

    process.inputStream.bufferedReader().readText() // consuma l'output, evita deadlock
    val exitCode = process.waitFor()

    if (exitCode == 0) {
        println("✅ NAS mount attivo!")
        mountReady = true
        break
    }
    Thread.sleep(2000)
}

if (!mountReady) {
    println("\n❌ Timeout: NAS mount non disponibile dopo 60 secondi")
    System.exit(1)
}

// 1. Crea le directory sul NAS (con retry)
println("📁 Creazione struttura NAS...")
var dirCreated = false
for (attempt in 1..5) {
    if (sshCommand("sudo mkdir -p $REMOTE_NAS_HA/config")) {
        dirCreated = true
        break
    }
    if (attempt < 5) {
        println("⏳ Retry creazione directory (${attempt}/5)...")
        Thread.sleep(1000)
    }
}
if (!dirCreated) {
    println("❌ Errore: non riesco a creare le directory sul NAS")
    System.exit(1)
}

// 2.1 Services - clear server dir first to remove stale files
val servicesDir = File(projectRoot, "services")
if (servicesDir.exists() && servicesDir.isDirectory) {
    sshCommand("rm -rf ~/services/")
    scp(servicesDir.absolutePath + "/", "~/")
} else {
    println("⚠️  ATTENZIONE: Cartella 'services' non trovata in locale!")
}

// 2.2 Scripts
val scriptsDir = File(projectRoot, "scripts")
if (scriptsDir.exists() && scriptsDir.isDirectory) {
    scp(scriptsDir.absolutePath + "/", "~/")
} else {
    println("⚠️  ATTENZIONE: Cartella 'scripts' non trovata in locale!")
}

println("\n--- ⚙️  Esecuzione Deploy Remoto ---")
val remoteCommand = "chmod +x scripts/deploy_app.sh && ./scripts/deploy_app.sh"
val deploySuccess = sshCommand(remoteCommand)

if (deploySuccess) {
    println("\n✅ Aggiornamento completato con successo!")
} else {
    println("\n❌ Errore durante il deploy remoto.")
    System.exit(1)
}