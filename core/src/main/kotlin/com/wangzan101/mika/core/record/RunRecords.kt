package com.wangzan101.mika.core.record

/**
 * 保存された実行レコード (読み取り専用): 概要 (run.json) とイベント (events.jsonl) を読み取ります。レコードはディスクに置かれたときに感度が下げられ、切り詰められており、そのまま読み取られます。
 * レコードがクリアされ、番号が不明または読み取れず、例外がスローされない場合は、null/空のテーブルを返します。アプリ横のレコードを押すことで実現します。
 */
interface RunRecords {
    suspend fun summary(runId: String): RunSummary?
    suspend fun events(runId: String): List<RunEvent>
}
