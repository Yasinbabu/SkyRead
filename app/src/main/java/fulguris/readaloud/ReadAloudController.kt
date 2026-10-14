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
import android.webkit.WebView
import fulguris.js.ReadAloudAutoScroll
import timber.log.Timber
import java.io.IOException

class ReadAloudController(
    private val context: Context,
    private val autoScrollJs: ReadAloudAutoScroll,
    private val speechRate: Float = 1.0f,
    private val speakerId: Int = 0
) {

    interface Callback {
        fun onProgress(charIndex: Int, charLength: Int)
        fun onCompleted()
        fun onError(errorMessage: String)
    }

    private var callback: Callback? = null
    private var webView: WebView? = null
    private var ttsEngine: KokoroTTSEngine? = null
    private var isSpeaking = false
    private var currentText: String = ""
    private var currentCharIndex = 0
    private var currentSentenceIndex = 0
    private val sentences = mutableListOf<String>()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun attachWebView(webView: WebView?) {
        this.webView = webView
        injectAutoScrollScript()
    }

    fun detachWebView() {
        this.webView = null
    }

    private fun injectAutoScrollScript() {
        val wv = webView ?: return
        wv.evaluateJavascript(autoScrollJs.provideJs(), null)
    }

    private fun readReadabilityJs(): String? {
        return try {
            context.assets.open("js/readability.js").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Timber.e(e, "readability.js not found in assets")
            null
        }
    }

    fun extractAndSpeak() {
        val wv = webView ?: return

        val readabilityJs = readReadabilityJs()
        if (readabilityJs == null) {
            callback?.onError("Readability.js not found in assets")
            return
        }

        val extractionJs = """
            (function() {
                try {
                    var reader = new Readability(document);
                    var article = reader.parse();
                    if (!article || !article.textContent) {
                        return JSON.stringify({error: 'Could not parse article'});
                    }
                    var text = article.textContent.trim();
        """.trimIndent()

        val combinedJs = readabilityJs + "\n" + extractionJs + """
                    var paragraphs = text.split(/\n+/);
                    var i = 0;
                    var walker = document.createTreeWalker(
                        document.body,
                        NodeFilter.SHOW_ELEMENT,
                        {
                            acceptNode: function(node) {
                                return node.tagName === 'P' ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
                            }
                        },
                        false
                    );
                    var node = walker.nextNode();
                    while (node && i < paragraphs.length) {
                        node.setAttribute('data-skyread-para', String(i));
                        node.innerHTML = '<span id="skyread-span-' + i + '">' + node.innerHTML + '</span>';
                        node = walker.nextNode();
                        i++;
                    }
                    return JSON.stringify({text: text, paragraphCount: paragraphs.length});
                } catch(e) {
                    return JSON.stringify({error: e instanceof Error ? e.message : String(e)});
                }
            })();
        """.trimIndent()

        wv.evaluateJavascript(combinedJs) { result ->
            try {
                val jsonResult = result?.let { 
                    val unquoted = it.removeSurrounding("\"", "")
                    unquoted
                }
                if (jsonResult.isNullOrEmpty()) {
                    callback?.onError("No result from page")
                    return@evaluateJavascript
                }
                val json = org.json.JSONObject(jsonResult)
                if (json.has("error")) {
                    callback?.onError(json.getString("error"))
                    return@evaluateJavascript
                }
                currentText = json.getString("text")
                sentences.clear()
                sentences.addAll(currentText.split(Regex("[。！？.!?]\\s*")).filter { it.isNotBlank() })
                if (sentences.isEmpty()) {
                    sentences.add(currentText)
                }
                startSpeaking()
            } catch (e: Exception) {
                callback?.onError(e.message ?: "Parse error")
            }
        }
    }

    private fun startSpeaking() {
        if (sentences.isEmpty()) {
            callback?.onError("No text to speak")
            return
        }

        isSpeaking = true
        currentCharIndex = 0
        currentSentenceIndex = 0

        try {
            ttsEngine = KokoroTTSEngine.getInstance(
                context = context,
                speakerId = speakerId,
                speed = speechRate
            )
        } catch (e: Exception) {
            callback?.onError("TTS init failed: ${e.message}")
            isSpeaking = false
            return
        }

        ttsEngine?.setOnSpeechEventListener(object : KokoroTTSEngine.OnSpeechEventListener {
            override fun onStarted() {}
            override fun onCompleted() {
                uiHandler.post {
                    speakNextSentence()
                }
            }
            override fun onError(errorMessage: String) {
                uiHandler.post {
                    stop()
                    callback?.onError(errorMessage)
                }
            }
        })

        speakNextSentence()
    }

    private fun speakNextSentence() {
        val engine = ttsEngine ?: run {
            isSpeaking = false
            callback?.onCompleted()
            return
        }

        if (currentSentenceIndex >= sentences.size) {
            isSpeaking = false
            callback?.onCompleted()
            return
        }

        val sentence = sentences[currentSentenceIndex]
        val startCharIndex = sentence.length * currentSentenceIndex

        uiHandler.post {
            val spanId = "skyread-span-$currentSentenceIndex"
            webView?.evaluateJavascript(
                "(function() { var el = document.getElementById('$spanId'); if (el) { el.scrollIntoView({behavior: 'smooth', block: 'center'}); } })()",
                null
            )
        }

        engine.speak(sentence)
        currentCharIndex = startCharIndex + sentence.length
        callback?.onProgress(currentCharIndex, currentText.length)
        currentSentenceIndex++
    }

    fun stop() {
        ttsEngine?.stop()
        isSpeaking = false
        currentSentenceIndex = 0
        currentCharIndex = 0
    }

    fun release() {
        stop()
        KokoroTTSEngine.releaseInstance()
        webView = null
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun setSpeechRate(rate: Float) {
        ttsEngine?.speed = rate
    }
}
