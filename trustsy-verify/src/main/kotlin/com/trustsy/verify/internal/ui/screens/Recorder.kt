package com.trustsy.verify.internal.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.trustsy.verify.internal.liveness.LivenessEffects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The run video and the flash clip (contract §4 Liveness 6–8). CameraX allows
 * ONE active recording per [VideoCapture], so the run recording is closed
 * before the flash clip opens. Files live in the app's cache (container-local)
 * and are deleted by [discard] once uploaded or abandoned — they are faces.
 *
 * [videoCapture] is null when the device could not bind it next to preview and
 * analysis; video is optional on the wire, so the run continues without it.
 */
internal class CameraRecorder(private val context: Context, var videoCapture: VideoCapture<Recorder>?) : LivenessEffects {
    private val dir = File(context.cacheDir, "trustsy").apply { mkdirs() }
    val runFile = File(dir, "liveness-${System.nanoTime()}.mp4")
    val flashFile = File(dir, "liveness-flash-${System.nanoTime()}.mp4")

    private var recording: Recording? = null
    private var finalized: CompletableDeferred<Boolean>? = null
    private val ok = mutableMapOf<File, Boolean>()

    /** Called under the controller's lock from the analysis thread: hop to main. */
    override fun startRunRecording() {
        ContextCompat.getMainExecutor(context).execute { start(runFile) }
    }

    override suspend fun stopRunRecording() = stop()

    /** Starts at once on the calling (main) thread, so the first colour and the clip begin together. */
    fun startFlashClip() = start(flashFile)

    suspend fun stop() {
        val done = finalized ?: return
        withContext(Dispatchers.Main) { recording?.stop() }
        withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { done.await() }
        recording = null
        finalized = null
    }

    /** The file, when a recording into it finished cleanly and has bytes. */
    fun usable(file: File): File? = file.takeIf { ok[it] == true && it.length() > 0 }

    fun discard() {
        recording?.stop()
        runFile.delete()
        flashFile.delete()
    }

    @SuppressLint("MissingPermission") // no audio is recorded
    private fun start(file: File) {
        val capture = videoCapture ?: return
        if (recording != null) return
        val done = CompletableDeferred<Boolean>()
        finalized = done
        ok[file] = false
        recording = runCatching {
            capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        ok[file] = !event.hasError()
                        done.complete(!event.hasError())
                    }
                }
        }.getOrElse {
            done.complete(false)
            null
        }
    }

    private companion object {
        const val FINALIZE_TIMEOUT_MS = 4_000L
    }
}
