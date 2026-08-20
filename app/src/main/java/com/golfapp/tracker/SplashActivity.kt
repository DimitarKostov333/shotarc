package com.golfapp.tracker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Brief launch splash that animates a struck shot, then hands off to the setup wizard. */
class SplashActivity : AppCompatActivity() {

    private var advanced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splash = SplashView(this).apply { onDone = { proceed() } }
        setContentView(splash)
        // safety net if the animation never reports done
        splash.postDelayed({ proceed() }, 2600)
    }

    private fun proceed() {
        if (advanced) return
        advanced = true
        startActivity(Intent(this, SetupActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
