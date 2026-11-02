package com.wangzan101.mika.settings

import com.wangzan101.mika.core.docs.ReadmeSections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** README のサービス プロバイダーのプリセット セクションは、ソース コードに従って 1 つずつ読み取られます。ここで実際のオブジェクトと比較してみます。ソースコードの書き込み方法が変更され、読み取り方法が追いつかない場合は失敗します。 */
class ReadmePresetsTest {
    @Test fun sourceReadingMatchesProviderPresets() {
        val source = File("../${ReadmeSections.PRESETS_SOURCE}").readText()
        assertEquals(
            ProviderPresets.all.map { ReadmeSections.Preset(it.label, it.kind.name, it.baseUrl) },
            ReadmeSections.presets(source),
        )
    }
}
