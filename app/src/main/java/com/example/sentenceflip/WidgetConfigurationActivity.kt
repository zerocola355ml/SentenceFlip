package com.example.sentenceflip

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 홈 화면의 위젯 목록 → SentenceFlip을 선택할 때 폴더를 고르는 화면. */
class WidgetConfigurationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repository = SentenceRepository(this)
        val folders = repository.folders().sortedByDescending { it.createdAt }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("위젯에 표시할 폴더 선택",
                            style = MaterialTheme.typography.headlineSmall)
                        Text("추가할 SentenceFlip 위젯에서 학습할 문장을 선택하세요.")
                        folders.forEach { folder ->
                            OutlinedButton(
                                onClick = { selectFolder(widgetId, folder.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                    Text(folder.title)
                                    Text("${folder.groups.size}개 그룹",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("취소") }
                    }
                }
            }
        }
    }

    private fun selectFolder(widgetId: Int, folderId: String) {
        if (!SentenceRepository(this).assignWidgetFolder(widgetId, folderId)) return
        // 구성 화면이 있으면 최초 onUpdate가 생략될 수 있으므로 직접 갱신.
        sendBroadcast(Intent(this, SentenceWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        })
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }
}
