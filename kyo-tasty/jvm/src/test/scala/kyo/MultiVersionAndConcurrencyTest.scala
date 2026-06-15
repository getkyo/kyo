package kyo

import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Failure-mode behavior that needs real JVM filesystem and version-divergent jars:
  *   - multi-version stdlib roots under FailFast surface TastyError.FullNameCollisionError
  *
  * The atomic-rename + concurrent reader+writer test lives in ConcurrentSnapshotIoTest (shared/src/test), which runs on JVM, JS, and Native.
  */
class MultiVersionAndConcurrencyTest extends kyo.test.Test[Any]:

    "multi-version stdlib FailFast init aborts with FullNameCollisionError" in {
        val multiRoots = TestClasspaths2.multiVersionStdlibRoots
        System.availableProcessors.map { concurrency =>
            Scope.run(Abort.run[TastyError](
                ClasspathOrchestrator.init(multiRoots, Tasty.ErrorMode.FailFast, concurrency)
            )).map { result =>
                result match
                    case Result.Failure(_: TastyError.FullNameCollisionError) =>
                        succeed
                    case Result.Success(_) =>
                        fail(
                            "Expected Abort.fail(FullNameCollisionError) when loading two roots with same-fully-qualified name symbols under FailFast; init succeeded silently"
                        )
                    case Result.Failure(other) =>
                        fail(s"Expected FullNameCollisionError; got different TastyError: $other")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: $t")
            }
        }
    }

end MultiVersionAndConcurrencyTest
