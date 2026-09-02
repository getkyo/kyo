package kyo

class HostPathLockTest extends FileSystemLockTest:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-path-lock"))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            use(FileSystem.host, handle.path / "target.bin")
        }

    "Path lock operations dispatch through PathRead" in {
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-path-lock-dispatch"))(handle => Sync.Unsafe.defer(handle.remove())).map {
            handle =>
                val path = handle.path / "path-lock-target.bin"
                Scope.run {
                    Path.runReadOnlyWith(FileSystem.host) {
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

    "repeated interrupted acquisitions leave the path acquirable".ignore(
        "kyo-core can lose a Scope.ensure finalizer when its fiber is interrupted under load, which strands the lock; see #1928"
    ) in {
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

    "data path I/O while holding the lock does not disturb the claim" in {
        // The scenario from review: on POSIX, an fcntl lock on the data file itself is released
        // the moment the same process closes any other handle to that file, so a read of the
        // locked path would silently destroy cross-process exclusion. The claim lives on a
        // sentinel sibling that data I/O never opens, so reads and writes of the data path leave
        // it standing.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-sentinel"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "state.bin"
            Scope.run {
                FileSystem.host.lock(target, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                    FileSystem.host.write(target, "first", Path.WriteOptions()).andThen {
                        FileSystem.host.read(target).map { value =>
                            assert(value == "first")
                            FileSystem.host.write(target, "second", Path.WriteOptions()).andThen {
                                lock.check.andThen {
                                    FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map { conflicting =>
                                        assert(conflicting.isEmpty, "the claim was lost after data path I/O")
                                    }
                                }
                            }
                        }
                    }
                }
            }.andThen {
                Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(released => assert(released.isDefined)))
            }
        }
    }

    "claims under different sentinel suffixes are independent" in {
        // The suffix names the sentinel sibling and is part of the lock's identity: two exclusive
        // claims on one data path under different suffixes contend on different files, so both are
        // granted, while a second claim under the same suffix still conflicts.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-suffix"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "state.bin"
            Scope.run {
                FileSystem.host.lock(target, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { _ =>
                    FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map { sameSuffix =>
                        assert(sameSuffix.isEmpty, "a same-suffix claim did not conflict")
                        FileSystem.host.tryLock(target, Path.LockMode.Exclusive, sentinelSuffix = ".other-lock").map { otherSuffix =>
                            assert(otherSuffix.isDefined, "a claim under a different suffix was refused")
                        }
                    }
                }
            }
        }
    }

    "an invalid sentinel suffix is refused with a typed failure" in {
        // Empty would put the claim on the data file itself, resurrecting the POSIX
        // unlock-on-close hazard; a separator would move the sentinel into another directory.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-suffix-invalid"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "state.bin"
            Abort.run[FileReadException | FileLockException](
                Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive, sentinelSuffix = "").unit)
            ).map { empty =>
                assert(empty.failure.exists(_.isInstanceOf[FileInvalidPathException]))
                Abort.run[FileReadException | FileLockException](
                    Scope.run(FileSystem.host.lock(target, Path.LockMode.Exclusive, Path.LockWait.Immediate, "bad/suffix").unit)
                ).map { separator =>
                    assert(separator.failure.exists(_.isInstanceOf[FileInvalidPathException]))
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
