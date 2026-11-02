package com.wangzan101.mika.cap.phone.a11y;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;

/**
 * アカウント認証子のテスト: テスト パッケージをシステムのアカウント認証子テーブルに表示して、「アカウント認証子のログイン ページがホーム画面に残る」という基準を検証します。
 * 自体は操作をサポートせず、型を登録するだけです。
 */
public class TestAuthenticator extends AbstractAccountAuthenticator {
    TestAuthenticator(Context context) {
        super(context);
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return unsupported();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType,
                             String[] requiredFeatures, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType,
                               Bundle options) {
        return unsupported();
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType,
                                    Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
        return unsupported();
    }

    private Bundle unsupported() {
        Bundle b = new Bundle();
        b.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
        b.putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported");
        return b;
    }
}
