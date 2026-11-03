package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.uistate.AppCopies
import com.wangzan101.mika.core.uistate.AppCopy
import com.wangzan101.mika.core.uistate.AppProfiles
import com.wangzan101.mika.core.uistate.UserProfile
import com.wangzan101.mika.shell.IMikaShell

/** は、組み込みアシスタントに、開くことができる別のアプリ (アバターや仕事用プロファイルなど、別のユーザーにインストールされている) を尋ねました。 */
internal object AppCopyReader {
    /** ミカがいるユーザー: オリジナルのアプリはここにあります。他のユーザーは別のコピーを持っています。 */
    val myUser: Int get() = android.os.Process.myUid() / 100_000 // UserHandle.PER_USER_RANGE

    /**
     * には、ユーザーが使用できるコピーを決定するためのアシスタントが組み込まれており (ルールについては、[AppCopies] を参照)、それらのユーザー コピーに何がインストールされているかを確認し、現在のユーザーが開くことができる [apps] とそれらを照合します。
     * という名前は、システムによって `pm list users` で与えられます (システム言語に応じて)。要求されない場合は null となり、他にコピーがない場合は空になります。
     */
    fun read(shell: IMikaShell, apps: List<Pair<String, String>>): List<AppCopy>? {
        val ids = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) ?: return null
        if (ids.isEmpty()) return emptyList()
        val names = AppProfiles.parseUsers(shell.listUsers().orEmpty()).associate { it.id to it.name }
        val packages = ids.associateWith { AppProfiles.parsePackages(shell.listPackages(it) ?: return null) }
        return AppCopies.copies(apps, ids.map { UserProfile(it, names[it].orEmpty(), running = true) }, packages)
    }
}
