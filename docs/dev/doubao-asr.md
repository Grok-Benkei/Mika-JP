# Doubaoストリーミング音声認識2.0

任意の音声サービスDoubaoを使う場合の実装資料です。接続先の認証情報と課金条件を本人が確認し、明示的に設定します。端末内蔵の音声認識から有料サービスへ自動で切り替える機能ではありません。

## 設定と保存

「設定 › モデル › 音声テキスト変換」からDoubaoの設定ページ（`app/ui/SpeechModelScreen.kt`）を開きます。APIキー方式と、アプリID＋アクセストークン方式を切り替えられます。リソースIDは時間課金の `volc.seedasr.sauc.duration` または同時接続課金の `volc.seedasr.sauc.concurrent` です。対応サービスを提供元のアカウントで有効にしておく必要があります。課金方式だけを変える場合は保存済みの認証情報を再利用します。Arkのチャット設定・モデル一覧・認証情報とは別の経路です。

保存は端末内で暗号化して行い、接続確認や音声送信は行いません。保存済みという表示も、接続を検証できたという意味ではありません。マイクを長押しした時点で接続し、録音した音声を送ります。サービスの料金・利用枠が適用されます。認証情報は個別に削除でき、使用中のDoubao設定を削除すると音声入力を無効にします。

認証情報は `KeyCipher` と専用のAndroid Keystore別名で暗号化します。選択状態のJSONにはリソースIDだけを入れ、入力欄へ秘密値を埋め戻したり、永続化するCompose状態へ入れたりしません。診断に残すのはエンジンID・リソースIDだけです。秘密値や音声内容の反射を避けるため、サーバーのエラー本文をそのまま表示しません。

## ストリーミング処理

`SpeechChoice.Doubao` → `SpeechResolver` → `VoiceInput` → `DoubaoSpeech` を通り、チャット入力とフローティングカプセルで共有します。

- `PcmRecorder(streaming = true)` は16 kHz・モノラル・PCM16のサンプルを順に返します。非ストリーミング経路では従来どおり録音全体を返します。
- `DoubaoTransport` は専用のOkHttp WebSocketで `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` へ接続します。認証情報は固定した公式接続先だけへ送り、リダイレクトを追いません。
- 接続後にgzip圧縮した初期化JSONを送り、最初の確認応答を待ってから音声を送ります。50 msの録音単位を200 msのパケットにまとめ、蓄積済み音声も200 ms間隔で送ります。PCMはリトルエンディアン、長さと任意のシーケンス番号はビッグエンディアンです。
- 送信と受信を並行し、`result_type=full` を指定します。各 `Partial` は前の全文を置き換え、差分として連結しません。`definite` は文節の確定を示し、セッション終了の条件にはしません。
- 指を離すと録音を止め、キュー内の音声を送り終えて最終パケット（フラグ2）を送ります。200 ms未満の末尾や空の最終パケットも扱います。サーバーの最終フラグ・負のシーケンス番号を待ってから `Final` とし、その前の切断を成功扱いしません。
- キャンセルはイベント、録音、接続を閉じ、遅れて届いた結果を出しません。録音が自動終了した場合も最終パケットを送ります。接続と初期化は各10秒、最終送信後の待機は10秒、全体は75秒が上限です。UIの長押し録音上限は60秒です。
- 音声キューは約5秒分に制限し、送信・受信キューにも上限を設けます。超過時は音声を黙って捨てずに失敗として終了し、マイクを解放します。gzip展開後の応答は1 MiBまでです。

IMEI、MACアドレス、会話履歴、写真は送りません。双方向APIはサービス側の既定の言語処理を使い、一方向APIだけが対応する `audio.language` は送りません。

## 検査と未確認の範囲

`DoubaoProtocolTest`、`DoubaoSpeechTest`、`DoubaoTransportTest`、`DoubaoSettingsTest` で、プロトコル、逐次送信、全文置換、最終パケット、タイムアウト、キャンセル、両認証方式、認証失敗、AES/GCM保存・復元・削除を検査します。認証情報は架空の値を使い、WebSocketテストの接続先はローカルのMockWebServerだけです。

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'
./gradlew :app:assembleDebug
```

実アカウントの権限、料金、遅延、認識精度は、本人の認証と明示的な実施のもとで別に確認します。設定画面やモックの検査だけで、実サービスへの接続・精度を確認済みとは扱いません。

## 出典

2026-11-01時点で参照した提供元のプロトコル資料:

- [大規模モデルのストリーミング音声認識API](https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh)
- [双方向ストリーミング音声認識と2.0リソース](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)

接続先やリソースIDはプロトコル上の値として保持しています。リンク先の原資料の言語と、Mika JPの表示言語は別です。
