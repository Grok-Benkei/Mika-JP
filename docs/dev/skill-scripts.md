# サンドボックス スクリプト

のスキルと Web サイトの知識をスクリプトとともに持ち込んで、それぞれのディレクトリの `scripts/` の下に配置できます。パッケージ化後、これらのディレクトリはサンドボックスの `/opt/mika/skills/` の下に表示されます。
は、`run_bundled_script` によってサンドボックスで実行されます。パラメータ:

- `source`: スクリプトが配置されているディレクトリ。つまり、`knowledge_read` と `skill_read` の結果で指定される `scriptSource` (たとえば、`skills/documents`)。
- `script`: ディレクトリ内のスクリプト パス (`scripts/extract_pdf.py` など)。
- `args`: パラメーター配列 (最大 50)。シェルを経由せずにそのままスクリプトに渡されます。
- `timeoutSec`: 時間制限、1 ～ 600 秒、デフォルトは 60。

インタープリターは拡張子によって選択されます。`.py` は python3 を使用し、`.mjs` および `.js` はノードを使用し、`.sh` は sh(`ScriptManifest.interpreterFor`) を使用します。

現在、ウェアハウスにスクリプトがあります:

|スクリプト |場所 |何をすべきか |必要なもの |
|---|---|---|---|
| `scripts/extract_pdf.py` | `skills/documents/` |テキスト PDF から単語を取得し、ページを押したり、メタデータを表示したり、ファイルを書き込んだりできます。スキャンされたファイルは処理されません。アルパインパッケージ `py3-pypdf` |

上記のポジションはすべて `core/src/main/resources/` の下にあります。すべてのスクリプトには `--help` が含まれています。エラーが発生すると、ゼロ以外の値で終了し、標準エラーに理由を書き込みます。

## `scripts/manifest.json`

スクリプトを含む各ディレクトリには、配列である `scripts/manifest.json` のコピーが 1 つあり、スクリプトごとに 1 つの項目が含まれます。

```json
[
  {"file": "scripts/extract_pdf.py", "runnable": true, "needs": ["apk:py3-pypdf"]}
]
```

- `file`: ディレクトリに対するスクリプトの相対パス。ディレクトリ内の相対パスではない項目は破棄されます。
- `runnable`: サンドボックスで実行できますか? `false` のスクリプトは、不足しているものを説明するためにのみ使用され、`run_bundled_script` はそれを拒否します。
- `reason`: オプション。`runnable` が `false` の場合に指定される理由。
- `needs`: 依存関係リスト。 `apk:<包名>` は、この Alpine パッケージが必要であることを意味します (`SANDBOX_PACKAGES` にある必要があります)。 `net` は、インターネットに接続する必要があることを意味します。
- `network`: ネットワークが必要かどうか。 `true` に設定 → 詳細設定の「スクリプト ネットワークを許可する」がオフになっている場合 (デフォルトではオフ)、`run_bundled_script` は実行を拒否し、開くように求めます。

の解析と「現時点で実行できるかどうか」の判断は`core/.../knowledge/ScriptManifest.kt`によって行われます。リストおよびドキュメントに記載されているスクリプトがスクリプト ファイルと一致するかどうかは、`BundledScriptsManifestTest` によってチェックされます。

## 動作環境

サンドボックスは Alpine 3.22 に基づいており、`SANDBOX_PACKAGES` (`core/.../cap/sandbox/SandboxPackages.kt`) には、APK に付属するソフトウェア パッケージ (python3、nodejs、py3-pillow、py3-pypdf、tzdata) がリストされています。その中で、py3-pypdf は PDF ワード抽出スクリプトに使用されます。 py3-pillow は現在組み込みスキル スクリプトでは使用されていないため、パッケージ自体は今のところ予約されています。 tzdata を使用すると、コマンドは携帯電話の現在のタイム ゾーンに従って現地時間を処理できます。
これらのパッケージは、ビルド時に APK (`tools/sandbox/build-runtime.py`、`tools/sandbox/README.md` を参照) に付属する実行環境にロードされます。パッケージが変更されると、実行環境のバージョンもそれに応じて変更され、電話機の起動時に自動的に再解凍されます。
