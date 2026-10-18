/*
 * The contents of this file are subject to the Common Public Attribution License Version 1.0.
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * https://github.com/Slion/Fulguris/blob/main/LICENSE.CPAL-1.0.
 * The License is based on the Mozilla Public License Version 1.1, but Sections 14 and 15 have been
 * added to cover use of software over a computer network and provide for limited attribution for
 * the Original Developer. In addition, Exhibit A has been modified to be consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF
 * ANY KIND, either express or implied. See the License for the specific language governing rights
 * and limitations under the License.
 *
 * The Original Code is SkyRead.
 *
 * The Original Developer is the Initial Developer.
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 *
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

package fulguris.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fulguris.R
import timber.log.Timber

class SkyReadAudioService : Service() {

    companion object {
        private const val CHANNEL_ID = "skyread_audio_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "fulguris.readaloud.ACTION_START"
        const val ACTION_STOP = "fulguris.readaloud.ACTION_STOP"

        const val EXTRA_TEXT = "fulguris.readaloud.EXTRA_TEXT"
        const val EXTRA_SPEED = "fulguris.readaloud.EXTRA_SPEED"
        const val EXTRA_SPEAKER = "fulguris.readaloud.EXTRA_SPEAKER"
    }

    private var kokoroTTSEngine: KokoroTTSEngine? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                val speaker = intent.getIntExtra(EXTRA_SPEAKER, 0)
                startSpeaking(text, speed, speaker)
            }
            ACTION_STOP -> {
                stopSpeaking()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSpeaking()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SkyRead Read Aloud",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SkyRead")
            .setContentText("Reading aloud")
            .setSmallIcon(R.drawable.ic_tts)
            .setOngoing(true)
            .build()
    }

    private fun startSpeaking(text: String?, speed: Float, speakerId: Int) {
        if (text.isNullOrEmpty()) {
            Timber.w("No text to speak")
            stopSelf()
            return
        }

        try {
            kokoroTTSEngine = KokoroTTSEngine.getInstance(
                context = this,
                speakerId = speakerId,
                speed = speed
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TTS engine")
            stopSelf()
            return
        }

        kokoroTTSEngine?.setOnSpeechEventListener(object : KokoroTTSEngine.OnSpeechEventListener {
            override fun onStarted() {
                isRunning = true
                startForeground(NOTIFICATION_ID, createNotification())
            }

            override fun onCompleted() {
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            override fun onError(errorMessage: String) {
                Timber.e("TTS error: $errorMessage")
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        })

        startForeground(NOTIFICATION_ID, createNotification())
        kokoroTTSEngine?.speak(text)
    }

    private fun stopSpeaking() {
        kokoroTTSEngine?.stop()
        isRunning = false
    }

    fun isRunning(): Boolean = isRunning
}
