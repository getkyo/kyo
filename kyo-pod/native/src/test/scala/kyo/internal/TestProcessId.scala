package kyo.internal

import kyo.*
import scala.scalanative.libc.errno
import scala.scalanative.posix.errno.ESRCH
import scala.scalanative.posix.signal
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** This test process's own pid, plus a liveness probe for a foreign pid, for [[TestContainers]]'s ownership predicate.
  *
  * `kyo.Process` cannot serve either role: it is a handle over a process this program spawned, so its `pid` and `isAlive` describe a child
  * rather than the current process or an arbitrary pid read from a container label.
  */
private[kyo] object TestProcessId:

    /** This process's pid, stamped into the `kyo-test-owner-pid` label of every container it creates. */
    val pid: Long = unistd.getpid().toLong

    /** The process table [[pid]] belongs to, stamped into the `kyo-test-owner-ns` label: the host name, then on Linux the pid namespace.
      * See the JVM `TestProcessId` for why both parts are needed. `gethostname(2)` does no DNS lookup; a failed call or a missing
      * `/proc/self/ns/pid` leaves its part empty.
      */
    val namespace: String =
        Zone {
            val size = 256
            val buf  = alloc[Byte](size)
            // gethostname does not terminate a name it had to truncate.
            buf(size - 1) = 0.toByte
            val host = if unistd.gethostname(buf, (size - 1).toCSize) == 0 then fromCString(buf) else ""
            val n    = unistd.readlink(c"/proc/self/ns/pid", buf, (size - 1).toCSize)
            if n > 0 then
                buf(n) = 0.toByte
                s"$host/${fromCString(buf)}"
            else host
            end if
        }
    end namespace

    /** Whether `pid`, read from a container label, names a process that is still running.
      *
      * A value that does not parse as a `Long` reports not-running, which reaps the container. That is deliberate and it is the same
      * judgement as a missing owner label: the only writer of this label is `TestContainers`, which always writes
      * `TestProcessId.pid.toString`, so a value that does not parse cannot have come from a live test process.
      *
      * Every other unknown outcome reports RUNNING. See the comment below.
      */
    def isAlive(pid: String)(using Frame): Boolean < Sync =
        Sync.defer {
            pid.toLongOption.exists { p =>
                // kill(pid, 0) probes existence without delivering a signal. 0 means the process exists and is
                // signalable. On failure ONLY ESRCH ("no such process") is death: EPERM means the process
                // exists and this user may not signal it, and any other errno is a failure we do not
                // recognise. Both report RUNNING, and the unknown case does so DELIBERATELY: judging a
                // container dead on a probe we do not understand would let one broken probe remove the
                // containers of a concurrently running fork or worktree, which is the cross-fork hazard the
                // whole owner-pid predicate exists to prevent.
                // errno is read only on the failure branch, where kill is what set it; after a successful
                // kill the short circuit means errno is never consulted, so a stale value cannot be read.
                signal.kill(p.toInt, 0) == 0 || errno.errno != ESRCH
            }
        }

end TestProcessId
