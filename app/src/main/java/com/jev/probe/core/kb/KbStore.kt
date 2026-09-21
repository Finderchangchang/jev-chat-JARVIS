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

    /** Insert or replace by id. */
    fun saveNote(note: Note) = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        writeAtomic(notesFile, notesJson(list))
    }

    fun deleteNote(id: String) = synchronized(lock) {
        val list = loadNotes()
        if (list.removeAll { it.id == id }) writeAtomic(notesFile, notesJson(list))
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun saveContact(c: Contact) = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        val stamped = c.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        writeAtomic(contactsFile, contactsJson(list))
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String) = synchronized(lock) {
        val list = loadContacts()
        if (list.removeAll { it.id == id }) writeAtomic(contactsFile, contactsJson(list))
        logCache.remove(id)
        runCatching { logFile(id).delete() }
        Unit
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
     * Append entries not already present, keeping only the newest [MAX_LOG].
     * Dedupe key is (side, text), so re-reading the same screen over and over
     * does not grow the file.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val list = loadLog(contactId)
            val seen = HashSet<String>(list.size * 2)
            list.forEach { seen.add(key(it.side, it.text)) }
            var added = 0
            for (e in entries) {
                if (e.text.isBlank()) continue
                if (seen.add(key(e.side, e.text))) { list.add(e); added++ }
            }
            if (added == 0) return
            while (list.size > MAX_LOG) list.removeAt(0)
            writeAtomic(logFile(contactId), logJson(list))
            Log.d(TAG, "appendLog contact=$contactId added=$added total=${list.size}")
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
        readJsonArray(notesFile)?.let { arr ->
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
        notesCache = list
        return list
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        readJsonArray(contactsFile)?.let { arr ->
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
        contactsCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        readJsonArray(logFile(contactId))?.let { arr ->
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
        logCache[contactId] = list
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

    private fun readJsonArray(f: File): JSONArray? = try {
        if (!f.exists()) null else JSONArray(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName}")
        null
    }

    /** Temp file + rename, so a crash never leaves a half-written document. */
    private fun writeAtomic(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
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
