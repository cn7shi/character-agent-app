package com.character.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D27: the host runs each checkpoint in a fresh process, with real UI, Room and Keystore. */
@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var config: JSONObject
    private lateinit var activity: ActivityScenario<MainActivity>
    private val draft = "  中文 草稿 ☕🙂\n第二行：保留换行和空格  "
    private val nextDraft = "下一条独立草稿 🌙\n还没有发送"
    private val offlineText = "断网期间的原请求"
    private var waitingForKill = false

    @Before fun launch() {
        check(context.packageName == "com.character.app.devicecheck")
        config = JSONObject(File(context.filesDir, "device-check.json").readText())
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() {
        if (waitingForKill) File(context.filesDir, "kill-clean-exit").writeText("test returned normally")
        if (::activity.isInitialized) activity.close()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 30_000) {
        ui.waitUntil(timeout) { ui.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    private fun click(text: String) {
        val matcher = hasText(text) and hasClickAction()
        waitFor(matcher and isEnabled())
        ui.onNode(matcher).performClick()
    }
    private fun editor() = ui.onNode(hasSetTextAction())
    private fun assertDraft(text: String) {
        waitFor(hasSetTextAction() and isEnabled())
        editor().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    }
    private fun saveDraft(text: String) {
        val previous = editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        editor().performTextReplacement(text)
        if (text.isNotEmpty()) waitFor(hasText("草稿已保存在此设备"))
        // The empty draft has no label. Check committed storage before the host kills the process.
        ui.waitUntil(10_000) { if (text.isEmpty()) !draftTexts().contains(previous) else draftTexts().contains(text) }
    }
    private fun draftTexts(): List<String> = SQLiteDatabase.openDatabase(
        context.getDatabasePath("character-client.db").path, null, SQLiteDatabase.OPEN_READONLY
    ).use { db ->
        db.rawQuery("SELECT text FROM drafts", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }
    private fun openChat() {
        waitFor(hasText("遇见角色"))
        // A lazy list may not compose the recent conversation until scrolled.
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("继续聊天  ›"))
        click("继续聊天  ›")
        waitFor(hasSetTextAction() and isEnabled())
    }
    private fun connect(token: String, existing: Boolean) {
        ui.onNode(hasSetTextAction() and hasText("服务地址")).performTextReplacement(config.getString("url"))
        ui.onNode(hasSetTextAction() and hasText("开发访问凭证")).performScrollTo().performTextReplacement(token)
        val label = if (existing) "切换连接" else "保存并连接"
        ui.onNode(hasText(label) and hasClickAction()).performScrollTo()
        click(label)
        waitFor(hasText("开发角色 · 林舟"))
    }
    private fun newChat() {
        waitFor(hasText("开启新对话") and isEnabled())
        ui.onAllNodes(hasText("开启新对话") and hasClickAction())[0].performClick()
        waitFor(hasSetTextAction() and isEnabled())
    }
    private fun send(text: String) {
        saveDraft(text)
        click("发送")
    }
    private fun completed() { waitFor(hasText("已完成")); waitFor(hasText("发送") and isEnabled().not()) }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        // Never capture settings: the password editor's semantics can contain a credential.
        check(ui.onAllNodes(hasText("开发访问凭证")).fetchSemanticsNodes().isEmpty())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun connectChatAndSaveDraft() {
        connect(config.getString("tokenA"), existing = false)
        activity.onActivity { assertFalse(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars) }
        val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertTrue(preferences.contains("iv") && preferences.contains("payload"))
        assertFalse(preferences.all.values.any { it.toString().contains(config.getString("tokenA")) })
        newChat()
        send("你好")
        waitFor(hasText("【模拟回复】你好，我在。今天想聊些什么？"))
        completed()
        saveDraft(draft)
        activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertDraft(draft)
        activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        activity.moveToState(Lifecycle.State.CREATED)
        activity.moveToState(Lifecycle.State.RESUMED)
        assertDraft(draft)
        click("‹ 返回")
        openChat()
        assertDraft(draft)
        screenshot("01-draft")
    }

    @Test fun restoreAndSendWithNewDraft() {
        // The host force-stopped the app. Reaching this chat also exercises Keystore decryption.
        openChat()
        assertDraft(draft)
        click("发送")
        saveDraft(nextDraft)
        waitFor(hasText("发送") and isEnabled())
        assertDraft(nextDraft)
        screenshot("02-next-draft")
    }

    @Test fun restoreNewDraftAndClear() {
        openChat()
        assertDraft(nextDraft)
        saveDraft("")
        assertDraft("")
    }

    @Test fun clearedDraftNoteThemeModeAndIdentity() {
        openChat()
        assertDraft("")
        send("记笔记：设备验收；真实界面保存；重启后查看")
        waitFor(hasText("【模拟回复】已保存笔记《设备验收》，可以在笔记列表查看。"))
        waitFor(hasText("已完成"))
        click("‹ 返回")
        click("笔记")
        waitFor(hasText("设备验收"))
        screenshot("03-note")
        click("设置")
        val switches = ui.onAllNodes(isToggleable())
        switches[0].performClick()
        ui.waitForIdle()
        assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("dark", true))
        activity.onActivity { assertTrue(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars) }
        switches[1].performClick()
        waitFor(hasText("遇见角色"))
        assertEquals("POLLING", context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("mode", null))
        openChat()
        assertDraft("")
        send("今天有点累")
        waitFor(hasText("【模拟回复】那就先慢下来吧。你可以说说今天发生的事，我会陪你聊一会儿。"))
        saveDraft("身份 A 的草稿")
        waitFor(hasText("发送") and isEnabled())
        screenshot("04-light-polling")
        click("‹ 返回")
        click("设置")
        connect(config.getString("tokenB"), existing = true)
        newChat()
        assertDraft("")
        saveDraft("身份 B 的草稿")
        click("‹ 返回")
        click("设置")
        connect(config.getString("tokenA"), existing = true)
        openChat()
        assertDraft("身份 A 的草稿")
        saveDraft("")
        click("‹ 返回")
        click("设置")
        ui.onAllNodes(isToggleable())[1].performClick()
        waitFor(hasText("遇见角色"))
        assertEquals("REALTIME", context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("mode", null))
    }

    @Test fun offlineCacheAndQueue() {
        // The host has stopped the actual server; no radio toggles or fake repository responses.
        openChat()
        waitFor(hasText("【模拟回复】已保存笔记《设备验收》，可以在笔记列表查看。"))
        saveDraft(offlineText)
        click("发送")
        waitFor(hasText("用原请求重试") and isEnabled(), 45_000)
        saveDraft(nextDraft)
        SQLiteDatabase.openDatabase(context.getDatabasePath("character-client.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, requestKey, conversationId FROM outbox WHERE text = ?", arrayOf(offlineText)).use { rows ->
                assertEquals(1, rows.count)
                assertTrue(rows.moveToFirst())
                File(context.filesDir, "offline-request.json").writeText(JSONObject()
                    .put("id", rows.getString(0)).put("requestKey", rows.getString(1)).put("conversationId", rows.getString(2)).toString())
            }
        }
        screenshot("05-offline-queue")
    }

    @Test fun recoverOriginalRequest() {
        openChat()
        assertDraft(nextDraft)
        waitFor(hasText("用原请求重试") and isEnabled())
        click("用原请求重试")
        waitFor(hasText(offlineText))
        ui.waitUntil(30_000) { ui.onAllNodes(hasText("用原请求重试")).fetchSemanticsNodes().isEmpty() }
        waitFor(hasText("【模拟回复】你好，我在。今天想聊些什么？"))
        waitFor(hasText("发送") and isEnabled())
        assertDraft(nextDraft)
        screenshot("06-recovered")
    }

    @Test fun holdForegroundForKill() {
        openChat()
        saveDraft("运行中强杀：已保存的草稿 🌙\n不会自动发送")
        assertEquals(Lifecycle.State.RESUMED, activity.state)
        screenshot("07-before-kill")
        waitingForKill = true
        // The host must kill a live test with a resumed Activity, before @After/VM disposal.
        File(context.filesDir, "kill-ready.json").writeText(JSONObject().put("pid", Process.myPid()).toString())
        Thread.sleep(60_000)
        fail("Host did not terminate the foreground process")
    }

    @Test fun recoverForegroundKill() {
        assertFalse(File(context.filesDir, "kill-clean-exit").exists())
        openChat()
        assertDraft("运行中强杀：已保存的草稿 🌙\n不会自动发送")
        screenshot("08-after-kill")
        saveDraft("")
    }

    @Test fun cancelPartialNoteThenContinue() {
        openChat()
        send("记笔记：设备暂停；取消后保留笔记")
        waitFor(hasText("停止") and isEnabled())
        // The fixture pauses only after a text chunk, following the committed note tool.
        waitFor(hasText("【模拟"))
        click("停止")
        waitFor(hasText("已停止"))
        saveDraft("今天有点累")
        waitFor(hasText("发送") and isEnabled())
        click("发送")
        saveDraft("取消之后的新草稿")
        waitFor(hasText("发送") and isEnabled())
        waitFor(hasText("【模拟回复】那就先慢下来吧。你可以说说今天发生的事，我会陪你聊一会儿。"))
        screenshot("09-cancel-and-continue")
        click("‹ 返回")
        click("笔记")
        waitFor(hasText("设备暂停"))
    }

    private fun rawState(): JSONObject = SQLiteDatabase.openDatabase(
        context.getDatabasePath("character-client.db").path, null, SQLiteDatabase.OPEN_READONLY
    ).use { db ->
        val state = JSONObject()
        for ((table, order) in listOf("snapshots" to "scope, kind, id", "outbox" to "scope, id", "conversation_requests" to "scope, characterId")) {
            val items = JSONArray()
            db.rawQuery("SELECT * FROM $table ORDER BY $order", null).use { rows ->
                while (rows.moveToNext()) {
                    val row = JSONObject()
                    for (column in 0 until rows.columnCount) row.put(rows.getColumnName(column),
                        if (rows.isNull(column)) JSONObject.NULL else rows.getString(column))
                    items.put(row)
                }
            }
            state.put(table, items)
        }
        state
    }

    private fun assertSchema(version: Int) {
        SQLiteDatabase.openDatabase(context.getDatabasePath("character-client.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(version, it.version) }
    }

    @Test fun prepareLegacyOnline() {
        connect(config.getString("tokenA"), existing = false)
        newChat()
        editor().performTextReplacement("你好")
        click("发送")
        waitFor(hasText("【模拟回复】你好，我在。今天想聊些什么？"))
        waitFor(hasText("已完成"))
        assertSchema(1)
    }

    @Test fun prepareLegacyOffline() {
        openChat()
        editor().performTextReplacement("旧版待发送消息")
        click("发送")
        waitFor(hasText("用原请求重试") and isEnabled(), 45_000)
        assertSchema(1)
        val before = rawState()
        assertEquals(1, before.getJSONArray("outbox").length())
        File(context.filesDir, "legacy-state.json").writeText(before.toString())
        File(context.filesDir, "legacy-cipher").writeText(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("payload", null)!!)
        screenshot("10-legacy-outbox")
    }

    @Test fun verifyLegacyMigration() {
        // The static heading appears before Room opens; cached content proves migration finished.
        waitFor(hasText("开发角色 · 林舟"))
        assertSchema(2)
        assertEquals(JSONObject(File(context.filesDir, "legacy-state.json").readText()).toString(), rawState().toString())
        assertEquals(File(context.filesDir, "legacy-cipher").readText(), context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("payload", null))
        openChat()
        assertDraft("")
        saveDraft("升级后的新草稿 ☕")
        screenshot("11-upgraded-outbox")
    }

    @Test fun retryLegacyRequestAfterUpgrade() {
        openChat()
        assertDraft("升级后的新草稿 ☕")
        click("用原请求重试")
        waitFor(hasText("发送") and isEnabled())
        assertDraft("升级后的新草稿 ☕")
        waitFor(hasText("旧版待发送消息"))
        screenshot("12-upgraded-recovered")
    }
}
