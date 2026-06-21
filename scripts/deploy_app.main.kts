#!/usr/bin/env kotlin

@file:Suppress("UNREACHABLE_CODE")

import java.io.File
import java.nio.file.Files

// ─── Configuration ────────────────────────────────────────────────────────────

val home = System.getenv("HOME") ?: error("HOME not set")
val user = System.getenv("USER") ?: error("USER not set")

data class PathsConfig(
    val userConfigDir: String,
    val sysConfigDir: String,
    val nasSmarthome: String,
    val servicesSrc: String,
    val nasFstabSrc: String,
    val hacsDir: String,
)

val paths = PathsConfig(
    userConfigDir = "$home/.config/containers/systemd",
    sysConfigDir = "/etc/containers/systemd",
    nasSmarthome = "/var/mnt/nas/smarthome",
    servicesSrc = "$home/services",
    nasFstabSrc = "$home/services/nas.fstab",
    hacsDir = "/var/mnt/nas/smarthome/homeassistant/config/custom_components/hacs",
)

// ─── Shell Helpers ─────────────────────────────────────────────────────────────

fun run(vararg cmd: String, ignoreFailure: Boolean = false): Int {
    val result = ProcessBuilder(*cmd)
        .inheritIO()
        .start()
        .waitFor()
    return if (!ignoreFailure && result != 0) error("Command failed: ${cmd.joinToString(" ")}") else result
}

fun sudo(vararg cmd: String, ignoreFailure: Boolean = false) =
    run("sudo", *cmd, ignoreFailure = ignoreFailure)

fun systemctl(vararg args: String, user: Boolean = false, ignoreFailure: Boolean = false) =
    (if (user) ::run else ::sudo).let { exec ->
        if (user) run("systemctl", "--user", *args, ignoreFailure = ignoreFailure)
        else      sudo("systemctl", *args, ignoreFailure = ignoreFailure)
    }

fun mountpoint(path: String): Boolean =
    ProcessBuilder("mountpoint", "-q", path).start().waitFor() == 0

// ─── Step 1: Directory Structure ──────────────────────────────────────────────

fun createDirectories() {
    log("📂 Creazione struttura directory...")
    File(paths.userConfigDir).mkdirs()
    sudo("mkdir", "-p", paths.sysConfigDir)
    sudo("mkdir", "-p", "${paths.nasSmarthome}/homeassistant/config")
    sudo("chown", "-R", "$user:$user", paths.nasSmarthome, ignoreFailure = true)
}

// ─── Step 2: Container Definitions ────────────────────────────────────────────

val staleContainers = listOf("duckdns.container", "journiv.container", "caddy.container")

sealed class ContainerDeploy {
    data class System(val dest: String) : ContainerDeploy()   // rootful
    data class User(val dest: String)   : ContainerDeploy()   // rootless
}

fun resolveContainerDeploy(filename: String): ContainerDeploy? = when {
    !filename.endsWith(".container") -> null
    filename == "homeassistant.container" -> ContainerDeploy.System(paths.sysConfigDir)
    else                                  -> ContainerDeploy.User(paths.userConfigDir)
}

fun installServices() {
    log("🐳 Installazione definizioni Container...")

    // Remove stale containers
    staleContainers
        .map { File("${paths.userConfigDir}/$it") }
        .filter { it.exists() }
        .forEach { f ->
            sudo("rm", "-f", f.absolutePath)
            log("   -> Rimosso obsoleto: ${f.name}")
        }

    val servicesDir = File(paths.servicesSrc).also {
        if (!it.isDirectory) error("❌ ERRORE: Cartella ${paths.servicesSrc} non trovata!")
    }

    servicesDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val name = file.name
        when {
            name.endsWith(".container") -> {
                when (val deploy = resolveContainerDeploy(name)) {
                    is ContainerDeploy.System -> {
                        log("   -> Copia $name (System/Rootful)")
                        sudo("cp", file.absolutePath, deploy.dest)
                    }
                    is ContainerDeploy.User -> {
                        log("   -> Copia $name (User/Rootless)")
                        file.copyTo(File("${deploy.dest}/$name"), overwrite = true)
                    }
                    null -> Unit
                }
            }
            name.endsWith(".service") -> {
                log("   -> Copia $name (Systemd di sistema)")
                sudo("cp", file.absolutePath, "/etc/systemd/system/")
            }
        }
    }
}

// ─── Step 3: Legacy Unit Cleanup ──────────────────────────────────────────────

val legacyUnits = listOf(
    "/etc/systemd/system/mnt-nas.mount",
    "/etc/systemd/system/mnt-nas.automount"
)

fun removeLegacyUnits() {
    legacyUnits
        .map { File(it) }
        .filter { it.exists() }
        .forEach { f ->
            val unit = f.name
            sudo("systemctl", "disable", "--now", unit, ignoreFailure = true)
            sudo("rm", "-f", f.absolutePath)
            log("   -> Rimossa unità legacy: $unit")
        }
}

// ─── Step 4: NAS fstab Management ─────────────────────────────────────────────

val FSTAB_MARKER_START = "# >>> homeserver-fcos nas mounts >>>"
val FSTAB_MARKER_END   = "# <<< homeserver-fcos nas mounts <<<"

fun updateNasFstab() {
    val nasFstab = File(paths.nasFstabSrc)
    if (!nasFstab.exists()) {
        log("⚠️  nas.fstab non trovato in ${paths.nasFstabSrc}. Salto configurazione mount NAS.")
        return
    }

    log("📁 Aggiornamento mount NAS da nas.fstab...")

    val etcFstab = File("/etc/fstab")

    val currentLines = if (etcFstab.exists())
        runCatching { ProcessBuilder("sudo", "cat", "/etc/fstab").start().inputStream.reader().readLines() }
            .getOrDefault(emptyList())
    else {
        log("⚠️  /etc/fstab non trovato. Creo un nuovo file gestito.")
        emptyList()
    }

    // Strip our managed block from the existing fstab
    var skip = false
    val cleanLines = currentLines.filter { line ->
        when {
            line == FSTAB_MARKER_START -> {
                skip = true
                false
            }
            line == FSTAB_MARKER_END -> {
                skip = false
                false
            }
            else -> !skip
        }
    }

    val nasMountLines = nasFstab.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }

    val finalContent = (cleanLines + "" + FSTAB_MARKER_START + nasMountLines + FSTAB_MARKER_END)
        .joinToString("\n")

    val tmp = Files.createTempFile("fstab-", ".tmp").toFile()
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
    log("🔄 Ricaricamento Systemd...")
    systemctl("daemon-reload", user = true)
    systemctl("daemon-reload")

    log("💾 Attivazione mount NAS smarthome...")
    systemctl("stop", "var-mnt-nas-smarthome.automount", ignoreFailure = true)

    val mounted = systemctl("start", "var-mnt-nas-smarthome.mount", ignoreFailure = true) == 0
    if (!mounted) {
        log("⚠️  mount diretto fallito, fallback mount -a...")
        sudo("mount", "-a", ignoreFailure = true)
    }
    systemctl("restart", "remote-fs.target", ignoreFailure = true)

    if (!mountpoint(paths.nasSmarthome)) {
        error("❌ ${paths.nasSmarthome} non montato! HA non può partire.")
    }
    log("✅ ${paths.nasSmarthome} montato correttamente.")
}

// ─── Step 6: Services Restart ─────────────────────────────────────────────────

fun restartServices() {
    log("▶️  Riavvio servizi...")
    systemctl("restart", "homeassistant.service", ignoreFailure = true)
}

// ─── Step 7: HACS Auto-Install ────────────────────────────────────────────────

fun installHacsIfMissing() {
    log("🔍 Controllo presenza HACS...")
    Thread.sleep(10_000) // Wait for HA to start

    if (File(paths.hacsDir).isDirectory) {
        log("✅ HACS è già presente.")
        return
    }

    log("⚠️  HACS non trovato. Avvio installazione automatica...")
    val ok = sudo(
        "podman", "exec", "-it", "homeassistant",
        "bash", "-c", "wget -O - https://get.hacs.xyz | bash",
        ignoreFailure = true
    ) == 0

    if (ok) {
        log("✅ HACS installato con successo!")
        log("♻️  Riavvio Home Assistant per attivarlo...")
        systemctl("restart", "homeassistant.service", ignoreFailure = true)
    } else {
        log("❌ Errore durante l'installazione di HACS.")
    }
}

// ─── Step 8: Status Report ────────────────────────────────────────────────────

fun printStatus() {
    log("\n📊 Stato dei servizi:")
    log("---------------------")

    val haStatus = ProcessBuilder("sudo", "systemctl", "status", "homeassistant.service", "--no-pager")
        .start().inputStream.reader().readLines()
        .firstOrNull { it.contains("Active:") }
    log(haStatus ?: "❌ Home Assistant non attivo")

    log("\n📁 Stato automount NAS:")
    val automountStatus = ProcessBuilder(
        "sudo", "systemctl", "list-units", "--type=automount", "--all", "--no-pager"
    ).start().inputStream.reader().readLines()
        .filter { it.contains("mnt-nas") }
    if (automountStatus.isEmpty()) log("⚠️  Nessun automount NAS trovato")
    else automountStatus.forEach(::log)
}

// ─── Orchestration ────────────────────────────────────────────────────────────

fun log(msg: String) = println(msg)

val steps: List<Pair<String, () -> Unit>> = listOf(
    "Directory structure"    to ::createDirectories,
    "Service installation"   to ::installServices,
    "Legacy unit cleanup"    to ::removeLegacyUnits,
    "NAS fstab"              to ::updateNasFstab,
    "Systemd reload & mount" to ::reloadAndMount,
    "Service restart"        to ::restartServices,
    "HACS auto-install"      to ::installHacsIfMissing,
    "Status report"          to ::printStatus,
)

log("🚀 Inizio Deploy dell'infrastruttura...")

steps.forEach { (name, step) ->
    runCatching(step).onFailure { e ->
        log("❌ Step '$name' fallito: ${e.message}")
        throw e
    }
}

log("")
log("✅ Deploy completato.")
log("   Log Utente: journalctl --user -f")
log("   Log Sistema (HA): sudo journalctl -u homeassistant.service -f")