package com.wangzan101.mika.shell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * シェル コントロール テスト用のプローブ ページ: ブロードキャストを使用して、画面、タスク、プロセス、および受信したクリックとキーのプロセスでテストをミカに伝えます。
 * は、スタートアップ ポータル (MAIN + INFO) またはリンク mika-parity:// を押して開くことができます。
 * テスト APK は、Kotlin 標準ライブラリを使用せずに独自のプロセスで実行されるため、このページは Java で書かれています。
 */
public class ShellProbeActivity extends Activity {
    public static final String BROADCAST = "com.wangzan101.mika.test.SHELL_PROBE";
    public static final String RESUMED = "resumed";
    public static final String FOCUSED = "focused";
    public static final String TOUCH = "touch";
    public static final String KEY = "key";

    public static final String KIND = "kind";
    public static final String DISPLAY = "display";
    public static final String TASK = "task";
    public static final String PID = "pid";
    public static final String ACTION = "action";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String KEY_CODE = "keyCode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View content = new View(this);
        content.setBackgroundColor(Color.rgb(40, 120, 200));
        setContentView(content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendBroadcast(report(RESUMED));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) sendBroadcast(report(FOCUSED));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            Intent i = report(TOUCH);
            i.putExtra(ACTION, action);
            i.putExtra(X, Math.round(ev.getRawX()));
            i.putExtra(Y, Math.round(ev.getRawY()));
            sendBroadcast(i);
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Intent i = report(KEY);
        i.putExtra(ACTION, event.getAction());
        i.putExtra(KEY_CODE, event.getKeyCode());
        sendBroadcast(i);
        return true;
    }

    private Intent report(String kind) {
        String target = getPackageName();
        if (target.endsWith(".test")) target = target.substring(0, target.length() - ".test".length());
        Display display = getDisplay();
        Intent i = new Intent(BROADCAST).setPackage(target);
        i.putExtra(KIND, kind);
        i.putExtra(DISPLAY, display == null ? -1 : display.getDisplayId());
        i.putExtra(TASK, getTaskId());
        i.putExtra(PID, Process.myPid());
        return i;
    }
}
