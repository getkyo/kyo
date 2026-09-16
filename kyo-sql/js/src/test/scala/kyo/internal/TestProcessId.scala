package kyo.internal

import kyo.*
import scala.scalajs.js

// Node's process module, reached through TestNodeBuiltins at the call rather than through an import at load, for the
// reason that file records: an import runs as the module loads, and a page resolves none, so one of them here kept
// every suite in this module from loading there. The name carries the Test prefix because `kyo.internal.NodeProcess`
// is kyo-system's own Scala object, and a second class of that name on the same link is one the Scala.js linker
// resolves to whichever it saw first, which fails a later lookup of the other one's methods.
private object TestNodeProcess:
    private def module: Maybe[js.Dynamic] = TestNodeBuiltins.get("node:process")

    /** This process's pid, or 0 where there is no process to have one. */
    def pid: Int = module.fold(0)(_.selectDynamic("pid").asInstanceOf[Int])

    def kill(pid: Int, signal: Int): Boolean =
        module.fold(false)(p => p.applyDynamic("kill")(pid, signal).asInstanceOf[Boolean])
end TestNodeProcess

/** This test process's own pid, plus a liveness probe for a foreign pid, for [[SqlTestContainers]]'s ownership predicate.
  *
  * `kyo.Process` cannot serve either role: it is a handle over a process this program spawned, so its `pid` and `isAlive` describe a child
  * rather than the current process or an arbitrary pid read from a container label.
  */
private[kyo] object TestProcessId:

    /** This process's pid, stamped into the `kyo-sql-owner-pid` label of every container it creates. */
    val pid: Long = TestNodeProcess.pid.toLong

    /** Whether `pid`, read from a container label, names a process that is still running.
      *
      * A value that does not parse as a `Long` reports not-running, which reaps the container. That is deliberate and it is the same
      * judgement as a missing owner label: the only writer of this label is `SqlTestContainers.initSingleton`, which always writes
      * `TestProcessId.pid.toString`, so a value that does not parse cannot have come from a live kyo-sql test process.
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
                    val _ = TestNodeProcess.kill(p.toInt, 0)
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
