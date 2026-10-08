package com.paw.agent.device

/**
 * 统一的 Shell 与 Android input text 参数转义与校验工具。
 *
 * 避免在多个控制器中分散重复转义逻辑，统一杜绝单引号注入、控制字符截断与 %s 替换数据破坏。
 */
object ShellEscape {

    /**
     * 判断文本是否全部为合法的可打印 ASCII 字符 (32..126)。
     * 拒绝包含 \r, \n, \t, 退格等控制字符以避免破坏 shell 命令行结构。
     */
    fun isPrintableAscii(text: String): Boolean =
        text.all { it.code in 32..126 }

    /**
     * 转义用于 Android `input text '<escaped>'` 单引号内部的参数：
     * 1. 将单引号 `'` 转义为 `'\''`（闭合前一个单引号，插入转义字面单引号，再重新开启单引号）；
     * 2. 将字面空格 `' '` 转换为 `%s`（Android input 命令对空格的特殊处理语法）；
     * 3. 将字面 `%` 转义为 `%%`，防止字面 `%s` 被 Android input 误解析为空格而导致数据损坏。
     */
    fun escapeForInputText(line: String): String {
        return buildString {
            for (ch in line) {
                when (ch) {
                    '\'' -> append("'\\''")
                    ' ' -> append("%s")
                    '%' -> append("%%")
                    else -> append(ch)
                }
            }
        }
    }

    /**
     * 构造完整的 `input text '<escaped>'` 命令字符串供底层 Root/Shizuku 执行与单测断言。
     */
    fun buildInputTextCommand(line: String): String {
        val escaped = escapeForInputText(line)
        return "input text '$escaped'"
    }
}
