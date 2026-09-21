package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Note / contact / history counts, for the settings screen. */
data class KbCounts(val notes: Int, val contacts: Int, val logLines: Int)

/**
 * The knowledge-base store: three kinds of JSON file under `filesDir/kb`.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/<contactId>.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches logcat — only counts and lengths.
 */
class KbStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val root: File get() = File(app.filesDir, "kb")
    private val notesFile: File get() = File(root, "notes.json")
    private val contactsFile: File get() = File(root, "contacts.json")
    private fun logFile(contactId: String) = File(File(root, "logs"), "$contactId.json")

    private var notesCache: MutableList<Note>? = null
    private var contactsCache: MutableList<Contact>? = null
    private val logCache = HashMap<String, MutableList<LogEntry>>()

    // ------------------------------------------------------------------ notes

    fun notes(): List<Note> = synchronized(lock) { loadNotes().toList() }

    fun note(id: String): Note? = synchronized(lock) { loadNotes().firstOrNull { it.id == id } }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    fun saveNote(note: Note): Boolean = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null   // memory must not claim a write that failed
        ok
    }

    fun deleteNote(id: String): Boolean = synchronized(lock) {
        val list = loadNotes()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null
        ok
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun saveContact(c: Contact): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        val stamped = c.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (!ok) contactsCache = null
        ok
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        var ok = true
        if (list.removeAll { it.id == id }) {
            ok = writeAtomic(contactsFile, contactsJson(list))
            if (!ok) contactsCache = null
        }
        logCache.remove(id)
        runCatching { logFile(id).delete() }
        ok
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    fun findContact(title: String, app: String): Contact? {
        synchronized(lock) {
            val want = normalizeName(title)
            if (want.isEmpty()) return null
            val hits = loadContacts().filter { c ->
                normalizeName(c.name) == want || c.aliases.any { normalizeName(it) == want }
            }
            if (hits.isEmpty()) return null
            return hits.firstOrNull { app.isNotBlank() && it.apps.contains(app) } ?: hits.first()
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     */
    fun saveOrMergeContact(title: String, app: String): String {
        val display = displayName(title)
        if (display.isEmpty()) return "当前会话没有标题，存不了"
        val existing = findContact(title, app)
        if (existing == null) {
            val aliases = if (displayName(title) != title.trim()) listOf(title.trim()) else emptyList()
            saveContact(Contact(
                id = newId(),
                name = display,
                aliases = aliases,
                apps = if (app.isBlank()) emptyList() else listOf(app)
            ))
            return "已存为联系人「${display}」"
        }
        val apps = if (app.isBlank() || existing.apps.contains(app)) existing.apps else existing.apps + app
        val raw = title.trim()
        val known = (listOf(existing.name) + existing.aliases).map { normalizeName(it) }
        val aliases = if (raw.isNotEmpty() && normalizeName(raw) !in known)
            existing.aliases + raw else existing.aliases
        if (apps == existing.apps && aliases == existing.aliases)
            return "联系人「${existing.name}」已存在"
        saveContact(existing.copy(apps = apps, aliases = aliases))
        return "已并入联系人「${existing.name}」"
    }

    // ---------------------------------------------------------------- history

    /**
     * Append the visible messages, keeping only the newest [MAX_LOG].
     *
     * Dedupe is deliberately NARROW: an incoming line is skipped only when the
     * same (side, text) is already sitting in the recent tail — i.e. this screen
     * was captured a moment ago — and only when it is long enough
     * ([DEDUPE_MIN_LEN]+) for an exact repeat to certainly be the same message.
     * Deduping against the whole 300-line history would erase the fact that
     * someone really did say the same thing twice. The cost of the narrow rule:
     * short lines ("嗯", "好的") can be recorded again on a re-capture.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val list = loadLog(contactId)
            val window = maxOf(entries.size * 3, MIN_DEDUPE_WINDOW)
            val seen = HashSet<String>(window * 2)
            for (i in maxOf(0, list.size - window) until list.size) {
                val e = list[i]
                if (e.text.length >= DEDUPE_MIN_LEN) seen.add(key(e.side, e.text))
            }
            var added = 0
            for (e in entries) {
                if (e.text.isBlank()) continue
                if (e.text.length >= DEDUPE_MIN_LEN && !seen.add(key(e.side, e.text))) continue
                list.add(e); added++
            }
            if (added == 0) return true
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = writeAtomic(logFile(contactId), logJson(list))
            if (!ok) logCache.remove(contactId)
            Log.d(TAG, "appendLog contact=$contactId added=$added total=${list.size} ok=$ok")
            return ok
        }
    }

    /** The newest [n] entries, oldest first. */
    fun recentLog(contactId: String, n: Int): List<LogEntry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            val list = loadLog(contactId)
            return if (list.size <= n) list.toList()
            else list.subList(list.size - n, list.size).toList()
        }
    }

    fun logSize(contactId: String): Int = synchronized(lock) { loadLog(contactId).size }

    fun clearLog(contactId: String) = synchronized(lock) {
        logCache.remove(contactId)
        runCatching { logFile(contactId).delete() }
        Unit
    }

    // ------------------------------------------------------------------ admin

    fun counts(): KbCounts = synchronized(lock) {
        val contacts = loadContacts()
        var lines = 0
        contacts.forEach { lines += loadLog(it.id).size }
        KbCounts(loadNotes().size, contacts.size, lines)
    }

    /**
     * Wipe every knowledge-base file. Deletes only `filesDir/kb` — API keys,
     * whitelist and every other SharedPreferences value are untouched.
     */
    fun clearAll() = synchronized(lock) {
        notesCache = null
        contactsCache = null
        logCache.clear()
        runCatching { root.deleteRecursively() }
        Log.i(TAG, "kb cleared")
        Unit
    }

    // ------------------------------------------------------------------ io

    private fun loadNotes(): MutableList<Note> {
        notesCache?.let { return it }
        val list = ArrayList<Note>()
        val loaded = readJsonArray(notesFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Note(
                    id = o.optString("id").ifBlank { newId() },
                    title = o.optString("title"),
                    content = o.optString("content"),
                    tags = strList(o.optJSONArray("tags")),
                    alwaysOn = o.optBoolean("alwaysOn", false),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) notesCache = list
        return list
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        val loaded = readJsonArray(contactsFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Contact(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name"),
                    aliases = strList(o.optJSONArray("aliases")),
                    apps = strList(o.optJSONArray("apps")),
                    relationship = o.optString("relationship"),
                    notes = o.optString("notes"),
                    autoSummary = o.optString("autoSummary"),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) contactsCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        val loaded = readJsonArray(logFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(LogEntry(
                    side = o.optString("side", "other"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0L),
                    app = o.optString("app")
                ))
            }
        }
        if (loaded.trustworthy) logCache[contactId] = list
        return list
    }

    private fun notesJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("alwaysOn", n.alwaysOn)
                .put("enabled", n.enabled)
                .put("updatedAt", n.updatedAt))
        }
        return arr.toString()
    }

    private fun contactsJson(list: List<Contact>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("aliases", JSONArray(c.aliases))
                .put("apps", JSONArray(c.apps))
                .put("relationship", c.relationship)
                .put("notes", c.notes)
                .put("autoSummary", c.autoSummary)
                .put("updatedAt", c.updatedAt))
        }
        return arr.toString()
    }

    private fun logJson(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("side", e.side)
                .put("text", e.text)
                .put("ts", e.ts)
                .put("app", e.app))
        }
        return arr.toString()
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private class Loaded(val arr: JSONArray?, val trustworthy: Boolean)

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private val unreadable = HashSet<String>()

    private fun readJsonArray(f: File): Loaded {
        if (!f.exists()) { unreadable.remove(f.absolutePath); return Loaded(null, true) }
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            unreadable.remove(f.absolutePath)
            Loaded(arr, true)
        } catch (e: Exception) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            val backup = File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}")
            val kept = runCatching { f.renameTo(backup) }.getOrDefault(false)
            if (kept) unreadable.remove(f.absolutePath) else unreadable.add(f.absolutePath)
            Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName} preserved=$kept")
            Loaded(null, kept)
        }
    }

    /**
     * Temp file + rename, so a crash never leaves a half-written document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. Returns false when the data did not reach disk; callers
     * drop their cache so the next read goes back to the file.
     */
    private fun writeAtomic(f: File, text: String): Boolean {
        if (f.absolutePath in unreadable) {
            Log.w(TAG, "refusing to overwrite unparsable ${f.name}")
            return false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(f)) return true
            // Same-directory rename should not fail. If it somehow does, an
            // in-place overwrite is the only way left — not atomic, so say so.
            Log.w(TAG, "rename failed, overwriting ${f.name} in place")
            f.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun strList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun key(side: String, text: String) = side + " " + text

    companion object {
        private const val TAG = "JEVASSIST"
        const val MAX_LOG = 300

        /** Shorter lines are too common to dedupe on. Matches ContextBuilder. */
        const val DEDUPE_MIN_LEN = 4

        /** Tail of the history compared against an incoming screen. */
        private const val MIN_DEDUPE_WINDOW = 30

        @Volatile private var instance: KbStore? = null

        fun get(context: Context): KbStore =
            instance ?: synchronized(this) {
                instance ?: KbStore(context).also { instance = it }
            }

        fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 12)

        private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
        private val TRAILING_COUNT = Regex("[(（]\\s*\\d+\\s*[)）]\\s*$")

        /**
         * Name key for matching: trimmed, zero-width characters removed, the
         * group member count `(12)` / `（12）` dropped, case-insensitive.
         */
        fun normalizeName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            var t = ZERO_WIDTH.replace(s, "").trim()
            t = TRAILING_COUNT.replace(t, "").trim()
            return t.lowercase()
        }

        /** Same cleanup as [normalizeName] but keeps the original casing, for display. */
        fun displayName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            var t = ZERO_WIDTH.replace(s, "").trim()
            t = TRAILING_COUNT.replace(t, "").trim()
            return t
        }

        /** Loose key for substring matching (no member-count stripping). */
        fun normalizeText(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return ZERO_WIDTH.replace(s, "").trim().lowercase()
        }
    }
}
