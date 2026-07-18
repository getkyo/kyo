package kyo

/** Tests for the advisory lock capability: [[Path.FileLock]], [[FileSystem.lock]], and
  * [[FileLockUnavailableException]].
  *
  * Every leaf holds a [[FileSystem]] value directly ([[FileSystem.host]], [[FileSystem.inMemory]],
  * [[FileSystem.overlay]]) and calls `lock` on it, never through [[Path.run]] or [[Path.runWith]]'s
  * Op-suspension machinery: `lock` is a service-level surface, not a [[Path.Op]] case. No leaf uses a
  * sleep or a timing assumption; every "held while contended" scenario nests a second acquisition
  * inside the first's still-open `Scope`, a structural happens-before guaranteed by sequential
  * evaluation rather than a race.
  */
class PathLockTest extends kyo.test.Test[Any]:

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    private def expectLockUnavailable[A, S](path: Path)(program: A < (S & Abort[FileException]))(using
        kyo.test.AssertScope
    )
        : Unit < (S & Abort[FileException]) =
        Abort.run[FileException](program).map {
            case Result.Failure(e: FileLockUnavailableException) => assert(e.path.name == path.name)
            case other => assert(false, s"expected Failure(FileLockUnavailableException) on $path, got $other")
        }

    "host exclusive lock excludes a second exclusive acquirer, typed not panic" in {
        Scope.run {
            hostTempDir("kyo-path-lock-host-excl-excl").map { dir =>
                val p = dir / "lock.bin"
                FileSystem.host.lock(p, exclusive = true).map { _ =>
                    Scope.run(expectLockUnavailable(p)(FileSystem.host.lock(p, exclusive = true)))
                }
            }
        }
    }

    "host Scope exit releases the lock, permitting a new acquirer" in {
        Scope.run {
            hostTempDir("kyo-path-lock-host-release").map { dir =>
                val p = dir / "lock.bin"
                Scope.run(FileSystem.host.lock(p, exclusive = true).map(_ => ())).andThen {
                    Scope.run(FileSystem.host.lock(p, exclusive = true).map(lock => assert(lock.isExclusive)))
                }
            }
        }
    }

    "host shared lock excludes a concurrent exclusive acquirer" in {
        Scope.run {
            hostTempDir("kyo-path-lock-host-shared-excl").map { dir =>
                val p = dir / "lock.bin"
                FileSystem.host.lock(p, exclusive = false).map { _ =>
                    Scope.run(expectLockUnavailable(p)(FileSystem.host.lock(p, exclusive = true)))
                }
            }
        }
    }

    "host exclusive lock excludes a concurrent shared acquirer" in {
        Scope.run {
            hostTempDir("kyo-path-lock-host-excl-shared").map { dir =>
                val p = dir / "lock.bin"
                FileSystem.host.lock(p, exclusive = true).map { _ =>
                    Scope.run(expectLockUnavailable(p)(FileSystem.host.lock(p, exclusive = false)))
                }
            }
        }
    }

    "host isExclusive reports the granted mode: true for an exclusive acquisition and false for a shared acquisition".notJs.notWasm in {
        Scope.run {
            hostTempDir("kyo-path-lock-host-mode").map { dir =>
                val exclusivePath = dir / "excl.bin"
                val sharedPath    = dir / "shared.bin"
                FileSystem.host.lock(exclusivePath, exclusive = true).map { excl =>
                    FileSystem.host.lock(sharedPath, exclusive = false).map { shared =>
                        assert(excl.isExclusive)
                        assert(!shared.isExclusive)
                    }
                }
            }
        }
    }

    "Node/JS shared request degrades to exclusive: a second acquirer is excluded while the first is held".notJvm.notNative in {
        Scope.run {
            hostTempDir("kyo-path-lock-node-shared-degrade").map { dir =>
                val p = dir / "lock.bin"
                FileSystem.host.lock(p, exclusive = false).map { _ =>
                    Scope.run(expectLockUnavailable(p)(FileSystem.host.lock(p, exclusive = false)))
                }
            }
        }
    }

    "Node/JS isExclusive always reports true regardless of the requested mode".notJvm.notNative in {
        Scope.run {
            hostTempDir("kyo-path-lock-node-always-exclusive").map { dir =>
                val p = dir / "lock.bin"
                FileSystem.host.lock(p, exclusive = false).map(lock => assert(lock.isExclusive))
            }
        }
    }

    "Node/JS Scope exit releases the lockfile, permitting a new acquirer".notJvm.notNative in {
        Scope.run {
            hostTempDir("kyo-path-lock-node-release").map { dir =>
                val p = dir / "lock.bin"
                Scope.run(FileSystem.host.lock(p, exclusive = true).map(_ => ())).andThen {
                    Scope.run(FileSystem.host.lock(p, exclusive = true).map(lock => assert(lock.isExclusive)))
                }
            }
        }
    }

    "inMemory exclusive lock excludes a second exclusive acquirer, typed not panic" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("lock-mem-excl-excl.bin")
            fs.lock(p, exclusive = true).map { _ =>
                expectLockUnavailable(p)(fs.lock(p, exclusive = true))
            }
        }
    }

    "inMemory shared locks admit concurrent shared holders" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("lock-mem-shared-shared.bin")
            fs.lock(p, exclusive = false).map { firstLock =>
                fs.lock(p, exclusive = false).map { secondLock =>
                    assert(!firstLock.isExclusive)
                    assert(!secondLock.isExclusive)
                }
            }
        }
    }

    "inMemory Scope exit releases the lock, permitting a new acquirer" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("lock-mem-release.bin")
            Scope.run(fs.lock(p, exclusive = true).map(_ => ())).andThen {
                Scope.run(fs.lock(p, exclusive = true).map(lock => assert(lock.isExclusive)))
            }
        }
    }

    "overlay lock delegates to the lower FileSystem" in {
        FileSystem.inMemory.map { lower =>
            val p = Path("lock-overlay.bin")
            FileSystem.overlay(lower).map { ov =>
                ov.lock(p, exclusive = true).map { _ =>
                    Scope.run(expectLockUnavailable(p)(lower.lock(p, exclusive = true)))
                }
            }
        }
    }

    "Abort.recover[FileLockUnavailableException] catches the typed failure precisely" in {
        FileSystem.inMemory.map { fs =>
            val p = Path("lock-mem-recover.bin")
            fs.lock(p, exclusive = true).map { _ =>
                val contended: String < (Sync & Scope & Abort[FileException]) =
                    fs.lock(p, exclusive = true).map(_ => "not recovered")
                Abort.recover[FileLockUnavailableException](_ => "recovered")(contended).map { result =>
                    assert(result == "recovered")
                }
            }
        }
    }

    "lock on the Path.virtual/sandbox/transaction ephemeral forwarding service fails loud rather than silently misbehaving" in {
        val fwd = new ForwardingLowerFileSystem
        val p   = Path("forwarding-lock.dat")
        Abort.run[FileException](Path.run(fwd.lock(p, exclusive = true))).map {
            case Result.Failure(e: FileIOException) => assert(e.getMessage.contains("forwarding service"))
            case other                              => assert(false, s"expected Failure(FileIOException) from lock, got $other")
        }
    }

end PathLockTest
