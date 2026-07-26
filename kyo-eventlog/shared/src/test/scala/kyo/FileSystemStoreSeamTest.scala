package kyo

import kyo.AllowUnsafe.embrace.danger
import kyo.internal.FileSystemStoreSeam

/** Tests for the [[kyo.internal.FileSystemStoreSeam]] adapter: channel method-for-method
  * parity with [[kyo.internal.StoreSeam.Handle]], and the independent per-call release
  * property ([[kyo.internal.StoreSeam.Handle]] close / [[kyo.internal.SegmentStore.Lock]]
  * release do not affect a sibling handle or lock).
  *
  * Every leaf holds a [[FileSystem]] value directly ([[FileSystem.inMemory]]) and calls
  * [[kyo.internal.FileSystemStoreSeam.apply]] on it, matching the
  * [[kyo.PathChannelTest]]/[[kyo.PathLockTest]] off-[[Path.Op]] convention: the seam is a
  * service-level adapter, never suspended through [[Path.run]].
  */
class FileSystemStoreSeamTest extends kyo.test.Test[Any]:

    private def bytes(ints: Int*): Array[Byte] = ints.map(_.toByte).toArray

    "open/writeAt/readAt/close round trip over inMemory" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            val data = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
            seam.open(Path("roundtrip.dat")).map { handle =>
                handle.writeAt(0, data).andThen {
                    handle.readAt(0, data.length).map(read => assert(read.sameElements(data)))
                }.andThen(handle.close())
            }
        }
    }

    "readAt tolerates a short read past the written length" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            seam.open(Path("short.dat")).map { handle =>
                handle.writeAt(0, bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).andThen {
                    handle.readAt(5, 20).map { read =>
                        assert(read.length == 5)
                        assert(read.sameElements(bytes(6, 7, 8, 9, 10)))
                    }
                }.andThen(handle.close())
            }
        }
    }

    "truncate then size reflects the truncated length" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            seam.open(Path("trunc.dat")).map { handle =>
                handle.writeAt(0, bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)).andThen {
                    handle.truncate(5).andThen {
                        handle.size().map(sz => assert(sz == 5L))
                    }
                }.andThen(handle.close())
            }
        }
    }

    "syncDir over inMemory completes without a typed failure surfacing as a thrown exception" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            val dir  = Path("sync-dir")
            fs.mkDir(dir).andThen(seam.syncDir(dir)).andThen(seam.exists(dir)).map(exists => assert(exists))
        }
    }

    "two independently-opened handles on different paths: closing one leaves the other fully usable" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            seam.open(Path("a.dat")).map { a =>
                seam.open(Path("b.dat")).map { b =>
                    a.writeAt(0, bytes(1, 1, 1)).andThen {
                        b.writeAt(0, bytes(2, 2, 2)).andThen {
                            a.close().andThen {
                                b.readAt(0, 3).map(read => assert(read.sameElements(bytes(2, 2, 2))))
                            }
                        }
                    }
                }
            }
        }
    }

    "a losing handle-open race (two opens on the SAME path) closes the loser without affecting the winner's continued use" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            val p    = Path("race.dat")
            seam.open(p).map { winner =>
                seam.open(p).map { loser =>
                    winner.writeAt(0, bytes(9, 8, 7)).andThen {
                        loser.close().andThen {
                            winner.readAt(0, 3).map(read => assert(read.sameElements(bytes(9, 8, 7))))
                        }
                    }
                }
            }
        }
    }

    "acquireLock excludes a second exclusive acquire while the first is held" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            val root = Path("lock-root")
            seam.acquireLock(root).map { _ =>
                Abort.run[JournalStorageError](seam.acquireLock(root)).map {
                    case Result.Failure(e: JournalStorageError) => assert(e.detail.contains("Lock unavailable"))
                    case other                                  => assert(false, s"expected Failure(JournalStorageError), got $other")
                }
            }
        }
    }

    "acquireLock release unblocks an immediate reacquire on the same root" in {
        FileSystem.inMemory.map { fs =>
            val seam = FileSystemStoreSeam(fs)
            val root = Path("lock-release-root")
            seam.acquireLock(root).map { lock =>
                lock.release()
                Abort.run[JournalStorageError](seam.acquireLock(root)).map(r => assert(r.isSuccess))
            }
        }
    }

end FileSystemStoreSeamTest
