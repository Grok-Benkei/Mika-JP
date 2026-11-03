package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * はボタンをクリックし、NEW_TASK を使用して別のプロセス ({@link SpillLaunchService}) のサービスに渡し、そのアプリの起動ページ {@link #EXTRA_TARGET} を開きます。
 * は、アプリがバックグラウンド プロセス (サービス、SDK、ジャンプ) を通じて別のアプリを開くときと同じで、開始されたプロセスにはミカの画面にページがなく、システムはデフォルトでそれをホーム画面に配置します。
 * は「ユーザーのホーム画面を開く」を再現するために使用されます。 (同一プロセス内のアプリケーションコンテキストから開いた場合、Androidはプロセスのトップページが位置する画面を表示し、オーバーフローは発生しません。)
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class SpillTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";
    /** 指定されている場合は別のボタンを追加します: このダイアログ スタイルのページは、バックグラウンド サービスを通じても開かれます。 */
    public static final String EXTRA_DIALOG = "dialog";
    /** 指定されたら、別のボタンを追加します。バックグラウンド サービスを通じて全画面のアカウント ログイン ページを開きます (テスト パッケージはアカウント認証システムです)。 */
    public static final String EXTRA_AUTH = "auth";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        String target = extra != null ? extra : "com.android.settings";
        String dialog = getIntent().getStringExtra(EXTRA_DIALOG);
        String auth = getIntent().getStringExtra(EXTRA_AUTH);
        Button button = new Button(this);
        button.setText("open elsewhere");
        button.setTextSize(32f);
        button.setOnClickListener(v ->
            startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_TARGET, target)));
        if (dialog == null && auth == null) {
            setContentView(button);
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(button);
        if (dialog != null) {
            Button dialogButton = new Button(this);
            dialogButton.setText("open dialog");
            dialogButton.setTextSize(32f);
            dialogButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_DIALOG, dialog)));
            root.addView(dialogButton);
        }
        if (auth != null) {
            Button authButton = new Button(this);
            authButton.setText("open auth");
            authButton.setTextSize(32f);
            authButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_AUTH, auth)));
            root.addView(authButton);
        }
        setContentView(root);
    }
}
