package kyo

class PathStatTest extends kyo.test.Test[Any]:

    "stat returns size matching written bytes" in {
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

    "stat returns lastModifiedMs near current time" in {
        Path.tempDir("kyo-path-stat").map { dir =>
            val file  = dir / "data.bin"
            val bytes = Span.from(Array[Byte](0x42))
            file.writeBytes(bytes).map { _ =>
                Clock.now.map { now =>
                    file.stat.map { stat =>
                        val nowMs   = now.toJava.toEpochMilli
                        val deltaMs = math.abs(nowMs - stat.lastModifiedMs)
                        assert(deltaMs < 10_000L, s"expected stat.lastModifiedMs ~ $nowMs, got ${stat.lastModifiedMs}, delta ${deltaMs}ms")
                    }
                }
            }
        }
    }

    "stat on missing path aborts with FileReadException" in {
        Path.tempDir("kyo-path-stat").map { dir =>
            val missing = dir / "no-such-file.bin"
            Abort.run[FileReadException](missing.stat).map {
                case Result.Failure(_: FileReadException) => succeed
                case other                                => fail(s"expected FileReadException, got $other")
            }
        }
    }

    "setLastModified round-trips through stat" in {
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

    "setLastModified on missing path aborts with FileWriteException" in {
        Path.tempDir("kyo-path-stat").map { dir =>
            val missing = dir / "no-such-file.bin"
            Abort.run[FileWriteException](missing.setLastModified(1_000_000_000_000L)).map {
                case Result.Failure(_: FileWriteException) => succeed
                case other                                 => fail(s"expected FileWriteException, got $other")
            }
        }
    }

end PathStatTest
