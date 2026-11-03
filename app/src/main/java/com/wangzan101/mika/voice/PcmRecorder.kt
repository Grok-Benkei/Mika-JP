package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * は 16k モノラル 16 ビット PCM を記録します。 [level] (0..1、各ブロックの RMS によって正規化) は開始後 50ms ごとに更新されます。
 * stop は、これまでのすべての PCM を返します。 [maxMs] は、録画がいっぱいになると自動的に停止し、その後再び停止しても、録画されたコンテンツを返します。
 * 録音許可は、最初に呼び出し側によってチェックされます。ここでマイクを取得できない場合は false を返します。 stop は短時間ブロックするため (読み取りループが終了するのを待機します)、メインスレッドで呼び出さないでください。
 */
class PcmRecorder(private val maxMs: Int = 60_000, private val streaming: Boolean = false) : StreamingRecorder {
    // At most five seconds while a connection is opening. Never silently drop a sample.
    private val audio = Channel<ShortArray>(100)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission") // 権限は開始前に呼び出し元によってチェックされます
    override fun start(): Boolean {
        synchronized(lock) {
            if (record != null) return false
            val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val chunk = Wav.RATE / 20 // 50ms 1個
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, Wav.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, chunk * 2 * 4),
                )
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            try {
                rec.startRecording()
            } catch (_: IllegalStateException) {
                rec.release()
                return false
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return false
            }
            pcm = ShortArray(Wav.RATE * maxMs / 1000)
            size = 0
            _level.value = 0f
            record = rec
            job = scope.launch { readLoop(rec, chunk) }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int) {
        val buf = ShortArray(chunk)
        while (scope.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) {
                synchronized(lock) {
                    if (streaming && record === rec) audio.close(IOException("Audio capture failed"))
                }
                break // の読み取りが失敗したか、停止されました
            }
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val lvl = (sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0).toFloat()
            val full = synchronized(lock) {
                if (record !== rec) return // は停止/キャンセルされました
                // は、ゼロに戻った直後にキャンセルがこの作品のボリュームにかぶることを避けるために、ロックに入って停止していないことを確認した後に書き込まれます。
                _level.value = lvl
                val take = minOf(n, pcm.size - size)
                System.arraycopy(buf, 0, pcm, size, take)
                size += take
                if (streaming && audio.trySend(buf.copyOf(take)).isFailure) {
                    audio.close(IOException("Audio stream buffer exceeded"))
                    return@synchronized true
                }
                size >= pcm.size
            }
            if (full) break
        }
        // は、録音がいっぱいになると自動的に停止します。マイクを放し、次の停止のために録音内容を残しておきます。
        synchronized(lock) {
            if (record === rec) {
                release(rec)
                record = null
                _level.value = 0f // これ以上録音できません。最後の音量で波形を止めないでください。
            }
        }
        if (streaming) audio.close()
    }

    /** は停止し、これまでに記録されたすべての PCM を返します。録画中でなくても（自動停止含む）安心です。 */
    override fun stop(): ShortArray {
        val (rec, j) = synchronized(lock) {
            val r = record
            record = null
            if (r != null) release(r)
            r to job.also { job = null }
        }
        // 後で取得される PCM が変更されないことを確認するために、読み取りループが終了するまで待ちます。
        if (rec != null) runBlocking { j?.join() }
        if (streaming) audio.close()
        return synchronized(lock) {
            val out = pcm.copyOf(size)
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            out
        }
    }

    /** この録音を中止し、録音されたコンテンツを破棄します。繰り返し呼び出すことができます。 */
    override fun cancel() {
        synchronized(lock) {
            record?.let { release(it) }
            record = null
            job?.cancel()
            job = null
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }
}
