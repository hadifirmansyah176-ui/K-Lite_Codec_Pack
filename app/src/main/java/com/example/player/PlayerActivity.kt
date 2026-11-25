package com.example.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private var pfd: ParcelFileDescriptor? = null
    private var mediaRef: Media? = null
    private val handler = Handler(Looper.getMainLooper())

    private var stopped = false
    private var subtitleOn = true
    private var lastSpu = -1
    private var trackInfo = ""
    private var uriKey = ""
    private var videoName = ""

    private lateinit var seek: SeekBarView
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSub: TextView
    private lateinit var chrome: List<View>
    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }

    // Load Subtitle dari penyimpanan
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val f = File(cacheDir, "sub_${System.currentTimeMillis()}_${displayName(uri)}")
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                addSubtitleFile(f)
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal memuat subtitle", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        seek = findViewById(R.id.seekBar)
        tvState = findViewById(R.id.tvState)
        tvTime = findViewById(R.id.tvTime)
        btnPlay = findViewById(R.id.btnPlay)
        btnSub = findViewById(R.id.btnSub)
        chrome = listOf(
            R.id.titleBar, R.id.menuBar, R.id.seekBar, R.id.controlRow,
            R.id.statusBar, R.id.volOverlay
        ).map { findViewById<View>(it) }

        // Gaya subtitle: Arial, Bold, kuning, garis tepi hitam, ukuran 13
        val args = arrayListOf(
            "--freetype-font=Arial",
            "--freetype-bold",
            "--freetype-color=16776960",
            "--freetype-outline-color=0",
            "--freetype-outline-thickness=4",
            "--freetype-fontsize=13"
        )
        libVLC = LibVLC(this, args)
        player = MediaPlayer(libVLC)
        val videoLayout = findViewById<VLCVideoLayout>(R.id.videoLayout)
        player.attachViews(videoLayout, null, true, false)
        setupBrightnessGesture(videoLayout)
        volumeControlStream = AudioManager.STREAM_MUSIC
        player.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EndReached) stopped = true
            // Volume/boost baru efektif setelah output audio aktif, jadi pasang ulang di sini
            if (ev.type == MediaPlayer.Event.Playing) runOnUiThread { applyBoost() }
        }

        intent.data?.let { uri ->
            loadVideo(uri)
            intent.getStringExtra("sub")?.let { path ->
                player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(path)), true)
            }
        }

        setupControls()
        setupVolume()
        setupMenus()
        handler.post(tick)
    }

    // ---------- kecerahan layar (geser atas/bawah) ----------
    private val hideBrightInfo = Runnable {
        findViewById<View>(R.id.brightInfo).visibility = View.GONE
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        val x = v.coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = x
        window.attributes = lp
        val info = findViewById<TextView>(R.id.brightInfo)
        info.text = "Kecerahan: ${(x * 100).roundToInt()}%"
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideBrightInfo)
        handler.postDelayed(hideBrightInfo, 1000)
    }

    /** Geser ke atas = lebih terang, ke bawah = lebih gelap. Sentuh sekali = tampil/sembunyi kontrol. */
    private fun setupBrightnessGesture(layout: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var startB = 0f
        var moved = false
        layout.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.y; startB = currentBrightness(); moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - e.y
                    if (abs(dy) > slop) moved = true
                    if (moved && v.height > 0) setBrightness(startB + dy / v.height)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleChrome()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- layar penuh ----------
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            // Isi juga area poni/kamera di tepi layar
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // Terapkan ulang setelah menu/dialog ditutup supaya tetap penuh
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    // ---------- memuat video & pindah video ----------
    private fun loadVideo(uri: Uri) {
        player.stop()
        val oldMedia = mediaRef
        val oldPfd = pfd
        uriKey = uri.toString()
        videoName = displayName(uri)
        findViewById<TextView>(R.id.tvTitle).text = videoName
        trackInfo = ""
        stopped = false
        subtitleOn = true
        lastSpu = -1
        updateSubButton()
        pfd = contentResolver.openFileDescriptor(uri, "r")
        pfd?.let {
            val media = Media(libVLC, it.fileDescriptor)
            mediaRef = media
            player.media = media
            player.play()
        }
        oldMedia?.release()
        oldPfd?.close()
    }

    private var pendingNav = 0

    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) navigate(pendingNav)
        else Toast.makeText(this, "Izin dibutuhkan untuk pindah video", Toast.LENGTH_SHORT).show()
    }

    private fun readPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** dir = -1 video sebelumnya, 1 video berikutnya (satu folder, urut nama). */
    private fun navigate(dir: Int) {
        if (ContextCompat.checkSelfPermission(this, readPermission()) != PackageManager.PERMISSION_GRANTED) {
            pendingNav = dir
            askPerm.launch(readPermission())
            return
        }
        Thread {
            val (target, msg) = findNeighbor(dir)
            runOnUiThread {
                if (target != null) loadVideo(target)
                else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private data class VRow(val id: Long, val name: String, val path: String, val size: Long)

    private fun findNeighbor(dir: Int): Pair<Uri?, String> {
        val rows = ArrayList<VRow>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE
        )
        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                rows.add(VRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
            }
        }
        val size = pfd?.statSize ?: -1L
        val cands = rows.filter { it.name == videoName }
        val cur = cands.firstOrNull { it.size == size } ?: cands.firstOrNull()
            ?: return Pair(null, "Video ini tidak ditemukan di penyimpanan HP")
        val folder = File(cur.path).parent
        val list = rows.filter { File(it.path).parent == folder }.sortedBy { it.name.lowercase() }
        val i = list.indexOfFirst { it.id == cur.id }
        val j = i + dir
        if (j < 0) return Pair(null, "Ini video pertama di folder")
        if (j >= list.size) return Pair(null, "Ini video terakhir di folder")
        return Pair(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, list[j].id), "")
    }

    // ---------- kontrol ----------
    private fun setupControls() {
        btnPlay.setOnClickListener {
            if (stopped) { player.stop(); player.play(); stopped = false }
            else if (player.isPlaying) player.pause() else player.play()
        }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            player.stop(); stopped = true; seek.progress = 0f
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener { navigate(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { navigate(1) }
        findViewById<View>(R.id.btnRew).setOnClickListener { skip(-10_000) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { skip(10_000) }

        seek.onSeek = { p ->
            val len = player.length
            if (len > 0) player.time = (len * p).toLong()
        }

        // Subtitle on/off
        btnSub.setOnClickListener {
            if (subtitleOn) {
                lastSpu = player.spuTrack
                player.spuTrack = -1
            } else {
                val first = player.spuTracks?.firstOrNull { it.id != -1 }?.id ?: -1
                player.spuTrack = if (lastSpu != -1) lastSpu else first
            }
            subtitleOn = !subtitleOn
            updateSubButton()
        }
    }

    private fun updateSubButton() {
        btnSub.text = if (subtitleOn) "Subtitle: ON" else "Subtitle: OFF"
    }

    // ---------- volume ----------
    // Volume dasar = volume media Android, jadi saat aplikasi dibuka mengikuti volume bawaan HP.
    // Boost = penguat tambahan di dalam pemutar: 200% atau 400%.
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var lastIdx = 0
    private var boostLevel = 0          // 0 = mati, 200, 400
    private var eq: MediaPlayer.Equalizer? = null
    private var refreshVolumeUi: (() -> Unit)? = null

    private fun maxIdx() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private fun sysIdx() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    private fun sysPercent() = (sysIdx() * 100f / maxIdx()).roundToInt()
    private fun setSysIdx(i: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, i.coerceIn(0, maxIdx()), 0)
    }

    private fun wantedPlayerVolume() = if (boostLevel > 0) 200 else 100

    /**
     * 200% = penguat perangkat lunak pemutar (200).
     * 400% = penguat 200 ditambah preamp equalizer +6 dB (2x lagi).
     */
    private fun applyBoost() {
        if (player.volume != wantedPlayerVolume()) player.volume = wantedPlayerVolume()
        if (boostLevel >= 400) {
            val e = eq ?: MediaPlayer.Equalizer.create().also { eq = it }
            e.preAmp = 6f
            player.setEqualizer(e)
        } else if (eq != null) {
            player.setEqualizer(null)
            eq = null
        }
    }

    private fun setupVolume() {
        val box = findViewById<VolumeBoxView>(R.id.volumeBox)
        val btnBoost = findViewById<BoostButtonView>(R.id.btnBoost)
        val volNumber = findViewById<TextView>(R.id.volNumber)
        val imgVol = findViewById<ImageView>(R.id.imgVol)

        // Tampilan selalu mengikuti volume media HP (termasuk saat tombol fisik ditekan)
        fun render() {
            val p = sysPercent()
            box.level = p
            volNumber.text = p.toString()
            imgVol.alpha = if (p > 0) 1f else 0.4f
        }
        refreshVolumeUi = { render() }
        render()

        // Kotak hijau: sentuh/geser untuk mengatur volume
        box.onChange = { lvl ->
            setSysIdx((lvl * maxIdx() / 100f).roundToInt())
            render()
        }

        // Boost: putih = mati, merah = 200% / 400%
        btnBoost.onLevel = { lv ->
            boostLevel = lv
            applyBoost()
        }

        // Dua tombol kiri-tengah: atas = besarkan, bawah = kecilkan. Tahan untuk terus berubah.
        fun bindStep(btn: View, delta: Int) {
            val repeat = object : Runnable {
                override fun run() {
                    setSysIdx(sysIdx() + delta)
                    render()
                    handler.postDelayed(this, 150)
                }
            }
            btn.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setSysIdx(sysIdx() + delta)
                        render()
                        handler.postDelayed(repeat, 450)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(repeat); v.performClick(); true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(repeat); true
                    }
                    else -> false
                }
            }
        }
        bindStep(findViewById<View>(R.id.volUp), 1)
        bindStep(findViewById<View>(R.id.volDown), -1)

        // Ikon speaker di baris bawah: mute / unmute
        imgVol.setOnClickListener {
            if (sysIdx() > 0) { lastIdx = sysIdx(); setSysIdx(0) }
            else setSysIdx(if (lastIdx > 0) lastIdx else maxIdx())
            render()
        }
    }

    private fun skip(ms: Long) {
        val len = player.length
        val t = (player.time + ms).coerceAtLeast(0)
        player.time = if (len > 0) t.coerceAtMost(len - 500) else t
    }

    // ---------- subtitle ----------
    private fun addSubtitleFile(f: File) {
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
        subtitleOn = true
        updateSubButton()
        Toast.makeText(this, "Subtitle dimuat", Toast.LENGTH_SHORT).show()
    }

    private fun downloadSubtitle() {
        val query = SubtitleDownloader.cleanQuery(videoName)
        if (!SubtitleConfig.configured) {
            AlertDialog.Builder(this)
                .setTitle("Download Subtitle")
                .setMessage(
                    "Pencarian otomatis butuh akun OpenSubtitles. Isi API key, username, dan " +
                    "password di SubtitleConfig (file SubtitleDownloader.kt).\n\n" +
                    "Sementara, subtitle untuk \"$query\" bisa dicari lewat browser."
                )
                .setPositiveButton("Cari di browser") { _, _ ->
                    val q = URLEncoder.encode("$query subtitle indonesia", "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        Toast.makeText(this, "Mencari subtitle: $query", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = SubtitleDownloader.download(query, cacheDir)
                runOnUiThread { addSubtitleFile(f) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- pilih track ----------
    private fun showTracks(
        title: String,
        tracks: Array<MediaPlayer.TrackDescription>?,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        if (tracks == null || tracks.isEmpty()) {
            Toast.makeText(this, "Tidak ada track", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { it.name ?: "Track ${it.id}" }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == current }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(names, checked) { d, which ->
                onPick(tracks[which].id)
                d.dismiss()
            }
            .show()
    }

    // ---------- menu ----------
    private fun menu(anchor: View, vararg items: Pair<String, () -> Unit>) {
        val pm = PopupMenu(this, anchor)
        items.forEachIndexed { i, item -> pm.menu.add(0, i, i, item.first) }
        pm.setOnMenuItemClickListener { mi -> items[mi.itemId].second(); true }
        pm.show()
    }

    private fun setupMenus() {
        findViewById<View>(R.id.mFile).setOnClickListener { v ->
            menu(v,
                "Subtitle" to {
                    menu(v,
                        "Load Subtitle" to { pickSub.launch(arrayOf("*/*")) },
                        "Download Subtitle" to { downloadSubtitle() })
                },
                "Buka video lain" to { finish() },
                "Keluar" to { finishAffinity() })
        }
        findViewById<View>(R.id.mView).setOnClickListener { v ->
            menu(v,
                "Rasio: Best fit" to { player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT },
                "Rasio: Isi layar" to { player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL },
                "Rasio: 16:9" to { player.videoScale = MediaPlayer.ScaleType.SURFACE_16_9 },
                "Rasio: 4:3" to { player.videoScale = MediaPlayer.ScaleType.SURFACE_4_3 },
                "Ukuran asli" to { player.videoScale = MediaPlayer.ScaleType.SURFACE_ORIGINAL },
                "Tampilkan/sembunyikan kontrol" to { toggleChrome() })
        }
        findViewById<View>(R.id.mPlay).setOnClickListener { v ->
            menu(v,
                "Audio Track" to {
                    showTracks("Audio Track", player.audioTracks, player.audioTrack) {
                        player.audioTrack = it
                    }
                },
                "Subtitle Track" to {
                    showTracks("Subtitle Track", player.spuTracks, player.spuTrack) { id ->
                        player.spuTrack = id
                        subtitleOn = id != -1
                        if (id != -1) lastSpu = id
                        updateSubButton()
                    }
                })
        }
        findViewById<View>(R.id.mNav).setOnClickListener { v ->
            menu(v,
                "Previous" to { navigate(-1) },
                "Next" to { navigate(1) })
        }
        findViewById<View>(R.id.mFav).setOnClickListener { v ->
            menu(v,
                "Tambah posisi ini ke favorit" to {
                    prefs.edit().putLong(uriKey, player.time).apply()
                },
                "Lompat ke posisi favorit" to {
                    val t = prefs.getLong(uriKey, -1L)
                    if (t >= 0) player.time = t
                })
        }
        findViewById<View>(R.id.mHelp).setOnClickListener { v ->
            menu(v, "About" to {
                AlertDialog.Builder(this)
                    .setTitle("About")
                    .setMessage(
                        "Aplikasi ini hanya untuk hiburan saja tidak diperjual belikan\n\n" +
                        "Versi saat ini : V.1.0\n\n" +
                        "by. Anonim"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            })
        }
    }

    private fun toggleChrome() {
        val show = chrome[0].visibility != View.VISIBLE
        chrome.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
    }

    // ---------- status ----------
    private val tick = object : Runnable {
        override fun run() {
            val len = player.length
            val t = player.time
            if (len > 0) seek.progress = t.toFloat() / len
            tvTime.text = "${fmt(t)} / ${fmt(len)}"
            btnPlay.setImageResource(if (player.isPlaying) R.drawable.btn_pause else R.drawable.btn_play)
            refreshVolumeUi?.invoke()
            if (player.isPlaying && player.volume != wantedPlayerVolume()) player.volume = wantedPlayerVolume()
            val st = when {
                stopped -> "Stopped"
                player.isPlaying -> "Playing"
                else -> "Paused"
            }
            if (trackInfo.isEmpty()) trackInfo = buildTrackInfo()
            tvState.text = if (trackInfo.isEmpty()) st else "$st   $trackInfo"
            handler.postDelayed(this, 500)
        }
    }

    private fun buildTrackInfo(): String {
        val m = mediaRef ?: return ""
        var video = ""
        var audio = ""
        for (i in 0 until m.trackCount) {
            when (val t = m.getTrack(i)) {
                is IMedia.VideoTrack ->
                    if (video.isEmpty()) video = "[${trackName(t)} ${t.width}x${t.height}]"
                is IMedia.AudioTrack ->
                    if (audio.isEmpty()) audio = "[${trackName(t)} ${channels(t.channels)}]"
                else -> {}
            }
        }
        return "$video $audio".trim()
    }

    private fun trackName(t: IMedia.Track): String =
        t.description?.takeIf { it.isNotBlank() } ?: codecName(t.codec)

    private fun codecName(c: String?): String = when (c?.lowercase()) {
        "avc1", "h264" -> "H264"
        "hevc", "hvc1", "hev1" -> "HEVC"
        "mp4v" -> "MPEG4"
        "vp09", "vp9" -> "VP9"
        "vp80", "vp8" -> "VP8"
        "av01" -> "AV1"
        "mp4a", "aac" -> "AAC"
        "a52", "a52 ", "ac-3" -> "AC3"
        "eac3", "ec-3" -> "EAC3"
        "mpga", "mp3" -> "MP3"
        "dts", "dtsh" -> "DTS"
        "opus" -> "Opus"
        "vorb" -> "Vorbis"
        "flac" -> "FLAC"
        else -> c?.uppercase() ?: "?"
    }

    private fun channels(n: Int) = when (n) { 6 -> "5.1"; 8 -> "7.1"; else -> "$n.0" }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        }
        return uri.lastPathSegment ?: "Video"
    }

    override fun onStop() { super.onStop(); player.pause() }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        player.release(); mediaRef?.release(); libVLC.release(); pfd?.close()
    }
}
