package com.example.sentenceflip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SentenceFlip 전용 JSON 백업/복원.
 * 앱의 기존 sentence_state 저장 구조는 바꾸지 않습니다.
 */
class SentenceBackupManager(context: Context) {
    private val prefs = context.getSharedPreferences("sentence_state", Context.MODE_PRIVATE)
    private val repository = SentenceRepository(context)

    data class BackupInfo(val folders: Int, val groups: Int, val items: Int, val widgets: Int)

    private data class WidgetState(
        val id: Int,
        val folderId: String?,
        val index: Int,
        val phase: Int
    )

    private data class ParsedBackup(
        val folders: JSONArray,
        val folderItems: List<SentenceFolder>,
        val activeId: String,
        val sharedProgress: Map<String, Pair<Int, Int>>,
        val widgets: List<WidgetState>,
        val installationId: String
    )

    fun exportJson(widgetIds: Collection<Int>): String {
        val existing = repository.folders() // 구버전 데이터의 자동 이관 포함
        require(existing.isNotEmpty()) { "백업할 폴더가 없습니다." }
        val foldersJson = JSONArray(prefs.getString("folders_json", "[]") ?: "[]")
        val installationId = prefs.getString("backup_installation_id", null)
            ?: UUID.randomUUID().toString().also { generated ->
                prefs.edit().putString("backup_installation_id", generated).apply()
            }
        val activeId = repository.activeFolder()?.id ?: existing.first().id

        val shared = JSONArray()
        existing.forEach { folder ->
            shared.put(JSONObject().apply {
                put("folderId", folder.id)
                put("index", prefs.getInt("sync_index_${folder.id}", 0))
                put("phase", prefs.getInt("sync_phase_${folder.id}", 0))
            })
        }

        val widgets = JSONArray()
        widgetIds.distinct().forEach { id ->
            val folderId = repository.widgetAssignment(id)
            widgets.put(JSONObject().apply {
                put("id", id)
                put("folderId", folderId ?: JSONObject.NULL)
                put("index", prefs.getInt("index_$id", 0))
                put("phase", prefs.getInt("phase_$id", 0))
            })
        }

        val json = JSONObject().apply {
            put("format", "SentenceFlipBackup")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("installationId", installationId)
            put("folders", foldersJson)
            put("activeFolderId", activeId)
            put("sharedProgress", shared)
            put("widgets", widgets)
        }
        // 파일 생성 전에 백업이 다시 읽히는지 검증.
        parse(json.toString())
        return json.toString(2)
    }

    fun preview(jsonText: String): BackupInfo {
        val backup = parse(jsonText)
        return BackupInfo(
            folders = backup.folderItems.size,
            groups = backup.folderItems.sumOf { it.groups.size },
            items = backup.folderItems.sumOf { folder -> folder.groups.sumOf { it.size } },
            widgets = backup.widgets.size
        )
    }

    /**
     * 모든 JSON 구조를 검사한 뒤에만 한 번의 SharedPreferences 커밋으로 복원.
     * 위젯 ID는 기기별 값이므로 현재 설치된 ID에 대해서만 연결 정보를 적용합니다.
     * 반환값: 실제로 연결 정보가 복원된 위젯 수.
     */
    fun restore(jsonText: String, currentWidgetIds: Collection<Int>): Int {
        val backup = parse(jsonText) // 실패하면 기존 데이터 변경 없음
        val sameInstallation = backup.installationId ==
                prefs.getString("backup_installation_id", null)
        val current = if (sameInstallation) currentWidgetIds.toSet() else emptySet()
        val validFolderIds = backup.folderItems.map { it.id }.toSet()

        val editor = prefs.edit()
        // 기존 위치·연결 정보 제거 (예전 앱 버전의 키도 정리).
        prefs.all.keys.filter { key ->
            key.startsWith("widget_folder_") ||
                    key.startsWith("index_") || key.startsWith("phase_") ||
                    key.startsWith("sync_index_") || key.startsWith("sync_phase_")
        }.forEach { editor.remove(it) }

        editor.putString("folders_json", backup.folders.toString())
            .putString("active_folder_id", backup.activeId)

        backup.sharedProgress.forEach { (folderId, position) ->
            editor.putInt("sync_index_$folderId", position.first)
                .putInt("sync_phase_$folderId", position.second)
        }

        var count = 0
        backup.widgets.forEach { widget ->
            if (widget.id in current) {
                if (widget.folderId != null && widget.folderId in validFolderIds) {
                    editor.putString("widget_folder_${widget.id}", widget.folderId)
                }
                editor.putInt("index_${widget.id}", widget.index)
                    .putInt("phase_${widget.id}", widget.phase)
                count++
            }
        }

        check(editor.commit()) { "데이터 저장에 실패했습니다." }
        return count
    }

    private fun parse(text: String): ParsedBackup {
        val obj = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (_: Exception) {
            throw IllegalArgumentException("올바른 JSON 파일이 아닙니다.")
        }
        require(obj.optString("format") == "SentenceFlipBackup") {
            "SentenceFlip 백업 파일이 아닙니다."
        }
        require(obj.optInt("version", -1) == 1) {
            "지원하지 않는 백업 파일 버전입니다."
        }

        try {
            val folderArray = obj.getJSONArray("folders")
            require(folderArray.length() > 0) { "백업에 폴더가 없습니다." }
            val folders = (0 until folderArray.length()).map { i ->
                val f = folderArray.getJSONObject(i)
                val id = f.getString("id")
                val title = f.getString("title")
                require(id.isNotBlank() && title.isNotBlank()) { "폴더 이름 또는 ID가 비어 있습니다." }
                val groupsJson = f.getJSONArray("groups")
                require(groupsJson.length() > 0) { "폴더에 그룹이 없습니다." }
                val groups = (0 until groupsJson.length()).map { j ->
                    val group = groupsJson.getJSONArray(j)
                    require(group.length() > 0) { "빈 그룹은 복원할 수 없습니다." }
                    (0 until group.length()).map { k ->
                        group.getString(k).also { item ->
                            require(item.isNotBlank()) { "빈 문장이 있습니다." }
                        }
                    }
                }
                SentenceFolder(
                    id = id,
                    title = title,
                    createdAt = f.getLong("createdAt"),
                    groups = groups,
                    syncEnabled = f.getBoolean("syncEnabled")
                )
            }
            val byId = folders.associateBy { it.id }
            require(byId.size == folders.size) { "폴더 ID가 중복됩니다." }
            val activeId = obj.getString("activeFolderId")
            require(activeId in byId) { "기본 폴더 정보가 잘못되었습니다." }

            val sharedPositions = mutableMapOf<String, Pair<Int, Int>>()
            val progress = obj.getJSONArray("sharedProgress")
            for (i in 0 until progress.length()) {
                val p = progress.getJSONObject(i)
                val folderId = p.getString("folderId")
                require(folderId in byId) { "존재하지 않는 폴더의 학습 위치입니다." }
                val index = p.getInt("index")
                val phase = p.getInt("phase")
                require(index >= 0 && phase >= 0) { "학습 위치가 잘못되었습니다." }
                require(folderId !in sharedPositions) { "동기화 위치가 중복됩니다." }
                sharedPositions[folderId] = index to phase
            }

            val widgetList = mutableListOf<WidgetState>()
            val widgets = obj.getJSONArray("widgets")
            val seenIds = mutableSetOf<Int>()
            for (i in 0 until widgets.length()) {
                val w = widgets.getJSONObject(i)
                val id = w.getInt("id")
                require(id >= 0 && seenIds.add(id)) { "위젯 ID가 잘못되었거나 중복됩니다." }
                val folderId = if (w.isNull("folderId")) null else w.getString("folderId")
                require(folderId == null || folderId in byId) { "위젯 폴더 연결이 잘못되었습니다." }
                val index = w.getInt("index")
                val phase = w.getInt("phase")
                require(index >= 0 && phase >= 0) { "위젯 학습 위치가 잘못되었습니다." }
                widgetList.add(WidgetState(id, folderId, index, phase))
            }
            val installationId = obj.getString("installationId")
            require(installationId.isNotBlank()) { "백업 설치 식별자가 비어 있습니다." }
            return ParsedBackup(folderArray, folders, activeId, sharedPositions, widgetList, installationId)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("백업 파일의 폴더나 학습 정보가 올바르지 않습니다.")
        }
    }
}
