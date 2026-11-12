package com.artforge.mobile.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import com.artforge.mobile.core.util.ForgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Decoded mono waveform + metadata for the timeline audio track. */
data class WaveformData(
    val peaks: FloatArray,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
) {
    /** Peak at a normalised position (0..1) — used by the timeline waveform painter. */
    fun peakAt(fraction: Float): Float {
        if (peaks.isEmpty()) return 0f
        val idx = (fraction.coerceIn(0f, 0.9999f) * peaks.size).toInt()
        return peaks[idx]
    }
}

/** A loaded audio clip attached to the project. */
data class AudioClip(
    val id: String,
    val file: File,
    val displayName: String,
    val waveform: WaveformData?,
    val volume: Float = 1f,
    val muted: Boolean = false,
)

/**
 * Real audio playback and waveform extraction using Android MediaPlayer and
 * MediaExtractor/MediaCodec. Supports whatever containers the device decodes
 * (WAV, MP3, OGG/Opus, M4A/AAC) and generates waveform peaks for the editor.
 */
class AudioEngine(private val context: Context) {

    private var player: MediaPlayer? = null
    private var currentClip: AudioClip? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun loadClip(id: String, file: File, displayName: String = file.name): AudioClip {
        val waveform = readWaveform(file)
        return AudioClip(id = id, file = file, displayName = displayName, waveform = waveform)
    }

    /** Extracts a peak envelope from any decodable audio file. */
    fun readWaveform(file: File, buckets: Int = 1024): WaveformData? {
        if (!file.exists()) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val samples = ArrayList<Float>(buckets * 4)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeout = 5000L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeout)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, timeout)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outputIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            while (shorts.remaining() > 0) {
                                var sum = 0f
                                val frames = shorts.remaining() / channels.coerceAtLeast(1)
                                val take = minOf(frames, 64)
                                for (f in 0 until take) {
                                    for (c in 0 until channels) {
                                        if (shorts.remaining() > 0) sum += abs(shorts.get().toFloat())
                                    }
                                }
                                samples.add(sum / (take * channels).coerceAtLeast(1))
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (sawInputEos) sawOutputEos = true
                }
            }
            codec.stop()
            codec.release()

            val peaks = FloatArray(buckets)
            if (samples.isNotEmpty()) {
                val per = max(1, samples.size / buckets)
                for (i in 0 until buckets) {
                    var maxV = 0f
                    var sumSq = 0.0
                    var count = 0
                    for (j in 0 until per) {
                        val idx = i * per + j
                        if (idx >= samples.size) break
                        maxV = max(maxV, samples[idx])
                        sumSq += samples[idx] * samples[idx].toDouble()
                        count++
                    }
                    peaks[i] = if (count == 0) 0f else ((maxV * 0.6f + sqrt(sumSq / count).toFloat() * 0.4f) / 32768f).coerceIn(0f, 1f)
                }
            }
            WaveformData(
                peaks = peaks,
                durationMs = durationUs / 1000L,
                sampleRate = sampleRate,
                channels = channels,
            )
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "waveform extraction failed for ${file.name}", t)
            // Fall back to a lightweight player-based duration with an empty waveform.
            try {
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    WaveformData(FloatArray(buckets), mp.duration.toLong(), 44100, 2)
                } finally {
                    runCatching { mp.release() }
                }
            } catch (_: Throwable) {
                null
            }
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    /** Copies a content URI into app cache so the project owns its audio. */
    suspend fun importUri(uri: Uri, targetDir: File, name: String): File? = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(targetDir, if (safeName.contains('.')) safeName else "$safeName.wav")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            } ?: return@withContext null
            outFile
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "audio import failed", t)
            null
        }
    }

    fun play(clip: AudioClip, startMs: Int = 0, volume: Float = clip.volume) {
        stop()
        try {
            player = MediaPlayer().apply {
                setDataSource(clip.file.absolutePath)
                prepare()
                seekTo(startMs)
                setVolume(if (clip.muted) 0f else volume, if (clip.muted) 0f else volume)
                start()
            }
            currentClip = clip
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "playback failed", t)
        }
    }

    fun seekTo(ms: Int) {
        try {
            player?.seekTo(ms)
        } catch (_: Throwable) {
            Unit
        }
    }

    /** Seeks and resumes playback in sync with timeline playback. */
    fun syncTo(ms: Int, volume: Float, muted: Boolean) {
        val p = player ?: return
        try {
            p.setVolume(if (muted) 0f else volume, if (muted) 0f else volume)
            if (abs(p.currentPosition - ms) > 90) p.seekTo(ms)
            if (!p.isPlaying) p.start()
        } catch (_: Throwable) {
            Unit
        }
    }

    fun pause() {
        try {
            player?.pause()
        } catch (_: Throwable) {
            Unit
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (_: Throwable) {
            Unit
        }
        player = null
        currentClip = null
    }

    fun setVolume(volume: Float) {
        try {
            player?.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
        } catch (_: Throwable) {
            Unit
        }
    }

    fun positionMs(): Int = try {
        player?.currentPosition ?: 0
    } catch (_: Throwable) {
        0
    }

    fun durationMs(): Int = try {
        player?.duration ?: 0
    } catch (_: Throwable) {
        0
    }

    // ---- Voice-over recording ---------------------------------------------

    fun startRecording(targetDir: File): File? {
        return try {
            targetDir.mkdirs()
            val file = File(targetDir, "vo_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            file
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "recording failed to start", t)
            releaseRecorder()
            null
        }
    }

    fun stopRecording(): File? {
        val file = recordFile
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "recording stop failed", t)
        }
        releaseRecorder()
        return if (file?.exists() == true && file.length() > 0) file else null
    }

    private fun releaseRecorder() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Throwable) {
            Unit
        }
        recorder = null
        recordFile = null
    }

    /** Trims a WAV file by writing a shortened copy (used by the audio editor). */
    fun trimWav(source: File, target: File, startMs: Long, endMs: Long): Boolean {
        return try {
            val bytes = source.readBytes()
            if (bytes.size < 44) return false
            val header = bytes.copyOfRange(0, 44)
            val dataStart = 44
            val byteRate = ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val startByte = (startMs * byteRate / 1000).toInt().coerceIn(0, bytes.size - dataStart)
            val endByte = if (endMs <= 0) bytes.size - dataStart else (endMs * byteRate / 1000).toInt().coerceAtMost(bytes.size - dataStart)
            val body = bytes.copyOfRange(dataStart + startByte, dataStart + endByte)
            val newHeader = header.copyOf()
            ByteBuffer.wrap(newHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + body.size)
            ByteBuffer.wrap(newHeader, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size)
            target.outputStream().use { out ->
                out.write(newHeader)
                out.write(body)
            }
            true
        } catch (t: Throwable) {
            ForgeLog.e(TAG, "wav trim failed", t)
            false
        }
    }

    fun release() {
        stop()
        releaseRecorder()
    }

    companion object {
        private const val TAG = "AudioEngine"

        val SUPPORTED_EXTENSIONS = listOf("wav", "mp3", "ogg", "oga", "opus", "m4a", "aac", "flac")

        fun isSupported(name: String): Boolean =
            SUPPORTED_EXTENSIONS.any { name.lowercase().endsWith(".$it") }
    }
}
