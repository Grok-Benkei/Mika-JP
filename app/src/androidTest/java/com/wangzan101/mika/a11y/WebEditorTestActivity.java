package com.wangzan101.mika.a11y;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Web ページのリッチ テキスト編集領域 (コンテンツ編集可能) は、多くのアプリのメモ ボックスやコメント ボックスにあるようになります。
 * エディターはブラウザーの入力イベントのみを認識します。上記のテキストには、受信した入力イベントの数と現在の内容が表示されます。テストではこれを使用して、編集者が本当に入力を受信したかどうかを判断します。
 * テスト APK は、Kotlin 標準ライブラリを使用せずに独自のプロセスで実行されるため、このページは Java で書かれています。
 */
public class WebEditorTestActivity extends Activity {
    private TextView status;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("inputs=0 text=");
        status.setTextSize(20f);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "bridge");
        web.loadDataWithBaseURL(null,
            "<html><body><div id=\"ed\" contenteditable=\"true\" aria-label=\"正文\" style=\"min-height:200px;border:1px solid #888\"></div>"
                + "<script>var n=0;document.getElementById('ed').addEventListener('input',function(e){n++;bridge.report(n,e.target.innerText);});</script>"
                + "</body></html>",
            "text/html", "utf-8", null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(status);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800));
        setContentView(root);
    }

    private class Bridge {
        @JavascriptInterface
        public void report(int n, String text) {
            runOnUiThread(() -> status.setText("inputs=" + n + " text=" + text.trim()));
        }
    }
}
