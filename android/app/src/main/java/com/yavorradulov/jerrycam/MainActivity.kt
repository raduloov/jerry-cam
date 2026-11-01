package com.yavorradulov.jerrycam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** The camera phone's screen: a glowing paw and one word, a banner for whatever needs fixing, and a ⋯ menu. */
class MainActivity : ComponentActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // After two refusals Android stops asking, so send the owner to the app's settings instead.
            if (results.filterValues { !it }.keys.any { !shouldShowRequestPermissionRationale(it) }) openAppSettings()
            render()
        }

    /** How a mood looks: the glow color (none when stopped), the paw dimmed or not, and the texts. */
    private data class Look(
        @param:ColorRes val glow: Int?,
        val dim: Boolean,
        val title: String,
        @param:ColorRes val titleColor: Int,
        val detail: String? = null,
        val action: String? = null,
        val pulse: Boolean = false,
    )

    private lateinit var glow: View
    private lateinit var paw: ImageView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var action: Button
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerDetail: TextView
    private lateinit var menuButton: ImageButton
    private var screen: HomeScreen? = null
    private var pulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        glow = findViewById(R.id.glow)
        paw = findViewById(R.id.paw)
        title = findViewById(R.id.title)
        detail = findViewById(R.id.detail)
        action = findViewById(R.id.action)
        banner = findViewById(R.id.banner)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerDetail = findViewById(R.id.bannerDetail)
        menuButton = findViewById(R.id.menu)

        action.setOnClickListener {
            when (screen?.mood) {
                Mood.WELCOME -> lifecycleScope.launch { signIn() }
                Mood.STOPPED -> setEnabled(true)
                else -> Unit
            }
        }
        banner.setOnClickListener { screen?.fix?.let(::fix) }
        menuButton.setOnClickListener { showMenu(it) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CamService.status.collect { render() }
            }
        }
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    /** Opened by BootReceiver: start the service while we're in the foreground, then get out of the way. */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)
        lifecycleScope.launch {
            CamService.status.first { it.running }
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (readyToRun() && Prefs.enabled(this) && !CamService.status.value.running) CamService.start(this)
    }

    private fun render() {
        val status = CamService.status.value
        val screen = homeScreen(setup(), status).also { screen = it }
        val look = when (screen.mood) {
            Mood.WELCOME -> Look(
                R.color.accent, dim = false, getString(R.string.mood_welcome), R.color.fg,
                getString(R.string.mood_welcome_detail), action = getString(R.string.sign_in),
            )
            Mood.PROBLEM -> Look(R.color.accent, dim = false, getString(R.string.mood_problem), R.color.problem_text)
            Mood.STOPPED -> Look(
                null, dim = true, getString(R.string.mood_stopped), R.color.muted,
                getString(R.string.mood_stopped_detail), action = getString(R.string.start),
            )
            Mood.STARTING -> Look(R.color.idle, dim = true, getString(R.string.mood_starting), R.color.muted)
            Mood.OFFLINE -> Look(
                R.color.idle, dim = true, getString(R.string.mood_offline), R.color.muted, getString(R.string.mood_offline_detail),
            )
            Mood.READY -> Look(R.color.ready, dim = false, getString(R.string.mood_ready), R.color.fg, batteryText(status.battery))
            Mood.LIVE -> Look(
                R.color.live, dim = false, getString(R.string.mood_live), R.color.live_text,
                status.viewer?.let { getString(R.string.mood_live_viewer, it) } ?: getString(R.string.mood_live_someone),
                pulse = true,
            )
        }
        glow.background = look.glow?.let { glowOf(it, strong = look.pulse) }
        paw.alpha = if (look.dim) 0.35f else 1f
        title.text = look.title
        title.setTextColor(getColor(look.titleColor))
        detail.text = look.detail
        detail.isVisible = look.detail != null
        action.text = look.action
        action.isVisible = look.action != null
        menuButton.isVisible = screen.mood != Mood.WELCOME
        setPulse(look.pulse)

        banner.isVisible = screen.fix != null
        screen.fix?.let {
            val (heading, hint) = fixText(it)
            bannerTitle.text = heading
            bannerDetail.text = hint
        }
    }

    private fun batteryText(battery: BatteryState?): String? = battery?.let {
        getString(if (it.charging) R.string.battery_charging else R.string.battery_level, it.level)
    }

    private fun fixText(fix: Fix): Pair<String, String> = when (fix) {
        Fix.SWITCH_ACCOUNT -> getString(R.string.fix_switch_account) to getString(R.string.fix_switch_account_detail)
        Fix.CAMERA_AND_MIC -> getString(R.string.fix_camera_and_mic) to getString(R.string.fix_tap_to_allow)
        Fix.NOTIFICATIONS -> getString(R.string.fix_notifications) to getString(R.string.fix_tap_to_allow)
        Fix.BATTERY -> getString(R.string.fix_battery) to getString(R.string.fix_battery_detail)
    }

    private fun fix(fix: Fix) {
        when (fix) {
            Fix.SWITCH_ACCOUNT -> lifecycleScope.launch { signIn() }
            Fix.CAMERA_AND_MIC, Fix.NOTIFICATIONS -> requestPermissions.launch(requiredPermissions())
            Fix.BATTERY -> startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
            )
        }
    }

    private fun glowOf(@ColorRes color: Int, strong: Boolean) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = resources.displayMetrics.density * 130
        colors = intArrayOf(ColorUtils.setAlphaComponent(getColor(color), if (strong) 0x80 else 0x48), Color.TRANSPARENT)
    }

    /** Live breathes, so it reads as "on" from across the room. */
    private fun setPulse(on: Boolean) {
        if (!on) {
            pulse?.cancel()
            pulse = null
            glow.alpha = 1f
        } else if (pulse == null) {
            pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.45f).apply {
                duration = 1_200
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun showMenu(anchor: View) {
        val enabled = Prefs.enabled(this)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SWITCH_ACCOUNT, 0, R.string.menu_switch_account)
        popup.menu.add(0, MENU_START_STOP, 1, if (enabled) R.string.menu_stop else R.string.menu_start)
        popup.menu.add(
            0, MENU_RESTART, 2,
            if (Settings.canDrawOverlays(this)) R.string.menu_restart_on else R.string.menu_restart_off,
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SWITCH_ACCOUNT -> lifecycleScope.launch { signIn() }
                MENU_START_STOP -> setEnabled(!enabled)
                MENU_RESTART -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }
            true
        }
        popup.show()
    }

    private fun setEnabled(enable: Boolean) {
        Prefs.setEnabled(this, enable)
        if (!enable) CamService.stop(this) else if (readyToRun()) CamService.start(this)
        render()
    }

    private suspend fun signIn() {
        val option = GetSignInWithGoogleOption.Builder(getString(R.string.default_web_client_id)).build()
        try {
            val credential = CredentialManager.create(this)
                .getCredential(this, GetCredentialRequest.Builder().addCredentialOption(option).build())
                .credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) return
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            CamService.stop(this)
            auth.signOut()
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: GetCredentialCancellationException) {
            return
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
        }
        render()
        if (readyToRun() && Prefs.enabled(this)) CamService.start(this)
    }

    private fun setup() = Setup(
        signedIn = auth.currentUser != null,
        cameraAndMic = cameraAndMicGranted(),
        notifications = Build.VERSION.SDK_INT < 33 || isGranted(Manifest.permission.POST_NOTIFICATIONS),
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        enabled = Prefs.enabled(this),
    )

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private fun readyToRun() = auth.currentUser != null && cameraAndMicGranted()

    private fun cameraAndMicGranted() = isGranted(Manifest.permission.CAMERA) && isGranted(Manifest.permission.RECORD_AUDIO)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val MENU_SWITCH_ACCOUNT = 1
        private const val MENU_START_STOP = 2
        private const val MENU_RESTART = 3
    }
}
