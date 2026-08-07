package kyo

/** The conformance suite for a [[FileSystem]] backend that stages writes behind [[FileSystem.StagedChanges]].
  *
  * A backend supplies [[withFileSystem]]: a fresh backend, its staged changes, and a root to work under for each assertion. The suite
  * holds the lifecycle every implementation owes: writes become visible through `commit`, and a terminated stage refuses a second
  * terminal action. A backend at this tier also reads and writes, so its tests declare subclasses of [[FileSystemReadConformanceTest]]
  * and [[FileSystemWriteConformanceTest]] as well.
  *
  * `S >: Async` bounds the backend effect as described on [[FileSystemReadConformanceTest]].
  */
abstract class FileSystemStagedChangesConformanceTest[S >: Async] extends kyo.test.Test[Any]:

    /** Runs one assertion against a fresh backend, its staged changes, and a root to work under, shaped as a continuation for the
      * reason given on [[FileSystemReadConformanceTest.withFileSystem]].
      */
    protected def withFileSystem[A](
        use: (FileSystem.Write[S], FileSystem.StagedChanges[S & Abort[FileSystemException]], Path) => A <
            (S & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException])

    /** Declares whether a commit can succeed on this platform.
      *
      * A backend whose lower cannot make a commit durable here, such as a host without directory synchronization, must refuse the
      * commit rather than claim it. When this is false the suite asserts that refusal, and that the staged write is still visible.
      */
    protected def supportsCommit: Boolean = true

    "staged suite isolates writes until commit" in {
        withFileSystem { (fileSystem, staged, root) =>
            val path = root / "staged.txt"
            fileSystem.write(path, "value", Path.WriteOptions()).andThen {
                Abort.run[FileSystemException | CommitConflict](staged.commit).map { committed =>
                    if supportsCommit then assert(committed == Result.unit, s"commit failed: $committed")
                    else assert(committed.isFailure, s"an unsupported commit reported $committed")
                    fileSystem.read(path).map(value => assert(value == "value"))
                }
            }
        }
    }

    "staged suite is one-shot" in {
        withFileSystem { (_, staged, _) =>
            Abort.run[FileSystem.StagedChanges.TerminalState](staged.discard).map { discarded =>
                assert(discarded == Result.unit, s"discard failed: $discarded")
                Abort.run[CommitConflict](staged.commit).map {
                    case Result.Failure(_: FileSystem.StagedChanges.AlreadyTerminated) => assert(true)
                    case other => assert(false, s"expected terminal staged changes, got $other")
                }
            }
        }
    }

end FileSystemStagedChangesConformanceTest
