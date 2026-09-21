package com.jev.probe.core.kb

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs

/**
 * On-device smoke test for the knowledge-base path, reachable from the settings
 * screen ("自检"). It exercises the parts that are easy to get quietly wrong —
 * name normalization across half/full-width member counts, note keyword hits,
 * and history de-duplication against what is already on screen — then removes
 * everything it created.
 *
 * It runs against the real [KbStore] (temporary note + contact, deleted at the
 * end) but a scratch [Prefs] file, so the user's own contextEnabled setting is
 * never touched.
 */
object KbSelfCheck {

    private const val SCRATCH_PREFS = "jev_kb_selfcheck_scratch"
    private const val TITLE = "测试群(12)"
    private const val ALIAS = "测试群（12）"          // full-width parens on purpose
    private const val OLD_LINE = "上周说好周五交自检稿"

    /** @return a one-line human-readable pass/fail summary. */
    fun run(context: Context): String {
        val store = KbStore.get(context)
        val noteId = KbStore.newId()
        val contactId = KbStore.newId()
        val prefs = scratchPrefs(context)
        val failures = ArrayList<String>()
        try {
            prefs.contextEnabled = true
            prefs.contextHistoryCount = 30

            store.saveNote(Note(
                id = noteId,
                title = "自检临时笔记",
                content = "自检用的虚构事实：项目代号叫小蓝。",
                tags = listOf("测试"),
                alwaysOn = false,
                enabled = true
            ))
            store.saveContact(Contact(
                id = contactId,
                name = "自检临时联系人",
                aliases = listOf(ALIAS),
                apps = listOf("com.jev.probe"),
                relationship = "自检用的关系描述"
            ))

            val snapshot = ChatSnapshot(TITLE, listOf(
                Msg("other", "自检消息一：这条够长可以去重"),
                Msg("me", "自检消息二：这条也够长")
            ))

            // 1. contact hit via full-width alias + member-count stripping
            val ctx1 = ContextBuilder.build(context, snapshot, "com.jev.probe", prefs)
            if (ctx1.contact?.id != contactId)
                failures.add("联系人未命中（标题 ${TITLE} 应匹配别名 ${ALIAS}）")

            // 2. note hit via tag "测试" appearing in the conversation title
            if (ctx1.notes.none { it.id == noteId })
                failures.add("笔记未命中（tag=测试 应命中标题 ${TITLE}）")

            // 3. the on-screen messages were recorded but not echoed back as history
            if (ctx1.history.isNotEmpty())
                failures.add("历史去重失败：当屏消息不该出现在注入历史里（${ctx1.history.size} 条）")
            if (store.logSize(contactId) != snapshot.messages.size)
                failures.add("历史落盘条数不对：期望 ${snapshot.messages.size}，实际 ${store.logSize(contactId)}")

            // 4. an older line survives, and re-reading the same screen adds nothing
            store.appendLog(contactId, listOf(
                LogEntry("other", OLD_LINE, System.currentTimeMillis() - 86_400_000L, "com.jev.probe")))
            val ctx2 = ContextBuilder.build(context, snapshot, "com.jev.probe", prefs)
            if (ctx2.history.size != 1 || ctx2.history.firstOrNull()?.text != OLD_LINE)
                failures.add("历史注入不对：期望仅 1 条旧消息，实际 ${ctx2.history.size} 条")
            if (store.logSize(contactId) != snapshot.messages.size + 1)
                failures.add("重复采集被写了第二遍：${store.logSize(contactId)} 条")

            // 5. background carries the fabricated fact into the prompt
            val background = ctx2.background("默认关系")
            if (!background.contains("小蓝")) failures.add("background 里没有笔记正文")
            if (!background.contains("自检用的关系描述")) failures.add("background 里没有联系人关系")

            // 6. history is off by default (opt-in only)
            prefs.contextEnabled = false
            if (ContextBuilder.build(context, snapshot, "com.jev.probe", prefs).history.isNotEmpty())
                failures.add("contextEnabled=false 时仍注入了历史")
        } catch (e: Exception) {
            failures.add("异常：${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            runCatching { store.deleteNote(noteId) }
            runCatching { store.deleteContact(contactId) }
            runCatching {
                context.getSharedPreferences(SCRATCH_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
        val counts = store.counts()
        return if (failures.isEmpty())
            "自检通过：联系人匹配 / 笔记命中 / 历史去重 / 预算注入都正常。" +
                "当前知识库 ${counts.notes} 条笔记、${counts.contacts} 个联系人、${counts.logLines} 条历史。"
        else "自检失败（${failures.size}）：" + failures.joinToString("；")
    }

    /** A [Prefs] bound to a throwaway SharedPreferences file. */
    private fun scratchPrefs(context: Context): Prefs {
        context.getSharedPreferences(SCRATCH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        return Prefs(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(SCRATCH_PREFS, mode)
        })
    }
}
