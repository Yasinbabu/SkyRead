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

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class KokoroTTSEngine private constructor(
    private val tts: OfflineTts,
    private val sampleRate: Int
) {

    private var audioTrack: AudioTrack? = null
    private var isSpeaking = false
    private var currentText: String? = null
    @Volatile
    private var stopRequested = false

    interface OnSpeechEventListener {
        fun onStarted()
        fun onCompleted()
        fun onError(errorMessage: String)
    }

    private var speechListener: OnSpeechEventListener? = null

    companion object {
        private const val TAG = "KokoroTTSEngine"
        private const val MODEL_DIR_NAME = "kokoro"
        private const val MODEL_FILE = "model.onnx"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val LEXICON_US = "lexicon-us-en.txt"
        private const val LEXICON_ZH = "lexicon-zh.txt"
        private const val ASSET_MODEL_PATH = "kokoro/model.onnx"
        private const val ASSET_VOICES_PATH = "kokoro/voices.bin"
        private const val ASSET_TOKENS_PATH = "kokoro/tokens.txt"
        private const val ASSET_ESPEAK_PATH = "kokoro/espeak-ng-data"
        private const val ASSET_LEXICON_US = "kokoro/lexicon-us-en.txt"
        private const val ASSET_LEXICON_ZH = "kokoro/lexicon-zh.txt"

        @Volatile
        private var INSTANCE: KokoroTTSEngine? = null

        @Synchronized
        fun getInstance(context: Context, speakerId: Int = 0, speed: Float = 1.0f): KokoroTTSEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext, speakerId, speed).also { INSTANCE = it }
            }
        }

        fun releaseInstance() {
            INSTANCE?.release()
            INSTANCE = null
        }

        private fun create(context: Context, speakerId: Int, speed: Float): KokoroTTSEngine {
            val numThreads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 4 else 2
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$ASSET_MODEL_PATH",
                        voices = "$ASSET_VOICES_PATH",
                        tokens = "$ASSET_TOKENS_PATH",
                        dataDir = "$ASSET_ESPEAK_PATH",
                        lexicon = "$ASSET_LEXICON_US,$ASSET_LEXICON_ZH",
                        lang = "en",
                    ),
                    numThreads = numThreads,
                    debug = true,
                    provider = "cpu",
                )
            )

            val tts = try {
                OfflineTts(
                    assetManager = context.assets,
                    config = config
                )
            } catch (e: Exception) {
                Timber.e(e, "AssetManager init failed, falling back to internal storage")
                val internalDir = extractAssetsToInternalStorage(context)
                val fileConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "${internalDir.absolutePath}/$MODEL_FILE",
                            voices = "${internalDir.absolutePath}/$VOICES_FILE",
                            tokens = "${internalDir.absolutePath}/$TOKENS_FILE",
                            dataDir = "${internalDir.absolutePath}/$ESPEAK_DATA_DIR",
                            lexicon = "${internalDir.absolutePath}/$LEXICON_US,${internalDir.absolutePath}/$LEXICON_ZH",
                            lang = "en",
                        ),
                        numThreads = numThreads,
                        debug = true,
                        provider = "cpu",
                    )
                )
                OfflineTts(config = fileConfig)
            }

            val sampleRate = tts.sampleRate()
            val engine = KokoroTTSEngine(tts, sampleRate)
            engine.speakerId = speakerId
            engine.speed = speed
            return engine
        }

        private fun extractAssetsToInternalStorage(context: Context): File {
            val destDir = File(context.filesDir, MODEL_DIR_NAME)
            if (destDir.exists()) {
                return destDir
            }
            destDir.mkdirs()

            val assetFiles = context.assets.list(MODEL_DIR_NAME) ?: emptyArray()
            for (assetFile in assetFiles) {
                val assetPath = "$MODEL_DIR_NAME/$assetFile"
                val destFile = File(destDir, assetFile)
                if ((context.assets.list(assetPath) ?: emptyArray()).isNotEmpty()) {
                    copyAssetDir(context.assets, assetPath, destFile)
                } else {
                    copyAssetFile(context.assets, assetPath, destFile)
                }
            }

            return destDir
        }

        private fun copyAssetFile(assets: android.content.res.AssetManager, assetPath: String, destFile: File) {
            try {
                assets.open(assetPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Error copying asset: $assetPath")
            }
        }

        private fun copyAssetDir(assets: android.content.res.AssetManager, assetDir: String, destDir: File) {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val files = assets.list(assetDir) ?: return
            for (file in files) {
                val assetPath = "$assetDir/$file"
                val destFile = File(destDir, file)
                if ((assets.list(assetPath) ?: emptyArray()).isNotEmpty()) {
                    copyAssetDir(assets, assetPath, destFile)
                } else {
                    copyAssetFile(assets, assetPath, destFile)
                }
            }
        }
    }

    var speakerId: Int = 0
    var speed: Float = 1.0f

    fun setOnSpeechEventListener(listener: OnSpeechEventListener?) {
        this.speechListener = listener
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun speak(text: String) {
        if (isSpeaking) {
            stop()
        }
        stopRequested = false
        currentText = text
        isSpeaking = true
        speechListener?.onStarted()

        Thread {
            try {
                val genConfig = GenerationConfig(
                    speed = speed,
                    sid = speakerId,
                    silenceScale = 0.2f
                )

                val audio = tts.generateWithConfig(
                    text = text,
                    config = genConfig
                )

                if (stopRequested) {
                    isSpeaking = false
                    stopRequested = false
                    return@Thread
                }

                playAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                Timber.e(e, "Error generating speech")
                isSpeaking = false
                stopRequested = false
                speechListener?.onError(e.message ?: "TTS error")
            }
        }.start()
    }

    private fun playAudio(samples: FloatArray, sampleRateInHz: Int) {
        if (samples.isEmpty() || stopRequested) {
            isSpeaking = false
            stopRequested = false
            speechListener?.onCompleted()
            return
        }

        val bufferSize = samples.size * 2
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateInHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val actualBufferSize = maxOf(bufferSize, minBufferSize)

        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRateInHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
                actualBufferSize,
                AudioTrack.MODE_STREAM
            )

            this.audioTrack = track

            val shortBuffer = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = maxOf(-1.0f, minOf(1.0f, samples[i]))
            shortBuffer[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        track.write(shortBuffer, 0, shortBuffer.size)
        track.play()

        while (track.playState == AudioTrack.PLAYSTATE_PLAYING && !stopRequested) {
            Thread.sleep(100)
        }

        track.release()
        this.audioTrack = null
        isSpeaking = false
        val wasStopped = stopRequested
        stopRequested = false
        if (!wasStopped) {
            speechListener?.onCompleted()
        }
    }

    fun stop() {
        stopRequested = true
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isSpeaking = false
    }

    fun shutdown() {
        stop()
        release()
    }

    fun release() {
        stop()
        try {
            tts.release()
        } catch (e: Exception) {
            Timber.e(e, "Error releasing TTS")
        }
        isSpeaking = false
        currentText = null
    }

    fun getAvailableSpeakers(): List<String> {
        return try {
            val numSpeakers = tts.numSpeakers()
            (0 until numSpeakers).map { "Speaker $it" }
        } catch (e: Exception) {
            Timber.e(e, "Error getting speaker list")
            emptyList()
        }
    }
}
