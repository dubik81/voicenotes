package com.example.voicenotes

import android.media.MediaPlayer

/** Простой плеер одного файла. Играет/останавливает WAV заметки. */
class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(path: String, onDone: () -> Unit): Boolean {
        stop()
        return try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { onDone(); this@AudioPlayer.stop() }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (_: Exception) { false }
}
