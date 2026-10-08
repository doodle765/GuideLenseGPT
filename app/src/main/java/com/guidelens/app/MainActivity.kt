package com.guidelens.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceController
    private lateinit var alerts: AlertCenter
    private lateinit var camera: CameraController
    private lateinit var ocr: OcrReader
    private val handler = Handler(Looper.getMainLooper())

    private var position: Location? = null
    private var nav: Geo.Route? = null

    // ---- views ----
    private lateinit var scrHome: View
    private lateinit var scrNav: View
    private lateinit var scrCamera: View
    private lateinit var scrSos: View
    private lateinit var scrSettings: View
    private lateinit var gpsChip: com.google.android.material.chip.Chip
    private lateinit var battChip: com.google.android.material.chip.Chip
    private lateinit var themeBtn: MaterialButton
    private lateinit var navStatus: TextView
    private lateinit var camStatus: TextView
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlayView: OverlayView

    private val screens: Map<String, View> by lazy {
        mapOf("home" to scrHome, "nav" to scrNav, "camera" to scrCamera,
              "sos" to scrSos, "settings" to scrSettings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speaker = Speaker(this, prefs)
        voice = VoiceController(this,
            onCommand = { runOnUiThread { handleCommand(it) } },
            onError = { runOnUiThread { speaker.speak("Sorry, I didn't catch that. Try again.") } })
        alerts = AlertCenter(speaker, prefs, AlertCenter.vibratorOf(this))
        ocr = OcrReader()

        bindViews()
        styleButtons()
        applySimpleMode()

        camera = CameraController(this, previewView, overlayView, prefs, speaker, alerts) { msg ->
            runOnUiThread { camStatus.text = msg }
        }

        wireButtons()
        loadSettingsUI()
        updateBatteryChip()
        startGeo()

        if (!prefs.agreed) showDisclaimer()
        if (!voice.isSupported) {
            speaker.speak("Voice input is not supported on this device. Use the big buttons instead.")
        }
    }

    // ================= views =================
    private fun bindViews() {
        scrHome = findViewById(R.id.scrHome)
        scrNav = findViewById(R.id.scrNav)
        scrCamera = findViewById(R.id.scrCamera)
        scrSos = findViewById(R.id.scrSOS)
        scrSettings = findViewById(R.id.scrSettings)
        gpsChip = findViewById(R.id.gpsChip)
        battChip = findViewById(R.id.battChip)
        themeBtn = findViewById(R.id.themeBtn)
        navStatus = findViewById(R.id.navStatus)
        camStatus = findViewById(R.id.camStatus)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)

        // brand: "Guide" in text color + "Lens" in accent
        val brand = findViewById<TextView>(R.id.brand)
        val ss = SpannableString("GuideLens")
        ss.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent)), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        brand.text = ss
    }

    private fun twoLine(main: String, sub: String): CharSequence {
        val s = SpannableString(main + "\n" + sub)
        val start = main.length + 1
        s.setSpan(RelativeSizeSpan(0.72f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.text2)), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun styleButtons() {
        findViewById<MaterialButton>(R.id.btnTalk).text =
            twoLine("Hold to Talk", "Say \u201Chelp\u201D anytime")
        findViewById<MaterialButton>(R.id.btnNavigate).text =
            twoLine("Navigate somewhere", "Voice-guided walking route")
        findViewById<MaterialButton>(R.id.btnDetect).text =
            twoLine("Obstacle detection", "Poles, walls, people & more")
        findViewById<MaterialButton>(R.id.btnWhereAmI).text =
            twoLine("Where am I?", "Speaks your current address")
        findViewById<MaterialButton>(R.id.btnSettings).text =
            twoLine("Settings", "Voice, alerts, contact")
        findViewById<MaterialButton>(R.id.btnMore).text =
            twoLine("More options", "Extra features & settings")
        findViewById<MaterialButton>(R.id.btnSOS).text =
            twoLine("Emergency SOS", "Text your location to a contact")
    }

    private fun applySimpleMode() {
        val simple = prefs.simple
        findViewById<View>(R.id.btnSettings).visibility = if (simple) View.GONE else View.VISIBLE
        findViewById<View>(R.id.duoRow).visibility = if (simple) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnMore).visibility = if (simple) View.VISIBLE else View.GONE

        val talk = findViewById<MaterialButton>(R.id.btnTalk)
        talk.textSize = if (simple) 27f else 23f
        talk.minimumHeight = if (simple) (128 * resources.displayMetrics.density).toInt() else talk.minimumHeight

        val hero = findViewById<TextView>(R.id.heroLine)
        hero.textSize = if (simple) 25f else 21f
    }

    private fun show(name: String) {
        screens.values.forEach { it.visibility = View.GONE }
        screens[name]?.visibility = View.VISIBLE
        findViewById<View>(R.id.scroller).scrollTo(0, 0)
    }

    // ================= wiring =================
    private fun wireButtons() {
        val talk = findViewById<MaterialButton>(R.id.btnTalk)
        talk.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasPermissions(Manifest.permission.RECORD_AUDIO)) {
                        voice.start()
                    } else {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { voice.stop(); true }
                else -> false
            }
        }

        themeBtn.setOnClickListener {
            prefs.themeDark = !prefs.themeDark
            AppCompatDelegate.setDefaultNightMode(
                if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            syncThemeIcon()
            speaker.speak(if (prefs.themeDark) "Dark mode on." else "Light mode on.")
        }
        findViewById<MaterialButton>(R.id.simpleBtn).setOnClickListener {
            prefs.simple = !prefs.simple
            applySimpleMode()
            speaker.speak(if (prefs.simple) "Simple view on. Bigger buttons, fewer options."
                          else "Standard view on.")
        }

        findViewById<MaterialButton>(R.id.btnNavigate).setOnClickListener { show("nav") }
        findViewById<MaterialButton>(R.id.navBack).setOnClickListener { stopNavigation(silent = true); show("home") }
        findViewById<MaterialButton>(R.id.navGo).setOnClickListener {
            val q = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.navDestInput)
                .text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) navigateTo(q) else speaker.speak("Please type or say a destination first.")
        }
        findViewById<MaterialButton>(R.id.navStop).setOnClickListener { stopNavigation() }

        findViewById<MaterialButton>(R.id.btnDetect).setOnClickListener { show("camera"); startCamera() }
        findViewById<MaterialButton>(R.id.camStop).setOnClickListener { stopCameraAndHome() }
        findViewById<MaterialButton>(R.id.camStopTop).setOnClickListener { stopCameraAndHome() }

        findViewById<MaterialButton>(R.id.btnWhereAmI).setOnClickListener { whereAmI() }
        findViewById<MaterialButton>(R.id.btnAround).setOnClickListener { describeAround() }
        findViewById<MaterialButton>(R.id.btnRead).setOnClickListener { readSign() }

        findViewById<MaterialButton>(R.id.btnSOS).setOnClickListener { sosFlow() }
        findViewById<MaterialButton>(R.id.sosBack).setOnClickListener { show("home") }
        findViewById<MaterialButton>(R.id.sosSend).setOnClickListener { sendSOS() }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { show("settings") }
        findViewById<MaterialButton>(R.id.btnMore).setOnClickListener { show("settings") }
        findViewById<MaterialButton>(R.id.setBack).setOnClickListener { show("home") }

        findViewById<Slider>(R.id.setRate).addOnChangeListener { _, v, _ ->
            findViewById<TextView>(R.id.rateLabel).text = "Speech speed  %.1f\u00D7".format(v)
        }
        findViewById<MaterialButton>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<MaterialButton>(R.id.btnTestVoice).setOnClickListener {
            speaker.speak("This is how GuideLens sounds. You can change the speed in settings.", priority = true)
        }
    }

    private fun syncThemeIcon() {
        themeBtn.setIconResource(if (prefs.themeDark) R.drawable.ic_moon else R.drawable.ic_sun)
    }

    // ================= permissions =================
    private fun hasPermissions(vararg ps: String): Boolean =
        ps.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun ensureLocation(then: () -> Unit = {}) {
        if (hasPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            then()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC)
        }
    }

    private fun startCamera() {
        if (hasPermissions(Manifest.permission.CAMERA)) {
            camera.start()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_CAM -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera.start()
            REQ_MIC -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice.start()
            REQ_LOC -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startGeo()
        }
    }

    // ================= geo =================
    private fun startGeo() {
        if (!hasPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            gpsChip.text = " GPS"
            ensureLocation { startGeo() }
            return
        }
        val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        try {
            fused.requestLocationUpdates(req, object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    result.lastLocation?.let { loc ->
                        position = loc
                        gpsChip.text = " \u00B1" + loc.accuracy.roundToInt() + " m"
                        navTick()
                    }
                }
            }, Looper.getMainLooper())
        } catch (e: SecurityException) {
            gpsChip.text = " permission needed"
        }
    }

    private fun whereAmI() {
        val p = position
        if (p == null) {
            speaker.speak("I don't have your location yet. Please allow location access.")
            return
        }
        // Offline-first: say the no-internet message, exactly as specified.
        if (!isOnline(this)) {
            speaker.speak("No internet access. I can't tell you where you are right now.", priority = true)
            return
        }
        speaker.speak("Getting your location\u2026")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { Geo.whereAmIText(this@MainActivity, p) }
            when (text) {
                "NO_INTERNET" -> speaker.speak("No internet access. I can't tell you where you are right now.", priority = true)
                "COORDS" -> speaker.speak("You are at coordinates %.5f, %.5f.".format(p.latitude, p.longitude))
                else -> speaker.speak("You are at $text. GPS accuracy about ${p.accuracy.roundToInt()} meters.")
            }
        }
    }

    // ================= navigation =================
    private fun navigateTo(place: String) {
        val p = position
        if (p == null) {
            speaker.speak("I need your location first. Please allow location access.")
            return
        }
        // Walking routes require internet (map data lives on OSM/OSRM servers).
        if (!isOnline(this)) {
            speaker.speak("Navigation needs an internet connection to download the walking route. Everything else works offline.", priority = true)
            return
        }
        speaker.speak("Looking for $place\u2026")
        lifecycleScope.launch {
            val dest = Geo.geocode(place)
            if (dest == null) {
                speaker.speak("Sorry, I couldn't find $place. Try a nearby landmark or a full address.")
                return@launch
            }
            val route = Geo.fetchRoute(LatLng(p.latitude, p.longitude), dest)
            if (route == null) {
                speaker.speak("I couldn't find a walking route there. Please try again.")
                return@launch
            }
            nav = route
            show("nav")
            val len = Geo.routeLen(route.coords)
            navStatus.text = "Route to ${dest.label} \u2014 ${len.roundToInt()} m."
            speaker.speak("Route set to ${dest.label}. About ${len.roundToInt()} meters, roughly " +
                    "${(len / 80).roundToInt().coerceAtLeast(1)} minutes walking. Say 'stop' anytime.")
            vibrateSosPattern(short = true)
        }
    }

    private fun navTick() {
        val n = nav ?: return
        val p = position ?: return
        val here = LatLng(p.latitude, p.longitude)
        val steps = n.steps
        if (n.stepIdx >= steps.size) { finishNav(); return }
        val step = steps[n.stepIdx]
        val end = LatLng(step.lat, step.lng)
        val d = Geo.hav(here, end)

        if (Geo.hav(here, LatLng(n.dest.lat, n.dest.lng)) < 20.0) { finishNav(); return }

        while (n.stepIdx < steps.size - 1 && d < 8.0) {
            n.stepIdx++
            n.announcedPre = false
            n.announcedNow = false
            return navTick()
        }

        if (!n.announcedPre && d < 22.0 && d >= 6.0) {
            n.announcedPre = true
            val pre = if (d > 12.0) "In about ${((d / 5).roundToInt() * 5)} meters, " else ""
            speaker.speak(pre + Geo.maneuverText(step) + ".")
            vibrateSosPattern(short = true)
        }
        if (!n.announcedNow && d < 6.0) {
            n.announcedNow = true
            speaker.speak("Now. " + Geo.maneuverText(step) + ".", priority = true)
        }

        val off = Geo.nearestDistToRoute(here, n.coords)
        if (off > 35.0 && System.currentTimeMillis() - n.lastRecalc > 15000) {
            n.lastRecalc = System.currentTimeMillis()
            speaker.speak("You seem off the route. Recalculating.", priority = true)
            vibrateLong()
            val dest = n.dest
            lifecycleScope.launch {
                val r = Geo.fetchRoute(here, dest)
                if (r != null) {
                    nav = r
                    r.lastRecalc = System.currentTimeMillis()
                }
            }
        }
    }

    private fun finishNav() {
        val label = nav?.dest?.label ?: "your destination"
        speaker.speak("You have arrived near $label. I will stop guiding now.", priority = true)
        vibrateSosPattern(short = true)
        navStatus.text = "Arrived."
        nav = null
    }

    private fun stopNavigation(silent: Boolean = false) {
        if (nav == null && !silent) return
        nav = null
        navStatus.text = "Idle."
        if (!silent) speaker.speak("Navigation stopped.")
    }

    // ================= camera / vision =================
    private fun stopCameraAndHome() {
        camera.stop()
        show("home")
        speaker.speak("Detection stopped.")
    }

    private fun describeAround() {
        if (!camera.running) {
            show("camera")
            startCamera()
            handler.postDelayed({ describeAround() }, 5000)
            return
        }
        val parts = mutableListOf<String>()
        camera.lastSeen.filter { it.dist != null }
            .sortedBy { it.dist }
            .take(3)
            .forEach { parts.add("${it.label} ${it.side}, ${distWord(it.dist!!)}") }
        camera.lastZone?.let { parts.add("possible obstacle ahead, ${distWord(it.dist)}") }
        if (parts.isEmpty()) {
            speaker.speak("I don't see any obstacles nearby.")
        } else {
            speaker.speak("Around you: " + parts.joinToString(". ") + ".")
        }
    }

    private fun readSign() {
        if (!camera.running) {
            show("camera")
            startCamera()
            handler.postDelayed({ readSign() }, 3500)
            return
        }
        speaker.speak("Reading\u2026 hold the sign steady.")
        lifecycleScope.launch {
            val bmp = try { withContext(Dispatchers.Main) { previewView.bitmap } } catch (e: Exception) { null }
            if (bmp == null) { speaker.speak("Reading failed. Try again."); return@launch }
            val text = withContext(Dispatchers.IO) { ocr.read(bmp) }
            if (text.isNotBlank()) {
                speaker.speak("It says: $text")
                camStatus.text = "Read: $text"
            } else {
                speaker.speak("I couldn't read any text here. Try holding the camera steady and closer.")
            }
        }
    }

    // ================= SOS =================
    private fun sosFlow() {
        show("sos")
        if (prefs.contact.isBlank()) {
            speaker.speak("No emergency contact set. Please add a phone number in Settings.")
            return
        }
        vibrateSosPattern()
        speaker.speak("SOS ready. Press the big red button to send your location.")
    }

    private fun sendSOS() {
        val p = position
        if (p == null) {
            speaker.speak("No location available yet. Wait for the GPS fix, then try again.")
            return
        }
        val number = prefs.contact.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) {
            speaker.speak("The emergency contact number is not valid. Check Settings.")
            return
        }
        val gmaps = "https://maps.google.com/?q=${p.latitude},${p.longitude}"
        val body = "EMERGENCY \u2014 I need help. My location: $gmaps " +
                "(accuracy \u00B1${p.accuracy.roundToInt()} m). Sent via GuideLens."
        vibrateSosPattern()
        speaker.speak("Sending SOS with your location.", priority = true)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
        }
        try { startActivity(intent) } catch (e: Exception) {
            speaker.speak("No messaging app found on this phone.")
        }
    }

    // ================= voice commands =================
    private fun handleCommand(raw: String) {
        val t = raw.lowercase().replace(Regex("[.?!]"), "").trim()
        val m = Regex("(?:navigate|take me|go|walk)(?: to)? (.+)").find(t)
        when {
            m != null -> navigateTo(m.groupValues[1].trim())
            t.contains("where am i") || t.contains("my location") -> whereAmI()
            t.contains("read") -> readSign()
            t.contains("around") || t.contains("what do you see") || t.contains("surroundings") -> describeAround()
            t.contains("stop navigation") || t == "stop" -> stopNavigation()
            t.contains("repeat") -> speaker.repeat()
            t.contains("sos") || t.contains("emergency") || t.contains("help me") -> sosFlow()
            t.contains("setting") -> show("settings")
            t.contains("start detection") || t.contains("start camera") -> { show("camera"); startCamera() }
            else -> speaker.speak("Sorry, I didn't understand. Try 'navigate to the library', 'where am I', 'read this', or 'what's around me'.")
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SPACE &&
            event.action == android.view.KeyEvent.ACTION_DOWN && !event.isRepeat &&
            currentFocus !is com.google.android.material.textfield.TextInputEditText) {
            voice.start()
            return true
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SPACE && event.action == android.view.KeyEvent.ACTION_UP) {
            voice.stop()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ================= settings =================
    private fun loadSettingsUI() {
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkAudio).isChecked = prefs.audio
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkHaptics).isChecked = prefs.haptics
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkStepFree).isChecked = prefs.stepFree
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkSimple).isChecked = prefs.simple
        findViewById<RadioButton>(R.id.themeDark).isChecked = prefs.themeDark
        findViewById<RadioButton>(R.id.themeLight).isChecked = !prefs.themeDark
        checkVerb(prefs.verbosity)
        checkSens(prefs.sensitivity)
        findViewById<Slider>(R.id.setRate).value = prefs.rate
        findViewById<TextView>(R.id.rateLabel).text = "Speech speed  %.1f\u00D7".format(prefs.rate)
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.setContact).setText(prefs.contact)
        syncThemeIcon()
    }

    private fun checkVerb(v: String) {
        findViewById<RadioButton>(R.id.verbMinimal).isChecked = v == "minimal"
        findViewById<RadioButton>(R.id.verbStandard).isChecked = v == "standard"
        findViewById<RadioButton>(R.id.verbVerbose).isChecked = v == "verbose"
    }

    private fun checkSens(s: String) {
        findViewById<RadioButton>(R.id.sensNear).isChecked = s == "near"
        findViewById<RadioButton>(R.id.sensStandard).isChecked = s == "standard"
        findViewById<RadioButton>(R.id.sensFar).isChecked = s == "far"
    }

    private fun saveSettings() {
        prefs.audio = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkAudio).isChecked
        prefs.haptics = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkHaptics).isChecked
        prefs.stepFree = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkStepFree).isChecked
        prefs.simple = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.chkSimple).isChecked
        prefs.themeDark = findViewById<RadioButton>(R.id.themeDark).isChecked
        prefs.rate = findViewById<Slider>(R.id.setRate).value
        prefs.contact = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.setContact)
            .text?.toString()?.trim().orEmpty()
        prefs.verbosity = when {
            findViewById<RadioButton>(R.id.verbMinimal).isChecked -> "minimal"
            findViewById<RadioButton>(R.id.verbVerbose).isChecked -> "verbose"
            else -> "standard"
        }
        prefs.sensitivity = when {
            findViewById<RadioButton>(R.id.sensNear).isChecked -> "near"
            findViewById<RadioButton>(R.id.sensFar).isChecked -> "far"
            else -> "standard"
        }
        speaker.setRate(prefs.rate)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        syncThemeIcon()
        applySimpleMode()
        speaker.speak("Settings saved.")
        show("home")
    }

    // ================= misc =================
    private fun updateBatteryChip() {
        val i: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            battChip.text = " ${(level * 100 / scale)}%"
        }
    }

    private fun showDisclaimer() {
        val msg = "GuideLens is an experimental assistive aid. It helps you notice obstacles, read signs and follow walking routes \u2014 but it cannot replace your white cane, guide dog, or your own judgment.\n\n" +
                "Always cross roads the way you were trained. The app will never tell you a road is \"safe to cross\" \u2014 it only reports what it sees.\n\n" +
                "Camera video never leaves your phone. Location is used only when you ask."
        MaterialAlertDialogBuilder(this)
            .setTitle("Before you start")
            .setMessage(msg)
            .setPositiveButton("I understand \u2014 let's go") { d, _ ->
                prefs.agreed = true
                d.dismiss()
                speaker.speak("Great. Hold the green button and say, navigate to, followed by a place.")
            }
            .setCancelable(false)
            .show()
        speaker.speak("Welcome to GuideLens. Please listen to the safety information on screen.")
    }

    private fun vibrateSosPattern(short: Boolean = false) {
        val v = AlertCenter.vibratorOf(this) ?: return
        if (!prefs.haptics) return
        val pattern = if (short) longArrayOf(60, 80, 60) else longArrayOf(200, 100, 200, 100, 200, 700)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern.sum())
            }
        } catch (e: Exception) { }
    }

    private fun vibrateLong() {
        val v = AlertCenter.vibratorOf(this) ?: return
        if (!prefs.haptics) return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(300), -1))
            else { @Suppress("DEPRECATION") v.vibrate(300) }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { camera.shutdown() } catch (_: Exception) { }
        try { voice.destroy() } catch (_: Exception) { }
        try { ocr.close() } catch (_: Exception) { }
        try { speaker.shutdown() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val REQ_CAM = 1
        private const val REQ_MIC = 2
        private const val REQ_LOC = 3
    }
}
