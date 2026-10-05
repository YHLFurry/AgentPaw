package com.paw.agent.core.shell.command

import com.paw.agent.core.shell.CommandResult
import com.paw.agent.core.shell.ShellEnvironment
import com.paw.agent.core.shell.ShellIO
import com.paw.agent.core.shell.command.FileSupport.ensureTrailingNewline
import com.paw.agent.core.shell.command.FileSupport.readAll
import com.paw.agent.core.shell.command.FileSupport.toLines
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Text utilities
// ---------------------------------------------------------------------------

object EchoCommand : SandboxCommand {
    override val name = "echo"
    override val usage = "echo [-n] TEXT..."
    override val summary = "Print arguments back to stdout."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val noNewline = args.firstOrNull() == "-n"
        val text = if (noNewline) args.drop(1) else args
        return CommandResult.ok(text.joinToString(" ") + if (noNewline) "" else "\n")
    }
}

/**
 * Formats a template. Supports the escapes the [Lexer] already understands plus
 * `%s`/`%d`, which covers the way models usually emit test data.
 */
object PrintfCommand : SandboxCommand {
    override val name = "printf"
    override val usage = "printf FORMAT [ARG...]"
    override val summary = "Print formatted text; understands %s and %d."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.error("printf: missing format string")

        var argIndex = 0
        val nextArg = { if (argIndex < args.size) args[argIndex++] else "" }

        val out = buildString {
            var i = 0
            val format = args.first()
            while (i < format.length) {
                val c = format[i]
                if (c == '\\' && i + 1 < format.length) {
                    append(
                        when (val e = format[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '0' -> '\u0000'
                            else -> e
                        },
                    )
                    i += 2
                    continue
                }
                if (c == '%' && i + 1 < format.length) {
                    when (val spec = format[i + 1]) {
                        's' -> append(nextArg())
                        'd' -> append(nextArg().trim().toDoubleOrNull()?.toLong() ?: 0L)
                        '%' -> append('%')
                        else -> append('%').append(spec)
                    }
                    i += 2
                    continue
                }
                append(c)
                i++
            }
        }
        return CommandResult.ok(out)
    }
}

object CatCommand : SandboxCommand {
    override val name = "cat"
    override val usage = "cat [FILE...]"
    override val summary = "Concatenate files, or stdin when no file is given."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.ok(io.stdin)
        return readAll(args, env, io.stdin).let { CommandResult.ok(it) }
    }
}

object HeadCommand : SandboxCommand {
    override val name = "head"
    override val usage = "head [-n N] [FILE...]"
    override val summary = "Print the first N lines (default 10)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val (nValue, rest) = Args.option(args, "n")
        val n = Args.intOr(nValue, 10).coerceAtLeast(0)
        val text = readAll(rest, env, io.stdin)
        return CommandResult.ok(text.toLines().take(n).joinToString("\n").ensureTrailingNewline())
    }
}

object TailCommand : SandboxCommand {
    override val name = "tail"
    override val usage = "tail [-n N] [FILE...]"
    override val summary = "Print the last N lines (default 10)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val (nValue, rest) = Args.option(args, "n")
        val n = Args.intOr(nValue, 10).coerceAtLeast(0)
        val text = readAll(rest, env, io.stdin)
        return CommandResult.ok(text.toLines().takeLast(n).joinToString("\n").ensureTrailingNewline())
    }
}

object GrepCommand : SandboxCommand {
    override val name = "grep"
    override val usage = "grep [-i] [-v] [-c] [-n] PATTERN [FILE...]"
    override val summary = "Filter lines by a literal pattern or /regex/."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val ignoreCase = Args.flag(args, "i")
        val invert = Args.flag(args, "v")
        val countOnly = Args.flag(args, "c")
        val showNumbers = Args.flag(args, "n")
        val rest = args.filterNot { it.startsWith("-") && it.length > 1 }

        if (rest.isEmpty()) return CommandResult.error("grep: missing pattern")

        val pattern = rest.first()
        val files = rest.drop(1)

        // A /.../ pattern is treated as a regex; anything else is literal.
        val regex = if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) {
            runCatching {
                Regex(pattern.trim('/'), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            }.getOrNull()
        } else {
            null
        }

        fun matches(line: String): Boolean {
            val hit = when {
                regex != null -> regex.containsMatchIn(line)
                ignoreCase -> line.contains(pattern, ignoreCase = true)
                else -> line.contains(pattern)
            }
            return if (invert) !hit else hit
        }

        val text = readAll(files, env, io.stdin)
        val lines = text.toLines()

        if (countOnly) {
            return CommandResult.ok(lines.count { matches(it) }.toString() + "\n")
        }

        val out = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (matches(line)) {
                if (showNumbers) out.append("${index + 1}:")
                out.append(line).append('\n')
            }
        }

        return CommandResult(
            exitCode = if (out.isEmpty()) 1 else 0,
            stdout = out.toString(),
        )
    }
}

object WcCommand : SandboxCommand {
    override val name = "wc"
    override val usage = "wc [-l] [-w] [-c] [FILE...]"
    override val summary = "Count lines, words, or characters."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val lines = Args.flag(args, "l")
        val words = Args.flag(args, "w")
        val chars = Args.flag(args, "c")
        val files = args.filterNot { it.startsWith("-") && it.length > 1 }

        val text = readAll(files, env, io.stdin)
        val lineCount = text.toLines().size
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }

        val parts = when {
            lines -> listOf(lineCount.toString())
            words -> listOf(wordCount.toString())
            chars -> listOf(text.length.toString())
            else -> listOf(lineCount.toString(), wordCount.toString(), text.length.toString())
        }
        return CommandResult.ok(parts.joinToString(" ") + "\n")
    }
}

object SortCommand : SandboxCommand {
    override val name = "sort"
    override val usage = "sort [-n] [-r] [-u] [FILE...]"
    override val summary = "Sort lines numerically or alphabetically."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val numeric = Args.flag(args, "n")
        val reverse = Args.flag(args, "r")
        val unique = Args.flag(args, "u")
        val files = args.filterNot { it.startsWith("-") && it.length > 1 }

        var lines = readAll(files, env, io.stdin).toLines()

        lines = if (numeric) {
            lines.sortedBy { it.trim().toDoubleOrNull() ?: Double.MAX_VALUE }
        } else {
            lines.sorted()
        }
        if (reverse) lines = lines.reversed()
        if (unique) lines = lines.distinct()

        return CommandResult.ok(lines.joinToString("\n").ensureTrailingNewline())
    }
}

object UniqCommand : SandboxCommand {
    override val name = "uniq"
    override val usage = "uniq [-c] [FILE...]"
    override val summary = "Collapse repeated adjacent lines."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val count = Args.flag(args, "c")
        val files = args.filterNot { it.startsWith("-") && it.length > 1 }
        val lines = readAll(files, env, io.stdin).toLines()

        val out = StringBuilder()
        var index = 0
        while (index < lines.size) {
            var end = index
            while (end < lines.size && lines[end] == lines[index]) end++
            val repeats = end - index
            // Without -c, `uniq` emits each run once; with -c, it reports the count.
            if (count) {
                out.append(String.format("%7d %s%n", repeats, lines[index]))
            } else {
                out.append(lines[index]).append('\n')
            }
            index = end
        }
        return CommandResult.ok(out.toString())
    }
}

object CutCommand : SandboxCommand {
    override val name = "cut"
    override val usage = "cut -d DELIM -f LIST [FILE...]"
    override val summary = "Select fields or characters from each line."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val (delimiter, rest1) = Args.option(args, "d")
        val (fieldsSpec, rest) = Args.option(rest1, "f")
        val delim = delimiter?.firstOrNull() ?: ','
        val wanted = fieldsSpec?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        val text = readAll(rest, env, io.stdin)
        val out = text.toLines()
            .filter { it.isNotEmpty() }
            .joinToString("\n") { line ->
                val parts = line.split(delim)
                wanted.joinToString(delim.toString()) { index ->
                    parts.getOrNull(index - 1).orEmpty()
                }
            }
        return CommandResult.ok(out.ensureTrailingNewline())
    }
}

object SeqCommand : SandboxCommand {
    override val name = "seq"
    override val usage = "seq [FIRST] LAST [STEP]"
    override val summary = "Print a sequence of numbers."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.error("seq: missing operand")

        val numbers = args.mapNotNull { it.toDoubleOrNull() }
        if (numbers.size != args.size) return CommandResult.error("seq: invalid number")

        val (first, last, step) = when (numbers.size) {
            1 -> Triple(1.0, numbers[0], 1.0)
            2 -> Triple(numbers[0], numbers[1], 1.0)
            3 -> Triple(numbers[0], numbers[1], numbers[2])
            else -> return CommandResult.error("seq: too many arguments")
        }

        if (step == 0.0) return CommandResult.error("seq: step must not be zero")
        if (step > 0 && first > last) return CommandResult.ok("")
        if (step < 0 && first < last) return CommandResult.ok("")

        val out = StringBuilder()
        var value = first
        var guard = 0
        while ((step > 0 && value <= last) || (step < 0 && value >= last)) {
            out.append(formatNumber(value)).append('\n')
            value += step
            // Hard stop so a bad step cannot spin forever.
            if (++guard > MAX_ITERATIONS) break
        }
        return CommandResult.ok(out.toString())
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private const val MAX_ITERATIONS = 100_000
}

// ---------------------------------------------------------------------------
// Arithmetic and predicates
// ---------------------------------------------------------------------------

/**
 * Evaluates an infix arithmetic expression: `+ - * / % ^` with parentheses,
 * unary minus, and functions. Implemented with an explicit shunting-yard pass
 * rather than `eval`, so nothing can escape into the host language.
 */
object ExprCommand : SandboxCommand {
    override val name = "expr"
    override val usage = "expr EXPRESSION"
    override val summary = "Evaluate an arithmetic expression, e.g. expr \"(2+3)*4\"."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.error("expr: missing expression")
        val expression = args.joinToString(" ")

        return try {
            CommandResult.ok(format(ArithmeticEvaluator(expression).evaluate()) + "\n")
        } catch (e: IllegalArgumentException) {
            CommandResult.error("expr: ${e.message}")
        }
    }

    internal fun format(value: Double): String =
        if (value == value.toLong().toDouble() && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.')
        }
}

/** Shunting-yard evaluator for the arithmetic subset. */
internal class ArithmeticEvaluator(private val expression: String) {

    private val output = ArrayDeque<Double>()
    private val operators = ArrayDeque<String>()

    /** Function calls seen but not yet reduced, with their arity. */
    private val pendingFunctions = ArrayDeque<Pair<String, Int>>()

    fun evaluate(): Double {
        if (expression.isBlank()) throw IllegalArgumentException("empty expression")

        fun precedence(op: String): Int = when (op) {
            "+", "-" -> 1
            "*", "/", "%" -> 2
            "^" -> 3
            else -> -1
        }

        fun apply() {
            val op = operators.removeLastOrNull()
                ?: throw IllegalArgumentException("unexpected end of expression")
            if (op == UNARY_NEGATE) {
                val a = output.removeLastOrNull() ?: throw IllegalArgumentException("missing operand")
                output.addLast(-a)
                return
            }
            val b = output.removeLastOrNull() ?: throw IllegalArgumentException("missing operand")
            val a = output.removeLastOrNull() ?: throw IllegalArgumentException("missing operand")
            output.addLast(
                when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> {
                        if (b == 0.0) throw IllegalArgumentException("division by zero")
                        a / b
                    }
                    "%" -> {
                        if (b == 0.0) throw IllegalArgumentException("modulo by zero")
                        a % b
                    }
                    "^" -> a.pow(b)
                    else -> throw IllegalArgumentException("unknown operator '$op'")
                },
            )
        }

        var expectOperand = true
        var i = 0
        val source = expression

        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++

                // Argument separator inside a call such as `pow(2, 4)`.
                c == ',' -> i++

                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    // Scientific notation, e.g. 1e-3.
                    if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                        var probe = i + 1
                        if (probe < source.length && (source[probe] == '+' || source[probe] == '-')) probe++
                        if (probe < source.length && source[probe].isDigit()) {
                            i = probe
                            while (i < source.length && source[i].isDigit()) i++
                        }
                    }
                    val number = source.substring(start, i).toDoubleOrNull()
                        ?: throw IllegalArgumentException("malformed number at $start")
                    output.addLast(number)
                    expectOperand = false
                }

                c.isLetter() -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit())) i++
                    val name = source.substring(start, i)
                    if (!expectOperand) throw IllegalArgumentException("unexpected '$name'")

                    val arity = when (name.lowercase()) {
                        "sqrt" -> 1
                        "abs" -> 1
                        "floor" -> 1
                        "ceil" -> 1
                        "round" -> 1
                        "min" -> 2
                        "max" -> 2
                        "pow" -> 2
                        else -> throw IllegalArgumentException("unknown function '$name'")
                    }
                    if (i >= source.length || source[i] != '(') {
                        throw IllegalArgumentException("function '$name' requires parentheses")
                    }
                    // Mark the call site so the matching ')' knows to reduce it.
                    operators.addLast(FUNCTION_MARKER)
                    pendingFunctions.addLast(name.lowercase() to arity)
                    i++ // consume '('
                    expectOperand = true
                }

                c == '(' -> {
                    operators.addLast("(")
                    i++
                    expectOperand = true
                }

                c == ')' -> {
                    while (operators.isNotEmpty() &&
                        operators.last() != "(" &&
                        operators.last() != FUNCTION_MARKER
                    ) {
                        apply()
                    }
                    if (operators.isEmpty()) throw IllegalArgumentException("unbalanced parentheses")

                    if (operators.last() == FUNCTION_MARKER) {
                        operators.removeLast()
                        output.addLast(applyFunction(pendingFunctions.removeLast()))
                    } else {
                        operators.removeLast() // plain '('
                    }
                    i++
                    expectOperand = false
                }

                c in "+-*/%^" -> {
                    val op = c.toString()
                    if (op == "-" && expectOperand) {
                        operators.addLast(UNARY_NEGATE)
                    } else {
                        while (operators.isNotEmpty() &&
                            operators.last() != "(" &&
                            operators.last() != FUNCTION_MARKER &&
                            (precedence(operators.last()) > precedence(op) ||
                                (precedence(operators.last()) == precedence(op) && op != "^"))
                        ) {
                            apply()
                        }
                        operators.addLast(op)
                    }
                    i++
                    expectOperand = true
                }

                else -> throw IllegalArgumentException("unexpected character '$c'")
            }
        }

        while (operators.isNotEmpty()) {
            if (operators.last() == "(") throw IllegalArgumentException("unbalanced parentheses")
            apply()
        }

        return output.removeLastOrNull() ?: throw IllegalArgumentException("empty expression")
    }

    private fun applyFunction(entry: Pair<String, Int>): Double {
        val (name, arity) = entry
        val values = ArrayList<Double>(arity)
        repeat(arity) {
            values.add(output.removeLastOrNull() ?: throw IllegalArgumentException("missing argument"))
        }
        return when (name) {
            "sqrt" -> {
                if (values[0] < 0) throw IllegalArgumentException("sqrt of negative number")
                kotlin.math.sqrt(values[0])
            }
            "abs" -> abs(values[0])
            "floor" -> kotlin.math.floor(values[0])
            "ceil" -> kotlin.math.ceil(values[0])
            "round" -> Math.round(values[0]).toDouble()
            "min" -> minOf(values[0], values[1])
            "max" -> maxOf(values[0], values[1])
            "pow" -> values[0].pow(values[1])
            else -> throw IllegalArgumentException("unknown function '$name'")
        }
    }

    private companion object {
        /** Stands in for unary minus on the operator stack. */
        const val UNARY_NEGATE = "~neg"

        /** Marks a function call so its closing ')' reduces it. */
        const val FUNCTION_MARKER = "~fn"
    }
}

object TestCommand : SandboxCommand {
    override val name = "test"
    override val usage = "test -eq|-ne|-lt|-le|-gt|-ge A B   |   test -z|-n STRING"
    override val summary = "Evaluate a numeric or string comparison; exit 0 when true."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult(1)

        return when (args.size) {
            2 -> {
                val value = args[1]
                val result = when (args[0]) {
                    "-z" -> value.isEmpty()
                    "-n" -> value.isNotEmpty()
                    else -> false
                }
                CommandResult(if (result) 0 else 1)
            }

            // Numeric form is `test A OP B`, so the operator sits in the middle.
            3 -> {
                val operator = args[1]
                val a = args[0].toDoubleOrNull()
                val b = args[2].toDoubleOrNull()
                if (a == null || b == null) {
                    CommandResult.error("test: expected numbers, got '${args[0]}' and '${args[2]}'")
                } else {
                    val result = when (operator) {
                        "-eq" -> a == b
                        "-ne" -> a != b
                        "-lt" -> a < b
                        "-le" -> a <= b
                        "-gt" -> a > b
                        "-ge" -> a >= b
                        else -> return CommandResult.error("test: unknown operator '$operator'")
                    }
                    CommandResult(if (result) 0 else 1)
                }
            }

            else -> CommandResult.error("test: wrong number of arguments")
        }
    }
}

/** Always succeeds; the condition of an infinite `while true; do …; done`. */
object TrueCommand : SandboxCommand {
    override val name = "true"
    override val usage = "true"
    override val summary = "Exit with status 0 (loop condition helper)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult =
        CommandResult.ok()
}

/** Always fails; the negation of [TrueCommand]. */
object FalseCommand : SandboxCommand {
    override val name = "false"
    override val usage = "false"
    override val summary = "Exit with status 1 (loop condition helper)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult =
        CommandResult(1)
}

object SleepCommand : SandboxCommand {
    override val name = "sleep"
    override val usage = "sleep SECONDS"
    override val summary = "Pause for up to SECONDS (capped by the sandbox timeout)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val seconds = args.firstOrNull()?.toDoubleOrNull() ?: 0.0
        if (seconds <= 0) return CommandResult.ok()
        // Never block for more than a couple of seconds: the runner owns the
        // real deadline and will interrupt the script.
        Thread.sleep((seconds * 1000).toLong().coerceAtMost(2_000))
        return CommandResult.ok()
    }
}

object YesCommand : SandboxCommand {
    override val name = "yes"
    override val usage = "yes [STRING]"
    override val summary = "Repeat a string up to 1000 times (bounded)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val text = args.joinToString(" ").ifBlank { "y" }
        return CommandResult.ok((1..MAX_LINES).joinToString("") { "$text\n" })
    }

    private const val MAX_LINES = 1_000
}
