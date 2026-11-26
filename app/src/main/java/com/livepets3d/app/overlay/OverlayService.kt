package com.livepets3d.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowManager
import com.livepets3d.app.LivePetsApp
import com.livepets3d.app.R
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.data.Quest
import com.livepets3d.app.monetization.Monetization
import com.livepets3d.app.ui.MainActivity

/**
 * Foreground service hosting the floating pets. Keeps a persistent notification with
 * Pause / Resume / Stop, pauses rendering while the screen is off, and adapts to rotation.
 */
class OverlayService : Service(), FloatingPet.Host, Choreographer.FrameCallback {
    private lateinit var wm: WindowManager
    private val pets = ArrayList<FloatingPet>()
    private lateinit var menu: QuickMenu
    private var lastFrame = 0L
    private var paused = false
    private var screenOn = true
    private var looping = false
    private val area = Rect()
    private var visibleSeconds = 0f
    private var frameSkip = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            applyPause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        menu = QuickMenu(this, wm)
        createChannel()
        startInForeground()
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver, f)
        computeArea()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_STOP -> {
                LivePetsApp.instance.repo.overlayWanted = false
                stopSelf(); return START_NOT_STICKY
            }
            ACTION_PAUSE -> { paused = true; applyPause() }
            ACTION_RESUME -> { paused = false; applyPause() }
            ACTION_RECALL -> { paused = false; applyPause(); for (p in pets) p.recall() }
            ACTION_FEED -> pets.firstOrNull()?.feed()
            ACTION_PLAY -> pets.firstOrNull()?.play()
            ACTION_SLEEP -> pets.firstOrNull()?.nap()
            else -> rebuildPets()
        }
        updateNotification()
        return START_STICKY
    }

    private fun rebuildPets() {
        val repo = LivePetsApp.instance.repo
        val wanted = ArrayList<PetSpecies>()
        wanted.add(repo.species)
        for (c in repo.companions) if (c != repo.species && wanted.size < Monetization.maxPetsOnScreen) wanted.add(c)
        // remove pets no longer wanted
        val it = pets.iterator()
        while (it.hasNext()) { val p = it.next(); if (p.species !in wanted) { p.detach(); it.remove() } }
        // add missing ones
        for ((i, s) in wanted.withIndex()) if (pets.none { it.species == s }) {
            val p = FloatingPet(this, s, wm, this, i)
            pets.add(p); p.attach()
        }
        for (p in pets) p.applySettings()
        applyPause()
        if (!looping) { looping = true; lastFrame = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    private fun applyPause() {
        isPaused = paused
        val p = paused || !screenOn
        for (pet in pets) pet.setPaused(p)
        if (p) menu.hide()
        updateNotification()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        val dt = if (lastFrame == 0L) 0.016f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameTimeNanos
        val saver = LivePetsApp.instance.repo.batterySaver
        frameSkip = !frameSkip
        if (!paused && screenOn) {
            if (!saver || frameSkip) for (p in pets) p.frame(if (saver) dt * 2f else dt)
            visibleSeconds += dt
            if (visibleSeconds >= 60f) { visibleSeconds = 0f; LivePetsApp.instance.repo.bumpQuest(Quest.SCREEN) }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        computeArea()
        menu.hide()
        for (p in pets) p.onAreaChanged()
    }

    override fun onDestroy() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
        menu.hide()
        for (p in pets) p.detach()
        pets.clear()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        running = false; isPaused = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Host
    override fun area(): Rect = area
    override fun screenWidth(): Int = area.right
    override fun showQuickMenu(pet: FloatingPet) {
        val a = pet.anchor()
        menu.show(a[0], a[1], a[2], pet) { paused = true; applyPause(); updateNotification() }
    }
    override fun hideAll() { paused = true; applyPause() }

    private fun computeArea() {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val b = m.bounds
            area.set(ins.left, ins.top, b.width() - ins.right, b.height() - ins.bottom)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val status = dimen("status_bar_height"); val nav = dimen("navigation_bar_height")
            val landscape = dm.widthPixels > dm.heightPixels
            area.set(0, status, dm.widthPixels - (if (landscape) nav else 0), dm.heightPixels - (if (landscape) 0 else nav))
        }
    }

    private fun dimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ------------------------------------------------------------------ notification
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Pet on screen", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps your LivePets companion visible over other apps"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val repo = LivePetsApp.instance.repo
        val name = repo.nameOf(repo.species)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun svc(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, OverlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val isPaused = paused
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle(if (isPaused) "$name is resting" else "$name is on your screen")
            .setContentText(if (isPaused) "Tap Resume to bring your pet back" else "Long-press your pet for Feed · Play · Sleep")
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFF8B5CF6.toInt())
            .addAction(Notification.Action.Builder(null, if (isPaused) "Resume" else "Pause", svc(if (isPaused) ACTION_RESUME else ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Feed", svc(ACTION_FEED, 3)).build())
            .addAction(Notification.Action.Builder(null, "Stop", svc(ACTION_STOP, 2)).build())
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        const val CHANNEL = "pet_overlay"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "lp.stop"
        const val ACTION_PAUSE = "lp.pause"
        const val ACTION_RESUME = "lp.resume"
        const val ACTION_REFRESH = "lp.refresh"
        const val ACTION_FEED = "lp.feed"
        const val ACTION_RECALL = "lp.recall"
        const val ACTION_PLAY = "lp.play"
        const val ACTION_SLEEP = "lp.sleep"
        @Volatile var running = false
            private set
        @Volatile var isPaused = false
            private set

        fun start(c: Context) {
            val i = Intent(c, OverlayService::class.java).setAction(ACTION_REFRESH)
            c.startForegroundService(i)
        }
        fun refresh(c: Context) { if (running) start(c) }
        fun stop(c: Context) { c.startService(Intent(c, OverlayService::class.java).setAction(ACTION_STOP)) }
        fun send(c: Context, action: String) { if (running) c.startService(Intent(c, OverlayService::class.java).setAction(action)) }
    }
}
