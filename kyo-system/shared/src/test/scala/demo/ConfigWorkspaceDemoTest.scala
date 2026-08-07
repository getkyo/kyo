package demo

import kyo.*

/** Validates the kyo-system demo against the real Path capability pipeline.
  *
  * Runs the demo’s `flow` through
  * `ConfigWorkspaceDemo.flow` and asserts `validate` returns `Absent`.
  */
class ConfigWorkspaceDemoTest extends kyo.test.Test[Any]:

    "ConfigWorkspaceDemo: flow drives Path.run and validate returns Absent" in {
        Abort.run[FileSystemException | CommitConflict](ConfigWorkspaceDemo.flow).map {
            case Result.Success(snapshot) =>
                val verdict = ConfigWorkspaceDemo.validate(snapshot)
                assert(verdict == Absent, s"demo validate must return Absent; got: $verdict")
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

end ConfigWorkspaceDemoTest
