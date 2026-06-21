#!/usr/bin/env kotlin

@file:Import("./libs/util.kt")

import java.io.File
import java.nio.file.Paths

val serverHost = "fcos-ha"
val hostUser = "core"
val remoteTarget = "$hostUser@$serverHost"
// Percorsi sul NAS smarthome
val remoteNasSmartHome = "/var/mnt/nas/smarthome"

val remoteNasHomeAssistant = "$remoteNasSmartHome/homeassistant"

val cwd = Paths.get("").toAbsolutePath().toFile()
val projectRoot =
    when {
        File(cwd, "services").isDirectory && File(cwd, "scripts").isDirectory -> cwd
        File(cwd, "../services").isDirectory && File(cwd, "../scripts").isDirectory -> File(cwd, "..").canonicalFile
        else -> cwd
    }

println("🚀 Inizio aggiornamento su: $serverHost")

println("📂 Project Root rilevata: ${projectRoot.absolutePath}\n")

// 0. Attesa e verifica mount NAS (con accesso per triggare automount)
println("⏳ Attesa per il mount NAS...")
var mountReady = false

for (attempts in 1..5) {
    val success = runRemote(remoteTarget, "ls $remoteNasSmartHome 2>/dev/null")

    if (success) {
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
    if (runRemote(remoteTarget, "sudo mkdir -p $remoteNasHomeAssistant/config")) {
        dirCreated = true
        break
    }
    if (attempt < 5) {
        println("⏳ Retry creazione directory ($attempt/5)...")
        Thread.sleep(1000)
    }
}
if (!dirCreated) {
    println("❌ Errore: non riesco a creare le directory sul NAS")
    System.exit(1)
}

val servicesDir = File(projectRoot, "services")
if (servicesDir.exists() && servicesDir.isDirectory) {
    print("📦 Sincronizzazione 'services'... ")
    runRemote(remoteTarget, "rm -rf ~/services/ && mkdir -p ~/services", quiet = true)
    servicesDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val ok = scpRemote(remoteTarget, file.absolutePath, "~/services/", quiet = true)
        if (!ok) {
            println("❌")
            println("❌ Errore durante il trasferimento di ${file.absolutePath}")
            System.exit(1)
        }
    }
    println("✅")
} else {
    println("⚠️  ATTENZIONE: Cartella 'services' non trovata in locale!")
    System.exit(1)
}

// 2.2 Scripts
val scriptsDir = File(projectRoot, "scripts")
if (scriptsDir.exists() && scriptsDir.isDirectory) {
    print("📦 Sincronizzazione 'scripts'... ")
    runRemote(remoteTarget, "rm -rf ~/scripts/", quiet = true)
    val ok = scpRemote(remoteTarget, scriptsDir.absolutePath, "~/", quiet = true)
    if (!ok) {
        println("❌ Errore durante il trasferimento della cartella scripts")
        System.exit(1)
    }
} else {
    println("⚠️  ATTENZIONE: Cartella 'scripts' non trovata in locale!")
    System.exit(1)
}

val installKotlinCommand = "bash ./scripts/libs/install_kotlin_fcos.sh"

println("\n--- ⚙️  Installazione Kotlin su Homeserver Remoto ---")

val kotlinInstallSuccess: Boolean = runRemote(remoteTarget, installKotlinCommand)

if (kotlinInstallSuccess) {
    println("\n✅ Kotlin installato con successo!")
} else {
    println("\n❌ Errore durante l'installazione di Kotlin.")
    System.exit(1)
}

println("\n--- ⚙️  Deploy su Homeserver Remoto ---")

val remoteCommand: String = "chmod +x ./scripts/deployRemote.main.kts && ./scripts/deployRemote.main.kts"

val deploySuccess: Boolean = runRemote(remoteTarget, remoteCommand)

if (deploySuccess) {
    println("\n✅ Aggiornamento completato con successo!")
} else {
    println("\n❌ Errore durante il deploy remoto.")
    System.exit(1)
}
