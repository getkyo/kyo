package kyo

import scala.scalajs.js as sjs

/** Verifies the `isNodeRuntime` predicate and the typed [[JournalStorageError]]
  * failure produced by `Backend.file` on a non-Node runtime.
  *
  * Both arms of the predicate are exercised: the true arm runs naturally (these tests execute
  * under NodeJSEnv), and the false arm is forced by temporarily shadowing the global `process`
  * object. Node.js is single-threaded, so the shadow-and-restore is safe.
  */
class FileJournalNodeRuntimeTest extends kyo.test.Test[Any]:

    "isNodeRuntime" - {

        "returns true under NodeJSEnv (normal test runner)" in {
            assert(isNodeRuntime, "expected isNodeRuntime to be true under NodeJSEnv")
        }

        "returns false when global.process is undefined" in {
            val savedProcess = sjs.Dynamic.global.process
            sjs.Dynamic.global.updateDynamic("process")(sjs.undefined)
            val result =
                try isNodeRuntime
                finally sjs.Dynamic.global.updateDynamic("process")(savedProcess)
            assert(!result, "expected isNodeRuntime to be false when process is undefined")
        }

        "returns false when process.versions is undefined" in {
            val savedProcess = sjs.Dynamic.global.process
            val fakeProcess  = sjs.Dynamic.literal()
            sjs.Dynamic.global.updateDynamic("process")(fakeProcess)
            val result =
                try isNodeRuntime
                finally sjs.Dynamic.global.updateDynamic("process")(savedProcess)
            assert(!result, "expected isNodeRuntime to be false when process.versions is undefined")
        }

        "returns false when process.versions.node is undefined" in {
            val savedProcess = sjs.Dynamic.global.process
            val fakeVersions = sjs.Dynamic.literal()
            val fakeProcess  = sjs.Dynamic.literal(versions = fakeVersions)
            sjs.Dynamic.global.updateDynamic("process")(fakeProcess)
            val result =
                try isNodeRuntime
                finally sjs.Dynamic.global.updateDynamic("process")(savedProcess)
            assert(!result, "expected isNodeRuntime to be false when process.versions.node is undefined")
        }

    }

    "Backend.file on non-Node runtime" - {

        // Construct the file effect while isNodeRuntime is false (process shadowed);
        // the check is eager so the returned kyo value is already an Abort.fail.
        // Restore process before running it so subsequent IO (Scope.run) works normally.
        "produces JournalStorageError mentioning Node.js when isNodeRuntime is false" in {
            val savedProcess = sjs.Dynamic.global.process
            sjs.Dynamic.global.updateDynamic("process")(sjs.undefined)
            // Journal.Backend.file evaluates isNodeRuntime eagerly at call time.
            val effect =
                try Journal.Backend.file(Path("any-path-will-not-be-used"))
                finally sjs.Dynamic.global.updateDynamic("process")(savedProcess)
            // Run the effect; it is already an Abort.fail so no real I/O occurs.
            Scope.run {
                Abort.run[JournalStorageError](effect)
            }.map { result =>
                result match
                    case Result.Failure(e: JournalStorageError) =>
                        assert(
                            e.detail.contains("Node.js"),
                            s"expected 'Node.js' in error detail but got: ${e.detail}"
                        )
                    case other =>
                        fail(s"expected Failure(JournalStorageError) but got $other")
            }
        }

    }

end FileJournalNodeRuntimeTest
