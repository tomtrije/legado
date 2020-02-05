package io.legado.app.service

import android.app.PendingIntent
import android.media.MediaPlayer
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.IntentHelp
import io.legado.app.help.http.HttpHelper
import io.legado.app.help.http.api.HttpPostApi
import io.legado.app.service.help.ReadBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.URLEncoder

class HttpReadAloudService : BaseReadAloudService(),
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnCompletionListener {

    private val mediaPlayer = MediaPlayer()
    private lateinit var ttsFolder: String
    private var job: Job? = null
    private var playingIndex = -1

    override fun onCreate() {
        super.onCreate()
        ttsFolder = cacheDir.absolutePath + File.separator + "bdTts"
        mediaPlayer.setOnErrorListener(this)
        mediaPlayer.setOnPreparedListener(this)
        mediaPlayer.setOnCompletionListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        mediaPlayer.release()
    }

    override fun newReadAloud(dataKey: String?, play: Boolean) {
        mediaPlayer.reset()
        job?.cancel()
        playingIndex = -1
        super.newReadAloud(dataKey, play)
    }

    override fun play() {
        if (contentList.isEmpty()) return
        if (nowSpeak == 0) {
            downloadAudio()
        } else {
            val file = getSpeakFile(nowSpeak)
            if (file.exists()) {
                playAudio(FileInputStream(file).fd)
            }
        }
    }

    private fun downloadAudio() {
        job = launch(IO) {
            FileUtils.deleteFile(ttsFolder)
            for (index in 0 until contentList.size) {
                if (isActive) {
                    val bytes = HttpHelper.getByteRetrofit("http://tts.baidu.com")
                        .create(HttpPostApi::class.java)
                        .postMapByteAsync(
                            "http://tts.baidu.com/text2audio",
                            getAudioBody(contentList[index]), mapOf()
                        ).await()
                        .body()
                    if (bytes != null && isActive) {
                        val file = getSpeakFile(index)
                        file.writeBytes(bytes)
                        if (index == nowSpeak) {
                            playAudio(FileInputStream(file).fd)
                        }
                    }
                } else {
                    break
                }
            }
        }
    }

    @Synchronized
    private fun playAudio(fd: FileDescriptor) {
        if (playingIndex != nowSpeak && requestFocus()) {
            playingIndex = nowSpeak
            try {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(fd)
                mediaPlayer.prepareAsync()
                postEvent(EventBus.TTS_START, readAloudNumber + 1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getSpeakFile(index: Int = nowSpeak): File {
        return FileUtils.createFileIfNotExist("${ttsFolder}${File.separator}${index}.mp3")
    }

    private fun getAudioBody(content: String): Map<String, String> {
        return mapOf(
            Pair("tex", encodeTwo(content)),
            Pair("spd", ((getPrefInt("ttsSpeechRate", 25) + 5) / 5).toString()),
            Pair("per", getPrefString(PreferKey.ttsSpeechPer) ?: "0"),
            Pair("cuid", "baidu_speech_demo"),
            Pair("idx", "1"),
            Pair("cod", "2"),
            Pair("lan", "zh"),
            Pair("ctp", "1"),
            Pair("pdt", "1"),
            Pair("vol", "5"),
            Pair("pit", "5"),
            Pair("_res_tag_", "audio")
        )
    }

    private fun encodeTwo(content: String): String {
        return try {
            URLEncoder.encode(URLEncoder.encode(content, "UTF-8"), "UTF-8")
        } catch (e: Exception) {
            " "
        }
    }

    override fun pauseReadAloud(pause: Boolean) {
        super.pauseReadAloud(pause)
        mediaPlayer.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (playingIndex == -1) {
            play()
        } else {
            mediaPlayer.start()
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        job?.cancel()
        mediaPlayer.reset()
        for (i in 0 until nowSpeak) {
            contentList.removeAt(0)
        }
        nowSpeak = 0
        playingIndex = -1
        play()
    }

    override fun prevP() {
        if (nowSpeak > 0) {
            mediaPlayer.stop()
            nowSpeak--
            readAloudNumber -= contentList[nowSpeak].length.minus(1)
            play()
        }
    }

    override fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            mediaPlayer.stop()
            readAloudNumber += contentList[nowSpeak].length.plus(1)
            nowSpeak++
            play()
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        super.play()
        if (pause) return
        mp?.start()
        textChapter?.let {
            if (readAloudNumber + 1 > it.getReadLength(pageIndex + 1)) {
                pageIndex++
                ReadBook.moveToNextPage()
            }
        }
        postEvent(EventBus.TTS_START, readAloudNumber + 1)
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        handler.postDelayed({
            readAloudNumber += contentList[nowSpeak].length + 1
            if (nowSpeak < contentList.lastIndex) {
                nowSpeak++
                play()
            } else {
                nextChapter()
            }
        }, 1000)
        return true
    }

    override fun onCompletion(mp: MediaPlayer?) {
        readAloudNumber += contentList[nowSpeak].length + 1
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            play()
        } else {
            nextChapter()
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return IntentHelp.servicePendingIntent<HttpReadAloudService>(this, actionStr)
    }
}