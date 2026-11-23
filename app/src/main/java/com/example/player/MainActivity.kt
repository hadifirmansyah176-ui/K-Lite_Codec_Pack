package com.example.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private val pick = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val index = 0
            startPlayer(uris, index)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btnPick = Button(this).apply {
            text = "Pilih video"
            setOnClickListener {
                pick.launch(arrayOf("video/*", "application/octet-stream"))
            }
        }

        val btnDemo = Button(this).apply {
            text = "Putar video demo (1 menit)"
            setOnClickListener { playDemo() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(btnPick)
            addView(btnDemo)
        })
    }

    private fun startPlayer(uris: List<Uri>, index: Int) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putParcelableArrayListExtra(
                "videoUris",
                ArrayList(uris)
            )
            putExtra("videoIndex", index)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun playDemo() {
        val video = File(cacheDir, "demo.mp4")
        val sub = File(cacheDir, "demo.srt")

        resources.openRawResource(R.raw.demo).use { input ->
            video.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        resources.openRawResource(R.raw.demo_srt).use { input ->
            sub.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        startActivity(Intent(this, PlayerActivity::class.java).apply {
            data = Uri.fromFile(video)
            putExtra("sub", sub.absolutePath)
        })
    }
}
