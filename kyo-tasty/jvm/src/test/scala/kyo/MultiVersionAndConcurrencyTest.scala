package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource

/** Failure-mode and concurrency behaviors that need real JVM filesystem and version-divergent jars:
  *   - multi-version stdlib roots under FailFast surface TastyError.FqnCollisionError
  *   - concurrent SnapshotReader + SnapshotWriter on the same path observe pre-write or post-write contents, never a corrupt mix
  *
  * The atomic-rename semantics that make this safe live in JvmFileSource (java.nio.file.Files.move with ATOMIC_MOVE).
  */
class MultiVersionAndConcurrencyTest extends kyo.test.Test[Any]:

    "multi-version stdlib FailFast init aborts with FqnCollisionError" in {
        val multiRoots = TestClasspaths2.multiVersionStdlibRoots
        val src        = PlatformFileSource.get
        System.availableProcessors.map { concurrency =>
            Scope.run(Abort.run[TastyError](
                ClasspathOrchestrator.init(multiRoots, Tasty.ErrorMode.FailFast, src, concurrency)
            )).map { result =>
                result match
                    case Result.Failure(_: TastyError.FqnCollisionError) =>
                        succeed
                    case Result.Success(_) =>
                        fail(
                            "Expected Abort.fail(FqnCollisionError) when loading two roots with same-FQN symbols under FailFast; init succeeded silently"
                        )
                    case Result.Failure(other) =>
                        fail(s"Expected FqnCollisionError; got different TastyError: $other")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: $t")
            }
        }
    }

    "concurrent snapshot reader+writer: reader sees pre- or post-write, not corrupt" in {
        val digest = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        TestClasspaths.withClasspath()(Tasty.classpath).map { cp =>
            Sync.defer {
                TestClasspaths2.createTempDir("kyo-df2-rw-test")
            }.map { tmpDir =>
                TestClasspaths2.runConcurrentReaderWriterTest(cp, digest, tmpDir).map { ok =>
                    assert(ok, "Panic during concurrent reader+writer test")
                    succeed
                }
            }
        }
    }

end MultiVersionAndConcurrencyTest
