package com.wangzan101.mika.cap.phone.a11y;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** テスト用のアカウント認証システムをシステムに渡します ({@link TestAuthenticator} を参照)。 */
public class TestAuthenticatorService extends Service {
    private TestAuthenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new TestAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
