package kyo.internal

import kyo.*

/** This test process's own pid, plus a liveness probe for a foreign pid, for [[SqlTestContainers]]'s ownership predicate.
  *
  * `kyo.Process` cannot serve either role: it is a handle over a process this program spawned, so its `pid` and `isAlive` describe a child
  * rather than the current process or an arbitrary pid read from a container label.
  */
private[kyo] object TestProcessId:

    /** This process's pid, stamped into the `kyo-sql-owner-pid` label of every container it creates. */
    val pid: Long = ProcessHandle.current().pid()

    /** Whether `pid`, read from a container label, names a process that is still running.
      *
      * A value that does not parse as a `Long` reports not-running, which reaps the container. That is deliberate and it is the same
      * judgement as a missing owner label: the only writer of this label is `SqlTestContainers.initSingleton`, which always writes
      * `TestProcessId.pid.toString`, so a value that does not parse cannot have come from a live kyo-sql test process.
      */
    def isAlive(pid: String)(using Frame): Boolean < Sync =
        Sync.defer {
            pid.toLongOption.exists { p =>
                // This probe has no unknown outcome to spare on, unlike the JS and Native ones. JDK 25 declares
                // `ProcessHandle.of` as throwing `UnsupportedOperationException` alone (JEP 486 removed the
                // `SecurityException` case), and a pid naming no process returns an empty `Optional` rather than
                // failing, so the call is total for every long on every platform kyo's JVM tests run.
                // On a hypothetical platform without the operation, the throw would abort the sweep with nothing
                // further removed, which is the same spare-on-unknown direction the other two probes take.
                val handle = ProcessHandle.of(p)
                handle.isPresent && handle.get.isAlive
            }
        }

end TestProcessId
