package com.paw.agent.core.shell.parser

import com.paw.agent.core.shell.token.Lexer
import com.paw.agent.core.shell.token.ShellSyntaxException
import com.paw.agent.core.shell.token.Token

/**
 * Recursive-descent parser for the sandbox shell.
 *
 * Grammar (lowest to highest precedence):
 * ```
 * program  := sequence
 * sequence := pipeline ((';' | '&&' | '||') pipeline)*
 * pipeline := command ('|' command)*
 * command  := word+ redirection* | 'for' … | 'if' … | 'while' … | '(' program ')'
 * ```
 */
class Parser(private val tokens: List<Token>) {

    private var pos = 0

    companion object {
        /**
         * Characters that close an open group. A literal starting with one of
         * these continues the current word rather than starting a new argument.
         */
        const val CLOSING_CHARS = ")]}>"

        fun parse(source: String): Node = Parser(Lexer(source).tokenize()).parseProgram()
    }

    fun parseProgram(): Node {
        skipNewlines()
        if (isAtEnd()) return Node.Sequence(emptyList())

        val node = parseSequence()
        skipNewlines()
        if (!isAtEnd()) {
            val t = peek()
            throw ShellSyntaxException("Unexpected '${t.lexeme}'", t.line, t.column)
        }
        return node
    }

    // ---- sequence ---------------------------------------------------------

    private fun parseSequence(): Node {
        val items = mutableListOf<SequenceItem>()

        skipNewlines()
        items += SequenceItem(parsePipeline(), JoinOperator.SEQUENCE)

        while (true) {
            skipNewlines()
            if (isAtEnd() || isCloser()) break

            // Read the operator before consuming it, otherwise `a; b` would
            // swallow the `;` here and stop the loop before running `b`.
            val operator = when {
                checkSymbol("&&") -> JoinOperator.AND
                checkSymbol("||") -> JoinOperator.OR
                checkSymbol(";") -> JoinOperator.SEQUENCE
                checkSymbol("&") -> JoinOperator.SEQUENCE
                else -> break
            }
            skipSeparators()
            skipNewlines()
            // After a separator the next word may be a closer keyword
            // (`done`, `then`, `fi`, `else`), which ends this body rather than
            // starting a new command.
            if (isAtEnd() || isCloser()) break
            items += SequenceItem(parsePipeline(), operator)
        }

        return if (items.size == 1 && items.first().operator == JoinOperator.SEQUENCE) {
            items.first().node
        } else {
            Node.Sequence(items, line = items.first().node.line)
        }
    }

    // ---- pipeline ---------------------------------------------------------

    private fun parsePipeline(): Node {
        val stages = mutableListOf(parseCommand())
        while (checkSymbol("|")) {
            advance() // consume the pipe before the next stage
            stages += parseCommand()
        }
        return if (stages.size == 1) stages.first() else Node.Pipeline(stages)
    }

    // ---- command ----------------------------------------------------------

    private fun parseCommand(): Node {
        val token = peek()

        return when {
            token is Token.Word && token.lexeme == "for" && !token.quoted -> parseFor()
            token is Token.Word && token.lexeme == "if" && !token.quoted -> parseIf()
            token is Token.Word && token.lexeme == "while" && !token.quoted -> parseWhile()
            checkSymbol("(") -> parseSubshell()
            else -> parseSimpleCommand()
        }
    }

    private fun parseSimpleCommand(): Node.SimpleCommand {
        val first = peek()
        val line = first.line
        val args = mutableListOf<Arg>()
        val redirections = mutableListOf<Redirection>()
        var name: String? = null

        while (!isAtEnd()) {
            val token = peek()

            if (token is Token.Newline || isSeparator() || checkSymbol("|")) break
            // `)` alone ends a subshell, but inside an argument list it closes a
            // group such as `expr sqrt(9)`, so only stop on it at the top level.
            if (isCloser() && !checkSymbol(")")) break

            if (token is Token.Symbol) {
                val redirection = tryParseRedirection()
                if (redirection != null) {
                    redirections += redirection
                    continue
                }
                // Not a redirection: treat the symbol as literal argument text,
                // which is what `expr 2 + 3` and `sqrt(9)` need.
                // readSymbolGroup consumes a whole `( … )` run, so only advance
                // for the single-character case.
                val isGroup = checkSymbol("(")
                appendWord(args, Arg.Literal(readSymbolGroup(), token.spacedBefore))
                if (!isGroup) advance()
                continue
            }

            val arg = toArg(token)
            if (name == null) {
                // The first word is the command name, not an argument.
                name = (arg as? Arg.Literal)?.value
                    ?: throw ShellSyntaxException(
                        "Command name must be a literal, got '${token.lexeme}'",
                        token.line,
                        token.column,
                    )
            } else {
                appendWord(args, arg)
            }
            advance()
        }

        if (name == null) {
            throw ShellSyntaxException("Expected a command name", first.line, first.column)
        }

        return Node.SimpleCommand(name, args, redirections, line)
    }

    /**
     * Appends [piece] to the argument list, merging it into the previous entry
     * when the two belong to the same word (`a=$b`, `x$(cmd)y`, `sqrt(9)`).
     */
    private fun appendWord(args: MutableList<Arg>, piece: Arg) {
        val previous = args.lastOrNull()
        if (previous == null) {
            args += piece
            return
        }

        // Whitespace ended the previous word, so this starts a new one. This is
        // what keeps `test $i -lt 3` as three arguments rather than `0-lt3`.
        if (piece.spacedBefore) {
            args += piece
            return
        }

        val left = when (previous) {
            is Arg.Template -> previous.parts
            else -> listOf(previous)
        }
        val right = when (piece) {
            is Arg.Template -> piece.parts
            else -> listOf(piece)
        }
        args[args.lastIndex] = Arg.Template(left + right)
    }

    /**
     * Reads a symbol, expanding a balanced `( … )` group so a function call such
     * as `sqrt(9)` arrives as one token.
     */
    private fun readSymbolGroup(): String {
        if (!checkSymbol("(")) return peek().lexeme
        return readBalancedGroup()
    }

    /**
     * Copies a balanced `( … )` group verbatim, so function-call arguments such
     * as `sqrt(9)` reach the command as a single token.
     */
    private fun readBalancedGroup(): String {
        val sb = StringBuilder()
        var depth = 0

        while (!isAtEnd()) {
            val token = peek()
            when {
                checkSymbol("(") -> {
                    depth++
                    sb.append('(')
                    advance()
                }

                checkSymbol(")") -> {
                    depth--
                    sb.append(')')
                    advance()
                    if (depth == 0) return sb.toString()
                }

                token is Token.Newline || token is Token.Eof ->
                    throw ShellSyntaxException("Unbalanced parentheses", token.line, token.column)

                else -> {
                    sb.append(token.lexeme)
                    advance()
                }
            }
        }
        throw ShellSyntaxException("Unbalanced parentheses", peek().line, peek().column)
    }

    /** Returns the redirection if the cursor is on one, else null. */
    private fun tryParseRedirection(): Redirection? {
        return when {
            checkSymbol(">") -> {
                advance()
                Redirection.Output(readTarget(), append = false)
            }

            checkSymbol(">>") -> {
                advance()
                Redirection.Output(readTarget(), append = true)
            }

            checkSymbol("<") -> {
                advance()
                Redirection.Input(readTarget())
            }

            checkSymbol("2>") -> {
                advance()
                Redirection.Output(readTarget(), append = false, stderr = true)
            }

            // Arithmetic symbols are ordinary argument text (`expr 2 + 3`),
            // so anything that is not a real redirection is simply not one.
            else -> null
        }
    }

    private fun readTarget(): Arg {
        val token = peek()
        if (token is Token.Newline || isAtEnd()) {
            throw ShellSyntaxException("Expected a file after redirection", token.line, token.column)
        }
        val arg = toArg(token)
        advance()
        return arg
    }

    private fun parseSubshell(): Node {
        val open = peek()
        advance() // '('
        skipNewlines()
        val body = parseSequence()
        skipNewlines()
        if (!checkSymbol(")")) {
            val t = peek()
            throw ShellSyntaxException("Expected ')'", t.line, t.column)
        }
        advance()
        return body
    }

    // ---- control flow -----------------------------------------------------

    private fun parseFor(): Node.ForLoop {
        val start = advance() // 'for'
        val nameToken = peek()
        if (nameToken !is Token.Word) {
            throw ShellSyntaxException("Expected a loop variable", nameToken.line, nameToken.column)
        }
        advance()

        if (!checkWord("in")) {
            throw ShellSyntaxException("Expected 'in'", peek().line, peek().column)
        }
        advance()

        val items = mutableListOf<Arg>()
        while (!isAtEnd() && !checkWord("do") && !checkSymbol(";")) {
            items += toArg(peek())
            advance()
        }

        // `for x in a b c; do` — the separator is optional before `do`.
        expectKeyword("do")
        skipNewlines()
        val body = parseSequence()
        skipNewlines()
        expectWord("done")

        return Node.ForLoop(nameToken.lexeme, items, body, start.line)
    }

    private fun parseIf(): Node.IfStatement {
        val start = advance() // 'if'
        val condition = parseSequence()
        expectKeyword("then")
        skipNewlines()
        val thenBranch = parseSequence()
        skipNewlines()

        var elseBranch: Node? = null
        if (checkWord("else")) {
            advance()
            skipNewlines()
            elseBranch = parseSequence()
            skipNewlines()
        }

        expectWord("fi")
        return Node.IfStatement(condition, thenBranch, elseBranch, start.line)
    }

    private fun parseWhile(): Node.WhileLoop {
        val start = advance() // 'while'
        val condition = parseSequence()
        expectKeyword("do")
        skipNewlines()
        val body = parseSequence()
        skipNewlines()
        expectWord("done")
        return Node.WhileLoop(condition, body, start.line)
    }

    /**
     * Finishes a condition or header and consumes [keyword].
     *
     * The `;` before `do`/`then` is optional, so separators are skipped here
     * rather than being consumed by [parseSequence].
     */
    private fun expectKeyword(keyword: String) {
        skipSeparators()
        skipNewlines()
        expectWord(keyword)
    }

    // ---- helpers ----------------------------------------------------------

    private fun toArg(token: Token): Arg = when (token) {
        is Token.Word -> {
            when {
                // `$( … )` — parse the captured body as a nested program.
                token.quoted && token.lexeme.startsWith("$(") && token.lexeme.endsWith(")") -> {
                    val body = token.lexeme.substring(2, token.lexeme.length - 1)
                    Arg.Subshell(
                        body = Parser(Lexer(body).tokenize()).parseProgram(),
                        spacedBefore = token.spacedBefore,
                    )
                }

                token.quoted && token.lexeme.startsWith("$") ->
                    parseVariableRef(token).withSpaced(token.spacedBefore)

                else -> Arg.Literal(token.lexeme, token.spacedBefore)
            }
        }

        is Token.Number -> Arg.Literal(token.lexeme, token.spacedBefore)
        is Token.Symbol -> Arg.Literal(token.lexeme, token.spacedBefore)
        is Token.Newline, is Token.Eof -> Arg.Literal("")
    }

    private fun Arg.withSpaced(value: Boolean): Arg = when (this) {
        is Arg.Literal -> copy(spacedBefore = value)
        is Arg.Variable -> copy(spacedBefore = value)
        is Arg.Subshell -> copy(spacedBefore = value)
        is Arg.Template -> this
    }

    /** `$NAME` and `${NAME}` become [Arg.Variable]; a literal `$` stays literal. */
    private fun parseVariableRef(token: Token.Word): Arg {
        val body = token.lexeme
        return when {
            body.startsWith("\${") && body.endsWith("}") ->
                Arg.Variable(body.substring(2, body.length - 1))

            body.startsWith("$") && body.length > 1 ->
                Arg.Variable(body.substring(1))

            else -> Arg.Literal(body)
        }
    }

    private fun advance(): Token = tokens.getOrNull(pos++) ?: Token.Eof

    private fun peek(): Token = tokens.getOrNull(pos) ?: Token.Eof

    private fun isAtEnd(): Boolean = pos >= tokens.size

    private fun checkSymbol(lexeme: String): Boolean =
        (peek() as? Token.Symbol)?.lexeme == lexeme

    private fun checkWord(lexeme: String): Boolean =
        (peek() as? Token.Word)?.lexeme == lexeme

    private fun expectWord(lexeme: String) {
        if (!checkWord(lexeme)) {
            val t = peek()
            throw ShellSyntaxException("Expected '$lexeme'", t.line, t.column)
        }
        advance()
    }

    private fun isSeparator(): Boolean =
        checkSymbol(";") || checkSymbol("&&") || checkSymbol("||") || checkSymbol("&")

    /**
     * Keywords and `)` that terminate the current construct.
     *
     * `do` must be included: after `while <cond>;` the next word is `do`, and
     * without this the condition would swallow it as another command.
     */
    private fun isCloser(): Boolean = when {
        checkSymbol(")") -> true
        checkWord("done") || checkWord("do") || checkWord("then") ||
            checkWord("else") || checkWord("fi") -> true
        else -> false
    }

    private fun skipNewlines() {
        while (peek() is Token.Newline) advance()
    }

    private fun skipSeparators() {
        while (isSeparator()) advance()
    }
}
