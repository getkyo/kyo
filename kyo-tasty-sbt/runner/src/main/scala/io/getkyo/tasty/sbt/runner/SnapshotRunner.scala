package io.getkyo.tasty.sbt.runner

import kyo.*

/** Forked-JVM entrypoint invoked by KyoTastyPlugin's tastySnapshot task.
  *
  * Receives two arguments:
  *   - args(0): colon-separated (or OS path-separator-separated) compile classpath roots
  *   - args(1): absolute path to the snapshot output directory
  *
  * Calls `Tasty.Classpath.openCached`, which writes a `.krfl` snapshot to the given directory if none exists for the current classpath
  * digest. Exits 0 on success, 1 on known [[TastyError]], 2 on unexpected failure.
  *
  * Error output goes to stderr as a single line (no stack trace) so the sbt plugin can relay it in a
  * [[sbt.internal.util.MessageOnlyException]].
  */
object SnapshotRunner extends KyoApp:
    run {
        if args.size < 2 then
            java.lang.System.err.println(
                "kyo-tasty-sbt-runner: expected 2 arguments: <classpath-roots> <snapshot-dir>"
            )
            java.lang.Runtime.getRuntime.halt(1)
            Sync.defer(())
        else
            val roots       = args(0).split(java.io.File.pathSeparatorChar).toSeq.filterNot(_.isEmpty)
            val snapshotDir = args(1)

            Abort.run[TastyError](Tasty.Classpath.openCached(roots, snapshotDir)).map:
                case Result.Success(_) =>
                    java.lang.System.out.println(
                        s"kyo-tasty-sbt: snapshot written to $snapshotDir"
                    )
                case Result.Failure(err) =>
                    java.lang.System.err.println(
                        s"kyo-tasty-sbt-runner: TastyError: $err"
                    )
                    java.lang.Runtime.getRuntime.halt(1)
                case Result.Panic(t) =>
                    java.lang.System.err.println(
                        s"kyo-tasty-sbt-runner: unexpected error: ${t.getMessage}"
                    )
                    java.lang.Runtime.getRuntime.halt(2)
    }
end SnapshotRunner
