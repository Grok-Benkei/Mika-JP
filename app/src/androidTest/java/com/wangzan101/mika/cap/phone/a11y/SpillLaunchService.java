package com.wangzan101.mika.cap.phone.a11y;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.Display;

/**
 * アプリの起動ページを別のプロセスで開き、開いたら停止します。
 * テスト APK のコンポーネントは、テスト パッケージ独自のプロセスで実行され、Kotlin 標準ライブラリが含まれていないため、Java で書かれています。
 */
public class SpillLaunchService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String auth = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_AUTH) : null;
        String dialog = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_DIALOG) : null;
        String component = auth != null ? auth : dialog;
        if (component != null) {
            // ダイアログ スタイルまたは全画面ログイン ページ: ユーザーのホーム画面に表示されます。テストパッケージ自体はミカの画面上にページがあり、
            // 画面が指定されていない場合、システムは画面もそこに配置します。バックグラウンドプロセスのメイン画面にページがない場合、システムはデフォルトでそのページをメイン画面に配置します。ここではメイン画面をこのように指定します。
            Intent launch = new Intent().setComponent(new ComponentName(getPackageName(), component))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String target = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_TARGET) : null;
        Intent launch = target != null ? getPackageManager().getLaunchIntentForPackage(target) : null;
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
