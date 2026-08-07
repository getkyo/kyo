package demo

import kyo.*

/** Validates the kyo-system demo against the real Path capability pipeline.
  *
  * Runs the demo’s `flow` through
  * `ConfigWorkspaceDemo.flow` and asserts `validate` returns `Absent`.
  */
class ConfigWorkspaceDemoTest extends kyo.test.Test[Any]:

    "ConfigWorkspaceDemo: validates supported commits and reports unsupported host durability" in {
        Abort.run[FileSystemException | CommitConflict](ConfigWorkspaceDemo.flow).map {
            case Result.Success(snapshot) if !kyo.internal.Platform.isWindows =>
                val verdict = ConfigWorkspaceDemo.validate(snapshot)
                assert(verdict == Absent, s"demo validate must return Absent; got: $verdict")
            case result if kyo.internal.Platform.isWindows =>
                val failedDirectory = result match
                    case Result.Failure(FileAccessDeniedException(path))                             => path
                    case Result.Failure(FileIOException(path, FileSystemOperation.SyncDirectory, _)) => path
                    case other => fail(s"Expected a directory barrier failure, got $other")
                assert(OverlayFileSystemWindowsTest.directoryFailure(result, failedDirectory), s"Demo directory barrier result: $result")
                assert(failedDirectory.name.exists(_.startsWith("config-workspace-")))
                Path.run(failedDirectory.exists.map(exists => assert(!exists)))
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

end ConfigWorkspaceDemoTest
