package com.sleepanalysis.ui.components

import android.media.MediaPlayer
import java.io.File

/**
 * Single-clip playback holder (plan T4e) shared by the report and labeling view models:
 * stop-then-play, release on completion/failure/clear — the exact semantics of the two
 * MediaPlayer copies it replaces. Playing-id changes are pushed to the owner's state via
 * [onPlayingChange] so each screen keeps its own StateFlow.
 */
class ClipPlayer(private val onPlayingChange: (Long?) -> Unit) {

    private var mediaPlayer: MediaPlayer? = null

    fun play(eventId: Long, file: File) {
        stopPlayback()
        if (!file.exists()) return
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    onPlayingChange(null)
                    release()
                }
                prepare()
                start()
            }
            onPlayingChange(eventId)
        }.onFailure {
            onPlayingChange(null)
            release()
        }
    }

    fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        release()
        onPlayingChange(null)
    }

    /** Releases the player — call from the ViewModel's onCleared. */
    fun release() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }
}
