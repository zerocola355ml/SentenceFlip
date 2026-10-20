package com.example.sentenceflip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SentenceFolder(
    val id: String,
    val title: String,
    val createdAt: Long,
    val groups: List<List<String>>,
    val syncEnabled: Boolean = true
)

class SentenceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("sentence_state", Context.MODE_PRIVATE)

    companion object {
        private const val FOLDERS_KEY = "folders_json"
        private const val ACTIVE_KEY = "active_folder_id"

        private val DEFAULT_GROUPS = listOf(
            listOf("그렇게 오래 걸릴 줄은 몰랐어.", "I didn't expect it to take so long.", "expect + 목적어 + to부정사"),
            listOf("이제 막 나가려던 참이었어.", "I was just about to leave.")
        )

        fun parseGroups(input: String): List<List<String>>? {
            val normalized = input.replace("\r\n", "\n").trim()
            if (normalized.isBlank()) return null
            val groups = normalized.split(Regex("\n[ \\t]*\n+"))
                .map { block -> block.lines().map { it.trim() }.filter { it.isNotEmpty() } }
            return groups.takeIf { it.isNotEmpty() && it.all { group -> group.isNotEmpty() } }
        }

        fun timestampTitle(): String = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.KOREA
        ).format(Date())
    }

    // 기존 앱에서 저장한 groups_text는 첫 실행 시 '기존 문장 (이관)' 폴더로 보존.
    fun folders(): List<SentenceFolder> {
        val json = prefs.getString(FOLDERS_KEY, null)
        if (json == null) {
            val oldText = prefs.getString("groups_text", null)
            val initial = SentenceFolder(
                id = UUID.randomUUID().toString(),
                title = if (oldText == null) "기본 문장" else "기존 문장 (이관)",
                createdAt = System.currentTimeMillis(),
                groups = oldText?.let { parseGroups(it) } ?: DEFAULT_GROUPS
            )
            writeFolders(listOf(initial))
            prefs.edit().putString(ACTIVE_KEY, initial.id).apply()
            return listOf(initial)
        }
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val groupsArray = obj.getJSONArray("groups")
                val groups = (0 until groupsArray.length()).map { g ->
                    val items = groupsArray.getJSONArray(g)
                    (0 until items.length()).map { j -> items.getString(j) }
                }
                SentenceFolder(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    createdAt = obj.getLong("createdAt"),
                    groups = groups,
                    syncEnabled = obj.optBoolean("syncEnabled", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun activeFolder(): SentenceFolder? {
        val all = folders()
        val id = prefs.getString(ACTIVE_KEY, null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun activeGroups(): List<List<String>> = activeFolder()?.groups ?: DEFAULT_GROUPS

    // null = 기본 폴더를 따라가는 위젯. 지정한 폴더는 이름이 아니라 고유 ID로 저장.
    fun widgetAssignment(widgetId: Int): String? =
        prefs.getString("widget_folder_$widgetId", null)

    fun folderForWidget(widgetId: Int): SentenceFolder? {
        val all = folders()
        val assignedId = widgetAssignment(widgetId)
        return all.firstOrNull { it.id == assignedId }
            ?: activeFolder()
    }

    // 진도는 폴더 설정에 따라 폴더 단위 또는 위젯 단위로 저장한다.
    // 반환값: (그룹 인덱스, 그룹 내 항목 인덱스)
    fun position(folder: SentenceFolder, widgetId: Int): Pair<Int, Int> {
        if (folder.groups.isEmpty()) return 0 to 0
        val indexKey = if (folder.syncEnabled) "sync_index_${folder.id}"
        else "index_$widgetId"
        val phaseKey = if (folder.syncEnabled) "sync_phase_${folder.id}"
        else "phase_$widgetId"
        val index = prefs.getInt(indexKey, 0).coerceIn(0, folder.groups.lastIndex)
        val phase = prefs.getInt(phaseKey, 0)
            .coerceIn(0, folder.groups[index].lastIndex)
        return index to phase
    }

    fun savePosition(folder: SentenceFolder, widgetId: Int, index: Int, phase: Int) {
        if (folder.groups.isEmpty()) return
        val safeIndex = index.coerceIn(0, folder.groups.lastIndex)
        val safePhase = phase.coerceIn(0, folder.groups[safeIndex].lastIndex)
        val indexKey = if (folder.syncEnabled) "sync_index_${folder.id}"
        else "index_$widgetId"
        val phaseKey = if (folder.syncEnabled) "sync_phase_${folder.id}"
        else "phase_$widgetId"
        prefs.edit()
            .putInt(indexKey, safeIndex)
            .putInt(phaseKey, safePhase)
            .apply()
    }

    fun assignWidgetFolder(widgetId: Int, folderId: String?): Boolean {
        if (folderId != null && folders().none { it.id == folderId }) return false
        val editor = prefs.edit()
        if (folderId == null) editor.remove("widget_folder_$widgetId")
        else editor.putString("widget_folder_$widgetId", folderId)
        editor.putInt("index_$widgetId", 0)
            .putInt("phase_$widgetId", 0)
            .apply()
        return true
    }

    fun forgetWidget(widgetId: Int) {
        prefs.edit()
            .remove("widget_folder_$widgetId")
            .remove("index_$widgetId")
            .remove("phase_$widgetId")
            .apply()
    }

    fun addFolder(groups: List<List<String>>): SentenceFolder {
        val folder = SentenceFolder(
            id = UUID.randomUUID().toString(),
            title = timestampTitle(),
            createdAt = System.currentTimeMillis(),
            groups = groups
        )
        writeFolders(listOf(folder) + folders())
        prefs.edit().putString(ACTIVE_KEY, folder.id).apply()
        return folder
    }

    fun activateFolder(id: String): Boolean {
        if (folders().none { it.id == id }) return false
        prefs.edit().putString(ACTIVE_KEY, id).apply()
        return true
    }

    // 폴더 변경에 따라 위젯의 학습 위치를 일관되게 이전한다.
    // 위젯 ID는 화면에서 실제 등록된 목록을 전달한다.
    fun updateFolder(updated: SentenceFolder, widgetIds: Collection<Int> = emptyList()) {
        val all = folders()
        val before = all.firstOrNull { it.id == updated.id } ?: return
        val affected = widgetIds.filter { folderForWidget(it)?.id == updated.id }
        val editor = prefs.edit()

        when {
            before.groups != updated.groups -> {
                // 그룹을 편집하면 모든 관련 위젯을 첫 항목으로 초기화.
                editor.putInt("sync_index_${updated.id}", 0)
                    .putInt("sync_phase_${updated.id}", 0)
                affected.forEach { id ->
                    editor.putInt("index_$id", 0).putInt("phase_$id", 0)
                }
            }
            !before.syncEnabled && updated.syncEnabled -> {
                // 독립 → 동기화: 모든 위젯을 첫 문제로 통일.
                editor.putInt("sync_index_${updated.id}", 0)
                    .putInt("sync_phase_${updated.id}", 0)
            }
            before.syncEnabled && !updated.syncEnabled -> {
                // 동기화 → 독립: 공유된 마지막 위치에서 각자 이어서 시작.
                val index = prefs.getInt("sync_index_${updated.id}", 0)
                    .coerceIn(0, updated.groups.lastIndex)
                val phase = prefs.getInt("sync_phase_${updated.id}", 0)
                    .coerceIn(0, updated.groups[index].lastIndex)
                affected.forEach { id ->
                    editor.putInt("index_$id", index).putInt("phase_$id", phase)
                }
            }
        }
        editor.apply()
        writeFolders(all.map { if (it.id == updated.id) updated else it })
    }

    // 마지막 폴더는 보존. 활성 폴더를 삭제하면 가장 최근 폴더로 전환.
    fun deleteFolder(id: String): Boolean {
        val all = folders()
        if (all.size <= 1 || all.none { it.id == id }) return false

        val remaining = all.filterNot { it.id == id }
        val activeId = prefs.getString(ACTIVE_KEY, null)

        writeFolders(remaining)

        // 삭제한 폴더에 고정된 위젯은 기본 폴더를 따라가도록 전환.
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("widget_folder_") && value == id) {
                editor.remove(key)
            }
        }
        editor.remove("sync_index_$id")
            .remove("sync_phase_$id")
        editor.apply()

        if (activeId == id) {
            val newest = remaining.maxByOrNull { it.createdAt }!!
            prefs.edit().putString(ACTIVE_KEY, newest.id).apply()
        }
        return true
    }

    private fun writeFolders(folders: List<SentenceFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            val groupArray = JSONArray()
            folder.groups.forEach { group ->
                val itemArray = JSONArray()
                group.forEach { itemArray.put(it) }
                groupArray.put(itemArray)
            }
            array.put(JSONObject().apply {
                put("id", folder.id)
                put("title", folder.title)
                put("createdAt", folder.createdAt)
                put("groups", groupArray)
                put("syncEnabled", folder.syncEnabled)
            })
        }
        prefs.edit().putString(FOLDERS_KEY, array.toString()).apply()
    }
}
