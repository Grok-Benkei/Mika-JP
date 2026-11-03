# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""テキスト型PDFから本文を抽出する（スキャン画像は対象外。このスクリプトはOCRを行わない）。

使い方:
  extract_pdf.py FILE.pdf [--pages RANGE] [--metadata] [--out FILE.txt] [--password PASSWORD]

  --pages     1始まりのページ指定。例: "3"、"1-5"、"1,4,7-9"（既定: 全ページ）
  --metadata  本文の代わりにタイトル、著者、ページ数などをJSONで出力する
  --out       本文を指定ファイルへ保存し、代わりに1行のJSON要約を出力する
  --password  暗号化されたPDFのパスワード

各ページは "--- page N ---" の行で始まる。文字を抽出できないページは標準エラー出力へ通知する。
全ページに文字がない場合はスキャン画像の可能性があるため、終了コード3で終了する。
pypdfが必要。
"""
import argparse
import json
import logging
import os
import sys


def fail(msg, code=2):
    print(f"extract_pdf.py: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_pages(spec, count):
    """'1,4,7-9' -> [0, 3, 6, 7, 8]。1..countの範囲外ならValueErrorにする。"""
    out = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        lo, _, hi = part.partition("-")
        try:
            a, b = int(lo), int(hi) if hi else int(lo)
        except ValueError:
            raise ValueError(f"ページ指定「{part}」は整数または整数の範囲で指定してください") from None
        if a < 1 or b > count or a > b:
            raise ValueError(f"ページ範囲「{part}」は1-{count}の範囲外です")
        out.extend(range(a - 1, b))
    if not out:
        raise ValueError("ページが選択されていません")
    return out


class JapaneseArgumentParser(argparse.ArgumentParser):
    def format_usage(self):
        return super().format_usage().replace("usage: ", "使い方: ", 1)

    def format_help(self):
        return super().format_help().replace("usage: ", "使い方: ", 1)

    def error(self, message):
        if message.startswith("the following arguments are required: "):
            message = "必須引数が指定されていません: " + message.split(": ", 1)[1]
        elif message.startswith("unrecognized arguments: "):
            message = "不明な引数です: " + message.split(": ", 1)[1]
        elif message.startswith("argument ") and message.endswith(": expected one argument"):
            message = "引数に値が必要です: " + message[len("argument "):-len(": expected one argument")]
        elif message.startswith("argument ") and ": ignored explicit argument " in message:
            option, value = message[len("argument "):].split(": ignored explicit argument ", 1)
            message = f"{option}には値を指定できません: {value}"
        elif message.startswith("ambiguous option: ") and " could match " in message:
            option, candidates = message[len("ambiguous option: "):].split(" could match ", 1)
            message = f"オプション「{option}」は複数の候補に一致します: {candidates}"
        else:
            message = f"引数を確認してください: {message}"
        self.print_usage(sys.stderr)
        self.exit(2, f"{self.prog}: エラー: {message}\n")


def main(argv):
    p = JapaneseArgumentParser(prog="extract_pdf.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, add_help=False)
    p._positionals.title = "位置引数"
    p._optionals.title = "オプション"
    p.add_argument("-h", "--help", action="help", help="使い方を表示して終了する")
    p.add_argument("pdf", help="入力PDFのパス")
    p.add_argument("--pages", help="抽出するページ。例: 1,4,7-9")
    p.add_argument("--metadata", action="store_true", help="メタデータをJSONで出力する")
    p.add_argument("--out", help="抽出本文の保存先")
    p.add_argument("--password", help="暗号化PDFのパスワード")
    a = p.parse_args(argv)
    if not os.path.isfile(a.pdf):
        fail(f"ファイルが見つかりません: {a.pdf}")
    try:
        from pypdf import PdfReader
        from pypdf.errors import PdfReadError
    except ImportError:
        fail("pypdfがインストールされていません")
    # pypdf logs repair warnings for damaged files; the one-line reason below is enough
    logging.getLogger("pypdf").setLevel(logging.CRITICAL)
    try:
        reader = PdfReader(a.pdf)
        if reader.is_encrypted:
            if not a.password or not reader.decrypt(a.password):
                fail("PDFが暗号化されています。正しいパスワードを--passwordで指定してください")
        count = len(reader.pages)
        if a.metadata:
            meta = reader.metadata or {}
            info = {k.lstrip("/").lower(): str(v) for k, v in meta.items()}
            info["pages"] = count
            print(json.dumps(info, ensure_ascii=False))
            return 0
        indexes = parse_pages(a.pages, count) if a.pages else list(range(count))
        chunks, empty = [], []
        for i in indexes:
            text = (reader.pages[i].extract_text() or "").strip()
            if not text:
                empty.append(i + 1)
            chunks.append(f"--- page {i + 1} ---\n{text}\n")
    except ValueError as e:
        fail(str(e))
    except PdfReadError as e:
        fail(f"読み取り可能なPDFではありません: {e}")
    except Exception as e:  # damaged files raise many kinds of errors inside pypdf
        fail(f"PDFを読み取れませんでした: {type(e).__name__}: {e}")
    if empty:
        print(f"extract_pdf.py: 次のページから文字を抽出できませんでした: {', '.join(map(str, empty))}", file=sys.stderr)
    body = "\n".join(chunks)
    if a.out:
        try:
            with open(a.out, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as e:
            fail(f"ファイルに書き込めませんでした: {a.out}: {e}")
        print(json.dumps({"output": a.out, "pages": len(indexes), "chars": len(body), "emptyPages": empty}))
    else:
        sys.stdout.write(body)
    if len(empty) == len(indexes):
        fail("抽出できる文字がありません。スキャン画像の可能性があり、OCRが必要です", 3)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
