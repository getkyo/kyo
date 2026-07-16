package demo

import kyo.*

/** Validates the kyo-system demo against the real Path capability pipeline.
  *
  * Drives the SAME `flow` a deploy-tool author reads (no re-implemented copy) through
  * `ConfigWorkspaceDemo.flow` and asserts `validate` returns `Absent`.
  */
class DemoValidationTest extends kyo.test.Test[Any]:

    "ConfigWorkspaceDemo: flow drives Path.run and validate returns Absent" in {
        Abort.run[FileException | CommitConflict](ConfigWorkspaceDemo.flow).map {
            case Result.Success(snapshot) =>
                val verdict = ConfigWorkspaceDemo.validate(snapshot)
                assert(verdict == Absent, s"demo validate must return Absent; got: $verdict")
            case other =>
                assert(false, s"demo flow must not abort; got: $other")
        }
    }

end DemoValidationTest
