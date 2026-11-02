package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * スクリーンショット防止フラグのあるページ: 仮想画面は黒く塗られていますが、アクセシビリティ ツリーはまだ読み取ることができます。
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class SecureTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText("secure page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
