package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for [[FileSystem.zipReadOnly]]'s implementor: round-trip reads over an archive written
  * through [[FileSystem.zip]], nested-directory listing derived from entry-name prefixes,
  * absence of every write-family method and write-channel acquisition at compile time,
  * and cross-platform DEFLATED-entry inflate parity.
  */
class ZipReadOnlyFileSystemTest extends kyo.test.Test[Any]:

    private def bytesOf(s: String): Span[Byte] = Span.from(s.getBytes("UTF-8"))

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    private def expectFileIOException[A, S](program: A < (S & Abort[FileSystemException]))(using
        kyo.test.AssertScope
    ): Unit < (S & Abort[FileSystemException]) =
        Abort.run[FileSystemException](program).map {
            case Result.Failure(e: FileIOException) => assert(e.getMessage.contains("I/O error"))
            case other                              => assert(false, s"expected Failure(FileIOException), got $other")
        }

    // A zip archive containing one DEFLATED (method 8) entry "greeting.txt", built once via
    // java.util.zip.ZipOutputStream on the JVM at fixture-authoring time (mirroring
    // PortableInflateTest.scala's own checked-in-fixture convention, and byte-identical to the
    // fixture kyo.internal.ZipArchiveTest carries). Known original content:
    // "The quick brown fox jumps over the lazy dog. ".repeat(30).
    private val deflatedFixtureContent: Array[Byte] = "The quick brown fox jumps over the lazy dog. ".repeat(30).getBytes("UTF-8")
    private val deflatedFixtureZip: Array[Byte] = Array(
        80,
        75,
        3,
        4,
        20,
        0,
        8,
        8,
        8,
        0,
        153.toByte,
        9,
        242.toByte,
        92,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        12,
        0,
        0,
        0,
        103,
        114,
        101,
        101,
        116,
        105,
        110,
        103,
        46,
        116,
        120,
        116,
        11,
        201.toByte,
        72,
        85,
        40,
        44,
        205.toByte,
        76,
        206.toByte,
        86,
        72,
        42,
        202.toByte,
        47,
        207.toByte,
        83,
        72,
        203.toByte,
        175.toByte,
        80,
        200.toByte,
        42,
        205.toByte,
        45,
        40,
        86,
        200.toByte,
        47,
        75,
        45,
        82,
        40,
        1,
        74,
        231.toByte,
        36,
        86,
        85,
        42,
        164.toByte,
        228.toByte,
        167.toByte,
        235.toByte,
        41,
        132.toByte,
        140.toByte,
        42,
        30,
        85,
        60,
        170.toByte,
        120,
        84,
        241.toByte,
        168.toByte,
        98,
        84,
        197.toByte,
        0,
        80,
        75,
        7,
        8,
        43,
        241.toByte,
        31,
        103,
        59,
        0,
        0,
        0,
        70,
        5,
        0,
        0,
        80,
        75,
        1,
        2,
        20,
        0,
        20,
        0,
        8,
        8,
        8,
        0,
        153.toByte,
        9,
        242.toByte,
        92,
        43,
        241.toByte,
        31,
        103,
        59,
        0,
        0,
        0,
        70,
        5,
        0,
        0,
        12,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        103,
        114,
        101,
        101,
        116,
        105,
        110,
        103,
        46,
        116,
        120,
        116,
        80,
        75,
        5,
        6,
        0,
        0,
        0,
        0,
        1,
        0,
        1,
        0,
        58,
        0,
        0,
        0,
        117,
        0,
        0,
        0,
        0,
        0
    )

    "read-only zip exposes a scoped read channel and no write-channel acquisition" in {
        val positive = typeCheckErrors("""
            def use(fs: kyo.FileSystem.Read[kyo.Sync], path: kyo.Path)(using kyo.Frame) = fs.openReadChannel(path)
        """)
        val negative = typeCheckErrors("""
            def use(fs: kyo.FileSystem.Read[kyo.Sync], path: kyo.Path)(using kyo.Frame) =
                fs.openWriteChannel(path, kyo.FileSystem.WriteOpen.Existing)
        """)
        assert(positive.isEmpty)
        assert(negative.nonEmpty)

        Scope.run {
            hostTempDir("kyo-zip-read-channel").map { dir =>
                val archive = dir / "fixture.zip"
                FileSystem.host.writeBytes(archive, Span.from(deflatedFixtureZip), Path.WriteOptions()).andThen {
                    FileSystem.zipReadOnly(archive).map { fs =>
                        fs.openReadChannelUnscoped(Path("greeting.txt")).map { case (channel, release) =>
                            channel.readAt(0L, 9).map(result => assert(result.is(bytesOf("The quick")))).andThen {
                                release().andThen {
                                    Abort.run[FileSystemException](channel.size).map {
                                        case Result.Failure(_: FileIOException) => assert(true)
                                        case other => assert(false, s"expected closed zip channel failure, got $other")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "round-trip read of an entry written through FileSystem.zip and committed" in {
        Scope.run {
            hostTempDir("kyo-zip-readonly-roundtrip").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a", "b.txt"), bytesOf("hello"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { zr =>
                        zr.readBytes(Path("a", "b.txt")).map(result => assert(result.is(bytesOf("hello"))))
                    }
                }
            }
        }
    }

    "nested directory listing derives from entry-name prefixes, including an implied (no explicit entry) directory" in {
        Scope.run {
            hostTempDir("kyo-zip-readonly-listing").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a", "b.txt"), bytesOf("b"), Path.WriteOptions(createFolders = true)).andThen {
                        zw.writeBytes(Path("a", "c", "d.txt"), bytesOf("d"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                    }
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { zr =>
                        zr.list(Path("a")).map { listed =>
                            assert(listed == Chunk(Path("a", "b.txt"), Path("a", "c")))
                        }
                    }
                }
            }
        }
    }

    "Glob selection is portable, root-relative, and case-configurable" in {
        Scope.run {
            hostTempDir("kyo-zip-readonly-glob").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a", "TOP.JSON"), bytesOf("top"), Path.WriteOptions()).andThen {
                        zw.writeBytes(Path("a", "nested", "deep.json"), bytesOf("deep"), Path.WriteOptions()).andThen(zw.commit)
                    }
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { zr =>
                        zr.list(Path("a"), glob"*.json").map { sensitive =>
                            zr.list(Path("a"), glob"*.json", Glob.CaseSensitivity.Insensitive).map { insensitive =>
                                assert(sensitive.isEmpty)
                                assert(insensitive == Chunk(Path("a", "TOP.JSON")))
                            }
                        }
                    }
                }
            }
        }
    }

    "exists/isDirectory/isRegularFile distinguish an implied directory from a regular file with no explicit directory entry present" in {
        Scope.run {
            hostTempDir("kyo-zip-readonly-implied-dir").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("x", "y.txt"), bytesOf("y"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { zr =>
                        zr.isDirectory(Path("x")).map { xIsDir =>
                            zr.isRegularFile(Path("x", "y.txt")).map { yIsFile =>
                                zr.isRegularFile(Path("x")).map { xIsFile =>
                                    assert(xIsDir)
                                    assert(yIsFile)
                                    assert(!xIsFile)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "round-trip read of a DEFLATED entry inflates byte-identical on JVM, JS, Native, and Wasm" in {
        Scope.run {
            hostTempDir("kyo-zip-readonly-deflated").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.host.writeBytes(archive, Span.fromUnsafe(deflatedFixtureZip), Path.WriteOptions(createFolders = true)).andThen {
                    FileSystem.zipReadOnly(archive).map { zr =>
                        zr.readBytes(Path("greeting.txt")).map(result => assert(result.is(Span.fromUnsafe(deflatedFixtureContent))))
                    }
                }
            }
        }
    }

end ZipReadOnlyFileSystemTest
