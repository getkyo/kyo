package kyo

import kyo.internal.tasty.snapshot.DigestComputer

/** Cross-platform digest equality test.
  *
  * Verifies that DigestComputer.compute produces byte-identical results when called with the same
  * real filesystem content. Builds a synthetic classpath of known content using Path.tempDir and
  * Path.writeBytes, then asserts that two successive calls return the same 8-byte digest.
  *
  * This test is cross-platform: it runs on JVM, JS (Node.js), and Native, all of which support
  * real filesystem access via kyo.Path. The test is placed in shared/src/test so it exercises the
  * shared DigestComputer code path on every platform.
  */
class DigestEqualityTest extends kyo.test.Test[Any]:

    "compute on real files is deterministic across two calls" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-deq")).map { dir =>
                val fileA = dir / "Alpha.tasty"
                val fileB = dir / "Beta.tasty"
                Path.run(fileA.writeBytes(Span.from(Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05)))).map { _ =>
                    Path.run(fileB.writeBytes(Span.from(Array[Byte](0x10, 0x20, 0x30, 0x40)))).map { _ =>
                        val root = dir.toString
                        Abort.run[TastyError] {
                            DigestComputer.compute(Seq(root)).map { d1 =>
                                DigestComputer.compute(Seq(root)).map { d2 =>
                                    (d1, d2)
                                }
                            }
                        }
                            .map {
                                case Result.Success((d1, d2)) =>
                                    assert(d1.length == 8, s"digest must be 8 bytes, got ${d1.length}")
                                    assert(d1.sameElements(d2), s"compute must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
                                case Result.Failure(e) =>
                                    fail(s"unexpected failure: $e")
                                case Result.Panic(t) =>
                                    throw t
                            }
                    }
                }
            }
        }
    }

    "compute result changes when a file is added to the root" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-deq-add")).map { dir =>
                val fileA = dir / "Alpha.tasty"
                Path.run(fileA.writeBytes(Span.from(Array[Byte](0x01, 0x02, 0x03)))).map { _ =>
                    val root = dir.toString
                    Abort.run[TastyError] {
                        DigestComputer.compute(Seq(root)).map { d1 =>
                            val fileB = dir / "Beta.tasty"
                            Path.run(fileB.writeBytes(Span.from(Array[Byte](0x04, 0x05, 0x06)))).map { _ =>
                                DigestComputer.compute(Seq(root)).map { d2 =>
                                    (d1, d2)
                                }
                            }
                        }
                    }
                        .map {
                            case Result.Success((d1, d2)) =>
                                assert(!d1.sameElements(d2), "adding a file must change the digest")
                            case Result.Failure(e) =>
                                fail(s"unexpected failure: $e")
                            case Result.Panic(t) =>
                                throw t
                        }
                }
            }
        }
    }

end DigestEqualityTest
