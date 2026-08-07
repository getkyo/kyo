package kyo

import kyo.internal.NodeFs

class PathNodeTest extends kyo.test.Test[Any]:

    private def bytes(ints: Int*): Span[Byte] = Span.from(ints.map(_.toByte).toArray)

    "Node write-only Existing does not require read permission" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-node-write-only").map { dir =>
                    val path = dir / "write-only.bin"
                    path.writeBytes(bytes(1)).andThen {
                        Sync.Unsafe.defer(NodeFs.chmodSync(path.unsafe.show, 128)).andThen {
                            FileSystem.host.openWriteChannel(path, FileSystem.WriteOpen.Existing).map(_.writeAt(0L, bytes(9)))
                        }.andThen {
                            Sync.Unsafe.defer(NodeFs.chmodSync(path.unsafe.show, 384)).andThen {
                                path.readBytes.map(content => assert(content.is(bytes(9))))
                            }
                        }
                    }
                }
            }
        }
    }

    "Node Create preserves existing content and CreateNew remains exclusive" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-node-create-flags").map { dir =>
                    val path = dir / "create.bin"
                    path.writeBytes(bytes(1, 2, 3)).andThen {
                        FileSystem.host.openWriteChannel(path, FileSystem.WriteOpen.Create).andThen {
                            path.readBytes.map(content => assert(content.is(bytes(1, 2, 3))))
                        }.andThen {
                            Abort.run[FileSystemException](FileSystem.host.openWriteChannel(path, FileSystem.WriteOpen.CreateNew)).map {
                                case Result.Failure(_: FileAlreadyExistsException) => assert(true)
                                case other => assert(false, s"expected exclusive CreateNew failure, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "concurrent Node Create acquisitions preserve existing content" in {
        Scope.run {
            Path.run {
                Path.tempDir("kyo-node-concurrent-create").map { dir =>
                    val path = dir / "create.bin"
                    path.writeBytes(bytes(1, 2, 3)).andThen {
                        for
                            gate <- Latch.init(1)
                            fibers <- Kyo.fill(32)(Fiber.initUnscoped {
                                gate.await.andThen {
                                    Abort.run[FileSystemException](
                                        FileSystem.host.openWriteChannel(path, FileSystem.WriteOpen.Create)
                                    )
                                }
                            })
                            _       <- gate.release
                            results <- Kyo.foreach(fibers)(_.get)
                            content <- path.readBytes
                        yield
                            assert(results.forall(_.isSuccess))
                            assert(content.is(bytes(1, 2, 3)))
                        end for
                    }
                }
            }
        }
    }

    "Node copy honors followLinks for symbolic links" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-node-copy-links")
                source   = dir / "source.txt"
                link     = dir / "source-link"
                noFollow = dir / "no-follow-link"
                follow   = dir / "followed.txt"
                _              <- source.write("content")
                _              <- Sync.Unsafe.defer(NodeFs.symlinkSync(source.unsafe.show, link.unsafe.show))
                _              <- link.copy(noFollow, Path.CopyOptions(followLinks = false))
                _              <- link.copy(follow, Path.CopyOptions(followLinks = true))
                noFollowIsLink <- noFollow.isSymbolicLink
                followIsLink   <- follow.isSymbolicLink
                followedValue  <- follow.read
            yield
                assert(noFollowIsLink)
                assert(!followIsLink)
                assert(followedValue == "content")
        })
    }

    "Node copyAttributes controls copied modification time" in {
        Scope.run(Path.run {
            val sourceMtime = 1234567L
            for
                dir <- Path.tempDir("kyo-node-copy-stat")
                source    = dir / "source.txt"
                fresh     = dir / "fresh.txt"
                preserved = dir / "preserved.txt"
                _             <- source.write("content")
                _             <- source.setLastModified(sourceMtime)
                _             <- source.copy(fresh, Path.CopyOptions(copyAttributes = false))
                _             <- source.copy(preserved, Path.CopyOptions(copyAttributes = true))
                sourceStat    <- source.stat
                freshStat     <- fresh.stat
                preservedStat <- preserved.stat
            yield
                assert(sourceStat.lastModifiedMs == sourceMtime)
                assert(freshStat.lastModifiedMs != sourceMtime)
                assert(preservedStat.lastModifiedMs == sourceMtime)
            end for
        })
    }

    "Node directory copy enforces replacement policy for an existing target" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-node-copy-directory")
                source = dir / "source"
                target = dir / "target"
                _     <- source.mkDir
                _     <- target.mkDir
                never <- Abort.run[FileSystemException](Path.run(source.copy(target, Path.CopyOptions(replace = Path.Replace.Never))))
                _     <- source.copy(target, Path.CopyOptions(replace = Path.Replace.Existing))
            yield assert(never.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
        })
    }
end PathNodeTest
