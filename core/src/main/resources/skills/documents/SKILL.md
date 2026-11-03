---
name: documents
display: PDFを読む・整理する
description: PDFの本文とページ情報を読み、必要なページを抽出・結合して渡す
aliases: ["PDFを読む", "PDFから文字を抽出", "PDFを結合"]
terms: ["PDF", "文書", "文字抽出", "ページ", "結合", "分割", "スキャン"]
---
## 開始条件
公開リンクはweb_navigateで開く。ユーザーの添付は/workspace/inbox/、ダウンロードは/workspace/downloads/にある。web_downloadsで実際の保存先を確認できる。

## 手順
1. テキストPDFにはrun_bundled_scriptを使い、sourceをskills/documents、scriptをscripts/extract_pdf.pyにする。パスは/workspaceからの相対パス。
   - 全文: args: ["inbox/a.pdf"]
   - 指定ページ: args: ["inbox/a.pdf", "--pages", "1-5"]
   - メタデータ: args: ["inbox/a.pdf", "--metadata"]
   - テキストへ保存: args: ["inbox/a.pdf", "--out", "a.txt"]
2. ページは1始まり。出力の「--- page N ---」に従って引用元ページを示す。長い文書はread_fileやsearch_filesで分割して読む。
3. ページの抽出や結合はrun_commandと同梱のpypdfを使う。新しいファイルを/workspace内に作り、元の添付を上書きしない。
4. share_fileで成果物を渡す。

## 注意点
終了コード3で文字が取得できない場合は画像型PDF。PDF用のローカルOCRがあると仮定しない。表や段組みは抽出順が変わるため、数値の所属を原ページと照合する。ページ範囲が不明なら最初に--metadataで確認する。認証情報をスキルや会話へ記録しない。

## 完了の確認
抽出文とページが対応している。出力ファイルのページ数と内容を確認し、share_fileが成功している。

## ユーザーに引き継ぐ条件
保護された文書を開けない場合、または残すページや結合順に本人の選択が必要な場合。
