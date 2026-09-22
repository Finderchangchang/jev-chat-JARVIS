package com.jev.probe.jev

/** Plain-text fallback when the reply is not a JSON array. */
internal object ReplyLineParser {
    // Require a separator (or an empty item), so "1.5小时" remains reply text.
    private val listPrefix = Regex("""^(?:[1-3][.)]|[-*])(?:[ \t]+|$)""")

    fun parse(content: String): List<String> {
        val lines = content.split("\n").map {
            val line = it.trim().trimStart('"', ' ')
            listPrefix.replaceFirst(line, "").trimStart('"', ' ')
        }.filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
