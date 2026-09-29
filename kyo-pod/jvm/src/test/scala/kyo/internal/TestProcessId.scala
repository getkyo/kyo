package kyo.internal

import kyo.*

/** This test process's own pid, plus a liveness probe for a foreign pid, for [[TestContainers]]'s ownership predicate.
  *
  * `kyo.Process` cannot serve either role: it is a handle over a process this program spawned, so its `pid` and `isAlive` describe a child
  * rather than the current process or an arbitrary pid read from a container label.
  */
private[kyo] object TestProcessId:

    /** This process's pid, stamped into the `kyo-test-owner-pid` label of every container it creates. */
    val pid: Long = ProcessHandle.current().pid()

    /** The process table [[pid]] belongs to, stamped into the `kyo-test-owner-ns` label: the host name, then on Linux the pid namespace.
      *
      * Both parts are needed. A build container shares the host's daemon but not its process table, and its host name alone does not tell
      * two of them apart under `--network host`; the pid namespace does. Two Linux hosts sharing one daemon both sit in the initial pid
      * namespace, whose id is the same on every host; the host name tells those apart. On Linux both come from `/proc`, which does no DNS
      * lookup; elsewhere the host name comes from `InetAddress`, and a host whose own name does not resolve gets a fixed name, which judges
      * its containers by pid exactly as a namespace-less label would.
      */
    val namespace: String =
        val proc = java.nio.file.Paths.get("/proc/self/ns/pid")
        // The link names `pid:[<inode>]`, no file, so only a check that does not follow it finds it.
        if java.nio.file.Files.exists(proc, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
            val host = java.nio.file.Files.readString(java.nio.file.Paths.get("/proc/sys/kernel/hostname")).trim
            s"$host/${java.nio.file.Files.readSymbolicLink(proc)}"
        else Result.catching[java.net.UnknownHostException](java.net.InetAddress.getLocalHost.getHostName).getOrElse("unresolved-host")
        end if
    end namespace

    /** Whether `pid`, read from a container label, names a process that is still running.
      *
      * A value that does not parse as a `Long` reports not-running, which reaps the container. That is deliberate and it is the same
      * judgement as a missing owner label: the only writer of this label is `TestContainers`, which always writes
      * `TestProcessId.pid.toString`, so a value that does not parse cannot have come from a live test process.
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
