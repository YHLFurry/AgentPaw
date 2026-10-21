package com.paw.agent.core.skill

/**
 * 技能条件表达式。
 *
 * 设计目标是「够用且绝对安全」：没有 eval、没有反射、没有脚本引擎，
 * 只有比较、布尔运算与几个固定函数。表达式出错一律求值为 false，
 * 并把错误信息交给校验器/编辑器显示，而不是让技能在中途静默走错分支。
 *
 * 支持语法：
 * ```
 * {{mode}} == "fast"
 * foreground == "com.tencent.mm" && !empty(keyword)
 * count >= 3 || contains(screen_text, "登录")
 * empty(last_result)
 * ```
 * 函数：`contains(a,b)` `startsWith(a,b)` `endsWith(a,b)` `empty(a)`
 * `notEmpty(a)` `len(a)` `eq(a,b)` `neq(a,b)`
 */
object SkillExpression {

    /** 校验表达式语法；返回 null 表示合法。 */
    fun validate(expression: String): String? {
        if (expression.isBlank()) return null
        return try {
            val parser = Parser(tokenize(expression), emptyMap())
            parser.parseExpression()
            parser.expectEnd()
            null
        } catch (e: ExpressionException) {
            e.message ?: "表达式语法错误"
        }
    }

    /**
     * 求值。[variables] 中的键不带花括号，调用方负责把参数名原样放进去。
     * 任何解析/求值异常都返回 false——条件不成立比误执行安全。
     */
    fun evaluate(expression: String, variables: Map<String, String>): Boolean {
        if (expression.isBlank()) return true
        return try {
            val parser = Parser(tokenize(expression), variables)
            val value = parser.parseExpression()
            parser.expectEnd()
            value.truthy()
        } catch (e: ExpressionException) {
            false
        }
    }

    // ---------------------------------------------------------------- lexer

    private sealed interface Token {
        data class Ident(val name: String) : Token
        data class Str(val value: String) : Token
        data class Num(val value: Double) : Token
        data class Op(val symbol: String) : Token
    }

    private class ExpressionException(message: String) : Exception(message)

    private val COMPARISON_OPS = setOf("==", "!=", ">=", "<=", ">", "<")

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++

                c == '"' || c == '\'' -> {
                    val quote = c
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < source.length) {
                        val ch = source[i]
                        if (ch == '\\' && i + 1 < source.length) {
                            sb.append(source[i + 1])
                            i += 2
                            continue
                        }
                        if (ch == quote) {
                            closed = true
                            i++
                            break
                        }
                        sb.append(ch)
                        i++
                    }
                    if (!closed) throw ExpressionException("字符串缺少结束引号")
                    tokens += Token.Str(sb.toString())
                }

                c.isDigit() -> {
                    val start = i
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    tokens += Token.Num(source.substring(start, i).toDoubleOrNull() ?: 0.0)
                }

                // {{name}} / ${name}：模板变量直接当标识符处理，这样条件里
                // 可以原样复制步骤模板，不需要用户记住另一套写法。
                c == '{' && i + 1 < source.length && source[i + 1] == '{' -> {
                    val end = source.indexOf("}}", i + 2)
                    if (end < 0) throw ExpressionException("变量引用缺少 }}")
                    tokens += Token.Ident(source.substring(i + 2, end).trim())
                    i = end + 2
                }

                c == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    val end = source.indexOf('}', i + 2)
                    if (end < 0) throw ExpressionException("变量引用缺少 }")
                    tokens += Token.Ident(source.substring(i + 2, end).trim())
                    i = end + 1
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.')) i++
                    tokens += Token.Ident(source.substring(start, i))
                }

                c == '&' && i + 1 < source.length && source[i + 1] == '&' -> {
                    tokens += Token.Op("&&"); i += 2
                }

                c == '|' && i + 1 < source.length && source[i + 1] == '|' -> {
                    tokens += Token.Op("||"); i += 2
                }

                c == '=' && i + 1 < source.length && source[i + 1] == '=' -> {
                    tokens += Token.Op("=="); i += 2
                }

                c == '!' && i + 1 < source.length && source[i + 1] == '=' -> {
                    tokens += Token.Op("!="); i += 2
                }

                c == '>' && i + 1 < source.length && source[i + 1] == '=' -> {
                    tokens += Token.Op(">="); i += 2
                }

                c == '<' && i + 1 < source.length && source[i + 1] == '=' -> {
                    tokens += Token.Op("<="); i += 2
                }

                c in "><!()," -> {
                    tokens += Token.Op(c.toString()); i++
                }

                else -> throw ExpressionException("无法识别的字符 '$c'（位置 $i）")
            }
        }
        return tokens
    }

    // --------------------------------------------------------------- parser

    /** 求值期的一个值：数字或字符串，比较时按需转换。 */
    private data class Value(val raw: String) {
        val number: Double? get() = raw.trim().toDoubleOrNull()
        fun truthy(): Boolean = when {
            raw.equals("true", ignoreCase = true) -> true
            raw.equals("false", ignoreCase = true) -> false
            number != null -> number != 0.0
            else -> raw.isNotBlank()
        }
    }

    private class Parser(
        private val tokens: List<Token>,
        private val variables: Map<String, String>,
    ) {
        private var position = 0

        private fun peek(): Token? = tokens.getOrNull(position)

        private fun matchOp(symbol: String): Boolean {
            val token = peek()
            if (token is Token.Op && token.symbol == symbol) {
                position++
                return true
            }
            return false
        }

        private fun requireOp(symbol: String) {
            if (!matchOp(symbol)) throw ExpressionException("缺少 '$symbol'")
        }

        fun expectEnd() {
            if (position < tokens.size) {
                throw ExpressionException("表达式末尾有多余内容")
            }
        }

        fun parseExpression(): Value = parseOr()

        private fun parseOr(): Value {
            var left = parseAnd()
            while (matchOp("||")) {
                val right = parseAnd()
                left = Value((left.truthy() || right.truthy()).toString())
            }
            return left
        }

        private fun parseAnd(): Value {
            var left = parseUnary()
            while (matchOp("&&")) {
                val right = parseUnary()
                left = Value((left.truthy() && right.truthy()).toString())
            }
            return left
        }

        private fun parseUnary(): Value {
            if (matchOp("!")) {
                return Value((!parseUnary().truthy()).toString())
            }
            return parseComparison()
        }

        private fun parseComparison(): Value {
            val left = parsePrimary()
            val token = peek()
            if (token is Token.Op && token.symbol in COMPARISON_OPS) {
                position++
                val right = parsePrimary()
                return Value(compare(left, token.symbol, right).toString())
            }
            return left
        }

        private fun compare(left: Value, op: String, right: Value): Boolean {
            val ln = left.number
            val rn = right.number
            val result = if (ln != null && rn != null) {
                when (op) {
                    "==" -> ln == rn
                    "!=" -> ln != rn
                    ">" -> ln > rn
                    ">=" -> ln >= rn
                    "<" -> ln < rn
                    "<=" -> ln <= rn
                    else -> false
                }
            } else {
                val a = left.raw.trim()
                val b = right.raw.trim()
                when (op) {
                    "==" -> a.equals(b, ignoreCase = true)
                    "!=" -> !a.equals(b, ignoreCase = true)
                    ">" -> a > b
                    ">=" -> a >= b
                    "<" -> a < b
                    "<=" -> a <= b
                    else -> false
                }
            }
            return result
        }

        private fun parsePrimary(): Value {
            val token = peek() ?: throw ExpressionException("表达式不完整")
            return when (token) {
                is Token.Str -> {
                    position++
                    Value(token.value)
                }

                is Token.Num -> {
                    position++
                    Value(token.value.toString())
                }

                is Token.Ident -> {
                    position++
                    when {
                        matchOp("(") -> callFunction(token.name)
                        token.name.equals("true", true) -> Value("true")
                        token.name.equals("false", true) -> Value("false")
                        else -> Value(resolve(token.name))
                    }
                }

                is Token.Op -> {
                    if (token.symbol == "(") {
                        position++
                        val inner = parseExpression()
                        requireOp(")")
                        inner
                    } else {
                        throw ExpressionException("意外的符号 '${token.symbol}'")
                    }
                }
            }
        }

        private fun callFunction(name: String): Value {
            val args = mutableListOf<Value>()
            if (!matchOp(")")) {
                do {
                    args += parseExpression()
                } while (matchOp(","))
                requireOp(")")
            }
            fun arg(index: Int): String =
                args.getOrNull(index)?.raw?.trim()
                    ?: throw ExpressionException("函数 $name 缺少第 ${index + 1} 个参数")

            val result = when (name.lowercase()) {
                "contains" -> arg(0).contains(arg(1), ignoreCase = true)
                "startswith" -> arg(0).startsWith(arg(1), ignoreCase = true)
                "endswith" -> arg(0).endsWith(arg(1), ignoreCase = true)
                "empty" -> arg(0).isBlank()
                "notempty" -> arg(0).isNotBlank()
                "len" -> args.getOrNull(0)?.raw?.length ?: 0
                "eq" -> arg(0).equals(arg(1), ignoreCase = true)
                "neq" -> !arg(0).equals(arg(1), ignoreCase = true)
                else -> throw ExpressionException("未知函数 '$name'")
            }
            return Value(result.toString())
        }

        /** 变量解析：`{{name}}`、`${name}`、裸标识符都接受。 */
        private fun resolve(rawName: String): String {
            val name = rawName
                .removePrefix("{{").removeSuffix("}}")
                .removePrefix("\${").removeSuffix("}")
                .removePrefix("$")
            return variables[name] ?: ""
        }
    }

    /**
     * 对外入口的便捷包装：先绑定变量再求值。
     * （[evaluate] 内部会新建 Parser，这里保留一个语义更直观的重载。）
     */
    fun evaluateWith(expression: String, variables: Map<String, String>): Boolean =
        evaluate(expression, variables)
}
