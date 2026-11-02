package com.wangzan101.mika.core.cap.sandbox

/**
 * 実行環境にインストールされるパッケージ: Python と Node、およびスキル スクリプト リストの `apk:` で必要なパッケージ
 * (py3-pypdf: PDF ワード抽出。py3-pillow には現在使用する組み込みスキルがありません。パッケージ自体は今のところ予約されています)、および携帯電話のタイム ゾーンに従うために必要な tzdata。
 * はすべて Alpine 3.22 のメイン/コミュニティに含まれており、ビルド時に組み込みの実行環境が読み込まれます。
 * (tools/sandbox/build-runtime.py の WANT はこれと一致しており、BundledRuntimeTest によってチェックされています)。
 */
val SANDBOX_PACKAGES: List<String> = listOf("python3", "nodejs", "py3-pillow", "py3-pypdf", "tzdata")
