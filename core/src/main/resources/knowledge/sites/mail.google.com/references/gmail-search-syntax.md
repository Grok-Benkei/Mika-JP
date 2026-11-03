# Gmailの検索構文

上部の検索欄へ入力する。ウェブ版ではURLエンコードして#search/の後へ付ける方法もある。空白で区切った条件は同時に満たすものを探す。

| 条件 | 構文 |
|---|---|
| 場所 | in:inbox、in:sent、in:drafts、in:trash。in:anywhereは迷惑メールとゴミ箱も含む |
| 状態 | is:unread、is:starred、is:important |
| ラベル・カテゴリ | label:work、category:promotions、category:social |
| 送信者・宛先・CC | from:alice@example.com、to:、cc: |
| 件名 | subject:invoice |
| 添付 | has:attachment、filename:pdf |
| サイズ | larger:5M、smaller:1M |
| 相対期間 | newer_than:7d、older_than:30d。dは日、mは月、yは年 |
| 日付 | after:2026/02/01、before:2026/03/01 |
| いずれか | from:alice OR from:bob |
| 除外 | -from:noreply@ |
| グループ | (from:alice OR from:bob) subject:meeting |
| 完全な語句 | "exact phrase" |

例: 最近1日の未読メールからプロモーションを除外する場合はis:unread newer_than:1d -category:promotions。日付・期間の境界は実際の検索結果で確認する。
