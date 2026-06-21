#!/usr/bin/env kotlin

@file:Import("./libs/util.kt")

import java.io.File
import java.nio.file.Files

// ─── Configuration ────────────────────────────────────────────────────────────

val home = System.getenv("HOME") ?: error("HOME not set")
val user = System.getenv("USER") ?: error("USER not set")

val userConfigDir = "$home/.config/containers/systemd"
val sysConfigDir = "/etc/containers/systemd"
val nasSmarthome = "/var/mnt/nas/smarthome"
val servicesSrc = "$home/services"
val nasFstabSrc = "$home/services/nas.fstab"
val hacsDir = "/var/mnt/nas/smarthome/homeassistant/config/custom_components/hacs"

// ─── Step 1: Directory Structure ──────────────────────────────────────────────

fun createDirectories() {
    println("📂 Creazione struttura directory...")
    File(userConfigDir).mkdirs()
    sudo("mkdir", "-p", sysConfigDir)
    sudo("mkdir", "-p", "$nasSmarthome/homeassistant/config")
}

// ─── Step 2: Container Definitions ────────────────────────────────────────────

fun removeStaleContainer(containerName: String) {
    val f = File("$userConfigDir/$containerName")
    if (f.exists()) {
        sudo("rm", "-f", f.absolutePath)
        println("   -> Rimosso obsoleto: ${f.name}")
    }
}

fun installServices() {
    println("🐳 Installazione definizioni Container...")

    // Remove stale containers
    removeStaleContainer("duckdns.container")
    removeStaleContainer("journiv.container")
    removeStaleContainer("caddy.container")

    val servicesDir =
        File(servicesSrc).also {
            if (!it.isDirectory) error("❌ ERRORE: Cartella $servicesSrc non trovata!")
        }

    servicesDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val name = file.name
        when {
            name.endsWith(".container") -> {
                if (name == "homeassistant.container") {
                    println("   -> Copia $name (System/Rootful)")
                    sudo("cp", file.absolutePath, sysConfigDir)
                } else {
                    println("   -> Copia $name (User/Rootless)")
                    file.copyTo(File("$userConfigDir/$name"), overwrite = true)
                }
            }

            name.endsWith(".service") -> {
                println("   -> Copia $name (Systemd di sistema)")
                sudo("cp", file.absolutePath, "/etc/systemd/system/")
            }
        }
    }
}

// ─── Step 3: Legacy Unit Cleanup ──────────────────────────────────────────────

val legacyUnits =
    listOf(
        "/etc/systemd/system/mnt-nas.mount",
        "/etc/systemd/system/mnt-nas.automount",
    )
// ─── Step 4: NAS fstab Management ─────────────────────────────────────────────

val FSTAB_MARKER_START = "# >>> homeserver-fcos nas mounts >>>"
val FSTAB_MARKER_END = "# <<< homeserver-fcos nas mounts <<<"

fun updateNasFstab() {
    val nasFstab = File(nasFstabSrc)
    if (!nasFstab.exists()) {
        println("⚠️  nas.fstab non trovato in $nasFstabSrc. Salto configurazione mount NAS.")
        return
    }

    println("📁 Aggiornamento mount NAS da nas.fstab...")

    val etcFstab = File("/etc/fstab")

    val currentLines =
        if (etcFstab.exists()) {
            runCatching {
                ProcessBuilder("sudo", "cat", "/etc/fstab")
                    .start()
                    .inputStream
                    .reader()
                    .readLines()
            }.getOrDefault(emptyList())
        } else {
            println("⚠️  /etc/fstab non trovato. Creo un nuovo file gestito.")
            emptyList()
        }

    // Strip our managed block from the existing fstab
    var skip = false
    val cleanLines =
        currentLines.filter { line ->
            when {
                line == FSTAB_MARKER_START -> {
                    skip = true
                    false
                }

                line == FSTAB_MARKER_END -> {
                    skip = false
                    false
                }

                else -> {
                    !skip
                }
            }
        }

    val nasMountLines =
        nasFstab
            .readLines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }

    val finalContent =
        (cleanLines + "" + FSTAB_MARKER_START + nasMountLines + FSTAB_MARKER_END)
            .joinToString("\n")

    val tmp =
        Files
            .createTempFile("fstab-", ".tmp")
            .toFile()
            .also { it.writeText(finalContent) }

    sudo("install", "-m", "0644", tmp.absolutePath, "/etc/fstab")
    tmp.delete()

    // Create mountpoints declared in nas.fstab
    nasMountLines.forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 2) sudo("mkdir", "-p", parts[1])
    }
}

// ─── Step 5: Systemd Reload & NAS Mount ───────────────────────────────────────

fun reloadAndMount() {
    println("🔄 Ricaricamento Systemd...")
    systemctl("daemon-reload", user = true)
    systemctl("daemon-reload")

    println("💾 Attivazione mount NAS smarthome...")

    val mounted = systemctl("start", "var-mnt-nas-smarthome.mount", ignoreFailure = true) == 0
    if (!mounted) {
        println("⚠️  mount diretto fallito, fallback mount -a...")
        sudo("mount", "-a", ignoreFailure = true)
    }
    systemctl("restart", "remote-fs.target", ignoreFailure = true)

    if (!isMountPoint(nasSmarthome)) {
        error("❌ $nasSmarthome non montato! HA non può partire.")
    }
    println("✅ $nasSmarthome montato correttamente.")
}

// ─── Step 6: Services Restart ─────────────────────────────────────────────────

fun restartServices() {
    println("▶️  Riavvio servizi...")
    systemctl("restart", "homeassistant.service", ignoreFailure = true)
}

// ─── Step 7: HACS Auto-Install ────────────────────────────────────────────────

fun installHacsIfMissing() {
    println("🔍 Controllo presenza HACS...")
    Thread.sleep(10_000) // Wait for HA to start

    if (File(hacsDir).isDirectory) {
        println("✅ HACS è già presente.")
        return
    }

    println("⚠️  HACS non trovato. Avvio installazione automatica...")
    val ok =
        sudo(
            "podman",
            "exec",
            "-it",
            "homeassistant",
            "bash",
            "-c",
            "wget -O - https://get.hacs.xyz | bash",
            ignoreFailure = true,
        ) == 0

    if (ok) {
        println("✅ HACS installato con successo!")
        println("♻️  Riavvio Home Assistant per attivarlo...")
        systemctl("restart", "homeassistant.service", ignoreFailure = true)
    } else {
        println("❌ Errore durante l'installazione di HACS.")
    }
}

// ─── Step 8: Status Report ────────────────────────────────────────────────────

data class ServiceStatus(
    val name: String,
    val active: String,
    val sub: String,
) {
    val isOk get() = active == "active"
    val icon get() = if (isOk) "✅" else "❌"

    override fun toString() = "$icon  %-45s $active ($sub)".format(name)
}

fun serviceStatus(name: String): ServiceStatus {
    val props =
        ProcessBuilder("sudo", "systemctl", "show", name, "--property=ActiveState,SubState", "--no-pager")
            .start()
            .inputStream
            .reader()
            .readLines()
            .associate { line -> line.substringBefore("=") to line.substringAfter("=") }
    return ServiceStatus(
        name = name,
        active = props["ActiveState"] ?: "unknown",
        sub = props["SubState"] ?: "unknown",
    )
}

fun printStatus(services: List<String>) {
    println("\n📊 Stato dei servizi:")
    println("─".repeat(60))
    services.map(::serviceStatus).forEach(::println)
    println("─".repeat(60))
}

val monitoredServices =
    listOf(
        "var-mnt-nas-smarthome.mount",
        "var-mnt-nas-media.mount",
        "homeassistant.service",
    )

// ─── Orchestration ────────────────────────────────────────────────────────────

val steps: List<Pair<String, () -> Unit>> =
    listOf(
        "Directory structure" to ::createDirectories,
        "Service installation" to ::installServices,
        "NAS fstab" to ::updateNasFstab,
        "Systemd reload & mount" to ::reloadAndMount,
        "Service restart" to ::restartServices,
        "HACS auto-install" to ::installHacsIfMissing,
        "Status report" to { printStatus(monitoredServices) },
    )

println("🚀 Inizio Deploy dell'infrastruttura...")

steps.forEach { (name, step) ->
    runCatching(step).onFailure { e ->
        println("❌ Step '$name' fallito: ${e.message}")
        throw e
    }
}

println("\n✅ Deploy completato.")
