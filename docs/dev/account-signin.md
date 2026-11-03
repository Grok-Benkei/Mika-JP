# アカウントでログインする仕組み

プロバイダー追加画面のアカウントログインは、ChatGPT、xAI / Grok、OpenRouterに対応します。各項目の説明を開き、認証方法と注意点を確認してからログインを開始します。成功後の詳細画面では、アカウント、状態、更新可能なモデル一覧、メインモデルの選択、ローカルからのログアウトを扱います。

ChatGPTは `core/chatgpt/` の認可コード＋PKCEとローカルコールバックを使います。PKCEは `core/oauth/Pkce.kt`、ローカルコールバックは `LoopbackCallback.kt` に共通化しています。

認証、暗号化保存、トークン更新、各社APIの呼び出しは別の処理です。アクセストークン、リフレッシュトークン、デバイスコードはログや診断へ出さず、本人が照合するユーザーコードだけを認証画面へ表示します。セッションJSONとAPIキーは `provider-keys.json` に同じ暗号化方式で保存します。

## xAI / Grokのデバイスコード認証

- `https://auth.x.ai/.well-known/openid-configuration` を取得します。issuerは `https://auth.x.ai`、デバイスコード・トークンの接続先はHTTPSかつ `auth.x.ai` であることを確認し、一致しなければ停止します。
- RFC 8628のデバイスコード方式を使い、scopeは `openid profile email offline_access grok-cli:access api:access` です。本人がブラウザーでコードを確認する間、アプリがトークン交換をポーリングします。認証ページのホストが `auth.x.ai` / `accounts.x.ai` でなければ、固定の公式ページを開きます。
- 公開client_idは `b1a00492-073a-47ea-816f-4c329264a828` です。xAIの公式CLI用の入口を利用しており、Mika専用に登録されたアプリではありません。提供元の制限やアカウントへの影響をログイン前に説明し、本人が続行を選んだ場合だけ開始します。
- 呼び出しは `https://api.x.ai/v1/responses` と `ResponsesModel` を使います。
- 有効期限が近づくとリフレッシュトークンで更新し、同時更新は1回にまとめます。更新を拒否されて使えなくなった場合はローカルトークンを消し、ログイン期限切れを表示します。
- ログイン後のHTTP 403は、プランで利用できない経路として `ModelError.AccountPlanNotAllowed` に分類し、自動再試行しません。詳細画面とタスク終了時にAPIキーでの接続を案内します。
- ログアウトで消すのは端末内のトークンです。提供元での認可取り消しは本人がxAIのアカウント設定で行います。

## OpenRouterのOAuth PKCE

OpenRouterの[公式PKCE方式](https://openrouter.ai/docs/use-cases/oauth-pkce)で、通常のOpenRouter APIキーを取得します。

1. `LoopbackCallback` が `127.0.0.1` の空きポートで待ち受け、コールバックURLを `http://localhost:<ポート>/auth/callback` にします。
2. システムブラウザーで `https://openrouter.ai/auth?callback_url=…&code_challenge=…&code_challenge_method=S256&key_label=Mika&state=…` を開きます。検証用の値は毎回新しく作り、端末内に保持します。
3. コールバックの `code` と `state` を確認します。state不一致は400を返して正しい応答を待ちます。本人の拒否などでcodeまたはstateがなければ、認可されなかったものとして扱います。
4. `POST https://openrouter.ai/api/v1/auth/keys` へ `{code, code_verifier, code_challenge_method: "S256"}` を送り、返された `key` を取得します。403はコード・検証値・期限の問題、400は検証方式の不一致として扱い、エラー本文は残さず状態コードだけを記録します。完了後、ブラウザーへ結果ページを返します。

取得したキーは `KeyCipher` で暗号化し、OpenAI互換APIの `https://openrouter.ai/api/v1` として保存します。`providers.json` の `services[].signIn` に `"openrouter"` を記録し、手入力キーのプロバイダーと区別します。ログイン経由の登録は手入力キーを上書きせず、再ログインでもログイン経由のものだけを更新します。モデル一覧と呼び出しは通常のAPIキー接続と共通です。

ログアウトではローカルキーだけを消し、プロバイダー、モデル、用途は残します。提供元のキーを無効にする場合は、本人が[OpenRouterのキー管理](https://openrouter.ai/settings/keys)でMikaという名前のキーを削除します。

## 提供していないログイン方式

### Claudeのアカウント

現実装はClaude.aiのアカウントログインを提供しません。AnthropicにはAPIキーで接続します。サブスクリプションの利用枠を別の接続方式へ移す機能はありません。

### MiniMaxのアカウント

他アプリに登録されたclient_idを流用したログインは提供しません。MiniMaxを利用する場合は、任意の中国系プロバイダー表示から対象リージョンを選び、その接続先に対応したAPIキーを入力します。

### Google / Geminiのアカウント

他社のOAuthクライアントを借用するログインは提供しません。Geminiには対応するAPIキーで接続します。各サービスの利用条件と認証方式は、利用時に公式の案内を確認してください。
