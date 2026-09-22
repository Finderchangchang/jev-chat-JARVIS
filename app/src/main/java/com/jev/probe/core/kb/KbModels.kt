package com.jev.probe.core.kb

/**
 * Local knowledge base data model (v1.3, D stage).
 *
 * Everything here lives only in the app-private `filesDir/kb` directory as plain
 * JSON — no database, no network, no export. The user can wipe all of it from
 * settings ("清空知识库与历史"), which deletes that directory and nothing else.
 */

/** One free-text knowledge note the user maintains by hand. */
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    /** Always injected regardless of what the conversation is about. */
    val alwaysOn: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One person (or group) the user chats with. [aliases] is what makes a contact
 * cross-app: the same person shows up as different conversation titles in
 * WeChat / QQ / Feishu, and each of those titles can be listed here.
 */
data class Contact(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Package names this contact has been seen in, e.g. com.tencent.mm. */
    val apps: List<String> = emptyList(),
    val relationship: String = "",
    val notes: String = "",
    /** Reserved for the (deferred) auto-summary; never written in v1.3. */
    val autoSummary: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** One remembered chat line. side is "me" / "other", matching [com.jev.probe.core.Msg]. */
data class LogEntry(val side: String, val text: String, val ts: Long, val app: String)

/**
 * What one analysis gets to see beyond the on-screen messages: who the other
 * party is, older history for them, and the knowledge notes that matched.
 */
data class ChatContext(
    val contact: Contact?,
    val history: List<LogEntry>,
    val notes: List<Note>
) {

    /** True when there is nothing extra to inject (then no field is sent at all). */
    fun isEmpty(): Boolean = history.isEmpty() && notes.isEmpty() &&
        (contact == null || (contact.relationship.isBlank() &&
            contact.notes.isBlank() && contact.autoSummary.isBlank()))

    /**
     * The `background` string injected into Jev's state and the reply prompt:
     * relationship + contact notes + auto-summary + each matched note as
     * "title: content". Blank when there is nothing to say — callers must then
     * omit the field entirely rather than send an empty one.
     *
     * @param defaultRelationship unused when the contact carries no relationship
     *        of its own — that global default already goes out separately as
     *        `chat.relationship`, so repeating it here would just duplicate it.
     *        A contact with no relationship set simply omits the "关系：" line.
     */
    fun background(defaultRelationship: String): String {
        val sb = StringBuilder()
        contact?.let { c ->
            val rel = c.relationship.trim()
            if (rel.isNotEmpty()) sb.append("关系：").append(rel).append('\n')
            if (c.notes.isNotBlank()) sb.append("关于").append(c.name).append("：")
                .append(c.notes.trim()).append('\n')
            if (c.autoSummary.isNotBlank()) sb.append("过往摘要：")
                .append(c.autoSummary.trim()).append('\n')
        }
        notes.forEach { n ->
            sb.append(n.title.trim()).append(": ").append(n.content.trim()).append('\n')
        }
        return sb.toString().trim()
    }
}
