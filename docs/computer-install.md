# PC用CLIとスキルの導入

[PCからの利用](computer.md) · [接続とタスクコマンド](cli.md) · [MCPでの接続](mcp.md)

コマンドとスキルを使えるAIエージェントにはスキルを、ターミナルやスクリプトから使う場合はCLIを導入します。スキルにはCLIスクリプトが付属するため、両方を入れる必要はありません。MCPを使う場合は、このページを省略して[MCPガイド](mcp.md)へ進めます。

スマホ側の前提は[Mika JPの導入](setup-guide.ja.md)を参照してください。PC用ツールの導入や更新で、スマホのAPKは変更されません。Mika JPとPC用ツールには、このフォークの同じソース版を使ってください。上流のリリースAPK・スキルをMika JPの代わりに導入しないでください。

<a id="cli安装"></a>
<a id="cli-install"></a>

## CLIの導入

Python 3.10以降とpipxが必要です。ADB接続にはAndroid platform-toolsも必要です。このフォークをチェックアウトしたディレクトリのルートから実行します。

```bash
pipx install ./tools/mika
mika --version
```

pipxのパッケージ名は `mika-phone-cli`、コマンド名は `mika` です。CLIとAPKには別々のバージョン番号があります。同じ番号であることより、同じソース版から導入していることを確認してください。

インストールせずに、リポジトリのスクリプトを直接使うこともできます。

```bash
python3 tools/mika/scripts/mika_cli.py --version
```

Windowsでは `python3` を `py -3` に置き換えます。

## CLIの更新・以前の版への変更・削除

目的のソース版を別の作業ディレクトリに用意し、そのルートから次を実行します。未保存の変更がある作業ディレクトリを、更新のために破棄しないでください。

```bash
pipx install --force ./tools/mika
```

ローカルソースから導入したCLIの更新には、この手順を使います。`pipx upgrade` だけで別のソース版へ切り替わるとは限りません。削除する場合は次を使います。

```bash
pipx uninstall mika-phone-cli
```

<a id="skill安装"></a>
<a id="skill-install"></a>

## スキルの導入

スキルは、このフォークの `tools/mika` フォルダー全体です。`SKILL.md` がエージェント向けの説明、`scripts/mika_cli.py` が同梱CLIです。Python 3.10以降で実行でき、ADB接続時にはplatform-toolsも使います。

1. Mika JPと同じソース版の `tools/mika` を用意します。
2. フォルダー全体を、使用するエージェントのスキルディレクトリへコピーします。保存先は、そのエージェントの案内に従います。結果は `<スキルディレクトリ>/mika/SKILL.md` となり、さらに `mika` が入れ子にならないようにします。
3. 次のコマンドで同梱CLIの起動を確認します。

   ```bash
   python3 <スキルディレクトリ>/mika/scripts/mika_cli.py --version
   ```

4. エージェントの新しいセッションを開き、`mika` スキルが認識されることを確認します。

PCに `mika` コマンドがすでにある場合、スキルはそちらを優先します。スキルの更新では、別にインストールしたCLIは更新されません。

## スキルの更新・以前の版への変更・削除

古い `mika` フォルダーをスキルディレクトリの外へ退避し、目的の版の `tools/mika` 全体をコピーします。古いフォルダーへ上書きで混ぜないでください。削除するときは、対象の `mika` フォルダーだけを削除します。変更後はエージェントの新しいセッションを開いてください。

## 接続を確認する

スキルだけを導入した場合は、以下の `mika` を `python3 <スキルディレクトリ>/mika/scripts/mika_cli.py` に置き換えます。Windowsでは `py -3` を使います。

USB、またはPCと接続済みの無線ADBで確認します。`connect` はスマホのコンピューターアクセスを有効にする操作です。PCから利用する目的で実行してください。

```bash
mika devices --json
mika connect --device SERIAL --json
mika doctor --device SERIAL --json
```

Wi-Fi・プライベートVPNで直接接続する場合は、先にスマホでネットワークアクセスを有効にします。

```bash
mika --url http://PHONE:8765 --token-file /path/to/private-token-file doctor --json
```

トークンファイルにはトークンだけを保存し、チャットやログへ貼り付けないでください。詳しくは[接続方法](cli.md#接続)を参照してください。

`connect` や `doctor` の成功は、実際のタスクが完了した証拠にはなりません。`doctor` はモデル設定を未検証として報告し、エージェントがスキルを読み込んだことも確認しません。タスクの動作確認が必要な場合は、承認された単純な依頼を `task run` で開始し、`task wait` と実際の結果を確認します。[タスクコマンド](cli.md#任务命令)を参照してください。

| 表示 | 対処 |
| --- | --- |
| `incompatible_api` | スマホにタスクAPI v1がありません。対応するMika JPのAPKへ更新します。 |
| `stale_connection` | スマホの再起動などで接続情報が古くなっています。`connect` を再実行します。 |
| `device_selection` | 複数の端末があります。`mika devices` で確認し、`--device SERIAL` を指定します。 |
| `connection_info` | 端末をロック解除し、Mika JPを一度開いてから `connect` を実行します。 |
| `adb` / `adb_unavailable` | adbがPATHにあり、`adb devices` に対象が `device` と出ることを確認します。 |
| エージェントにスキルが表示されない | フォルダーの入れ子、`SKILL.md` と `scripts/` の有無を確認し、新しいセッションを開きます。 |

PC用ツールを削除しても、スマホのデータやコンピューターアクセス設定は変わりません。アクセスを止める場合は、スマホの「設定 › コンピューターに接続」で該当する接続を無効にします。
