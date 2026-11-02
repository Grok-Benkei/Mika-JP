package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ダイアログ スタイル ページ: バックグラウンド サービスから開くと、ユーザーのホーム画面に表示されます。ログイン認証やアカウント確認など、ユーザーが確認のためにクリックする必要があるポップアップ ウィンドウを再現するために使用されます。
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class DialogSpillTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Verify it's you");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Are you trying to sign in? Tap Confirm on this phone to continue.");
        body.setTextSize(16f);
        body.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(body);

        Button confirm = new Button(this);
        confirm.setText("Confirm");
        confirm.setOnClickListener(v -> finish());
        root.addView(confirm);

        setContentView(root);
    }
}
