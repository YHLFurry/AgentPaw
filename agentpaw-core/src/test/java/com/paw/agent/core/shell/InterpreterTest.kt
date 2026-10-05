package com.paw.agent.core.shell

import com.paw.agent.core.shell.command.CommandRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end tests for the sandbox interpreter: real files in a temp directory,
 * no mocks, so the path guards are genuinely exercised.
 */
class InterpreterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun run(
        script: String,
        limits: SandboxLimits = SandboxLimits(),
    ): CommandResult {
        // Each run needs its own root; TemporaryFolder forbids a duplicate name.
        val root = temp.newFolder()
        val env = ShellEnvironment(rootDir = root.absolutePath)
        return Interpreter(CommandRegistry(), limits).execute(script, env)
    }

    // ---- basics -----------------------------------------------------------

    @Test
    fun `echo prints its arguments`() {
        val result = run("echo hello world")
        assertEquals(0, result.exitCode)
        assertEquals("hello world\n", result.stdout)
    }

    @Test
    fun `unknown command fails with 127`() {
        val result = run("definitely_not_a_command")
        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("command not found"))
    }

    @Test
    fun `syntax error is reported not thrown`() {
        val result = run("echo 'unterminated")
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("syntax error"))
    }

    // ---- arithmetic -------------------------------------------------------

    @Test
    fun `expr evaluates with correct precedence`() {
        assertEquals("14\n", run("expr 2 + 3 * 4").stdout)
        assertEquals("20\n", run("expr (2 + 3) * 4").stdout)
        assertEquals("1\n", run("expr 7 % 3").stdout)
        assertEquals("512\n", run("expr 2 ^ 9").stdout)
        assertEquals("-5\n", run("expr -5").stdout)
        assertEquals("2.5\n", run("expr 5 / 2").stdout)
    }

    @Test
    fun `expr supports functions`() {
        assertEquals("3\n", run("expr sqrt(9)").stdout)
        assertEquals("16\n", run("expr pow(2, 4)").stdout)
        assertEquals("2\n", run("expr min(2, 9)").stdout)
        assertEquals("4\n", run("expr abs(0 - 4)").stdout)
    }

    @Test
    fun `expr rejects division by zero`() {
        val result = run("expr 1 / 0")
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("division by zero"))
    }

    @Test
    fun `expr rejects unknown function`() {
        val result = run("expr evil(1)")
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("unknown function"))
    }

    // ---- pipelines and redirection ---------------------------------------

    @Test
    fun `pipeline feeds stdout into the next stage`() {
        val result = run("seq 1 5 | grep 3")
        assertEquals("3\n", result.stdout)
    }

    @Test
    fun `multi stage pipeline works`() {
        val result = run("seq 1 10 | grep -c /[0-9]/")
        assertTrue(result.stdout.startsWith("10"))
    }

    @Test
    fun `output redirection writes a file`() {
        val result = run("echo persisted > out.txt; cat out.txt")
        assertEquals("persisted\n", result.stdout)
    }

    @Test
    fun `append redirection accumulates`() {
        val result = run("echo a > f.txt; echo b >> f.txt; cat f.txt")
        assertEquals("a\nb\n", result.stdout)
    }

    // ---- control flow -----------------------------------------------------

    @Test
    fun `for loop iterates`() {
        val result = run("for i in 1 2 3; do echo \$i; done")
        assertEquals("1\n2\n3\n", result.stdout)
    }

    @Test
    fun `if else branches on exit status`() {
        assertEquals("yes\n", run("if test 1 -lt 2; then echo yes; else echo no; fi").stdout)
        assertEquals("no\n", run("if test 5 -lt 2; then echo yes; else echo no; fi").stdout)
    }

    @Test
    fun `and or short circuit`() {
        // `&&` runs both when the first succeeds.
        assertEquals("one\nboth\n", run("echo one && echo both").stdout)
        // `||` runs the fallback when the first fails.
        assertEquals("second\n", run("false || echo second").stdout)
    }

    @Test
    fun `while loop terminates`() {
        val result = run("i=0; while test \$i -lt 3; do echo \$i; i=\$(expr \$i + 1); done")
        assertEquals("0\n1\n2\n", result.stdout)
    }

    // ---- text processing --------------------------------------------------

    @Test
    fun `grep supports regex and invert`() {
        assertEquals("apple\n", run("echo apple | grep /ap/").stdout)
        assertEquals("banana\n", run("printf 'apple\\nbanana\\n' | grep -v apple").stdout)
    }

    @Test
    fun `sort and uniq combine`() {
        val result = run("printf 'b\\na\\nb\\n' | sort | uniq")
        assertEquals("a\nb\n", result.stdout)
    }

    @Test
    fun `wc counts lines words and chars`() {
        assertEquals("2\n", run("printf 'a\\nb\\n' | wc -l").stdout)
        assertEquals("2\n", run("printf 'a b\\n' | wc -w").stdout)
    }

    @Test
    fun `head and tail slice output`() {
        val result = run("seq 1 10 | head -n 3")
        assertEquals("1\n2\n3\n", result.stdout)

        val tail = run("seq 1 10 | tail -n 2")
        assertEquals("9\n10\n", tail.stdout)
    }

    @Test
    fun `cut selects fields`() {
        val result = run("printf 'a,1\\nb,2\\n' | cut -d , -f 2")
        assertEquals("1\n2\n", result.stdout)
    }

    // ---- sandbox guarantees ----------------------------------------------

    @Test
    fun `path traversal outside the root is refused`() {
        val result = run("cat ../../../../etc/passwd")
        // Either blocked outright or reported as missing — never actual content.
        assertTrue(
            "sandbox leaked a file: ${result.stdout}",
            result.exitCode != 0 || result.stdout.isBlank(),
        )
    }

    @Test
    fun `absolute path outside the root is refused`() {
        val result = run("cat /etc/passwd")
        assertTrue(result.exitCode != 0 || result.stdout.isBlank())
    }

    @Test
    fun `infinite loop is stopped by the step budget`() {
        val result = run("while true; do echo x; done", SandboxLimits(maxSteps = 2_000))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("steps"))
    }

    @Test
    fun `output is truncated at the configured limit`() {
        val result = run("yes hello", SandboxLimits(maxOutputChars = 500))
        assertTrue("expected truncation flag", result.truncated)
        assertTrue("stdout too long: ${result.stdout.length}", result.stdout.length <= 600)
    }

    @Test
    fun `command substitution works`() {
        val result = run("echo value=$(echo inner)")
        assertEquals("value=inner\n", result.stdout)
    }

    @Test
    fun `variables persist across statements`() {
        val result = run("export NAME=agentpaw; echo \$NAME")
        assertEquals("agentpaw\n", result.stdout)
    }

    @Test
    fun `unset variable expands to empty`() {
        val result = run("echo [\$NOT_SET]")
        assertEquals("[]\n", result.stdout)
    }

    @Test
    fun `help lists the available commands`() {
        val result = run("help")
        assertTrue(result.stdout.contains("expr"))
        assertTrue(result.stdout.contains("grep"))
    }

    @Test
    fun `files written in one statement are visible in the next`() {
        val root = temp.newFolder("persist")
        val env = ShellEnvironment(rootDir = root.absolutePath)
        val interpreter = Interpreter(CommandRegistry())

        // `write` consumes a pipeline's stdin; `>` redirection is the simpler path here.
        interpreter.execute("echo content > data.txt", env)
        val result = interpreter.execute("cat data.txt", env)

        assertEquals("content\n", result.stdout)
        assertTrue(File(root, "data.txt").exists())
    }
}
