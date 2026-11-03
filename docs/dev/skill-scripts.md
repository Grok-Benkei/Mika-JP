# 知識・スキルに同梱するスクリプト

スキルとサイトの知識には、それぞれの `scripts/` にスクリプトを含められます。APKに同梱した後は、サンドボックスの `/opt/mika/skills/` 配下から利用します。`run_bundled_script` の引数は次のとおりです。

| 引数 | 内容 |
|---|---|
| `source` | `knowledge_read` / `skill_read` の結果にある `scriptSource`。例: `skills/documents` |
| `script` | そのディレクトリ内の相対パス。例: `scripts/extract_pdf.py` |
| `args` | 最大50件の引数配列。シェルを介さず渡す |
| `timeoutSec` | 1〜600秒。既定は60秒 |

インタープリターは `ScriptManifest.interpreterFor` が拡張子で選びます。`.py` は `python3`、`.mjs` / `.js` は `node`、`.sh` は `sh` です。

## 同梱スクリプト

パスは `core/src/main/resources/` からの相対です。

| スクリプト | 場所 | 機能 | 依存 |
|---|---|---|---|
| `scripts/extract_pdf.py` | `skills/documents/` | テキストPDFからの文字抽出、ページ指定、メタデータ表示、ファイル出力。画像だけのPDFは処理しない | Alpineの `py3-pypdf` |

`--help` で使い方を表示します。失敗時は0以外の終了コードと、標準エラーへの理由を返します。

## scripts/manifest.json

スクリプトを含むディレクトリごとに、配列形式の `scripts/manifest.json` を1つ置きます。

```json
[
  {"file": "scripts/extract_pdf.py", "runnable": true, "needs": ["apk:py3-pypdf"]}
]
```

| 項目 | 内容 |
|---|---|
| `file` | 対象ディレクトリ内の相対パス。外へ出るパスは無効 |
| `runnable` | サンドボックスで実行可能か。`false` は不足条件を示すだけで、実行を拒否 |
| `reason` | 実行できない場合の説明。任意 |
| `needs` | `apk:<パッケージ名>` は `SANDBOX_PACKAGES` にあるAlpineパッケージ、`net` はネットワーク接続が必要なことを示す |
| `network` | `true` ならネットワーク許可が必要。設定のスクリプト用ネットワークが無効（既定）の場合は実行を拒否し、設定を案内 |

解析と実行可否の判定は `core/.../knowledge/ScriptManifest.kt` が担います。マニフェスト、説明にある一覧、実ファイルの対応は `BundledScriptsManifestTest` で確認します。

## 実行環境

Alpine 3.22系を使い、同梱パッケージは `core/.../cap/sandbox/SandboxPackages.kt` の `SANDBOX_PACKAGES` が正本です。`python3`、`nodejs`、`py3-pillow`、`py3-pypdf`、`tzdata` を含みます。`py3-pypdf` はPDF抽出で使い、Pillowは画像処理用です。`tzdata` により、端末の現在のタイムゾーンと時差規則を使えます。

ビルド時に `tools/sandbox/build-runtime.py` で実行環境へ組み込み、APKに同梱します。実行環境の版が変わると、起動時に新しい内容へ展開します。再構築と対応ソースの作成は[サンドボックスの説明](../../tools/sandbox/README.md)を参照してください。
