package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * は通常のページで、別のアプリをシミュレートして前面に表示するために使用されます。
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class PlainTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("plain page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
