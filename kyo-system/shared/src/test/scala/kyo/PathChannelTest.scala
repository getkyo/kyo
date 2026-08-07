package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for typed, Scope-managed positioned file channels. */
class PathChannelTest extends kyo.test.Test[Any]:

    private def bytes(ints: Int*): Span[Byte] = Span.from(ints.map(_.toByte).toArray)

    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h => Sync.Unsafe.defer(h.remove())).map(_.path)

    "typed channel capabilities are enforced by the compiler" in {
        val readPositive = typeCheckErrors("""
            def use[S](channel: kyo.Path.ReadChannel[S])(using kyo.Frame) =
                (channel.readAt(0L, 1), channel.size)
        """)
        val readNegative = typeCheckErrors("""
            def use[S](channel: kyo.Path.ReadChannel[S])(using kyo.Frame) =
                (channel.writeAt(0L, kyo.Span.empty[Byte]), channel.truncate(0L), channel.sync(metadata = false))
        """)
        val writePositive = typeCheckErrors("""
            def use[S](channel: kyo.Path.WriteChannel[S])(using kyo.Frame) =
                (channel.writeAt(0L, kyo.Span.empty[Byte]), channel.truncate(0L), channel.sync(metadata = true))
        """)
        val writeNegative = typeCheckErrors("""
            def use[S](channel: kyo.Path.WriteChannel[S])(using kyo.Frame) =
                (channel.readAt(0L, 1), channel.size)
        """)
        val readWritePositive = typeCheckErrors("""
            def use[S](channel: kyo.Path.ReadWriteChannel[S])(using kyo.Frame) =
                (channel.readAt(0L, 1), channel.size, channel.writeAt(0L, kyo.Span.empty[Byte]),
                    channel.truncate(0L), channel.sync(metadata = false))
        """)
        assert(readPositive.isEmpty)
        assert(readNegative.nonEmpty)
        assert(writePositive.isEmpty)
        assert(writeNegative.nonEmpty)
        assert(readWritePositive.isEmpty)
    }

    "filesystem tiers expose only their permitted acquisitions" in {
        val readPositive = typeCheckErrors("""
            def use[S](fs: kyo.FileSystem.Read[S], path: kyo.Path)(using kyo.Frame) = fs.openReadChannel(path)
        """)
        val readNegative = typeCheckErrors("""
            def use[S](fs: kyo.FileSystem.Read[S], path: kyo.Path)(using kyo.Frame) =
                (fs.openWriteChannel(path, kyo.FileSystem.WriteOpen.Existing),
                    fs.openReadWriteChannel(path, kyo.FileSystem.WriteOpen.Existing))
        """)
        val writePositive = typeCheckErrors("""
            def use[S](fs: kyo.FileSystem.Write[S], path: kyo.Path)(using kyo.Frame) =
                (fs.openReadChannel(path), fs.openWriteChannel(path, kyo.FileSystem.WriteOpen.Existing),
                    fs.openReadWriteChannel(path, kyo.FileSystem.WriteOpen.Create))
        """)
        assert(readPositive.isEmpty)
        assert(readNegative.nonEmpty)
        assert(writePositive.isEmpty)
    }

    "host and in-memory read-write channels perform positioned short reads and sparse writes" in {
        def exercise[S](fs: FileSystem.Write[S], path: Path)(using Frame): Unit < (S & Scope & Abort[FileSystemException]) =
            fs.openReadWriteChannel(path, FileSystem.WriteOpen.Create).map { channel =>
                channel.writeAt(3L, bytes(7)).andThen {
                    channel.readAt(0L, 10).map(result => assert(result.is(bytes(0, 0, 0, 7))))
                }
            }
        Scope.run {
            hostTempDir("kyo-typed-channel").map(dir => exercise(FileSystem.host, dir / "host.bin"))
        }.andThen {
            Scope.run(FileSystem.inMemory.map(fs => exercise(fs, Path("memory.bin"))))
        }
    }

    "truncate, size, and both sync policies preserve content" in {
        Scope.run {
            FileSystem.inMemory.map { fs =>
                fs.openReadWriteChannel(Path("size.bin"), FileSystem.WriteOpen.Create).map { channel =>
                    channel.writeAt(0L, bytes(1, 2, 3, 4)).andThen(channel.sync(metadata = false)).andThen {
                        channel.truncate(2L).andThen(channel.sync(metadata = true)).andThen {
                            channel.size.map(size => assert(size == 2L)).andThen {
                                channel.readAt(0L, 8).map(result => assert(result.is(bytes(1, 2))))
                            }
                        }
                    }
                }
            }
        }
    }

    "open policies distinguish existing, create, and create-new" in {
        Scope.run {
            FileSystem.inMemory.map { fs =>
                val path = Path("policy.bin")
                val missing = Seq(
                    Abort.run[FileSystemException](fs.openReadChannel(path)),
                    Abort.run[FileSystemException](fs.openWriteChannel(path, FileSystem.WriteOpen.Existing)),
                    Abort.run[FileSystemException](fs.openReadWriteChannel(path, FileSystem.WriteOpen.Existing))
                )
                Kyo.collectAll(missing).map(_.foreach {
                    case Result.Failure(_: FileNotFoundException) => assert(true)
                    case other                                    => assert(false, s"expected missing-file failure, got $other")
                }).andThen(fs.openWriteChannel(path, FileSystem.WriteOpen.Create)).andThen {
                    Abort.run[FileSystemException](fs.openWriteChannel(path, FileSystem.WriteOpen.CreateNew)).map {
                        case Result.Failure(_: FileAlreadyExistsException) => assert(true)
                        case other                                         => assert(false, s"expected already-exists failure, got $other")
                    }
                }
            }
        }.andThen {
            Scope.run {
                hostTempDir("kyo-channel-policy").map { dir =>
                    val path = dir / "policy.bin"
                    Abort.run[FileSystemException](FileSystem.host.openReadWriteChannel(path, FileSystem.WriteOpen.Existing)).map {
                        case Result.Failure(_: FileNotFoundException) => assert(true)
                        case other                                    => assert(false, s"expected host missing-file failure, got $other")
                    }.andThen(FileSystem.host.openWriteChannel(path, FileSystem.WriteOpen.Create)).andThen {
                        Abort.run[FileSystemException](FileSystem.host.openReadWriteChannel(path, FileSystem.WriteOpen.CreateNew)).map {
                            case Result.Failure(_: FileAlreadyExistsException) => assert(true)
                            case other => assert(false, s"expected host already-exists failure, got $other")
                        }
                    }
                }
            }
        }
    }

    "CreateNew admits exactly one concurrent creator on in-memory, overlay, and zip rewrite" in {
        def contend(fs: FileSystem.Write[Sync], path: Path)(using Frame): Int < (Sync & Async & Scope) =
            for
                gate <- Latch.init(1)
                fibers <- Kyo.fill(32)(Fiber.initUnscoped {
                    gate.await.andThen(Abort.run[FileSystemException](fs.openWriteChannel(path, FileSystem.WriteOpen.CreateNew)))
                })
                _       <- gate.release
                results <- Kyo.foreach(fibers)(_.get)
            yield
                val successes = results.count(_.isSuccess)
                val failures  = results.collect { case Result.Failure(e) => e }
                assert(failures.forall(_.isInstanceOf[FileAlreadyExistsException]))
                successes
            end for
        end contend

        Scope.run {
            FileSystem.inMemory.map { inMemory =>
                contend(inMemory, Path("create-new-race.bin")).map(count => assert(count == 1)).andThen {
                    FileSystem.overlay(inMemory).map { overlay =>
                        contend(overlay, Path("overlay-create-new-race.bin")).map(count => assert(count == 1))
                    }
                }.andThen {
                    hostTempDir("kyo-zip-create-new-race").map { dir =>
                        FileSystem.zip(dir / "race.zip").map { zip =>
                            contend(zip, Path("entry.bin")).map(count => assert(count == 1))
                        }
                    }
                }
            }
        }
    }

    "invalid positions, lengths, and truncation bounds fail in typed channels" in {
        Scope.run {
            FileSystem.inMemory.map { fs =>
                fs.openReadWriteChannel(Path("bounds.bin"), FileSystem.WriteOpen.Create).map { channel =>
                    val failures = Seq(
                        Abort.run[FileSystemException](channel.readAt(-1L, 1)),
                        Abort.run[FileSystemException](channel.readAt(0L, -1)),
                        Abort.run[FileSystemException](channel.writeAt(-1L, bytes(1))),
                        Abort.run[FileSystemException](channel.truncate(-1L))
                    )
                    Kyo.collectAll(failures).map(_.foreach {
                        case Result.Failure(_: FileIOException) => assert(true)
                        case other                              => assert(false, s"expected typed FileIOException, got $other")
                    })
                }
            }
        }
    }

    "scope release closes in-memory and host channels" in {
        FileSystem.inMemory.map { fs =>
            val path = Path("closed.bin")
            fs.openReadWriteChannelUnscoped(path, FileSystem.WriteOpen.Create).map { case (channel, release) =>
                release().andThen {
                    Abort.run[FileSystemException](channel.writeAt(0L, bytes(1))).map {
                        case Result.Failure(_: FileIOException) => assert(true)
                        case other                              => assert(false, s"expected closed failure, got $other")
                    }
                }
            }
        }.andThen {
            Scope.run {
                hostTempDir("kyo-channel-close").map { dir =>
                    val path = dir / "closed.bin"
                    FileSystem.host.openReadWriteChannelUnscoped(path, FileSystem.WriteOpen.Create).map { case (channel, release) =>
                        release().andThen {
                            Abort.run[FileSystemException](channel.writeAt(0L, bytes(1))).map {
                                case Result.Failure(_: FileIOException) => assert(true)
                                case other                              => assert(false, s"expected closed host failure, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "unscoped release is idempotent and leaves a typed closed channel" in {
        Scope.run {
            hostTempDir("kyo-channel-double-release").map { dir =>
                val path = dir / "double-release.bin"
                FileSystem.host.openReadWriteChannelUnscoped(path, FileSystem.WriteOpen.Create).map { case (channel, release) =>
                    release().andThen(release()).andThen {
                        Abort.run[FileSystemException](channel.writeAt(0L, bytes(1))).map {
                            case Result.Failure(_: FileIOException) => assert(true)
                            case other => assert(false, s"expected typed closed failure after double release, got $other")
                        }
                    }
                }
            }
        }
    }

    "interrupting channel use releases the handle exactly once" in {
        FileSystem.inMemory.map { fs =>
            val path = Path("interrupted.bin")
            fs.mkFile(path).andThen {
                for
                    ready  <- Latch.init(1)
                    hold   <- Latch.init(1)
                    closes <- AtomicInt.init(0)
                    fiber <- Fiber.initUnscoped {
                        Scope.run {
                            fs.openReadChannelUnscoped(path).map { case (channel, release) =>
                                Scope.acquireRelease(channel)(_ => release().andThen(closes.incrementAndGet.unit)).map { _ =>
                                    ready.release.andThen(hold.await)
                                }
                            }
                        }
                    }
                    _           <- ready.await
                    interrupted <- fiber.interrupt
                    _           <- assertEventually(closes.get.map(_ == 1))
                    count       <- closes.get
                yield
                    assert(interrupted)
                    assert(count == 1)
                end for
            }
        }
    }

    "overlay channel closes at nested Scope exit without mutating staged or lower content" in {
        Scope.run {
            FileSystem.inMemory.map { lower =>
                val path = Path("overlay-closed.bin")
                lower.writeBytes(path, bytes(1, 2), Path.WriteOptions()).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        AtomicRef.init[Maybe[Path.ReadWriteChannel[Sync]]](Absent).map { captured =>
                            Scope.run {
                                overlay.openReadWriteChannel(path, FileSystem.WriteOpen.Existing).map(channel =>
                                    captured.set(Present(channel))
                                )
                            }.andThen {
                                captured.get.map {
                                    case Present(channel) =>
                                        Abort.run[FileSystemException](channel.writeAt(0L, bytes(9, 9))).map {
                                            case Result.Failure(_: FileIOException) => assert(true)
                                            case other => assert(false, s"expected closed overlay channel failure, got $other")
                                        }
                                    case Absent => fail("channel was not captured")
                                }.andThen {
                                    overlay.readBytes(path).map(staged => assert(staged.is(bytes(1, 2)))).andThen {
                                        lower.readBytes(path).map(base => assert(base.is(bytes(1, 2))))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "overlay channel reports read bounds as Read and write bounds as Write without mutation" in {
        Scope.run {
            FileSystem.inMemory.map { lower =>
                val path = Path("overlay-negative-bounds.bin")
                lower.writeBytes(path, bytes(1, 2), Path.WriteOptions()).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        overlay.openReadWriteChannel(path, FileSystem.WriteOpen.Existing).map { channel =>
                            val reads = Seq(
                                Abort.run[FileSystemException](channel.readAt(-1L, 1)),
                                Abort.run[FileSystemException](channel.readAt(0L, -1))
                            )
                            Kyo.collectAll(reads).map(_.foreach {
                                case Result.Failure(e: FileIOException) => assert(e.operation == FileSystemOperation.Read)
                                case other                              => assert(false, s"expected typed read-bound failure, got $other")
                            }).andThen {
                                Abort.run[FileSystemException](channel.writeAt(-1L, bytes(9))).map {
                                    case Result.Failure(e: FileIOException) => assert(e.operation == FileSystemOperation.Write)
                                    case other => assert(false, s"expected typed write-bound failure, got $other")
                                }
                            }.andThen {
                                overlay.readBytes(path).map(staged => assert(staged.is(bytes(1, 2)))).andThen {
                                    lower.readBytes(path).map(base => assert(base.is(bytes(1, 2))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "overlay channels retain staged isolation until commit" in {
        Scope.run {
            FileSystem.inMemory.map { lower =>
                val path = Path("overlay.bin")
                lower.writeBytes(path, bytes(1, 1), Path.WriteOptions()).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        overlay.openReadWriteChannel(path, FileSystem.WriteOpen.Existing).map { channel =>
                            channel.writeAt(0L, bytes(9, 9)).andThen {
                                lower.readBytes(path).map(before => assert(before.is(bytes(1, 1)))).andThen {
                                    overlay.commit.andThen(lower.readBytes(path).map(after => assert(after.is(bytes(9, 9)))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end PathChannelTest
