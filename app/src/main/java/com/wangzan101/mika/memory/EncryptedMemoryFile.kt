package com.wangzan101.mika.memory

import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.security.ProviderException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * はメモリ全体を `memory/memory.bin` に暗号化します (AES-GCM、キーは呼び出し元によって与えられ、正式には `KeystoreKeyCipher("mika-memory")` として使用されます)。
 * 一時ファイルの書き込み後、名前はアトミックに変更され、ファイルは完全に暗号化されるか、変更されません。
 * のロックを解除できない (キーが紛失している) か、コンテンツが破損しています。上書きせず、名前を変更して保存し、最初から開始してください。[brokenNotice] はインターフェイス プロンプトに使用されます。
 */
class EncryptedMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : MemoryPersistence {
    private val memDir = File(dir, "memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryItem.serializer())

    private val _brokenNotice = MutableStateFlow<String?>(null)
    /** 保存されたファイル名が正しくありません。 null = 問題ありません。 IO 障害のため、 */
    val brokenNotice: StateFlow<String?> = _brokenNotice.asStateFlow()

    @Synchronized
    override fun load(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes() // を読み取ることができません。通常どおりスローされ、不良ファイルとして扱われません。
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // キーストアが一時的に利用できない (ロックが解除されていない、ビジー状態) ということは、ファイルが壊れていることを意味するわけではありません。スローされ、次回もう一度読み取られ、別の名前で保存されません。
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            // キーの無効化、認証の失敗、切り捨て、JSON の不正: それらをすべて個別に保存し、次の書き込みで上書きしないようにしてください
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<MemoryItem>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        // まずディスクにドロップしてから名前を変更します。電源をオフにしても、名前が変更された空のファイルは残りません。
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 「すべてクリア」: メモリ ファイル (保存された不良ファイルを含む) を削除します。キーの再構築は、エイリアスを削除した後、呼び出し元によって行われます。 */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
        _brokenNotice.value = null
    }

    private fun moveAside() {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(memDir, "$FILE.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(memDir, "$FILE.broken-$stamp-${n++}")
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        _brokenNotice.value = target.name
    }

    private companion object {
        const val FILE = "memory.bin"
    }
}
