package kyo

/** Tests for the positioned channel tier: [[Path.Channel]], [[FileSystem.openChannel]],
  * [[FileSystem.syncDir]], and [[FileSystem.ChannelMode]].
  *
  * Every leaf holds a [[FileSystem]] value directly ([[FileSystem.host]], [[FileSystem.inMemory]],
  * [[FileSystem.overlay]]) and calls `openChannel`/`syncDir` on it, never through [[Path.run]] or
  * [[Path.runWith]]'s Op-suspension machinery: the channel tier is a service-level surface, not a
  * [[Path.Op]] case.
  */
class PathChannelTest extends kyo.test.Test[Any]:

    private def bytes(ints: Int*): Span[Byte] = Span.from(ints.map(_.toByte).toArray)

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    "host channel readAt/writeAt round trip is byte-identical" in {
        Scope.run {
            hostTempDir("kyo-path-channel-roundtrip").map { dir =>
                val p = dir / "roundtrip.bin"
                FileSystem.host.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                    ch.writeAt(0, bytes(1, 2, 3, 4, 5)).andThen {
                        ch.readAt(1, 3).map(result => assert(result.is(bytes(2, 3, 4))))
                    }
                }
            }
        }
    }

    "inMemory channel readAt/writeAt round trip is byte-identical" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("roundtrip-mem.bin")
            fs.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                ch.writeAt(0, bytes(10, 20, 30)).andThen {
                    ch.readAt(0, 3).map(result => assert(result.is(bytes(10, 20, 30))))
                }
            }
        }
    }

    "overlay channel writeAt stages in the upper and becomes visible on the lower only after commit" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("overlay-stage.bin")
            lower.writeBytes(p, bytes(1, 1), createFolders = true).andThen {
                FileSystem.overlay(lower).map { ov =>
                    ov.openChannel(p, FileSystem.ChannelMode.ReadWrite).map { ch =>
                        ch.writeAt(0, bytes(9, 9)).andThen {
                            lower.readBytes(p).map { beforeCommit =>
                                assert(beforeCommit.is(bytes(1, 1)))
                            }.andThen {
                                ov.commit.andThen {
                                    lower.readBytes(p).map(afterCommit => assert(afterCommit.is(bytes(9, 9))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "overlay channel readAt falls through to the lower when the path is unobserved in the upper" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("overlay-passthrough.bin")
            lower.writeBytes(p, bytes(7, 8, 9), createFolders = true).andThen {
                FileSystem.overlay(lower).map { ov =>
                    ov.openChannel(p, FileSystem.ChannelMode.Read).map { ch =>
                        ch.readAt(0, 3).map(result => assert(result.is(bytes(7, 8, 9))))
                    }
                }
            }
        }
    }

    "host channel readAt tolerates a short read past end-of-file" in {
        Scope.run {
            hostTempDir("kyo-path-channel-short").map { dir =>
                val p = dir / "short.bin"
                FileSystem.host.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                    ch.writeAt(0, bytes(1, 2)).andThen {
                        ch.readAt(0, 10).map { result =>
                            assert(result.size == 2)
                            assert(result.is(bytes(1, 2)))
                        }
                    }
                }
            }
        }
    }

    "writeAt past the current end zero-fills the gap on host and inMemory channels" in {
        Scope.run {
            hostTempDir("kyo-path-channel-gap").map { dir =>
                val p = dir / "gap.bin"
                FileSystem.host.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                    ch.writeAt(3, bytes(7)).andThen {
                        ch.readAt(0, 4).map(result => assert(result.is(bytes(0, 0, 0, 7))))
                    }
                }
            }.andThen {
                FileSystem.inMemory.map { fs =>
                    val p = Path("gap-mem.bin")
                    fs.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                        ch.writeAt(3, bytes(7)).andThen {
                            ch.readAt(0, 4).map(result => assert(result.is(bytes(0, 0, 0, 7))))
                        }
                    }
                }
            }
        }
    }

    "Read mode rejects writeAt on host, inMemory, and overlay channels" in {
        def expectFailure[A, S](program: A < (S & Abort[FileException])): Unit < (S & Abort[FileException]) =
            Abort.run[FileException](program).map {
                case Result.Failure(_) => assert(true)
                case other             => assert(false, s"expected Failure(FileException), got $other")
            }

        Scope.run {
            hostTempDir("kyo-path-channel-ro-host").map { dir =>
                val p = dir / "ro.bin"
                FileSystem.host.mkFile(p).andThen {
                    FileSystem.host.openChannel(p, FileSystem.ChannelMode.Read).map { ch =>
                        expectFailure(ch.writeAt(0, bytes(9)))
                    }
                }
            }
        }.andThen {
            FileSystem.inMemory.map { fs =>
                val p = Path("ro-mem.bin")
                fs.mkFile(p).andThen {
                    fs.openChannel(p, FileSystem.ChannelMode.Read).map { ch =>
                        expectFailure(ch.writeAt(0, bytes(9)))
                    }
                }
            }
        }.andThen {
            FileSystem.inMemory.map { lower =>
                val p = Path("ro-overlay.bin")
                lower.mkFile(p).andThen {
                    FileSystem.overlay(lower).map { ov =>
                        ov.openChannel(p, FileSystem.ChannelMode.Read).map { ch =>
                            expectFailure(ch.writeAt(0, bytes(9)))
                        }
                    }
                }
            }
        }
    }

    "Read mode rejects truncate on a host channel" in {
        Scope.run {
            hostTempDir("kyo-path-channel-trunc-ro").map { dir =>
                val p = dir / "trunc-ro.bin"
                FileSystem.host.mkFile(p).andThen {
                    FileSystem.host.openChannel(p, FileSystem.ChannelMode.Read).map { ch =>
                        Abort.run[FileException](ch.truncate(0)).map {
                            case Result.Failure(_) => assert(true)
                            case other             => assert(false, s"expected Failure(FileException), got $other")
                        }
                    }
                }
            }
        }
    }

    "ReadWriteCreate on an absent path creates it and ReadWrite on an absent path fails FileNotFoundException" in {
        FileSystem.inMemory.map { fs =>
            val created = Path("created.bin")
            val missing = Path("missing.bin")
            fs.openChannel(created, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                ch.size().map(sz => assert(sz == 0L))
            }.andThen {
                Abort.run[FileException](fs.openChannel(missing, FileSystem.ChannelMode.ReadWrite)).map {
                    case Result.Failure(_: FileNotFoundException) => assert(true)
                    case other                                    => assert(false, s"expected Failure(FileNotFoundException), got $other")
                }
            }
        }
    }

    "truncate discards trailing bytes on host and inMemory channels" in {
        Scope.run {
            hostTempDir("kyo-path-channel-trunc").map { dir =>
                val p = dir / "trunc.bin"
                FileSystem.host.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                    ch.writeAt(0, bytes(1, 2, 3, 4)).andThen {
                        ch.truncate(2).andThen {
                            ch.readAt(0, 10).map(result => assert(result.is(bytes(1, 2))))
                        }
                    }
                }
            }.andThen {
                FileSystem.inMemory.map { fs =>
                    val p = Path("trunc-mem.bin")
                    fs.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                        ch.writeAt(0, bytes(1, 2, 3, 4)).andThen {
                            ch.truncate(2).andThen {
                                ch.readAt(0, 10).map(result => assert(result.is(bytes(1, 2))))
                            }
                        }
                    }
                }
            }
        }
    }

    "size reflects the channel's current view after writeAt and after truncate" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("size-view.bin")
            fs.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                ch.writeAt(0, bytes(1, 2, 3, 4, 5)).andThen {
                    ch.size().map { firstSize =>
                        assert(firstSize == 5L)
                    }.andThen {
                        ch.truncate(2).andThen {
                            ch.size().map(secondSize => assert(secondSize == 2L))
                        }
                    }
                }
            }
        }
    }

    "sync completes and a freshly reopened host channel observes the synced bytes" in {
        Scope.run {
            hostTempDir("kyo-path-channel-sync").map { dir =>
                val p = dir / "sync.bin"
                FileSystem.host.openChannel(p, FileSystem.ChannelMode.ReadWriteCreate).map { ch =>
                    ch.writeAt(0, bytes(4, 5, 6)).andThen(ch.sync())
                }.andThen {
                    FileSystem.host.openChannel(p, FileSystem.ChannelMode.Read).map { reopened =>
                        reopened.readAt(0, 3).map(result => assert(result.is(bytes(4, 5, 6))))
                    }
                }
            }
        }
    }

    "FileSystem.syncDir succeeds without throwing on host after creating a child, and is a documented no-op on inMemory and overlay" in {
        Scope.run {
            hostTempDir("kyo-path-channel-syncdir").map { dir =>
                val child = dir / "child.bin"
                FileSystem.host.mkFile(child).andThen(FileSystem.host.syncDir(dir))
            }
        }.andThen {
            FileSystem.inMemory.map(fs => fs.syncDir(Path("some-dir")))
        }.andThen {
            FileSystem.inMemory.map { lower =>
                FileSystem.overlay(lower).map(ov => ov.syncDir(Path("some-dir")))
            }
        }.map(_ => assert(true))
    }

    "openChannel/syncDir on the Path.virtual/sandbox/transaction ephemeral forwarding service fail loud rather than silently misbehaving" in {
        val fwd = new ForwardingLowerFileSystem
        val p   = Path("forwarding-channel.dat")
        Abort.run[FileException](Path.run(fwd.openChannel(p, FileSystem.ChannelMode.ReadWrite))).map {
            case Result.Failure(_: FileIOException) => assert(true)
            case other                              => assert(false, s"expected Failure(FileIOException) from openChannel, got $other")
        }.andThen {
            Abort.run[FileException](Path.run(fwd.syncDir(p))).map {
                case Result.Failure(_: FileIOException) => assert(true)
                case other                              => assert(false, s"expected Failure(FileIOException) from syncDir, got $other")
            }
        }
    }

    // The two trait declarations below are transcribed verbatim from their live sources
    // (kyo.Path.Channel in kyo-system, kyo.internal.StoreSeam.Handle in kyo-eventlog); a
    // mismatch here means one trait's method set drifted from the other without the
    // matching update.
    "Path.Channel methods match StoreSeam.Handle methods in name and argument shape by source scan" in {
        val channelSource =
            """trait Channel[S]:
              |    def readAt(pos: Long, len: Int)(using Frame): Span[Byte] < (S & Abort[FileException])
              |    def writeAt(pos: Long, bytes: Span[Byte])(using Frame): Unit < (S & Abort[FileException])
              |    def sync()(using Frame): Unit < (S & Abort[FileException])
              |    def truncate(size: Long)(using Frame): Unit < (S & Abort[FileException])
              |    def size()(using Frame): Long < (S & Abort[FileException])
              |end Channel""".stripMargin

        val handleSource =
            """trait Handle[S]:
              |    def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < S
              |    def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < S
              |    def sync()(using Frame): Unit < S
              |    def truncate(size: Long)(using Frame): Unit < S
              |    def size()(using Frame): Long < S
              |    def close()(using Frame): Unit < S
              |end Handle""".stripMargin

        val methodPattern = """def (\w+)\(""".r

        def methodNames(source: String): Chunk[String] =
            Chunk.from(methodPattern.findAllMatchIn(source).map(_.group(1)).toList)

        val channelMethods = methodNames(channelSource)
        val handleMethods  = methodNames(handleSource)

        assert(channelMethods == Chunk("readAt", "writeAt", "sync", "truncate", "size"))
        assert(handleMethods == Chunk("readAt", "writeAt", "sync", "truncate", "size", "close"))
        assert(channelMethods == handleMethods.dropRight(1))
    }

end PathChannelTest
