package kyo

class PathStatTest extends kyo.test.Test[Any]:

    "stat returns size matching written bytes" in {
        Scope.run {
            Path.tempDir("kyo-path-stat").map { dir =>
                val file  = dir / "data.bin"
                val bytes = Span.from(Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05))
                file.writeBytes(bytes).map { _ =>
                    file.stat.map { stat =>
                        assert(stat.sizeBytes == 5L)
                    }
                }
            }
        }
    }

    "stat reports a lastModifiedMs bracketed by the write" in {
        Scope.run {
            Path.tempDir("kyo-path-stat").map { dir =>
                val file  = dir / "data.bin"
                val bytes = Span.from(Array[Byte](0x42))
                // Bracket the write between two wall-clock reads with a couple-seconds slack on BOTH sides, and
                // assert the mtime falls inside. The slack absorbs two legitimate sources of disagreement a tight
                // bound would race: coarse-resolution filesystems that floor mtime to the second (FAT/HFS+), and, on
                // Scala.js, that the filesystem mtime and Clock.now (V8's Date.now) are read through different clocks,
                // so the mtime can land a hair after `after`. It still catches a wrong mtime (epoch zero, wrong unit,
                // garbage), which is off by far more than a couple seconds.
                Clock.now.map { before =>
                    file.writeBytes(bytes).map { _ =>
                        Clock.now.map { after =>
                            file.stat.map { stat =>
                                val lo = before.toJava.toEpochMilli - 2000L
                                val hi = after.toJava.toEpochMilli + 2000L
                                assert(
                                    stat.lastModifiedMs >= lo && stat.lastModifiedMs <= hi,
                                    s"expected stat.lastModifiedMs in [$lo, $hi], got ${stat.lastModifiedMs}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "stat on missing path aborts with FileReadException" in {
        Scope.run {
            Path.tempDir("kyo-path-stat").map { dir =>
                val missing = dir / "no-such-file.bin"
                Abort.run[FileSystemException](missing.stat).map {
                    case Result.Failure(_: FileReadException) => succeed
                    case other                                => fail(s"expected FileReadException, got $other")
                }
            }
        }
    }

    "setLastModified round-trips through stat" in {
        Scope.run {
            Path.tempDir("kyo-path-stat").map { dir =>
                val file     = dir / "mtime.bin"
                val targetMs = 1_000_000_000_000L // 2001-09-08 UTC, well in the past
                file.writeBytes(Span.from(Array[Byte](0x01))).map { _ =>
                    file.setLastModified(targetMs).map { _ =>
                        file.stat.map { st =>
                            // Allow up to 2000ms rounding: some filesystems only have 1-second resolution.
                            assert(
                                math.abs(st.lastModifiedMs - targetMs) <= 2000L,
                                s"expected lastModifiedMs near $targetMs, got ${st.lastModifiedMs}"
                            )
                        }
                    }
                }
            }
        }
    }

    "setLastModified on missing path aborts with FileWriteException" in {
        Scope.run {
            Path.tempDir("kyo-path-stat").map { dir =>
                val missing = dir / "no-such-file.bin"
                Abort.run[FileSystemException](missing.setLastModified(1_000_000_000_000L)).map {
                    case Result.Failure(_: FileWriteException) => succeed
                    case other                                 => fail(s"expected FileWriteException, got $other")
                }
            }
        }
    }

end PathStatTest
