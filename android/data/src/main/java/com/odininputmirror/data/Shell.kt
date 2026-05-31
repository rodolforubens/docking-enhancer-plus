package com.odininputmirror.data

internal class Shell {
    fun runSu(command: String): String = runProcess(arrayOf("su", "-c", command))

    fun runShell(command: String): String = runProcess(arrayOf("sh", "-c", command))

    private fun runProcess(command: Array<String>): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("Command failed ($exitCode): $output")
        }

        return output
    }
}

internal fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"
