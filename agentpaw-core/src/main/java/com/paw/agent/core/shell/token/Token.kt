package com.paw.agent.core.shell.token

/** A lexical token produced by [Lexer]. */
sealed interface Token {
    val lexeme: String
    val line: Int
    val column: Int

    /**
     * True when whitespace or nothing separated this token from the previous
     * one. The parser relies on this to tell `a$(cmd)b` (one word) from
     * `test $i -lt 3` (three words).
     */
    val spacedBefore: Boolean

    data class Word(
        override val lexeme: String,
        override val line: Int,
        override val column: Int,
        /** True when the word came from a quoted string, so whitespace is preserved. */
        val quoted: Boolean = false,
        override val spacedBefore: Boolean = false,
    ) : Token

    data class Number(
        val value: Double,
        override val lexeme: String,
        override val line: Int,
        override val column: Int,
        override val spacedBefore: Boolean = false,
    ) : Token

    /** An operator or separator; [lexeme] is the canonical symbol. */
    data class Symbol(
        override val lexeme: String,
        override val line: Int,
        override val column: Int,
        override val spacedBefore: Boolean = false,
    ) : Token

    data class Newline(
        override val lexeme: String,
        override val line: Int,
        override val column: Int,
        override val spacedBefore: Boolean = false,
    ) : Token

    /**
     * End of input. Distinct from [Newline] on purpose: a newline is a real
     * separator, whereas EOF must not make [com.paw.agent.core.shell.parser.Parser]
     * treat "no more tokens" as "end of this line".
     */
    data object Eof : Token {
        override val lexeme: String = ""
        override val line: Int = 0
        override val column: Int = 0
        override val spacedBefore: Boolean = true
    }
}

/** Thrown for malformed input; carries the position so the tool can report it. */
class ShellSyntaxException(
    message: String,
    val line: Int,
    val column: Int,
) : Exception("$message (line $line, column $column)")

/** Returns a copy of this token carrying [value] as its `spacedBefore` flag. */
fun Token.withSpacedBefore(value: Boolean): Token = when (this) {
    is Token.Word -> copy(spacedBefore = value)
    is Token.Number -> copy(spacedBefore = value)
    is Token.Symbol -> copy(spacedBefore = value)
    is Token.Newline -> copy(spacedBefore = value)
    Token.Eof -> this
}
