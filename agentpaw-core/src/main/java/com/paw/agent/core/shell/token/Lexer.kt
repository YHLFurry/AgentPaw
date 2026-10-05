package com.paw.agent.core.shell.token

/**
 * Hand-written lexer for the sandbox shell.
 *
 * Supports the subset a model actually needs to run calculations and text
 * processing: words, numbers, single/double quotes, escapes, variable
 * expansion, and the operator set the [com.paw.agent.core.shell.parser.Parser]
 * understands. No process substitution, no globbing, no backticks — those are
 * deliberately absent because they are the usual escape hatch out of a sandbox.
 */
class Lexer(private val source: String) {

    private var pos = 0
    private var line = 1
    private var column = 1

    /** Set when whitespace was skipped, consumed by the next [emit]. */
    private var pendingSpace = false

    private fun emit(tokens: MutableList<Token>, token: Token) {
        tokens += token.withSpacedBefore(pendingSpace)
        pendingSpace = false
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (pos < source.length) {
            val c = source[pos]

            when {
                c == '\n' -> {
                    emit(tokens, Token.Newline("\n", line, column))
                    advance()
                }

                c.isWhitespace() -> {
                    pendingSpace = true
                    advance()
                }

                c == '#' -> skipComment()

                c == '\'' -> emit(tokens, readSingleQuoted())

                c == '"' -> emit(tokens, readDoubleQuoted())

                c == '\\' -> {
                    val startLine = line
                    val startCol = column
                    advance()
                    if (pos >= source.length) {
                        throw ShellSyntaxException("Dangling escape", startLine, startCol)
                    }
                    val escaped = source[pos]
                    val ch = when (escaped) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        '"' -> '"'
                        '\'' -> '\''
                        '$' -> '$'
                        else -> throw ShellSyntaxException(
                            "Unsupported escape \\$escaped",
                            startLine,
                            startCol,
                        )
                    }
                    advance()
                    emit(tokens, Token.Word(ch.toString(), startLine, startCol, quoted = true))
                }

                c == '$' -> emit(tokens, readDollar())

                c.isDigit() || (c == '.' && pos + 1 < source.length && source[pos + 1].isDigit()) ->
                    emit(tokens, readNumber())

                isWordChar(c) -> emit(tokens, readWord())

                else -> {
                    val symbol = readSymbol()
                    if (symbol == null) {
                        throw ShellSyntaxException("Unexpected character '$c'", line, column)
                    }
                    emit(tokens, symbol)
                }
            }
        }

        return tokens
    }

    // ---- readers ----------------------------------------------------------

    private fun readWord(): Token {
        val startLine = line
        val startCol = column
        val sb = StringBuilder()
        while (pos < source.length && isWordChar(source[pos])) {
            sb.append(source[pos])
            advance()
        }
        return Token.Word(sb.toString(), startLine, startCol)
    }

    private fun readNumber(): Token {
        val startLine = line
        val startCol = column
        val sb = StringBuilder()
        var seenDot = false

        while (pos < source.length && source[pos].isDigit()) {
            sb.append(source[pos]); advance()
        }

        // Fractional part: consume '.' only when followed by a digit, so "1.foo"
        // is reported as an error rather than silently splitting into "1" + ".foo".
        if (pos < source.length && source[pos] == '.' && source.getOrNull(pos + 1)?.isDigit() == true) {
            seenDot = true
            sb.append('.'); advance()
            while (pos < source.length && source[pos].isDigit()) {
                sb.append(source[pos]); advance()
            }
        }

        // Exponent: e/E with an optional sign, must be followed by a digit.
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            var probe = pos + 1
            if (probe < source.length && (source[probe] == '+' || source[probe] == '-')) probe++
            if (probe < source.length && source[probe].isDigit()) {
                while (pos <= probe) { sb.append(source[pos]); advance() }
                while (pos < source.length && source[pos].isDigit()) {
                    sb.append(source[pos]); advance()
                }
            }
        }

        val text = sb.toString()
        val value = text.toDoubleOrNull()
            ?: throw ShellSyntaxException("Malformed number '$text'", startLine, startCol)
        return Token.Number(value, text, startLine, startCol)
    }

    private fun readSingleQuoted(): Token {
        val startLine = line
        val startCol = column
        advance() // opening quote
        val sb = StringBuilder()

        while (true) {
            if (pos >= source.length) {
                throw ShellSyntaxException("Unterminated single quote", startLine, startCol)
            }
            val c = source[pos]
            if (c == '\'') {
                advance()
                break
            }
            if (c == '\\' && source.getOrNull(pos + 1) == '\'') {
                sb.append('\'')
                advance(); advance()
                continue
            }
            if (c == '\n') sb.append('\n') else sb.append(c)
            advance()
        }
        return Token.Word(sb.toString(), startLine, startCol, quoted = true)
    }

    private fun readDoubleQuoted(): Token {
        val startLine = line
        val startCol = column
        advance() // opening quote
        val sb = StringBuilder()

        while (true) {
            if (pos >= source.length) {
                throw ShellSyntaxException("Unterminated double quote", startLine, startCol)
            }
            val c = source[pos]
            when {
                c == '"' -> {
                    advance()
                    break
                }

                c == '\\' -> {
                    advance()
                    val next = source.getOrNull(pos)
                        ?: throw ShellSyntaxException("Dangling escape", startLine, startCol)
                    sb.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            '$' -> '$'
                            else -> next
                        },
                    )
                    advance()
                }

                c == '$' -> sb.append(readVariableText())

                else -> {
                    if (c == '\n') sb.append('\n') else sb.append(c)
                    advance()
                }
            }
        }
        return Token.Word(sb.toString(), startLine, startCol, quoted = true)
    }

    /** Reads `$NAME` or `${NAME}`, returning the literal text to emit. */
    private fun readVariableText(): String {
        advance() // '$'
        if (pos < source.length && source[pos] == '{') {
            advance()
            val sb = StringBuilder()
            while (pos < source.length && source[pos] != '}') {
                sb.append(source[pos]); advance()
            }
            if (pos >= source.length) throw ShellSyntaxException("Unterminated \${", line, column)
            advance() // '}'
            return "\${" + sb.toString() + "}"
        }

        val sb = StringBuilder()
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
            sb.append(source[pos]); advance()
        }
        if (sb.isEmpty()) {
            throw ShellSyntaxException("Expected a variable name after '$'", line, column)
        }
        return "$" + sb.toString()
    }

    /**
     * Handles a bare `$` outside quotes.
     *
     * `$( … )` is captured whole (balanced parentheses) and emitted as a single
     * quoted word so the parser can turn it into a subshell; `$NAME` and
     * `${NAME}` are emitted as quoted words for variable expansion.
     */
    private fun readDollar(): Token {
        val startLine = line
        val startCol = column

        if (source.getOrNull(pos + 1) == '(') {
            advance() // '$'
            advance() // '('
            val body = StringBuilder()
            var depth = 1
            while (pos < source.length && depth > 0) {
                val c = source[pos]
                when {
                    c == '(' -> { depth++; body.append(c); advance() }
                    c == ')' -> {
                        depth--
                        if (depth == 0) { advance(); break }
                        body.append(c); advance()
                    }
                    c == '"' || c == '\'' -> {
                        // Copy the quoted span verbatim so an unbalanced paren
                        // inside a string does not throw off the depth count.
                        val quote = c
                        body.append(c); advance()
                        while (pos < source.length && source[pos] != quote) {
                            body.append(source[pos]); advance()
                        }
                        if (pos < source.length) { body.append(quote); advance() }
                    }
                    else -> { body.append(c); advance() }
                }
            }
            if (depth != 0) {
                throw ShellSyntaxException("Unterminated command substitution", startLine, startCol)
            }
            return Token.Word("\$(" + body.toString() + ")", startLine, startCol, quoted = true)
        }

        val raw = readVariableText()
        return Token.Word(raw, startLine, startCol, quoted = true)
    }

    private fun skipComment() {
        while (pos < source.length && source[pos] != '\n') advance()
    }

    private fun readSymbol(): Token? {
        val startLine = line
        val startCol = column

        // Two-character operators first.
        for (op in TWO_CHAR_SYMBOLS) {
            if (source.startsWith(op, pos)) {
                repeat(2) { advance() }
                return Token.Symbol(op, startLine, startCol)
            }
        }
        if (source[pos] in SINGLE_CHAR_SYMBOLS) {
            val c = source[pos]
            advance()
            return Token.Symbol(c.toString(), startLine, startCol)
        }
        return null
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Characters that may appear inside a bare word.
     *
     * Arithmetic symbols are deliberately excluded so `expr 2 + 3` lexes as
     * three tokens; `expr` re-joins its arguments with spaces before
     * evaluating, so both `2 + 3` and `2+3` end up as the same string.
     */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c == '/' || c == '@' ||
            c == ':' || c == '-' || c == '?' || c == '~' || c == ',' ||
            c == '[' || c == ']' || c == '='

    private fun advance() {
        if (pos < source.length && source[pos] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        pos++
    }

    private companion object {
        val TWO_CHAR_SYMBOLS = listOf("&&", "||", "==", "!=", "<=", ">=", ">>", "2>")
        val SINGLE_CHAR_SYMBOLS = setOf(
            '|', '<', '>', '(', ')', ';', '&', '=', '+', '-', '*', '/', '%', '^', '!',
            '{', '}', ',', '#',
        )
    }
}
