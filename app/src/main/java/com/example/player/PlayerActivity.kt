package com.example.player

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import android.net.Uri
import java.io.File
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : AppCompatActivity() {

    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var eq: MediaPlayer.Equalizer
    private var pfd: ParcelFileDescriptor? = null

    private var subtitleOn = true
    private var lastSpu = -1
    private var bass = 0 // 0..200 (%)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

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
        player.attachViews(findViewById<VLCVideoLayout>(R.id.videoLayout), null, true, false)

        eq = MediaPlayer.Equalizer.create()
        player.setEqualizer(eq)

        intent.data?.let { uri ->
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

        val btnSub = findViewById<Button>(R.id.btnSub)
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

        val btnBass = findViewById<Button>(R.id.btnBass)
        btnBass.setOnClickListener {
            bass = if (bass >= 200) 0 else bass + 20
            applyBass()
            btnBass.text = "Bass $bass%"
        }

        val vol = findViewById<VolumeBarView>(R.id.volumeBar)
        vol.level = 100
        vol.onChange = { player.volume = it }
    }

    private fun applyBass() {
        val db = bass / 200f * 20f
        eq.setAmp(0, db)
        eq.setAmp(1, db * 0.8f)
        eq.setAmp(2, db * 0.5f)
        eq.preAmp = -db / 2f
        player.setEqualizer(eq)
    }

    override fun onStop() { super.onStop(); player.pause() }

    override fun onDestroy() {
        super.onDestroy()
        player.release(); libVLC.release(); pfd?.close()
    }
}
