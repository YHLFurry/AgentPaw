package com.paw.agent.core.shell.parser

/**
 * AST for the sandbox shell.
 *
 * The grammar is a POSIX subset: pipelines joined by `|`, sequences joined by
 * `;`/`&&`/`||`, redirections, and simple `for`/`if`/`while` statements. There is
 * deliberately no command substitution or process substitution, both of which
 * would let generated code escape the sandbox's intent.
 */
sealed interface Node {
    /** Source span, for error messages. */
    val line: Int

    /** A single command with its arguments and any redirections. */
    data class SimpleCommand(
        val name: String,
        val args: List<Arg>,
        val redirections: List<Redirection> = emptyList(),
        override val line: Int = 1,
    ) : Node

    /** `left | right` — the right side receives the left side's stdout. */
    data class Pipeline(val stages: List<Node>, override val line: Int = 1) : Node

    /** `a; b`, `a && b`, or `a || b`. */
    data class Sequence(
        val items: List<SequenceItem>,
        override val line: Int = 1,
    ) : Node

    /** `for name in list; do ... done` */
    data class ForLoop(
        val variable: String,
        val iterable: List<Arg>,
        val body: Node,
        override val line: Int = 1,
    ) : Node

    /** `if cond; then ... else ... fi` */
    data class IfStatement(
        val condition: Node,
        val thenBranch: Node,
        val elseBranch: Node? = null,
        override val line: Int = 1,
    ) : Node

    /** `while cond; do ... done` */
    data class WhileLoop(
        val condition: Node,
        val body: Node,
        override val line: Int = 1,
    ) : Node
}

data class SequenceItem(val node: Node, val operator: JoinOperator)

enum class JoinOperator {
    /** `;` — always run the next item. */
    SEQUENCE,

    /** `&&` — run only if the previous item succeeded. */
    AND,

    /** `||` — run only if the previous item failed. */
    OR,
}

/** A command argument: a literal, a variable reference, or a nested placeholder. */
sealed interface Arg {
    /**
     * True when whitespace preceded this piece, meaning it begins a new word
     * rather than continuing the previous one.
     */
    val spacedBefore: Boolean

    data class Literal(val value: String, override val spacedBefore: Boolean = false) : Arg

    /** `$NAME` or `${NAME}`; resolved at run time from the environment. */
    data class Variable(val name: String, override val spacedBefore: Boolean = false) : Arg

    /** A subshell `( … )` whose stdout becomes this argument. */
    data class Subshell(val body: Node, override val spacedBefore: Boolean = false) : Arg

    /**
     * Adjacent pieces glued into one word, as in `pre$(cmd)post` or `a=$b`.
     * Shell words concatenate without a separator, so keeping them distinct
     * would insert a spurious space.
     */
    data class Template(
        val parts: List<Arg>,
        override val spacedBefore: Boolean = false,
    ) : Arg
}

sealed interface Redirection {
    /** Target file, or null for a numeric fd such as `2>`. */
    data class Output(
        val file: Arg,
        val append: Boolean,
        val stderr: Boolean = false,
    ) : Redirection

    /** `< file` */
    data class Input(val file: Arg) : Redirection
}
