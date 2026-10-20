package com.example.sentenceflip

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.widget.RemoteViews

class SentenceWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PIN_SUCCESS = "com.example.sentenceflip.PIN_SUCCESS"
        const val EXTRA_PIN_FOLDER_ID = "pin_folder_id"

        private const val PREV = "com.example.sentenceflip.PREV"
        private const val NEXT = "com.example.sentenceflip.NEXT"
        private const val FLIP = "com.example.sentenceflip.FLIP"
        private const val MIN_TEXT_SP = 12
        private const val MAX_TEXT_SP = 22

        // 구버전 MainActivity 등에서 참조할 수 있으므로 호환 유지.
        val DEFAULT_CONTENT = """
            그렇게 오래 걸릴 줄은 몰랐어.
            I didn't expect it to take so long.
            expect + 목적어 + to부정사

            이제 막 나가려던 참이었어.
            I was just about to leave.
        """.trimIndent()

        fun parseGroups(input: String): List<List<String>>? =
            SentenceRepository.parseGroups(input)
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(
            context, appWidgetManager, appWidgetId, newOptions
        )
        updateWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        // 앱에서 '위젯 추가'로 생성한 위젯은 성공 콜백을 받은 뒤 지정 폴더로 연결.
        if (action == ACTION_PIN_SUCCESS) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val folderId = intent.getStringExtra(EXTRA_PIN_FOLDER_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && folderId != null) {
                val success = SentenceRepository(context).assignWidgetFolder(id, folderId)
                if (success) {
                    // 추가 순서에 관계없이 해당 위젯을 즉시 갱신.
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, id)
                }
            }
            return
        }
        if (action != PREV && action != NEXT && action != FLIP) return

        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val repo = SentenceRepository(context)
        val folder = repo.folderForWidget(id) ?: return
        val groups = folder.groups.takeIf { it.isNotEmpty() } ?: return
        var (index, phase) = repo.position(folder, id)

        when (action) {
            PREV -> {
                index = (index - 1 + groups.size) % groups.size
                phase = 0
            }
            NEXT -> {
                index = (index + 1) % groups.size
                phase = 0
            }
            FLIP -> phase = (phase + 1) % groups[index].size
        }

        repo.savePosition(folder, id, index, phase)
        val manager = AppWidgetManager.getInstance(context)
        if (folder.syncEnabled) {
            // 같은 폴더를 사용하는 홈/잠금화면 위젯 모두 갱신.
            val allIds = manager.getAppWidgetIds(
                android.content.ComponentName(context, SentenceWidgetProvider::class.java)
            )
            allIds.filter { repo.folderForWidget(it)?.id == folder.id }
                .forEach { updateWidget(context, manager, it) }
        } else {
            updateWidget(context, manager, id)
        }
    }

    private fun calculateTextSize(
        context: Context,
        text: String,
        widthDp: Int,
        heightDp: Int
    ): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val widthPx = ((widthDp - 76).coerceAtLeast(30) * density)
            .toInt().coerceAtLeast(1)
        val heightPx = ((heightDp - 10).coerceAtLeast(12) * density)
            .toInt().coerceAtLeast(1)

        for (sp in MAX_TEXT_SP downTo MIN_TEXT_SP) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sp * scaledDensity
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val layout = StaticLayout.Builder.obtain(
                text, 0, text.length, paint, widthPx
            ).setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
            val maxLines = (heightPx / paint.fontSpacing).toInt().coerceAtLeast(1)
            if (layout.height <= heightPx && layout.lineCount <= maxLines) {
                return sp to maxLines
            }
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = MIN_TEXT_SP * scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        return MIN_TEXT_SP to (heightPx / paint.fontSpacing).toInt().coerceAtLeast(1)
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        optionsOverride: Bundle? = null
    ) {
        val repo = SentenceRepository(context)
        val folder = repo.folderForWidget(id) ?: return
        val groups = folder.groups.takeIf { it.isNotEmpty() } ?: return
        val (index, phase) = repo.position(folder, id)
        val text = groups[index][phase]
        val displayText = text

        val options = optionsOverride ?: manager.getAppWidgetOptions(id)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            .takeIf { it > 0 } ?: 200
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
            .takeIf { it > 0 } ?: 48
        val (textSize, maxLines) = calculateTextSize(
            context, displayText, widthDp, heightDp
        )

        val views = RemoteViews(context.packageName, R.layout.widget_sentence)
        views.setTextViewText(R.id.sentenceText, displayText)
        views.setTextViewTextSize(
            R.id.sentenceText, TypedValue.COMPLEX_UNIT_SP, textSize.toFloat()
        )
        views.setInt(R.id.sentenceText, "setMaxLines", maxLines)
        views.setOnClickPendingIntent(
            R.id.prevButton, clickIntent(context, id, PREV, 1)
        )
        views.setOnClickPendingIntent(
            R.id.sentenceText, clickIntent(context, id, FLIP, 2)
        )
        views.setOnClickPendingIntent(
            R.id.nextButton, clickIntent(context, id, NEXT, 3)
        )
        manager.updateAppWidget(id, views)
    }

    private fun clickIntent(
        context: Context,
        id: Int,
        action: String,
        button: Int
    ): PendingIntent {
        val intent = Intent(context, SentenceWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id * 10 + button,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val repo = SentenceRepository(context)
        appWidgetIds.forEach { repo.forgetWidget(it) }
        super.onDeleted(context, appWidgetIds)
    }
}
