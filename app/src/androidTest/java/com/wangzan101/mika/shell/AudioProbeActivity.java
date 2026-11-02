package com.wangzan101.mika.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;

/**
 * 音を発するプローブのページ: 開くと無音音がループ再生され (プレーヤーのステータスは「再生中」であり、聞こえません)、閉じると停止します。
 * 残りは {@link ShellProbeActivity} と同じです（画面とタスクをテストにレポートします）。タスクの最後に、「音を鳴らしているアプリをデフォルトでオフにする」ので、これをテストに使用します。
 */
public class AudioProbeActivity extends ShellProbeActivity {
    private static final int RATE = 8000;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        short[] silence = new short[RATE / 2];
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.length * 2)
                .build();
        track.write(silence, 0, silence.length);
        track.setLoopPoints(0, silence.length, -1);
        track.play();
    }

    @Override
    protected void onDestroy() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // は再生開始前にオフになりました
            }
            track.release();
            track = null;
        }
        super.onDestroy();
    }
}
