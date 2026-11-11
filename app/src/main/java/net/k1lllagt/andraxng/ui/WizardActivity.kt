package net.k1lllagt.andraxng.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import net.k1lllagt.andraxng.App
import net.k1lllagt.andraxng.R
import net.k1lllagt.andraxng.core.DeviceInfo
import net.k1lllagt.andraxng.core.Fmt
import net.k1lllagt.andraxng.core.InstallState
import net.k1lllagt.andraxng.core.NativeLibs
import net.k1lllagt.andraxng.core.Profile
import net.k1lllagt.andraxng.core.Profiles
import net.k1lllagt.andraxng.core.RootStatus
import net.k1lllagt.andraxng.service.SessionService

/** Device check -> choose rootfs -> install progress -> done. */
class WizardActivity : Activity() {
    private enum class Step { DEVICE, PROFILE, PROGRESS, DONE }

    private lateinit var col: LinearLayout
    private var step = Step.DEVICE
    private var lastState: InstallState.Snapshot? = null

    private var phaseView: TextView? = null
    private var barView: ProgressBar? = null
    private var bytesView: TextView? = null
    private var detailView: TextView? = null
    private var shaInput: EditText? = null
    private var sourceBox: LinearLayout? = null

    private val listener: (InstallState.Snapshot) -> Unit = { s ->
        runOnUiThread { if (!isFinishing) onInstallState(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Set up ANDRAX-NG"
        val pg = Ui.page(this)
        col = pg.col
        setContentView(pg.scroll)
        step = if (InstallState.snapshot.running) Step.PROGRESS else Step.DEVICE
        render()
        InstallState.addListener(listener)
    }

    override fun onDestroy() {
        InstallState.removeListener(listener)
        super.onDestroy()
    }

    private fun onInstallState(s: InstallState.Snapshot) {
        when {
            s.running && step != Step.PROGRESS -> {
                step = Step.PROGRESS
                render()
            }
            s.running -> updateProgress(s)
            s.finished && step == Step.PROGRESS -> {
                lastState = s
                step = Step.DONE
                render()
            }
        }
    }

    private fun render() {
        col.removeAllViews()
        phaseView = null
        barView = null
        bytesView = null
        detailView = null
        shaInput = null
        sourceBox = null
        when (step) {
            Step.DEVICE -> renderDevice()
            Step.PROFILE -> renderProfile()
            Step.PROGRESS -> renderProgress()
            Step.DONE -> renderDone()
        }
    }

    // ---------------------------------------------------------------- step 1

    private fun hasStorage() =
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun renderDevice() {
        col.addView(Ui.title(this, "Step 1 of 3: this device"))
        col.addView(Ui.body(this, "Model: ${DeviceInfo.model}"))
        col.addView(Ui.body(this, "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
        col.addView(Ui.body(this, "CPU ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"))
        col.addView(Ui.body(this, if (DeviceInfo.has32BitAbi)
            "32-bit support: yes (the 32-bit ANDRAX archive can run here)"
        else
            "32-bit support: no (only the 64-bit Kali images are offered)"))
        val rootView = Ui.body(this, "Root: checking...")
        val modeView = Ui.body(this, "")
        val nativeView = Ui.body(this, "proot helper: testing...")
        col.addView(rootView)
        col.addView(modeView)
        col.addView(nativeView)
        col.addView(Ui.body(this, "Free space: ${Fmt.bytes(App.paths.files.usableSpace)}"))
        col.addView(Ui.body(this, "Storage access (shared folder): " + if (hasStorage()) "granted" else "not granted"))

        Thread {
            if (DeviceInfo.rootStatus == RootStatus.UNKNOWN) DeviceInfo.probeRoot()
            val test = NativeLibs.selfTest(App.paths)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rootView.text = "Root: " + when (DeviceInfo.rootStatus) {
                    RootStatus.GRANTED -> "granted (chroot mode available)"
                    RootStatus.DENIED -> "su found but access was denied. " + DeviceInfo.ROOT_HELP
                    RootStatus.NO_SU -> "none (proot mode)"
                    RootStatus.UNKNOWN -> "unknown"
                }
                modeView.text = "Mode to use: " + DeviceInfo.effectiveMode(App.settings).name.lowercase() +
                    "  (change in Settings)"
                nativeView.text = "proot helper: $test"
            }
        }.start()

        if (!hasStorage()) {
            col.addView(Ui.button(this, "Grant storage access") {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
                )
            })
        }
        col.addView(Ui.button(this, "Allow background running (battery)") { askBatteryExemption() })
        col.addView(Ui.button(this, "Continue") {
            step = Step.PROFILE
            render()
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (step == Step.DEVICE) render()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- step 2

    private fun renderProfile() {
        col.addView(Ui.title(this, "Step 2 of 3: choose a rootfs"))
        val avail = Profiles.availableFor(DeviceInfo.has32BitAbi)
        if (selectedId == null || avail.none { it.id == selectedId }) {
            selectedId = (avail.firstOrNull { it.downloadUrl != null } ?: avail.first()).id
        }
        val group = RadioGroup(this)
        val ids = HashMap<Int, Profile>()
        for (p in avail) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = p.title + (if (App.paths.isInstalled(p)) "  [installed]" else "") + "\n" + p.summary
            group.addView(rb)
            ids[rb.id] = p
            if (p.id == selectedId) rb.isChecked = true
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        sourceBox = box
        group.setOnCheckedChangeListener { _, checkedId ->
            val p = ids[checkedId]
            if (p != null) {
                selectedId = p.id
                renderSource(p)
            }
        }
        col.addView(group)
        col.addView(box)
        renderSource(Profiles.byId(selectedId!!)!!)
        col.addView(Ui.button(this, "Back") {
            step = Step.DEVICE
            render()
        })
    }

    private fun renderSource(p: Profile) {
        val box = sourceBox ?: return
        box.removeAllViews()
        val canDownload = p.downloadUrl != null
        if (!canDownload) importMode = true

        if (App.paths.isInstalled(p)) {
            box.addView(Ui.body(this, "Already installed. Continuing will replace it. Files in the shared folder are not touched."))
        }
        if (canDownload) {
            val g = RadioGroup(this)
            val dl = RadioButton(this)
            dl.id = View.generateViewId()
            dl.text = "Download (verified against Kali's SHA256SUMS)"
            val im = RadioButton(this)
            im.id = View.generateViewId()
            im.text = "Import a file I already have"
            g.addView(dl)
            g.addView(im)
            (if (importMode) im else dl).isChecked = true
            g.setOnCheckedChangeListener { _, checkedId ->
                importMode = checkedId == im.id
                renderSource(p)
            }
            box.addView(g)
        }
        if (importMode) {
            box.addView(Ui.body(this, "Choose the .tar.xz archive. It is read in place, never copied."))
            box.addView(Ui.button(this, "Choose archive file...") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                startActivityForResult(i, REQ_IMPORT)
            })
            box.addView(Ui.body(this, "Selected: " + (if (importUri == null) "nothing yet" else importName)))
            val sha = EditText(this)
            sha.hint = "SHA-256 to verify (optional)"
            sha.setSingleLine(true)
            sha.setText(p.fixedSha256 ?: "")
            shaInput = sha
            box.addView(sha)
            box.addView(Ui.body(this, "With no hash the extracted archive is not verified. Its hash is written to the log."))
        } else {
            box.addView(Ui.body(this, "Source: kali.download. Needs a working internet connection. The download can resume."))
        }
        box.addView(Ui.button(this, "Install") { startInstall(p) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // not every provider allows persisting; the grant still lasts for this task
            }
            importUri = uri
            importName = queryName(uri)
            selectedId?.let { Profiles.byId(it) }?.let { renderSource(it) }
        }
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i) ?: uri.toString()
                }
            }
        } catch (e: Exception) {
        }
        return uri.toString()
    }

    private fun startInstall(p: Profile) {
        if (importMode && importUri == null) {
            Toast.makeText(this, "Choose an archive file first", Toast.LENGTH_LONG).show()
            return
        }
        val go = {
            val sha = shaInput?.text?.toString()?.trim().orEmpty()
            SessionService.install(this, p.id, importMode, if (importMode) importUri else null, sha.ifEmpty { null })
            step = Step.PROGRESS
            render()
        }
        if (App.paths.isInstalled(p)) {
            AlertDialog.Builder(this)
                .setTitle("Replace existing install?")
                .setMessage("${p.title} will be deleted and installed again.")
                .setPositiveButton("Replace") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            go()
        }
    }

    // ---------------------------------------------------------------- step 3

    private fun renderProgress() {
        col.addView(Ui.title(this, "Step 3 of 3: installing"))
        phaseView = Ui.body(this, "Starting...")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.isIndeterminate = true
        barView = bar
        bytesView = Ui.body(this, "")
        detailView = Ui.body(this, "")
        col.addView(phaseView)
        col.addView(bar)
        col.addView(bytesView)
        col.addView(detailView)
        col.addView(Ui.body(this, "You can leave this screen. The install keeps running in the background (see the notification)."))
        col.addView(Ui.button(this, "Cancel install") { SessionService.cancelInstall(this) })
        updateProgress(InstallState.snapshot)
    }

    private fun updateProgress(s: InstallState.Snapshot) {
        phaseView?.text = s.phase
        val bar = barView ?: return
        if (s.total > 0) {
            bar.isIndeterminate = false
            bar.progress = ((s.done * 1000) / s.total).toInt().coerceIn(0, 1000)
            bytesView?.text = "${Fmt.bytes(s.done)} / ${Fmt.bytes(s.total)}  (${(s.done * 100) / s.total}%)"
        } else {
            bar.isIndeterminate = true
            bytesView?.text = ""
        }
        detailView?.text = s.detail
    }

    // ---------------------------------------------------------------- done

    private fun renderDone() {
        val s = lastState
        val p = s?.profileId?.let { Profiles.byId(it) }
        if (s?.error == null && p != null) {
            col.addView(Ui.title(this, "Installed"))
            col.addView(Ui.body(this, "${p.title} is ready."))
            col.addView(Ui.button(this, "Open terminal") {
                startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_PROFILE, p.id))
                finish()
            })
            col.addView(Ui.button(this, "Add home-screen shortcut") { pinShortcut(p) })
            col.addView(Ui.button(this, "Done") { finish() })
        } else {
            col.addView(Ui.title(this, "Install did not finish"))
            col.addView(Ui.body(this, s?.error ?: "Unknown error"))
            col.addView(Ui.body(this, "Nothing was left half-installed. Settings > Export logs has the details."))
            col.addView(Ui.button(this, "Back") {
                step = Step.PROFILE
                render()
            })
        }
    }

    private fun pinShortcut(p: Profile) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "This launcher cannot pin shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, TerminalActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(TerminalActivity.EXTRA_PROFILE, p.id)
        val info = ShortcutInfo.Builder(this, "open-" + p.id)
            .setShortLabel(if (p.kind.name == "KALI") "Kali" else "ANDRAX")
            .setLongLabel(p.title)
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(i)
            .build()
        sm.requestPinShortcut(info, null)
    }

    companion object {
        private const val REQ_IMPORT = 42
        private var selectedId: String? = null
        private var importMode = false
        private var importUri: Uri? = null
        private var importName = ""
    }
}
