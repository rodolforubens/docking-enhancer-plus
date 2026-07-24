package com.odininputmirror.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PServerShellTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readReturnsThisCallsOwnOutput() {
        val shell = PServerShell(temp.root, FakeTransactor { inner -> "OUT:$inner" })

        assertEquals("OUT:hello", shell.read("hello"))
        assertEquals("OUT:world", shell.read("world"))
    }

    @Test
    fun eachReadUsesAUniqueStagingFile() {
        val fake = FakeTransactor { "x" }
        val shell = PServerShell(temp.root, fake)

        shell.read("a"); shell.read("b"); shell.read("c")

        // The core fix: no shared file, so concurrent callers can't clobber one another.
        assertEquals(3, fake.writtenPaths.size)
        assertEquals(3, fake.writtenPaths.toSet().size)
    }

    @Test
    fun readReturnsEmptyWhenTransactFails() {
        val shell = PServerShell(temp.root, FakeTransactor(succeed = false) { "unwritten" })

        assertEquals("", shell.read("cmd"))
    }

    @Test
    fun failedReadDoesNotLeakAPriorCallsOutput() {
        // The exact regression: with the old shared staging file a failed transact left the previous
        // call's content in place and read() returned it as if fresh. Unique files make it "" instead.
        val fake = FakeTransactor { "FIRST" }
        val shell = PServerShell(temp.root, fake)

        assertEquals("FIRST", shell.read("cmd1"))
        fake.succeed = false
        assertEquals("", shell.read("cmd2"))
    }

    @Test
    fun readDeletesItsStagingFile() {
        val shell = PServerShell(temp.root, FakeTransactor { "data" })

        shell.read("cmd")

        val leftovers = temp.root.listFiles { f -> f.name.startsWith("pserver_read_") } ?: emptyArray()
        assertTrue("staging files should be cleaned up, found ${leftovers.map { it.name }}", leftovers.isEmpty())
    }
}

/**
 * Simulates PServerBinder: on success, parses the `> '<path>'` redirect out of the wrapped command
 * and writes the (simulated) command output there, exactly as the real service's shell would.
 */
private class FakeTransactor(
    var succeed: Boolean = true,
    private val output: (innerCommand: String) -> String,
) : PServerTransactor {
    val writtenPaths = mutableListOf<String>()

    override val isAvailable: Boolean = true

    override fun executeAsRoot(command: String): Result<String?> {
        if (!succeed) return Result.failure(IllegalStateException("transact failed"))
        val path = Regex("> '([^']*)'").find(command)?.groupValues?.get(1)
        val inner = Regex("^\\((.*)\\) > '").find(command)?.groupValues?.get(1)
        if (path != null && inner != null) {
            writtenPaths += path
            File(path).writeText(output(inner))
        }
        return Result.success(null)
    }
}
