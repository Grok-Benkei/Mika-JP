package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.cap.root.RootCommandClass.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandClassTest {
    private fun readOnly(vararg commands: String) = commands.forEach { assertEquals(it, Kind.ReadOnly, RootCommandClass.classify(it)) }
    private fun changes(vararg commands: String) = commands.forEach { assertEquals(it, Kind.Changes, RootCommandClass.classify(it)) }

    @Test
    fun readingCommandsRunDirectly() = readOnly(
        "ls -la /data/data/com.example.app",
        "cat /data/data/com.example.app/shared_prefs/settings.xml",
        "du -sh /sdcard/Download 2>/dev/null",
        "stat /data/system/packages.xml",
        "find /sdcard/Download -name '*.apk' -size +10M",
        "ls /data/app | head -n 20; df -h",
        "id && getprop ro.build.version.release",
        "grep -r 'token' /data/data/com.example.app/shared_prefs 2>&1 | wc -l",
        "/system/bin/ls -l /data/local/tmp",
        "toybox ls /data",
        "LANG=C ls /data",
        "pm list packages -3",
        "cmd package list packages",
        "settings get secure enabled_accessibility_services",
        "settings --user 0 list global",
        "dumpsys battery",
        "sed -n '1,20p' /data/system/packages.list",
        "logcat -d -t 100",
        "mount",
        "date",
        "sort /data/system/packages.list | uniq",
        "echo hi >&2",
        "cat < /data/system/packages.list",
        "{ ls /data; ls /sdcard; }",
        "(cd /data/data && ls)",
    )

    @Test
    fun sqliteQueriesThatOnlyReadRunDirectly() = readOnly(
        "sqlite3 -readonly /data/data/com.example.app/databases/notes.db 'SELECT title FROM notes LIMIT 10'",
        "sqlite3 -readonly -header -csv /data/data/com.example.app/databases/notes.db \"select count(*) from notes;\"",
        "sqlite3 -readonly /data/data/com.example.app/databases/notes.db .tables",
        "sqlite3 -readonly notes.db 'PRAGMA table_info(notes)'",
        "sqlite3 -readonly notes.db '.schema notes'",
    )

    @Test
    fun writesAndDeletesNeedTheUsersOk() = changes(
        "rm -rf /sdcard/Download/old",
        "mv /sdcard/a /sdcard/b",
        "cp /data/data/com.example.app/databases/a.db /sdcard/",
        "echo 1 > /sys/class/leds/x/brightness",
        "cat a >> /sdcard/log.txt",
        "ls /data | tee /sdcard/list.txt",
        "pm uninstall com.example.app",
        "pm clear com.example.app",
        "cmd package uninstall com.example.app",
        "settings put global adb_enabled 0",
        "dd if=/dev/zero of=/dev/block/sda bs=1M count=1",
        "mount -o remount,rw /system",
        "chmod 777 /data/local/tmp/x",
        "setprop persist.sys.x 1",
        "am force-stop com.example.app",
        "reboot",
        "find /sdcard -name '*.tmp' -delete",
        "find /sdcard -name '*.tmp' -exec rm {} \\;",
        "sed -i 's/a/b/' /data/system/x.xml",
        "logcat -c",
        "dmesg -c",
        "date -s 20260101",
        "sort -o /sdcard/out.txt in.txt",
        "uniq in.txt out.txt",
        "dumpsys battery set level 5",
        "ls /data; rm /data/local/tmp/x",
        "ls /data && touch /data/local/tmp/x",
    )

    @Test
    fun sqliteWritesNeedTheUsersOk() = changes(
        "sqlite3 -readonly notes.db 'DELETE FROM notes'",
        "sqlite3 -readonly notes.db \"UPDATE notes SET title='x'\"",
        "sqlite3 -readonly notes.db 'select 1; drop table notes'",
        "sqlite3 -readonly notes.db 'PRAGMA journal_mode=WAL'",
        "sqlite3 -readonly notes.db 'PRAGMA wal_checkpoint'",
        "sqlite3 -readonly notes.db",
        "sqlite3 -readonly notes.db '.output /sdcard/x.txt'",
        "sqlite3 -readonly notes.db 'WITH x AS (SELECT 1) DELETE FROM notes'",
        "sqlite3 -readonly -cmd '.shell rm x' notes.db 'select 1'",
        "sqlite3 -readonly -unknownflag notes.db 'select 1'",
    )

    /** 読み取り名でも、実行先・環境・オプション・接続方法が変更を起こし得る場合は確認する。 */
    @Test
    fun readerNamesCannotBypassConfirmation() = changes(
        "/data/local/tmp/cat /data/x",
        "PATH=/data/local/tmp cat /data/x",
        "LD_PRELOAD=/data/local/tmp/hook.so cat /data/x",
        "toybox /data/local/tmp/cat /data/x",
        "cat \$OPTIONS /data/x",
        "cat \"\$OPTIONS\" /data/x",
        "sed -f /data/local/tmp/script /data/x",
        "sed '1w /sdcard/copied' /data/x",
        "dumpsys batterystats --reset",
        "date 110312002026",
        "logcat -f/sdcard/log.txt",
        "logcat -G1M",
        "dmesg --clear",
        "sort --compress-program=/data/local/tmp/run /data/x",
        "uniq -- /data/x /sdcard/copied",
        "printf -v result '%s' data",
        "file -C -m /sdcard/magic",
        "sqlite3 /sdcard/new.db 'SELECT 1'",
    )

    /** 展開結果に-oや-c等のファイル名が含まれ得るため、未引用のglobは変更として確認する。 */
    @Test
    fun filenameExpansionCannotInjectWriteOptions() = changes(
        "cd /sdcard/Download; sort *",
        "logcat * -d",
        "find . *",
        "du -sh /sdcard/* 2>/dev/null",
        "sort file?.txt",
        "cat /sdcard/[ab].txt",
    )

    /** 不明な書式はすべて変更扱い。コマンド置換、別シェル、変数名、未閉鎖の引用、here-doc、未知の実行先を含む。 */
    @Test
    fun anythingUnclearNeedsTheUsersOk() = changes(
        "ls $(cat /sdcard/list)",
        "ls `cat /sdcard/list`",
        "echo 'rm -rf /sdcard' | sh",
        "sh -c 'ls /data'",
        "\$CMD /data",
        "eval ls",
        "xargs rm < list",
        "awk '{print}' /data/x",
        "ls '/data",
        "cat <<EOF\nhi\nEOF",
        "busybox",
        "some_unknown_tool --flag",
        "env rm x",
        "ls > /sdcard/out.txt",
        "ls 2> /sdcard/err.txt",
        "",
        "   ",
    )

    /** 引用内の演算子は文字データとして扱い、シェルの構文とは解釈しない。 */
    @Test
    fun quotedOperatorsAreJustText() = readOnly(
        "grep 'a;b|c>d' /data/x",
        "echo \"rm -rf / > x\"",
        "grep -e 'x' -e \"y && z\" /data/x",
        "grep '\$HOME' /data/x",
        "echo \\\$HOME",
        "sed -n '\$p' /data/x",
        "sort '*'",
        "logcat \"*\" -d",
        "find . -name '*.apk'",
        "cat '/sdcard/file?.txt'",
        "cat \"/sdcard/[ab].txt\"",
        "sort \\*",
        "cat /sdcard/file\\?.txt",
        "cat /sdcard/\\[ab\\].txt",
    )
}
