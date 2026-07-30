package kyo

class PathLockTest extends FileSystemLockTestSuite:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map(fileSystem => use(fileSystem, Path("lock-target.bin")))

    "Path lock operations dispatch through PathRead" in {
        FileSystem.inMemory.map { fileSystem =>
            val path = Path("path-lock-target.bin")
            Scope.run {
                Path.runReadOnlyWith(fileSystem) {
                    path.lock(Path.LockMode.Shared, Path.LockWait.Immediate).map { lock =>
                        path.tryLock(Path.LockMode.Exclusive).map { conflicting =>
                            assert(lock.mode == Path.LockMode.Shared)
                            assert(conflicting.isEmpty)
                        }
                    }
                }
            }
        }
    }

    "Path lock operations forward through staged-write overlays" in {
        // Previously this ran the three staged-write shapes and asserted `true`, which held for any
        // implementation that did not throw, including one that granted nothing or leaked every
        // claim. Each arm now asserts the mode it was granted and that the claim is gone once the
        // scope closes, which is what "forwards correctly" has to mean.
        FileSystem.inMemory.map { fileSystem =>
            Path.runWith(fileSystem) {
                val staged    = Path("staged-lock.bin")
                val discarded = Path("discarded-lock.bin")
                val committed = Path("committed-lock.bin")
                def acquire(path: Path) =
                    path.lock(Path.LockMode.Exclusive, Path.LockWait.Immediate).map(lock => assert(lock.mode == Path.LockMode.Exclusive))
                def reacquirable(path: Path) =
                    Scope.run(path.tryLock(Path.LockMode.Exclusive)).map { again =>
                        assert(again.isDefined, s"the claim on $path outlived the scope that took it")
                    }
                Scope.run(Path.stageWrites(acquire(staged)).unit).andThen(reacquirable(staged)).andThen {
                    Scope.run(Path.discardWrites(acquire(discarded))).andThen(reacquirable(discarded))
                }.andThen {
                    Scope.run(Path.commitWritesOnSuccess(acquire(committed))).andThen(reacquirable(committed))
                }
            }
        }
    }
end PathLockTest

class HostPathLockTest extends FileSystemLockTestSuite:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-path-lock"))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            use(FileSystem.host, handle.path / "target.bin")
        }

    "an interrupt delivered once the lock is held still releases it" in {
        // The acquisition interrupts its own fiber from inside the claim node, via the hook
        // HostFileSystem exposes for exactly this. The request is made with the OS lock already
        // held, and delivery happens at the next safepoint, so the interrupt lands after the
        // acquisition produced the lock rather than before it started. That instant is the whole
        // defect: with the finalizer registered in a continuation after acquire, nothing capable of
        // releasing the claim exists yet, and the path stays unacquirable for the life of the
        // process.
        //
        // Mutation-checked rather than assumed: rewriting tryLock as Scope.acquireRelease, with the
        // hook in the same position inside acquire, fails this case on the retry below. An earlier
        // version of this test interrupted from outside the fiber and passed under both orderings,
        // because that interrupt is delivered before the claim is ever made.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-window"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "windowed.bin"
            Scope.ensure(Sync.defer(HostFileSystem.afterClaimHook = () => ())).andThen {
                Fiber.Promise.init[Fiber[Unit, Any], Any].map { handoff =>
                    Fiber.initUnscoped {
                        handoff.get.map { self =>
                            Sync.defer {
                                import AllowUnsafe.embrace.danger
                                HostFileSystem.afterClaimHook = () => discard(self.unsafe.interrupt())
                            }.andThen {
                                Abort.run[FileReadException | FileLockException](
                                    Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).unit)
                                ).unit
                            }
                        }
                    }.map { fiber =>
                        handoff.complete(Result.succeed(fiber)).andThen(fiber.getResult)
                    }
                }.andThen {
                    Sync.defer(HostFileSystem.afterClaimHook = () => ())
                }.andThen {
                    // Retried rather than attempted once. Interrupting a fiber starts its finalizer
                    // but does not wait for it, so a correct release may still be in flight; that is
                    // a lock briefly held, not a stranded one. A stranded lock never becomes
                    // available, so only the retry distinguishes the two.
                    assertEventually {
                        Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_.isDefined))
                    }
                }
            }
        }
    }

    "repeated interrupted acquisitions leave the path acquirable" in {
        // Interrupting from outside the fiber, which lands before the claim is made. That is the
        // opposite end of the acquisition from the case above and is worth holding separately: it
        // pins that an acquisition abandoned before it claims anything leaves nothing behind, so
        // repeated attempts cannot accumulate claims.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-interrupt"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "contended.bin"
            Loop.indexed { i =>
                if i >= 200 then Loop.done
                else
                    Fiber.initUnscoped(Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_ => ())))
                        .map(_.interrupt)
                        .andThen(Loop.continue)
            }.andThen {
                assertEventually {
                    Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_.isDefined))
                }
            }
        }
    }

    "failed raw release remains retryable" in {
        AtomicInt.init(0).map { releases =>
            val raw = new Path.RawLock:
                def isExclusive: Boolean                                               = true
                def check()(using AllowUnsafe, Frame): Result[FileLockException, Unit] = Result.unit
                def release()(using AllowUnsafe, Frame): Result[FileLockException, Unit] =
                    if releases.unsafe.incrementAndGet() == 1 then Result.fail(FileLockOwnershipLostException(Path("retry-lock")))
                    else Result.unit
            AtomicInt.init(0).map { state =>
                val service = new HostFileSystem.HostFileSystem
                val lock    = service.lockFrom(Path("retry-lock"), raw, Path.LockMode.Exclusive, state)
                Abort.run[FileLockException](lock.release(lock.ownership)).map { first =>
                    assert(first.isFailure)
                    lock.release(lock.ownership).andThen(releases.get.map(count => assert(count == 2)))
                }
            }
        }
    }
end HostPathLockTest

/** The read-only zip backend carries its own lock implementation, with its own claim map and its
  * own shared/exclusive merge, entirely separate from the host registry. It was registered for read
  * conformance and never once run against the lock contract, so none of its sixteen cases had ever
  * been asked of it.
  */
class ZipReadOnlyPathLockTest extends FileSystemLockTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zipReadOnly.map((fileSystem, file, _) => use(fileSystem, file))
end ZipReadOnlyPathLockTest

/** The rewritable zip backend delegates locks to its in-memory upper, so a claim never reaches the
  * archive and is visible only within one instance. Running the contract states that semantic
  * rather than leaving it undeclared.
  */
class ZipRewritePathLockTest extends FileSystemLockTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => use(fileSystem, root / "lock-target.bin"))
end ZipRewritePathLockTest
