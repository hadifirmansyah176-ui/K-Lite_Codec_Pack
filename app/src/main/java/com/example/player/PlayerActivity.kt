package com.example.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var eq: MediaPlayer.Equalizer
    private var pfd: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())

    private var stopped = false
    private var subtitleOn = true
    private var lastSpu = -1
    private var bass = 0 // 0..200 (%)
    private var trackInfo = ""
    private var uriKey = ""

    private lateinit var seek: SeekBarView
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var chrome: List<View>
    private val prefs by lazy { getSharedPreferences("fav", MODE_PRIVATE) }

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
        chrome = listOf(R.id.titleBar, R.id.menuBar, R.id.seekBar, R.id.controlRow, R.id.statusBar)
            .map { findViewById<View>(it) }

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

        eq = MediaPlayer.Equalizer.create()
        player.setEqualizer(eq)

        intent.data?.let { uri ->
            uriKey = uri.toString()
            findViewById<TextView>(R.id.tvTitle).text = displayName(uri)
            pfd = contentResolver.openFileDescriptor(uri, "r")
            pfd?.let {
                val media = Media(libVLC, it.fileDescriptor)
                player.media = media
                media.release()
                player.play()
            }
            intent.getStringExtra("sub")?.let { path ->
                player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(path)), true)
            }
        }

        setupControls()
        setupMenus()
        handler.post(tick)
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
        findViewById<View>(R.id.btnPrev).setOnClickListener { player.time = 0 }
        findViewById<View>(R.id.btnNext).setOnClickListener { skip(60_000) }
        findViewById<View>(R.id.btnRew).setOnClickListener { skip(-10_000) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { skip(10_000) }
        findViewById<View>(R.id.btnStep).setOnClickListener {
            if (player.isPlaying) player.pause()
            player.nextFrame()
        }

        seek.onSeek = { p ->
            val len = player.length
            if (len > 0) player.time = (len * p).toLong()
        }

        // Subtitle on/off
        val btnSub = findViewById<TextView>(R.id.btnSub)
        btnSub.setOnClickListener {
            if (subtitleOn) {
                lastSpu = player.spuTrack
                player.spuTrack = -1
            } else {
                val first = player.spuTracks?.firstOrNull { it.id != -1 }?.id ?: -1
                player.spuTrack = if (lastSpu != -1) lastSpu else first
            }
            subtitleOn = !subtitleOn
            btnSub.text = if (subtitleOn) "Subtitle: ON" else "Subtitle: OFF"
        }

        // Bass: tiap tekan +20% sampai 200%, lalu kembali 0
        val btnBass = findViewById<TextView>(R.id.btnBass)
        btnBass.setOnClickListener {
            bass = if (bass >= 200) 0 else bass + 20
            applyBass()
            btnBass.text = "Bass $bass%"
        }

        // Volume 0-100
        val vol = findViewById<VolumeBarView>(R.id.volumeBar)
        vol.level = 100
        vol.onChange = { player.volume = it }
    }

    private fun skip(ms: Long) {
        val len = player.length
        val t = (player.time + ms).coerceAtLeast(0)
        player.time = if (len > 0) t.coerceAtMost(len - 500) else t
    }

    private fun applyBass() {
        val db = bass / 200f * 20f
        eq.setAmp(0, db)
        eq.setAmp(1, db * 0.8f)
        eq.setAmp(2, db * 0.5f)
        eq.preAmp = -db / 2f
        player.setEqualizer(eq)
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
                "Play / Pause" to { btnPlay.performClick() },
                "Stop" to { findViewById<View>(R.id.btnStop).performClick() },
                "Kecepatan 0.5x" to { player.rate = 0.5f },
                "Kecepatan 1x" to { player.rate = 1f },
                "Kecepatan 1.5x" to { player.rate = 1.5f },
                "Kecepatan 2x" to { player.rate = 2f })
        }
        findViewById<View>(R.id.mNav).setOnClickListener { v ->
            menu(v,
                "Mundur 10 detik" to { skip(-10_000) },
                "Maju 10 detik" to { skip(10_000) },
                "Maju 1 menit" to { skip(60_000) },
                "Ke awal" to { player.time = 0 })
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
            menu(v, "Tentang" to {
                AlertDialog.Builder(this)
                    .setTitle("K-Lite Codec Pack A")
                    .setMessage("Pemutar video berbasis libVLC.\nMendukung hampir semua format video.")
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
        val v = player.currentVideoTrack
        val a = player.currentAudioTrack
        val sb = StringBuilder()
        if (v != null) sb.append("[${codecName(v.codec)} ${v.width}x${v.height}]")
        if (a != null) sb.append(" [${codecName(a.codec)} ${channels(a.channels)}]")
        return sb.toString().trim()
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
        player.release(); libVLC.release(); pfd?.close()
    }
}
