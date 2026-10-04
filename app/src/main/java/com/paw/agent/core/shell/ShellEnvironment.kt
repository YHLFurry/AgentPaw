package com.paw.agent.core.shell

/**
 * The result of running one command inside the sandbox.
 *
 * [stdout] and [stderr] are already truncated to the configured limits, so
 * callers can hand them to a model without further guarding.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val durationMillis: Long = 0,
    val truncated: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** stdout and stderr combined, for feeding back into a prompt. */
    val output: String
        get() = buildString {
            append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }

    companion object {
        fun ok(stdout: String = ""): CommandResult = CommandResult(0, stdout)
        fun error(message: String, code: Int = 1): CommandResult =
            CommandResult(code, stderr = message)
    }
}

/**
 * Where a command's stdin comes from and where its stdout goes. The interpreter
 * uses these to wire pipelines together.
 */
class ShellIO(
    val stdin: String = "",
    val stdout: StringBuilder = StringBuilder(),
    val stderr: StringBuilder = StringBuilder(),
)

/** Mutable state shared by everything running inside one shell session. */
class ShellEnvironment(
    /** Absolute path of the sandbox root; nothing outside it is reachable. */
    val rootDir: String,
    variables: Map<String, String> = emptyMap(),
) {
    private val vars = linkedMapOf<String, String>().apply {
        put("PWD", rootDir)
        put("HOME", rootDir)
        put("PATH", "/bin:/usr/bin")
        put("SHELL", "/bin/pawsh")
        put("TMPDIR", rootDir)
        putAll(variables)
    }

    /** Working directory, always normalised and kept inside [rootDir]. */
    var workingDir: String = rootDir
        private set

    val variables: Map<String, String> get() = vars.toMap()

    fun get(name: String): String? = vars[name]

    fun set(name: String, value: String) {
        vars[name] = value
    }

    fun unset(name: String) {
        vars.remove(name)
    }

    fun exportAll(): Map<String, String> = vars.toMap()

    /**
     * Resolves [path] against the working directory and refuses to escape the
     * sandbox root, which is what keeps generated code from touching the device.
     */
    fun resolvePath(path: String): String {
        val expanded = expandTildes(path)
        val candidate = if (expanded.startsWith("/")) {
            expanded
        } else {
            val base = if (workingDir.endsWith("/")) workingDir.dropLast(1) else workingDir
            "$base/$expanded"
        }
        return normalizePath(candidate)
    }

    fun cd(path: String) {
        val target = resolvePath(path.ifBlank { "~" })
        if (!isInsideRoot(target)) {
            throw SandboxSecurityException("cd outside the sandbox: $path")
        }
        workingDir = target
        vars["PWD"] = target
    }

    fun isInsideRoot(path: String): Boolean {
        val normalizedRoot = normalizePath(rootDir)
        val normalized = normalizePath(path)
        return normalized == normalizedRoot ||
            normalized.startsWith(if (normalizedRoot.endsWith("/")) normalizedRoot else "$normalizedRoot/")
    }

    /** The normalised sandbox root, for guards that must not delete it. */
    fun normalizeSandboxRoot(): String = normalizePath(rootDir)

    private fun expandTildes(path: String): String = when {
        path == "~" -> rootDir
        path.startsWith("~/") -> "$rootDir/${path.removePrefix("~/")}"
        else -> path
    }

    companion object {
        /** Collapses `.`, `..`, and duplicate slashes without touching the disk. */
        fun normalizePath(path: String): String {
            val isAbsolute = path.startsWith("/")
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            val stack = ArrayDeque<String>()

            for (part in parts) {
                when (part) {
                    ".." -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> stack.addLast(part)
                }
            }
            val joined = stack.joinToString("/")
            return if (isAbsolute) "/$joined" else joined.ifEmpty { "." }
        }
    }
}

/** Raised when a script tries to step outside the sandbox or use a denied feature. */
class SandboxSecurityException(message: String) : Exception(message)

/** Raised when a command does not exist. */
class CommandNotFoundException(name: String) : Exception("command not found: $name")

/** Raised when a script exceeds its step or time budget. */
class SandboxTimeoutException(message: String) : Exception(message)
