package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place

/** テストの場合: 指定された画面読み取り値、コマンド出力、およびツールの事実を順番に押して台帳に記録します (ステップには順番に番号が付けられています)。 */
fun ledgerOf(screens: List<String> = emptyList(), outputs: List<String> = emptyList(), facts: List<String> = emptyList()): EvidenceLedger =
    EvidenceLedger().also { l ->
        var step = 0
        screens.forEach { l.add(++step, ObsKind.Read, null, 0, it) }
        outputs.forEach { l.add(++step, ObsKind.Output, null, 0, it) }
        facts.forEach { l.add(++step, ObsKind.Fact, null, 0, it) }
    }

/** テストでURLやパッケージ名を記述する場合、httpで始まるものがWebページ、残りがAppとなります。 */
fun placeOf(where: String?): Place? = where?.let { if (it.startsWith("http")) Place.page(it) else Place.app(it) }

/** テストの略語: ステップ [step] は、[where] の [text] を読み取ります。 */
fun EvidenceLedger.add(
    step: Int, kind: ObsKind, where: String?, atMs: Long, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
): Observation? = add(obs(step, kind, where, text, inputs, focused, authored, title), step, atMs)

/** テスト用: 1 つの観測。 */
fun obs(
    step: Int, kind: ObsKind, where: String?, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
) = Observation(kind, text, place = placeOf(where), title = title, authored = authored, inputs = inputs?.let { InputHints(it, focused) }, step = step)

/** テスト用: 読み取られたテキストをもう一度見てください (なぜ空の文字列が読み取られなかったのか)。 記録された */
fun freshOf(text: String): List<Observation> = if (text.isEmpty()) emptyList() else listOf(Observation(ObsKind.Read, text, inputs = InputHints(emptyList())))
