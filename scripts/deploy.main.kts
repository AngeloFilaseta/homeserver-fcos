#!/usr/bin/env kotlin

@file:Import("./libs/util.kt")
@file:Import("./libs/const.kt")

import java.io.File
import java.nio.file.Paths

val cwd = Paths.get("").toAbsolutePath().toFile()
val projectRoot =
    when {
        File(cwd, "services").isDirectory && File(cwd, "scripts").isDirectory -> cwd
        File(cwd, "../services").isDirectory && File(cwd, "../scripts").isDirectory -> File(cwd, "..").canonicalFile
        else -> cwd
    }

println("🚀 Inizio aggiornamento su: ${Server.host}")

// 0. Attesa e verifica mount NAS (con accesso per triggare automount)
println("⏳ Attesa per il mount NAS...")
var mountReady = false

for (attempts in 1..5) {
    val success = runRemote("ls ${Nas.smartHomePath} 2>/dev/null")

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
    if (runRemote("sudo mkdir -p ${Nas.homeAssistantPath}/config")) {
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
    runRemote("rm -rf ~/services/ && mkdir -p ~/services", quiet = true)
    servicesDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val ok = scpRemote(file.absolutePath, "~/services/", quiet = true)
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

val confDir = File(projectRoot, "conf")
if (confDir.exists() && confDir.isDirectory) {
    print("📦 Sincronizzazione 'conf' direttamente su NAS... ")
    runRemote("mkdir -p /var/mnt/nas/conf", quiet = true)
    val ok = scpRemote("${confDir.absolutePath}/.", "/var/mnt/nas/conf/", quiet = true)
    if (!ok) {
        println("❌")
        println("❌ Errore durante il trasferimento della cartella conf su /var/mnt/nas/conf")
        System.exit(1)
    }
    println("✅")
} else {
    println("⚠️  ATTENZIONE: Cartella 'conf' non trovata in locale!")
    System.exit(1)
}

val secretsFile = File(projectRoot, "secrets.env")
if (secretsFile.isFile) {
    val envMap =
        secretsFile
            .readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line -> line.substringBefore("=") to line.substringAfter("=") }

    val requiredJournivSecrets =
        mapOf(
            "journiv_secret_key" to "JOURNIV_SECRET_KEY",
            "oidc_client_secret" to "OIDC_CLIENT_SECRET",
            "postgres_password" to "POSTGRES_PASSWORD",
        )

    requiredJournivSecrets.forEach { (_, envName) ->
        val value =
            envMap[envName]?.takeIf { it.isNotBlank() }
                ?: run {
                    println("❌ Variabile obbligatoria mancante in secrets.env: $envName")
                    System.exit(1)
                    ""
                }
        if (value.startsWith("CHANGE_ME")) {
            println("❌ Variabile $envName non valorizzata: sostituisci il placeholder '$value'")
            System.exit(1)
        }
    }

    print("🔐 Sincronizzazione 'secrets.env'... ")
    val ok = scpRemote(secretsFile.absolutePath, "~/secrets.env", quiet = true)
    if (!ok) {
        println("❌")
        println("❌ Errore durante il trasferimento di ${secretsFile.absolutePath}")
        System.exit(1)
    }
    println("✅")

} else {
    println("⚠️  ATTENZIONE: secrets.env non trovato in locale!")
    System.exit(1)
}

// 2.2 Scripts
val scriptsDir = File(projectRoot, "scripts")
if (scriptsDir.exists() && scriptsDir.isDirectory) {
    print("📦 Sincronizzazione 'scripts'... ")
    runRemote("rm -rf ~/scripts/", quiet = true)
    val ok = scpRemote(scriptsDir.absolutePath, "~/", quiet = true)
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

if (runRemote(installKotlinCommand)) {
    println("\n✅ Kotlin installato con successo!")
} else {
    println("\n❌ Errore durante l'installazione di Kotlin.")
    System.exit(1)
}

println("\n--- ⚙️  Deploy su Homeserver Remoto ---")

val deploySuccess: Boolean =
    runRemote(
        "chmod +x ./scripts/deployRemote.main.kts && ./scripts/deployRemote.main.kts",
    )

if (deploySuccess) {
    println("\n✅ Aggiornamento completato con successo!")
} else {
    println("\n❌ Errore durante il deploy remoto.")
    System.exit(1)
}
