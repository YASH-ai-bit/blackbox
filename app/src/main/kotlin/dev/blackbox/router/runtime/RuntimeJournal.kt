package dev.blackbox.router.runtime

import android.util.AtomicFile
import dev.blackbox.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Secrets are never put in the rollback journal. Each intent is fsynced before execution. */
class RuntimeJournal(val directory: File) : ChangeJournal {
    private val changes=mutableListOf<NetworkChange>()
    init { directory.mkdirs() }
    fun write(name:String,text:String) {
        val atomic=AtomicFile(File(directory,name)); val out=atomic.startWrite()
        try { out.write(text.toByteArray());atomic.finishWrite(out) } catch(e:Exception) {atomic.failWrite(out);throw e}
    }
    override suspend fun append(change:NetworkChange) {
        changes+=change
        write("journal.json",JSONObject().put("changes",JSONArray(changes.map { JSONObject().put("label",it.label).put("undo",it.undo) })).toString(2))
        val dir=shellQuote(directory.absolutePath)
        write("recover.sh",buildString {
            // Android mksh marks auxiliary exec-redirection FDs close-on-exec. Standard FD 0 is inherited by flock.
            appendLine("#!/system/bin/sh\ncd $dir || exit 1\nexec 0<>recovery.lock || exit 1\nflock -x 0 || exit 1\n[ -f active ] || exit 0\nfailed=0")
            changes.asReversed().forEach { appendLine("( ${it.undo} ) || failed=1") }
            appendLine("if [ -f verify.sh ]; then sh verify.sh || failed=1; fi")
            appendLine("if [ \"\$failed\" = 0 ]; then rm -f active hostapd.conf dnsmasq.conf; echo recovered > result; else echo incomplete > result; fi\nexit \$failed")
        })
    }
    override suspend fun completed() { File(directory,"active").delete();write("result","recovered") }
    override suspend fun failed(message:String) { write("result",message) }
    fun heartbeat() { write("heartbeat",(System.currentTimeMillis()/1000).toString()) }
    fun watchdog():String {
        val d=shellQuote(directory.absolutePath)
        val owner=android.os.Process.myPid()
        val started=File("/proc/$owner/stat").readText().substringAfterLast(") ").split(' ')[19]
        return """
            #!/system/bin/sh
            cd $d || exit 1
            while [ -f active ]; do
              sleep 5
              now=${'$'}(date +%s)
              last=${'$'}(cat heartbeat 2>/dev/null)
              case "${'$'}last" in ''|*[!0-9]*) last=0;; esac
              owner_start=${'$'}(cat /proc/$owner/stat 2>/dev/null | sed 's/.*) //' | cut -d ' ' -f20)
              if [ "${'$'}owner_start" != "$started" ] && [ ${'$'}((now-last)) -gt 10 ]; then
                sh recover.sh > recovery.log 2>&1
                exit ${'$'}?
              fi
            done
        """.trimIndent()
    }
}
