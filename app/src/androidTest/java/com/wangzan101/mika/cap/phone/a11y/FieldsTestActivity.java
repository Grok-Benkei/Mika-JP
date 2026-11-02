package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * テスト ページ:
 * - 「結果」テキストはアクセシビリティにとって重要ではないものとしてマークされています (電卓の表示領域など)。変更のみを行う場合は「7」を押してください。
 * - 「空の入力ボックス」をクリックすると、入力フォーカスが変更されるだけです。
 * - 「ホット ワードの例」は、プロンプトのみを表示する検索ボックスです。
 * - 「変更可能なボックス」は、入力後に新しい入力コントロールに置き換えられます (一部のアプリは、コンテンツが変更されるとすぐに入力ビューを再構築します)。
 * - 「偽の検索ボックス」は、クリックできる単なるテキストです。クリックすると、実際の入力ボックスが表示され、フォーカスが置かれます (ホームページ上の多くの検索バーがこれに当てはまります)。
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class FieldsTestActivity extends Activity {
    private LinearLayout holder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView result = new TextView(this);
        result.setText("0");
        result.setTextSize(32f);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(result);
        Button seven = new Button(this);
        seven.setText("7");
        seven.setOnClickListener(v -> result.append("7"));
        root.addView(seven);
        EditText empty = new EditText(this);
        empty.setHint("空输入框");
        empty.setContentDescription(null);
        root.addView(empty);
        EditText hinted = new EditText(this);
        hinted.setHint("热词示例");
        root.addView(hinted);
        holder = new LinearLayout(this);
        root.addView(holder);
        holder.addView(swapping(""), fullWidth());
        EditText real = new EditText(this);
        real.setHint("真正的输入框");
        real.setVisibility(View.GONE);
        TextView fake = new TextView(this);
        fake.setText("假搜索框");
        fake.setTextSize(24f);
        fake.setOnClickListener(v -> {
            real.setVisibility(View.VISIBLE);
            real.requestFocus();
        });
        root.addView(fake);
        root.addView(real);
        setContentView(root);
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private EditText swapping(CharSequence initial) {
        EditText field = new EditText(this);
        field.setHint("会换的框");
        field.setText(initial);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String text = s == null ? "" : s.toString();
                if (text.isEmpty()) return;
                field.post(() -> {
                    holder.removeAllViews();
                    EditText next = swapping(text);
                    holder.addView(next, fullWidth());
                    next.requestFocus();
                });
            }
        });
        return field;
    }
}
