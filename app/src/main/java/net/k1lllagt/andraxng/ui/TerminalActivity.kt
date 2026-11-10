package net.k1lllagt.andraxng.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import net.k1lllagt.andraxng.App
import net.k1lllagt.andraxng.core.DeviceInfo
import net.k1lllagt.andraxng.core.LaunchBuilder
import net.k1lllagt.andraxng.core.Mode
import net.k1lllagt.andraxng.core.Profile
import net.k1lllagt.andraxng.core.Profiles
import net.k1lllagt.andraxng.core.RootStatus
import net.k1lllagt.andraxng.core.SafeFs
import net.k1lllagt.andraxng.core.SessionHub
import net.k1lllagt.andraxng.service.SessionService

class TerminalActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private lateinit var status: TextView
    private var session: TerminalSession? = null
    private var profile: Profile? = null
    private var mode = Mode.PROOT
    private var ctrlOn = false
    private var altOn = false
    private var ctrlBtn: Button? = null
    private var altBtn: Button? = null
    private var fontDp = 14
    private var finishedHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(intent.getStringExtra(EXTRA_PROFILE) ?: App.settings.activeProfile)
        if (p == null || !App.paths.isInstalled(p)) {
            Toast.makeText(this, "No rootfs installed. Run setup first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        profile = p
        title = p.title
        fontDp = getSharedPreferences("andrax-ng", Context.MODE_PRIVATE).getInt("fontDp", 14)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        status = TextView(this)
        status.setTextColor(Color.LTGRAY)
        status.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4))
        status.text = "Starting ${p.title}..."
        root.addView(status)

        terminalView = TerminalView(this, null)
        terminalView.setTerminalViewClient(viewClient)
        terminalView.setTextSize(Ui.dp(this, fontDp))
        root.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildExtraKeys())
        setContentView(root)

        Thread {
            try {
                if (DeviceInfo.rootStatus == RootStatus.UNKNOWN) DeviceInfo.probeRoot()
                val m = DeviceInfo.effectiveMode(App.settings)
                val spec = LaunchBuilder.prepare(applicationContext, App.paths, App.settings, App.log, p, m)
                runOnUiThread { if (!isFinishing) startSession(spec) }
            } catch (e: Exception) {
                App.log.e("launch failed", e)
                runOnUiThread { showError(e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun startSession(spec: net.k1lllagt.andraxng.core.LaunchSpec) {
        mode = spec.mode
        val argv = arrayOf(spec.exe) + spec.args.toTypedArray()
        val s = TerminalSession(spec.exe, spec.cwd, argv, spec.env.toTypedArray(), 2000, sessionClient)
        session = s
        terminalView.attachSession(s)
        terminalView.requestFocus()
        status.text = spec.title
        SessionHub.activeSessions = 1
        SessionHub.onStopRequested = { runOnUiThread { session?.finishIfRunning() } }
        SessionService.hold(this)
    }

    private fun showError(msg: String) {
        status.text = "Could not start: $msg"
        status.setTextColor(Color.rgb(255, 120, 120))
    }

    // ------------------------------------------------------------ keys row

    private fun buildExtraKeys(): HorizontalScrollView {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun key(label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.minWidth = Ui.dp(this, 48)
            b.minimumWidth = Ui.dp(this, 48)
            b.setOnClickListener { action() }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }
        key("ESC") { send("\u001b") }
        key("TAB") { send("\t") }
        ctrlBtn = key("CTRL") {
            ctrlOn = !ctrlOn
            paintToggles()
        }
        altBtn = key("ALT") {
            altOn = !altOn
            paintToggles()
        }
        key("\u2190") { arrow('D') }
        key("\u2191") { arrow('A') }
        key("\u2193") { arrow('B') }
        key("\u2192") { arrow('C') }
        key("HOME") { send("\u001b[H") }
        key("END") { send("\u001b[F") }
        key("PGUP") { send("\u001b[5~") }
        key("PGDN") { send("\u001b[6~") }
        key("-") { send("-") }
        key("/") { send("/") }
        key("|") { send("|") }
        key("~") { send("~") }
        key("KBD") { showKeyboard() }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        return hs
    }

    private fun paintToggles() {
        ctrlBtn?.alpha = if (ctrlOn) 1f else 0.55f
        altBtn?.alpha = if (altOn) 1f else 0.55f
    }

    private fun send(s: String) {
        session?.write(s)
    }

    private fun arrow(c: Char) {
        val app = session?.emulator?.isCursorKeysApplicationMode == true
        send(if (app) "\u001bO$c" else "\u001b[$c")
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.requestFocus()
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun takeCtrl(): Boolean {
        val v = ctrlOn
        if (v) {
            ctrlOn = false
            runOnUiThread { paintToggles() }
        }
        return v
    }

    private fun takeAlt(): Boolean {
        val v = altOn
        if (v) {
            altOn = false
            runOnUiThread { paintToggles() }
        }
        return v
    }

    private fun changeFont(bigger: Boolean) {
        fontDp = (fontDp + if (bigger) 1 else -1).coerceIn(8, 32)
        terminalView.setTextSize(Ui.dp(this, fontDp))
        getSharedPreferences("andrax-ng", Context.MODE_PRIVATE).edit().putInt("fontDp", fontDp).apply()
    }

    // ------------------------------------------------------------ clients

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFont(scale > 1f)
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = takeCtrl()
        override fun readAltKey(): Boolean = takeAlt()
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "error", e) }
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = terminalView.onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) = sessionEnded(finishedSession.exitStatus)

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@TerminalActivity)
            if (!t.isNullOrEmpty()) session?.emulator?.paste(t.toString())
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "error", e) }
    }

    // ------------------------------------------------------------ lifecycle

    private fun sessionEnded(exit: Int) {
        if (finishedHandled) return
        finishedHandled = true
        App.log.i("session ended, exit status $exit")
        cleanupMounts()
        SessionHub.activeSessions = 0
        SessionHub.onStopRequested = null
        SessionService.release(this)
        Toast.makeText(this, "Session ended (exit $exit)", Toast.LENGTH_SHORT).show()
        if (!isFinishing) finish()
    }

    /** Safety net for chroot mode: the launch script unmounts on exit, this catches a killed script. */
    private fun cleanupMounts() {
        val p = profile ?: return
        if (mode != Mode.CHROOT) return
        Thread { SafeFs.unmountAll(App.paths.rootfs(p), App.log) }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back keeps the session alive. Type "exit" or use the notification's Stop action to end it.
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        val s = session
        if (s != null && s.isRunning) {
            s.finishIfRunning()
            sessionEnded(-1)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
