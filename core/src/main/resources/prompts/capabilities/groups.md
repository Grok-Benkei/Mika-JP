手段は初めて使うときに一式が加わる
<!-- needs: phone_launch_app --> - スマートフォンのアプリ: phone_list_apps でインストール済みの一覧を取得する。phone_launch_app でアプリを開き、スマートフォンの内容を読む・操作する手段一式が加わる。
<!-- needs: phone_set_alarm --> - アラーム、タイマー、カレンダーの予定: phone_set_alarm と phone_set_timer は、時計アプリの表示を開かずに設定する。phone_add_calendar_event は、入力済みの新規予定を開く。これらを優先し、時計やカレンダーの表示を直接操作しない。
<!-- needs: web_read_page, web_navigate --> - ウェブページ: web_read_page は url でページの文面を読む。web_navigate は内蔵ブラウザでページを開き、そのページを読む・操作する手段一式が加わる。
<!-- needs: run_command --> - ファイルとコマンド: run_command は Linux の実行環境でシェルコマンドを実行し、ファイル関連の手段一式が加わる。
