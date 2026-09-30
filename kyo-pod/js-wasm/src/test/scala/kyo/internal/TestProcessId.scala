package kyo.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Node's process module, reached through a namespace import rather than the `process` global for the reason
// kyo-core's NodeChildProcess records: @JSImport compiles to require() under CommonJS and to import under
// ESModule, so one facade serves both the JS and the WebAssembly backend.
@js.native
@JSImport("node:process", JSImport.Namespace)
private[kyo] object NodeProcess extends js.Object:
    def pid: Int                             = js.native
    def kill(pid: Int, signal: Int): Boolean = js.native
end NodeProcess

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object NodeOs extends js.Object:
    def hostname(): String = js.native
end NodeOs

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object NodeFs extends js.Object:
    def readlinkSync(path: String): String = js.native
end NodeFs

/** This test process's own pid, plus a liveness probe for a foreign pid, for [[TestContainers]]'s ownership predicate.
  *
  * `kyo.Process` cannot serve either role: it is a handle over a process this program spawned, so its `pid` and `isAlive` describe a child
  * rather than the current process or an arbitrary pid read from a container label.
  */
private[kyo] object TestProcessId:

    /** This process's pid, stamped into the `kyo-test-owner-pid` label of every container it creates. */
    val pid: Long = NodeProcess.pid.toLong

    /** The process table [[pid]] belongs to, stamped into the `kyo-test-owner-ns` label: the host name, then on Linux the pid namespace.
      * See the JVM `TestProcessId` for why both parts are needed. `os.hostname()` is `gethostname(2)`, with no DNS lookup.
      */
    val namespace: String =
        // Node reports a missing link by throwing; outside Linux there is no /proc and the host name stands alone.
        val pidNamespace =
            try "/" + NodeFs.readlinkSync("/proc/self/ns/pid")
            catch case _: Throwable => ""
        NodeOs.hostname() + pidNamespace
    end namespace

    /** Whether `pid`, read from a container label, names a process that is still running.
      *
      * A value that does not parse as a `Long` reports not-running, which reaps the container. That is deliberate and it is the same
      * judgement as a missing owner label: the only writer of this label is `TestContainers`, which always writes
      * `TestProcessId.pid.toString`, so a value that does not parse cannot have come from a live test process.
      *
      * Every other unknown outcome reports RUNNING. See the catch below.
      */
    def isAlive(pid: String)(using Frame): Boolean < Sync =
        Sync.defer {
            pid.toLongOption.exists { p =>
                try
                    // Signal 0 probes existence without delivering anything, and Node reports the outcome by
                    // throwing, so the catch is the probe rather than error handling. Node exposes no
                    // non-throwing existence check, so this is the whole boundary.
                    val _ = NodeProcess.kill(p.toInt, 0)
                    true
                catch
                    case thrown: Throwable =>
                        // ONLY a definite "no such process" is death. EPERM means the process exists and this
                        // user may not signal it, and anything else is a failure we do not recognise. Both
                        // report RUNNING, and the unknown case does so DELIBERATELY: judging a container dead
                        // on a probe we do not understand would let one broken probe remove the containers of
                        // a concurrently running fork or worktree, which is the cross-fork hazard the whole
                        // owner-pid predicate exists to prevent. Reading the message rather than the error's
                        // `code` is what keeps that default safe: if Node's wording ever changes, the test
                        // stops matching and the container is spared rather than destroyed.
                        !String.valueOf(thrown).contains("ESRCH")
            }
        }

end TestProcessId
