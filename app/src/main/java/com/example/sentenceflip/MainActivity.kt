package com.example.sentenceflip

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private fun widgetIds(): List<Int> {
        val manager = AppWidgetManager.getInstance(this)
        return manager.getAppWidgetIds(
            ComponentName(this, SentenceWidgetProvider::class.java)
        ).sorted()
    }

    // 전체 위젯의 화면은 갱신하지만, 지정한 위젯만 학습 위치를 초기화한다.
    private fun refreshWidgets(resetIds: Collection<Int> = emptyList()) {
        val ids = widgetIds().toIntArray()
        if (resetIds.isNotEmpty()) {
            val editor = getSharedPreferences("sentence_state", MODE_PRIVATE).edit()
            resetIds.forEach { id ->
                editor.putInt("index_$id", 0)
                editor.putInt("phase_$id", 0)
            }
            editor.apply()
        }
        sendBroadcast(Intent(this, SentenceWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        })
    }

    // 폴더에서 직접 홈 화면 위젯 추가 요청. 승인이 완료되면 콜백에서 폴더 ID를 연결.
    private fun requestPinFolder(folderId: String): Boolean {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) return false

        // 연속 요청 시 PendingIntent가 서로 덮어써지지 않도록 요청마다 고유 data URI 사용.
        val callback = Intent(this, SentenceWidgetProvider::class.java).apply {
            action = SentenceWidgetProvider.ACTION_PIN_SUCCESS
            data = Uri.parse("sentenceflip://pin/${UUID.randomUUID()}")
            putExtra(SentenceWidgetProvider.EXTRA_PIN_FOLDER_ID, folderId)
        }
        val pending = PendingIntent.getBroadcast(
            this, 0, callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return manager.requestPinAppWidget(
            ComponentName(this, SentenceWidgetProvider::class.java),
            null,
            pending
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SentenceRepository(this)
        val backupManager = SentenceBackupManager(this)

        setContent {
            var folders by remember { mutableStateOf(repository.folders()) }
            var activeId by remember { mutableStateOf(repository.activeFolder()?.id) }
            var page by remember { mutableStateOf("home") }
            var importText by remember { mutableStateOf("") }
            var selectedId by remember { mutableStateOf("") }
            var editingTitle by remember { mutableStateOf("") }
            var editingSync by remember { mutableStateOf(true) }
            var editingGroups by remember { mutableStateOf<List<List<String>>>(emptyList()) }
            var message by remember { mutableStateOf("") }
            var deleteTarget by remember { mutableStateOf<SentenceFolder?>(null) }
            var pendingRestoreJson by remember { mutableStateOf<String?>(null) }
            var restorePreview by remember { mutableStateOf<SentenceBackupManager.BackupInfo?>(null) }

            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    try {
                        val data = backupManager.exportJson(widgetIds())
                        val output = contentResolver.openOutputStream(uri)
                            ?: error("저장할 파일을 열 수 없습니다.")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                        message = "백업 파일을 저장했어요."
                    } catch (e: Exception) {
                        message = "백업 실패: ${e.message ?: "파일 쓰기 오류"}"
                    }
                }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    try {
                        val input = contentResolver.openInputStream(uri)
                            ?: error("선택한 파일을 읽을 수 없습니다.")
                        val buffer = ByteArrayOutputStream()
                        input.use { stream ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val size = stream.read(chunk)
                                if (size < 0) break
                                if (size == 0) continue
                                if (buffer.size() + size > 8 * 1024 * 1024) {
                                    error("백업 파일이 너무 큽니다 (최대 8MB).")
                                }
                                buffer.write(chunk, 0, size)
                            }
                        }
                        val json = buffer.toString("UTF-8")
                        val summary = backupManager.preview(json)
                        pendingRestoreJson = json
                        restorePreview = summary
                        message = ""
                    } catch (e: Exception) {
                        pendingRestoreJson = null
                        restorePreview = null
                        message = "복원 파일 확인 실패: ${e.message ?: "읽기 오류"}"
                    }
                }
            }

            fun reload() {
                folders = repository.folders()
                activeId = repository.activeFolder()?.id
            }

            fun saveEditedFolder() {
                val cleaned = editingGroups.map { group -> group.map { it.trim() } }
                when {
                    editingTitle.isBlank() -> message = "폴더 이름을 입력하세요."
                    cleaned.isEmpty() -> message = "그룹을 하나 이상 추가하세요."
                    cleaned.any { it.isEmpty() || it.any(String::isBlank) } ->
                        message = "각 그룹에 빈칸 없이 최소 한 항목이 필요합니다."
                    else -> {
                        val original = folders.firstOrNull { it.id == selectedId }
                        if (original == null) {
                            message = "폴더를 찾을 수 없습니다."
                        } else {
                            val affected = widgetIds().filter { id ->
                                repository.folderForWidget(id)?.id == selectedId
                            }
                            repository.updateFolder(
                                original.copy(
                                    title = editingTitle.trim(),
                                    groups = cleaned,
                                    syncEnabled = editingSync
                                ),
                                widgetIds()
                            )
                            refreshWidgets()
                            reload()
                            page = "home"
                            message = "폴더 편집 내용이 저장되었어요."
                        }
                    }
                }
            }

            BackHandler(enabled = page != "home") { page = "home" }

            MaterialTheme {
                deleteTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text("폴더 삭제") },
                        text = {
                            Text(
                                if (target.id == activeId)
                                    "'${target.title}' 폴더를 삭제할까요? 이 폴더의 문장은 복구되지 않으며, 이 폴더를 쓰던 위젯은 기본 폴더로 전환됩니다."
                                else
                                    "'${target.title}' 폴더를 삭제할까요? 문장은 복구되지 않으며, 이 폴더를 고정한 위젯은 기본 폴더를 따라가게 됩니다."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val affected = widgetIds().filter { id ->
                                    repository.folderForWidget(id)?.id == target.id
                                }
                                if (repository.deleteFolder(target.id)) {
                                    refreshWidgets(affected)
                                    reload()
                                    message = "'${target.title}' 폴더를 삭제했어요."
                                } else {
                                    message = "마지막 폴더는 삭제할 수 없어요."
                                }
                                deleteTarget = null
                            }) {
                                Text("삭제", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) {
                                Text("취소")
                            }
                        }
                    )
                }

                if (pendingRestoreJson != null && restorePreview != null) {
                    val summary = restorePreview!!
                    AlertDialog(
                        onDismissRequest = {
                            pendingRestoreJson = null
                            restorePreview = null
                        },
                        title = { Text("백업 복원 확인") },
                        text = {
                            Text(
                                "폴더 ${summary.folders}개 · 그룹 ${summary.groups}개 · 항목 ${summary.items}개\n\n" +
                                        "현재 저장된 폴더와 문장이 모두 백업 내용으로 교체됩니다. " +
                                        "기존 데이터는 자동으로 합쳐지지 않아요.\n\n" +
                                        "현재 기기에서 ID가 일치하는 위젯만 연결 및 학습 위치가 복원됩니다."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val json = pendingRestoreJson!!
                                try {
                                    val restored = backupManager.restore(json, widgetIds())
                                    refreshWidgets()
                                    reload()
                                    page = "home"
                                    message = "복원 완료! ${summary.folders}개 폴더, 위젯 $restored 개 연결/위치 반영."
                                } catch (e: Exception) {
                                    message = "복원 실패: ${e.message ?: "저장 오류"}"
                                }
                                pendingRestoreJson = null
                                restorePreview = null
                            }) { Text("기존 데이터 교체", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingRestoreJson = null
                                restorePreview = null
                            }) { Text("취소") }
                        }
                    )
                }

                val scrollState = rememberScrollState()
                var jumpToNewGroup by remember { mutableStateOf(false) }

                LaunchedEffect(page, selectedId) {
                    scrollState.scrollTo(0)
                }
                LaunchedEffect(page, editingGroups.size) {
                    if (page == "edit" && jumpToNewGroup) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                        jumpToNewGroup = false
                    }
                }

                Scaffold(
                    bottomBar = {
                        if (page == "edit") {
                            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            editingGroups = editingGroups + listOf(listOf(""))
                                            jumpToNewGroup = true
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text("+ 그룹", maxLines = 1) }
                                    OutlinedButton(
                                        onClick = { page = "home" },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text("취소", maxLines = 1) }
                                    Button(
                                        onClick = { saveEditedFolder() },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) { Text("저장", maxLines = 1) }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (page) {
                            "home" -> {
                                Text("SentenceFlip", style = MaterialTheme.typography.headlineMedium)
                                Text("폴더를 누르면 문장을 편집할 수 있어요.")
                                Button(
                                    onClick = {
                                        importText = ""
                                        message = ""
                                        page = "import"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("+ 새 문장 일괄 등록") }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val fileName = "SentenceFlip_" +
                                                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                                                    ".json"
                                            exportLauncher.launch(fileName)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("JSON 백업") }
                                    OutlinedButton(
                                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("백업 복원") }
                                }
                                if (message.isNotBlank()) {
                                    Text(message, color = MaterialTheme.colorScheme.primary)
                                }
                                HorizontalDivider()
                                Text("저장된 폴더 (${folders.size})", style = MaterialTheme.typography.titleMedium)

                                folders.sortedByDescending { it.createdAt }.forEach { folder ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    selectedId = folder.id
                                                    editingTitle = folder.title
                                                    editingGroups = folder.groups.map { it.toList() }
                                                    editingSync = folder.syncEnabled
                                                    message = ""
                                                    page = "edit"
                                                }.padding(vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(folder.title, style = MaterialTheme.typography.titleSmall)
                                                Text("${folder.groups.size}개 그룹  ·  눌러서 편집",
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    enabled = folders.size > 1,
                                                    onClick = { deleteTarget = folder },
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("삭제") }
                                                Button(
                                                    onClick = {
                                                        val accepted = try {
                                                            requestPinFolder(folder.id)
                                                        } catch (_: IllegalStateException) {
                                                            false
                                                        }
                                                        message = if (accepted)
                                                            "위젯 추가를 요청했어요. 홈 화면의 확인창에서 추가해 주세요."
                                                        else
                                                            "현재 홈 화면에서는 앱 내부 위젯 추가가 지원되지 않아요. 홈 화면의 위젯 목록에서 추가해 주세요."
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("위젯 추가") }
                                            }
                                        }
                                    }
                                }
                            }

                            "import" -> {
                                Text("새 문장 일괄 등록", style = MaterialTheme.typography.headlineSmall)
                                Text("그룹은 빈 줄로, 그룹 안 항목은 줄바꿈으로 구분하세요. 그룹당 최소 한 줄이 필요합니다.")
                                OutlinedTextField(
                                    value = importText,
                                    onValueChange = { importText = it; message = "" },
                                    label = { Text("오늘의 영작 문장") },
                                    modifier = Modifier.fillMaxWidth().height(300.dp)
                                )
                                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                                Button(
                                    onClick = {
                                        val groups = SentenceRepository.parseGroups(importText)
                                        if (groups == null) {
                                            message = "형식을 확인하세요. 각 그룹에 최소 한 줄이 필요합니다."
                                        } else {
                                            val folder = repository.addFolder(groups)
                                            // 새 등록은 기본 폴더를 따라가는 위젯에만 적용.
                                            val followingDefault = widgetIds().filter { id ->
                                                repository.widgetAssignment(id) == null
                                            }
                                            refreshWidgets(followingDefault)
                                            reload()
                                            page = "home"
                                            message = "'${folder.title}' 폴더를 생성하고 적용했어요."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("새 폴더 생성하고 위젯에 적용") }
                                OutlinedButton(onClick = { page = "home" }, modifier = Modifier.fillMaxWidth()) {
                                    Text("취소")
                                }
                            }

                            "edit" -> {
                                Text("폴더 편집", style = MaterialTheme.typography.headlineSmall)
                                if (message.isNotBlank()) {
                                    Text(message, color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedTextField(
                                    value = editingTitle,
                                    onValueChange = { editingTitle = it },
                                    label = { Text("폴더 이름") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("위젯 학습 위치 동기화",
                                                style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                if (editingSync)
                                                    "같은 폴더의 모든 위젯이 같은 문장과 확인 단계를 표시합니다."
                                                else
                                                    "같은 폴더여도 각 위젯이 독립적으로 진행합니다.",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Switch(
                                            checked = editingSync,
                                            onCheckedChange = { editingSync = it }
                                        )
                                    }
                                }
                                Text("각 그룹에는 항목을 1개 이상 입력할 수 있습니다.")

                                editingGroups.forEachIndexed { groupIndex, group ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("그룹 ${groupIndex + 1}", style = MaterialTheme.typography.titleMedium)
                                            group.forEachIndexed { itemIndex, item ->
                                                OutlinedTextField(
                                                    value = item,
                                                    onValueChange = { newText ->
                                                        editingGroups = editingGroups.mapIndexed { gi, oldGroup ->
                                                            if (gi != groupIndex) oldGroup
                                                            else oldGroup.mapIndexed { ii, oldItem ->
                                                                if (ii == itemIndex) newText else oldItem
                                                            }
                                                        }
                                                    },
                                                    label = {
                                                        Text(when (itemIndex) {
                                                            0 -> "한국어 문제"
                                                            1 -> "영어 모범 답안"
                                                            else -> "주요 표현 ${itemIndex - 1}"
                                                        })
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    minLines = 1,
                                                    maxLines = 3
                                                )
                                                TextButton(onClick = {
                                                    editingGroups = editingGroups.mapIndexed { gi, oldGroup ->
                                                        if (gi == groupIndex) oldGroup.filterIndexed { ii, _ -> ii != itemIndex }
                                                        else oldGroup
                                                    }
                                                }) { Text("이 항목 삭제") }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        editingGroups = editingGroups.mapIndexed { gi, oldGroup ->
                                                            if (gi == groupIndex) oldGroup + "" else oldGroup
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("+ 항목") }
                                                OutlinedButton(
                                                    onClick = {
                                                        editingGroups = editingGroups.filterIndexed { gi, _ -> gi != groupIndex }
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("그룹 삭제") }
                                            }
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
            }
        }
    }
}
