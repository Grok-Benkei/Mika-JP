package com.wangzan101.mika.i18n;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ResultReceiver;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** テスト APK 内だけで言語と夜間設定を変え、実 Activity の Resources 更新を報告する。 */
public class LocaleConfigTestActivity extends Activity {
    private Context frameworkBase;
    private Context foreignBase;
    private final List<Configuration> overrides = new ArrayList<>();
    private ResultReceiver receiver;
    private int request;
    private int changes;
    private int originalOrientation;
    private boolean resumed;
    private boolean nightChanged;
    private String setupError;

    @Override protected void attachBaseContext(Context base) {
        frameworkBase = base;
        Configuration french = new Configuration();
        french.setLocales(LocaleList.forLanguageTags("fr-FR"));
        foreignBase = base.createConfigurationContext(french);
        Context recorded = new ContextWrapper(foreignBase) {
            @Override public Context createConfigurationContext(Configuration configuration) {
                overrides.add(new Configuration(configuration));
                return super.createConfigurationContext(configuration);
            }
        };
        try {
            // テスト APK の別プロセスにはアプリ本体や Kotlin の重複クラスが入らない。
            // 署名済みの instrumentation manifest が示す本体 APK から、実装をそのまま呼ぶ。
            PackageInfo testPackage = base.getPackageManager().getPackageInfo(
                    base.getPackageName(), PackageManager.GET_INSTRUMENTATION);
            String targetPackage = testPackage.instrumentation[0].targetPackage;
            Context code = base.createPackageContext(targetPackage,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Class<?> language = code.getClassLoader().loadClass("com.wangzan101.mika.i18n.AppLanguage");
            Object companion = language.getField("Companion").get(null);
            Context localized = (Context) companion.getClass().getMethod("wrap", Context.class)
                    .invoke(companion, recorded);
            super.attachBaseContext(localized);
        } catch (Exception error) {
            setupError = "本体 APK のロケール処理を呼び出せませんでした: " + error;
            super.attachBaseContext(base);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        originalOrientation = getRequestedOrientation();
        TextView label = new TextView(this);
        label.setText("日本語の構成変更テスト");
        setContentView(label);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        receiver = intent.getParcelableExtra("receiver");
        request = intent.getIntExtra("request", 0);
        switch (intent.getStringExtra("command")) {
            case "orientation":
                setRequestedOrientation(intent.getIntExtra("orientation", originalOrientation));
                break;
            case "night":
                if (Build.VERSION.SDK_INT >= 31) {
                    nightChanged = true;
                    getSystemService(UiModeManager.class).setApplicationNightMode(intent.getIntExtra("night", UiModeManager.MODE_NIGHT_AUTO));
                }
                break;
            case "restore":
                restoreSettings();
                break;
            case "finish":
                restoreSettings();
                finish();
                return;
        }
        report();
    }

    private void restoreSettings() {
        setRequestedOrientation(originalOrientation);
        if (nightChanged && Build.VERSION.SDK_INT >= 31) {
            // AUTO はこのテスト APK の夜間 override を未指定に戻す。端末の設定は変えない。
            getSystemService(UiModeManager.class).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO);
            nightChanged = false;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        changes++;
        report();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        report();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        restoreSettings();
        super.onDestroy();
    }

    private void report() {
        if (receiver == null) return;
        Bundle report = new Bundle();
        report.putInt("request", request);
        report.putInt("instance", System.identityHashCode(this));
        report.putInt("changes", changes);
        report.putBoolean("resumed", resumed);
        report.putParcelable("framework", new Configuration(frameworkBase.getResources().getConfiguration()));
        report.putParcelable("localized", new Configuration(getResources().getConfiguration()));
        report.putString("source_locale", foreignBase.getResources().getConfiguration().getLocales().toLanguageTags());
        report.putInt("override_count", overrides.size());
        if (!overrides.isEmpty()) report.putParcelable("override", overrides.get(overrides.size() - 1));
        if (setupError != null) report.putString("error", setupError);
        receiver.send(0, report);
    }
}
