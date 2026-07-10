package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Tests for ClasspathFingerprint covering hash stability, content-change detection, and ordering invariance. */
class ClasspathFingerprintTest extends kyo.test.Test[Any]:

    // Create a temporary file with given contents and return it as a kyo.Path.
    private def makeTempFile(dir: kyo.Path, name: String, content: Array[Byte])(using Frame): kyo.Path < (Sync & Abort[Doctest.Error]) =
        val file = dir / name
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(file, "write", e))) {
            Path.run(file.writeBytes(Span.from(content)).andThen(file))
        }
    end makeTempFile

    // Per-test temp directory, cleaned up via Scope.acquireRelease.
    private def withTempDir[A, S](f: kyo.Path => A < (Async & Sync & Scope & S))(using Frame): A < (Async & Sync & Scope & S) =
        for
            id <- Random.uuid
            dir = Path.basePaths.tmp / s"kyo-doctest-fp-test-$id"
            _   <- Abort.run[FileException](Path.run(dir.mkDir)).unit
            res <- Scope.acquireRelease(Sync.defer(dir))(_ => Abort.run[FileException](Path.run(dir.removeAll)).unit).flatMap(f)
        yield res

    "compute returns stable hash for unchanged jars" in {
        withTempDir { dir =>
            makeTempFile(dir, "a.jar", Array[Byte](1, 2, 3, 4)).flatMap { jar1 =>
                makeTempFile(dir, "b.jar", Array[Byte](5, 6, 7, 8)).flatMap { jar2 =>
                    ClasspathFingerprint.compute(Chunk(jar1, jar2)).flatMap { hash1 =>
                        ClasspathFingerprint.compute(Chunk(jar1, jar2)).map { hash2 =>
                            assert(hash1 == hash2, s"expected stable hash but got '$hash1' vs '$hash2'")
                            assert(hash1.nonEmpty, "hash should be non-empty")
                            assert(hash1.forall(c => "0123456789abcdef".contains(c)), s"hash '$hash1' is not valid hex")
                        }
                    }
                }
            }
        }
    }

    "compute differs after jar content changes" in {
        withTempDir { dir =>
            makeTempFile(dir, "lib.jar", Array[Byte](1, 2, 3, 4)).flatMap { jar =>
                ClasspathFingerprint.compute(Chunk(jar)).flatMap { hash1 =>
                    // Overwrite the jar with different content.
                    Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(jar, "write", e))) {
                        Path.run(jar.writeBytes(Span.from(Array[Byte](9, 8, 7, 6))))
                    }.flatMap { _ =>
                        ClasspathFingerprint.compute(Chunk(jar)).map { hash2 =>
                            assert(hash1 != hash2, s"expected different hashes after content change, but both were '$hash1'")
                        }
                    }
                }
            }
        }
    }

    "compute is invariant to jar ordering" in {
        withTempDir { dir =>
            makeTempFile(dir, "x.jar", Array[Byte](10, 20, 30)).flatMap { jar1 =>
                makeTempFile(dir, "y.jar", Array[Byte](40, 50, 60)).flatMap { jar2 =>
                    makeTempFile(dir, "z.jar", Array[Byte](70, 80, 90)).flatMap { jar3 =>
                        ClasspathFingerprint.compute(Chunk(jar1, jar2, jar3)).flatMap { hash1 =>
                            // Different orderings should produce the same hash.
                            ClasspathFingerprint.compute(Chunk(jar3, jar1, jar2)).flatMap { hash2 =>
                                ClasspathFingerprint.compute(Chunk(jar2, jar3, jar1)).map { hash3 =>
                                    assert(hash1 == hash2, s"ordering 1 vs 2: '$hash1' != '$hash2'")
                                    assert(hash1 == hash3, s"ordering 1 vs 3: '$hash1' != '$hash3'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end ClasspathFingerprintTest
