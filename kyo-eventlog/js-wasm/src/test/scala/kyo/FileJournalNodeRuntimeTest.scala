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

    private def binaryConfiguration(using Frame) =
        for
            codecs    <- EventLogCodecs.bytes()
            journalId <- JournalId("fj-node-runtime")
            configuration = FileJournal.Binary.configuration(journalId, codecs)
        yield configuration

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

        // The requireNode guard lives in the platformSyncStore seam provider, so it fires at the
        // first store operation (StoreSeam.open / acquireLock) during Scope.run's execution, not at
        // the Journal.Backend.file call site. process stays shadowed across construction AND
        // execution (sequenced via Sync.defer so it runs in program order around Scope.run), then is
        // restored afterward either way.
        "produces JournalStorageError mentioning Node.js when isNodeRuntime is false" in {
            for
                configuration <- binaryConfiguration
                savedProcess <- Sync.defer {
                    val saved = sjs.Dynamic.global.process
                    sjs.Dynamic.global.updateDynamic("process")(sjs.undefined)
                    saved
                }
                result <- Scope.run {
                    Abort.run[JournalStorageError](Journal.Backend.file(Path("any-path-will-not-be-used"), configuration))
                }
                _ <- Sync.defer(sjs.Dynamic.global.updateDynamic("process")(savedProcess))
            yield result match
                case Result.Failure(e: JournalStorageError) =>
                    assert(
                        e.detail.contains("Node.js"),
                        s"expected 'Node.js' in error detail but got: ${e.detail}"
                    )
                case other =>
                    fail(s"expected Failure(JournalStorageError) but got $other")
        }

    }

end FileJournalNodeRuntimeTest
