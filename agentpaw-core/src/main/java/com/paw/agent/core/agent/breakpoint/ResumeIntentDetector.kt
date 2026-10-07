package com.paw.agent.core.agent.breakpoint

/**
 * 续操意图检测结果
 *
 * @param isResume 是否识别为从断点继续执行的指令
 * @param additionalInstruction 用户在"继续"之外追加的细节指示（例如"刚才那步点第二个按钮"）
 */
data class ResumeIntentResult(
    val isResume: Boolean,
    val additionalInstruction: String? = null,
)

/**
 * 用户"继续"类指令与续操意图智能识别器
 */
object ResumeIntentDetector {

    private val directKeywords = listOf(
        "继续刚才的操作", "继续剩余操作", "继续下一步", "继续执行", "继续操作",
        "继续做", "继续吧", "接着执行", "接着做", "接着来", "接着干",
        "恢复执行", "keep going", "proceed", "continue", "resume",
        "go on", "继续", "接着",
    )

    private val acknowledgmentKeywords = listOf(
        "好了", "弄好了", "已完成", "搞定了", "完成了", "ok", "okay", "done", "已登录", "好了继续",
    )

    /**
     * 分析用户输入是否属于断点恢复指令
     *
     * @param input 用户发送的原始文本
     * @param hasActiveBreakpoint 当前是否存在活跃的待恢复断点
     */
    fun detect(input: String, hasActiveBreakpoint: Boolean): ResumeIntentResult {
        if (!hasActiveBreakpoint) {
            return ResumeIntentResult(isResume = false, additionalInstruction = input)
        }

        val text = input.trim()
        if (text.isEmpty()) {
            return ResumeIntentResult(isResume = true, additionalInstruction = null)
        }

        // 1. 完全匹配直接继续关键词
        for (kw in directKeywords) {
            if (text.equals(kw, ignoreCase = true)) {
                return ResumeIntentResult(isResume = true, additionalInstruction = null)
            }
        }

        // 2. 以继续关键词开头，附带指示（如 "继续，刚才那一步点确定"、"接着做：输入密码"）
        for (kw in directKeywords) {
            val prefixes = listOf(
                "$kw，", "$kw,", "$kw ", "$kw：", "$kw:", "${kw}并", "${kw}把", "${kw}从",
                "${kw}执行，", "${kw}做，",
            )
            for (prefix in prefixes) {
                if (text.startsWith(prefix, ignoreCase = true)) {
                    val detail = text.substring(prefix.length).trim()
                    return ResumeIntentResult(
                        isResume = true,
                        additionalInstruction = if (detail.isBlank()) null else detail,
                    )
                }
            }
        }

        // 3. 包含断点继续语义，例如 "我输入完了，继续"、"已经登录，继续执行"、"手动完成了，接着做"
        val resumePattern = Regex("""(?i).*(?:已经|已|我已|手动).*(?:好|了|完毕|完成).*[,，\s]*(?:继续|接着).*""")
        if (resumePattern.matches(text)) {
            return ResumeIntentResult(isResume = true, additionalInstruction = text)
        }

        // 4. 简短确认词（如 "好了"、"完成"、"ok"），在断点等待期间代表用户完成了手动干预（如输完验证码/扫码）
        for (ack in acknowledgmentKeywords) {
            if (text.equals(ack, ignoreCase = true)) {
                return ResumeIntentResult(
                    isResume = true,
                    additionalInstruction = "用户已确认手动操作就绪（$text），请直接从断点继续执行后续步骤",
                )
            }
        }

        // 5. 否则视为用户发起了全新的指令或意图，不继续原断点
        return ResumeIntentResult(isResume = false, additionalInstruction = text)
    }
}
