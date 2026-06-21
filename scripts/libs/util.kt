#!/usr/bin/env kotlin

import java.io.File

/**
 * Executes a local system command.
 *
 * @param cmd The command and its arguments.
 * @param ignoreFailure If false, throws an exception if the process exits with a non-zero code.
 * @param quiet If true, suppresses all standard and error output.
 * @return The exit code of the process.
 * @throws IllegalStateException If the command fails and [ignoreFailure] is false.
 */

fun run(
    vararg cmd: String,
    ignoreFailure: Boolean = false,
    quiet: Boolean = false,
): Int {
    val builder = ProcessBuilder(*cmd)

    if (quiet) {
        builder.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
        builder.redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
    } else {
        builder.inheritIO()
    }

    val result = builder.start().waitFor()

    if (!ignoreFailure && result != 0) {
        error("Command failed: ${cmd.joinToString(" ")}")
    }
    return result
}

/**
 * Executes a local command with administrative privileges using sudo.
 */
fun sudo(
    vararg cmd: String,
    ignoreFailure: Boolean = false,
) = run("sudo", *cmd, ignoreFailure = ignoreFailure)

/**
 * Controls the systemd system and service manager.
 */
fun systemctl(
    vararg args: String,
    user: Boolean = false,
    ignoreFailure: Boolean = false,
) = if (user) {
    run("systemctl", "--user", *args, ignoreFailure = ignoreFailure)
} else {
    sudo("systemctl", *args, ignoreFailure = ignoreFailure)
}

/**
 * Checks whether the specified directory is a mountpoint.
 */
fun isMountPoint(path: String): Boolean =
    run(
        "mountpoint",
        "-q",
        path,
        ignoreFailure = true,
        quiet = true,
    ) == 0

/**
 * Executes a command on a remote host via SSH.
 */
fun runRemote(
    command: String,
    quiet: Boolean = false,
): Boolean = run("ssh", Server.remoteTarget, command, ignoreFailure = true, quiet = quiet) == 0

/**
 * Copies files or directories securely to a remote host via SCP.
 */
fun scpRemote(
    localPath: String,
    remoteDest: String,
    quiet: Boolean = false,
): Boolean = run("scp", "-r", localPath, "${Server.remoteTarget}:$remoteDest", ignoreFailure = true, quiet = quiet) == 0
