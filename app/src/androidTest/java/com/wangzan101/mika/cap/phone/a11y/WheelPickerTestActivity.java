package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import java.util.Locale;

/**
 * スクロール ホイール テスト ページ: 2 つのオリジナル デジタル スクロール ホイール (0 ～ 59、2 桁表示、ループ)、どちらも 0 から始まります。
 * 「最小」は直接入力できます。 「ロック」すると、内部の入力ボックスがブロックされます（フォーカスが取得できず、書き込まれた単語が使用されなくなります）ので、一度に 1 つのボックスしかスクロールできません。
 * テスト APK ページは、Kotlin 標準ライブラリを使用せずにテスト パッケージ独自のプロセスで実行されるため、Java で書かれています。 画面読み取りと入力検証用の
 */
public class WheelPickerTestActivity extends Activity {
    public static final String MINUTES = "分";
    public static final String LOCKED = "锁住";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addPicker(row, MINUTES, false);
        addPicker(row, LOCKED, true);
        setContentView(row);
    }

    private void addPicker(LinearLayout row, String label, boolean block) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(20f);
        col.addView(title);
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(59);
        picker.setValue(0);
        picker.setFormatter(v -> String.format(Locale.ROOT, "%02d", v));
        picker.setContentDescription(label);
        if (block) picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        col.addView(picker);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
