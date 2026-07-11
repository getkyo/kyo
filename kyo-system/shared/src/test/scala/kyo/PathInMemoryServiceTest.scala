package kyo

import java.nio.charset.StandardCharsets

class PathInMemoryServiceTest extends kyo.test.Test[Any]:

    // Helper: run a path program through a fresh in-memory service.
    private def withInMem[A, S](program: Path.Service[Sync] => A < (Sync & Abort[FileException] & S))
        : A < (Sync & Abort[FileException] & S) =
        Path.Service.inMemory.map(svc => program(svc))

    "read after write returns the written string" in {
        withInMem { svc =>
            val p = Path("hello.txt")
            Path.runWith(svc)(p.write("hello world").andThen(p.read)).map { result =>
                assert(result == "hello world")
            }
        }
    }

    "list after three writes returns exactly those three names sorted" in {
        withInMem { svc =>
            val dir = Path("dir")
            val prog =
                dir.mkDir.andThen {
                    (dir / "c.txt").write("c").andThen {
                        (dir / "a.txt").write("a").andThen {
                            (dir / "b.txt").write("b").andThen {
                                dir.list
                            }
                        }
                    }
                }
            Path.runWith(svc)(prog).map { paths =>
                assert(paths.map(_.name.getOrElse("")) == Chunk("a.txt", "b.txt", "c.txt"))
            }
        }
    }

    "append then read returns the concatenation" in {
        withInMem { svc =>
            val p    = Path("app.txt")
            val prog = p.write("foo").andThen(p.append("bar")).andThen(p.read)
            Path.runWith(svc)(prog).map { result =>
                assert(result == "foobar")
            }
        }
    }

    "truncate reduces the byte count and read returns only the kept prefix" in {
        withInMem { svc =>
            val p    = Path("trunc.txt")
            val data = "abcdefgh"
            val prog = p.write(data).andThen(p.truncate(4)).andThen(p.readBytes.map(b =>
                (b.size.toLong, new String(b.toArrayUnsafe, StandardCharsets.UTF_8))
            ))
            Path.runWith(svc)(prog).map { case (sz, content) =>
                assert(sz == 4L)
                assert(content == "abcd")
            }
        }
    }

    "move leaves source absent and target holding the bytes" in {
        withInMem { svc =>
            val a = Path("mv-a.txt")
            val b = Path("mv-b.txt")
            val prog =
                a.write("moved").andThen(a.move(b)).andThen {
                    Abort.run[FileException](a.exists).map(aRes => b.readBytes.map(bBytes => (aRes, bBytes)))
                }
            Path.runWith(svc)(prog).map { case (aRes, bBytes) =>
                assert(aRes == Result.succeed(false))
                assert(new String(bBytes.toArray, StandardCharsets.UTF_8) == "moved")
            }
        }
    }

    "copy leaves both source and target holding the bytes" in {
        withInMem { svc =>
            val a    = Path("cp-a.txt")
            val b    = Path("cp-b.txt")
            val prog = a.write("shared").andThen(a.copy(b)).andThen(a.read.map(ar => b.read.map(br => (ar, br))))
            Path.runWith(svc)(prog).map { case (ar, br) =>
                assert(ar == "shared")
                assert(br == "shared")
            }
        }
    }

    "move onto an existing target without replaceExisting aborts FileAlreadyExistsException" in {
        withInMem { svc =>
            val a = Path("me-a.txt")
            val b = Path("me-b.txt")
            // Abort.run must be OUTSIDE Path.runWith: exceptions raised by the service during
            // PathWrite dispatch propagate out of Path.runWith, not into the inner Abort.run scope.
            val prog = a.write("a").andThen(b.write("b")).andThen(a.move(b, replaceExisting = false))
            Abort.run[FileException](Path.runWith(svc)(prog)).map { result =>
                assert(result.isFailure)
                assert(result.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
            }
        }
    }

    "copy onto an existing target without replaceExisting aborts FileAlreadyExistsException" in {
        withInMem { svc =>
            val a    = Path("ce-a.txt")
            val b    = Path("ce-b.txt")
            val prog = a.write("a").andThen(b.write("b")).andThen(a.copy(b, replaceExisting = false))
            Abort.run[FileException](Path.runWith(svc)(prog)).map { result =>
                assert(result.isFailure)
                assert(result.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
            }
        }
    }

    "stat after write reflects the written byte count" in {
        withInMem { svc =>
            val p    = Path("stat.txt")
            val data = "12345"
            val prog = p.write(data).andThen(p.stat)
            Path.runWith(svc)(prog).map { st =>
                assert(st.sizeBytes == data.getBytes(StandardCharsets.UTF_8).length.toLong)
            }
        }
    }

    "isSymbolicLink returns false for both files and directories" in {
        withInMem { svc =>
            val f = Path("sym-f.txt")
            val d = Path("sym-d")
            val prog =
                f.write("x").andThen(d.mkDir).andThen {
                    f.isSymbolicLink.map(fi => d.isSymbolicLink.map(di => (fi, di)))
                }
            Path.runWith(svc)(prog).map { case (fi, di) =>
                assert(!fi)
                assert(!di)
            }
        }
    }

    "two concurrent writes to sibling paths both land without lost updates" in {
        Path.Service.inMemory.map { svc =>
            val aPath = Path("race-a.txt")
            val bPath = Path("race-b.txt")
            val aData = Span.from("alpha".getBytes(StandardCharsets.UTF_8))
            val bData = Span.from("beta".getBytes(StandardCharsets.UTF_8))
            for
                gate   <- Latch.init(1)
                fiberA <- Fiber.initUnscoped(gate.await.andThen(Path.runWith(svc)(aPath.writeBytes(aData))))
                fiberB <- Fiber.initUnscoped(gate.await.andThen(Path.runWith(svc)(bPath.writeBytes(bData))))
                _      <- gate.release
                _      <- fiberA.get
                _      <- fiberB.get
                aBytes <- Path.runWith(svc)(aPath.readBytes)
                bBytes <- Path.runWith(svc)(bPath.readBytes)
            yield
                assert(new String(aBytes.toArray, StandardCharsets.UTF_8) == "alpha")
                assert(new String(bBytes.toArray, StandardCharsets.UTF_8) == "beta")
            end for
        }
    }

    "writeTo abort before finish leaves target absent" in {
        Path.Service.inMemory.map { svc =>
            val target = Path("sink-abort.bin")
            // A stream that fails mid-way; the write handle's finish() is never called so no bytes land.
            val failingStream: Stream[Byte, Abort[FileException]] =
                Stream.init(Chunk[Byte](1, 2, 3)).concat(
                    Stream.init(Abort.fail[FileException](FileNotFoundException(target)).map(_ => Chunk.empty[Byte]))
                )
            val prog =
                Abort.run[FileException](
                    Scope.run(
                        Path.runWith(svc)(failingStream.writeTo(target))
                    )
                ).andThen(
                    Abort.run[FileException](Path.runWith(svc)(target.exists))
                )
            prog.map { existsResult =>
                assert(existsResult == Result.succeed(false))
            }
        }
    }

    "writeTo with a complete stream writes the exact bytes to the target" in {
        Path.Service.inMemory.map { svc =>
            val target  = Path("sink-ok.bin")
            val payload = Chunk[Byte](10, 20, 30, 40)
            val prog =
                Scope.run(
                    Path.runWith(svc)(Stream.init(payload).writeTo(target))
                ).andThen(
                    Path.runWith(svc)(target.readBytes)
                )
            prog.map { bytes =>
                assert(bytes.toArrayUnsafe sameElements payload.toArray)
            }
        }
    }

    "tempDir scope cleanup removes only the in-memory entry and does not touch a coincident host directory" in {
        Path.Service.inMemory.map { svc =>
            AtomicRef.init[Maybe[Path]](Absent).map { pathRef =>
                // Open a scope that creates an in-memory temp dir; simultaneously create a
                // real host directory at the same path string.
                Scope.run(
                    Path.runWith(svc) {
                        Path.tempDir("conf-tmp").map { tmpPath =>
                            pathRef.set(Present(tmpPath)).andThen {
                                // Unsafe: creates a host-FS directory to verify that in-memory remove does not touch it.
                                Sync.Unsafe.defer(Abort.get(tmpPath.unsafe.mkDir()))
                            }
                        }
                    }
                ).andThen {
                    // Scope exit has called TempDirHandle.remove() on the in-memory service.
                    pathRef.use {
                        case Absent => fail("tempDir path was never captured")
                        case Present(p) =>
                            Path.runWith(svc)(p.exists).map { inMemExists =>
                                assert(!inMemExists, s"in-memory entry should be gone after scope exit: $p")
                            }.andThen {
                                // Unsafe: checks host-FS existence to verify the real dir is untouched.
                                Sync.Unsafe.defer(p.unsafe.exists()).map { hostExists =>
                                    assert(hostExists, s"coincident host dir should still exist: $p")
                                }
                            }.andThen {
                                // Unsafe: cleans up the host-FS temp directory created by this test.
                                Sync.Unsafe.defer(discard(p.unsafe.removeAll()))
                            }
                    }
                }
            }
        }
    }

    "Service.host disposition is AutoCommit" in {
        assert(Path.Service.host.disposition == Path.Disposition.AutoCommit)
    }

    "Service.inMemory disposition is AutoCommit" in {
        Path.Service.inMemory.map { svc =>
            assert(svc.disposition == Path.Disposition.AutoCommit)
        }
    }

    "Path.Disposition has exactly three cases" in {
        assert(Path.Disposition.values.size == 3)
    }

end PathInMemoryServiceTest
