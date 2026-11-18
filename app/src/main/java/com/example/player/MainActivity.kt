package com.example.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Langsung membuka pemilih file video, lalu memutarnya di PlayerActivity. */
class MainActivity : AppCompatActivity() {

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick.launch(arrayOf("video/*", "application/octet-stream"))
    }
}
