package com.paw.agent.device.accessibility

/** 与系统输入一致：替换选区，无选区时在末尾追加，clearBeforeInput 时整体替换。 */
internal data class TextInputEdit(val text: String, val cursor: Int) {
    companion object {
        fun resolve(existing: String, incoming: String, start: Int, end: Int, clear: Boolean): TextInputEdit {
            if (clear) return TextInputEdit(incoming, incoming.length)
            val validSelection = start in 0..existing.length && end in 0..existing.length
            val from = if (validSelection) minOf(start, end) else existing.length
            val to = if (validSelection) maxOf(start, end) else existing.length
            return TextInputEdit(existing.replaceRange(from, to, incoming), from + incoming.length)
        }
    }
}
