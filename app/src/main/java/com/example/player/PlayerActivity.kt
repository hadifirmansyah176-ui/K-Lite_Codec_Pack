package com.example.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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

    private var videoUris: ArrayList<Uri> = arrayListOf()
    private var videoIndex = 0

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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

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
        videoLayout.setOnClickListener { toggleChrome() }
        player.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EndReached) stopped = true
        }

        intent.data?.let { uri ->
        videoUris =
            intent.getParcelableArrayListExtra<Uri>("videoUris") ?: arrayListOf()

        videoIndex = intent.getIntExtra("videoIndex", 0)

        if (videoUris.isNotEmpty()) {
        val uri = videoUris[videoIndex.coerceIn(videoUris.indices)]
        openVideo(uri)
        } else {
            intent.data?.let { uri ->
                openVideo(uri)
    }
}

        setupControls()
        setupVolume()
        setupMenus()
        handler.post(tick)

        private fun openVideo(uri: Uri) {
    player.stop()
    pfd?.close()
    mediaRef?.release()

    uriKey = uri.toString()
    videoName = displayName(uri)
    findViewById<TextView>(R.id.tvTitle).text = videoName
    stopped = false
    trackInfo = ""

    pfd = contentResolver.openFileDescriptor(uri, "r")
    pfd?.let {
        val media = Media(libVLC, it.fileDescriptor)
        mediaRef = media
        player.media = media
        player.play()
    }
}

private fun playPrevious() {
    if (videoUris.isEmpty()) {
        player.time = 0
        return
    }

    videoIndex =
        if (videoIndex > 0) videoIndex - 1
        else videoUris.lastIndex

    openVideo(videoUris[videoIndex])
}

private fun playNext() {
    if (videoUris.isEmpty()) {
        skip(60_000)
        return
    }

    videoIndex =
        if (videoIndex < videoUris.lastIndex) videoIndex + 1
        else 0

    openVideo(videoUris[videoIndex])
}
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
        findViewById<View>(R.id.btnPrev).setOnClickListener { playPrevious() }
        findViewById<View>(R.id.btnNext).setOnClickListener { playNext() }
        findViewById<View>(R.id.btnStep).setOnClickListener {
            if (player.isPlaying) player.pause()
            player.time = player.time + 40
        }

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
    private var lastVol = 100
    private var boost = false

    /** Volume efektif: 0-100, atau 0-200 bila boost aktif. */
    private fun applyVolume(level: Int) {
        player.volume = if (boost) level * 2 else level
    }

    private fun setupVolume() {
        val box = findViewById<VolumeBoxView>(R.id.volumeBox)
        val btnBoost = findViewById<BoostButtonView>(R.id.btnBoost)
        val volNumber = findViewById<TextView>(R.id.volNumber)
        val imgVol = findViewById<ImageView>(R.id.imgVol)

        fun render() {
            volNumber.text = box.level.toString()
            imgVol.alpha = if (box.level > 0) 1f else 0.4f
        }
        fun setLevel(l: Int) {
            box.level = l.coerceIn(0, 100)
            applyVolume(box.level)
            render()
        }

        box.level = 100
        render()
        box.onChange = { applyVolume(it); render() }

        // Boost: merah = aktif (sampai 200%), putih = nonaktif
        btnBoost.onToggle = { on ->
            boost = on
            box.multiplier = if (on) 2 else 1
            applyVolume(box.level)
        }

        // Dua tombol volume kiri-tengah: atas = besarkan, bawah = kecilkan (0-100).
        // Tahan tombol untuk mengubah terus-menerus.
        fun bindStep(btn: View, delta: Int) {
            val repeat = object : Runnable {
                override fun run() {
                    setLevel(box.level + delta)
                    handler.postDelayed(this, 120)
                }
            }
            btn.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setLevel(box.level + delta)
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
        bindStep(findViewById<View>(R.id.volUp), 5)
        bindStep(findViewById<View>(R.id.volDown), -5)

        // Ikon speaker di baris bawah: sentuh = mute / unmute
        imgVol.setOnClickListener {
            if (box.level > 0) { lastVol = box.level; setLevel(0) }
            else setLevel(if (lastVol > 0) lastVol else 100)
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
        "Previous" to { playPrevious() },
        "Next" to { playNext() })
}
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
            btnPlay.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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
                    if (video.isEmpty()) video = "[${codecName(t.codec)} ${t.width}x${t.height}]"
                is IMedia.AudioTrack ->
                    if (audio.isEmpty()) audio = "[${codecName(t.codec)} ${channels(t.channels)}]"
                else -> {}
            }
        }
        return "$video $audio".trim()
    }

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
