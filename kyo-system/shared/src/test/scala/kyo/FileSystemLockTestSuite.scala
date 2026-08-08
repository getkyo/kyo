package kyo

/** Shared behavioral contract for [[FileSystem.Read]] lock implementations. */
abstract class FileSystemLockTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException])

    private def assertImmediate(first: Path.LockMode, second: Path.LockMode, expected: Boolean)(using
        kyo.test.AssertScope
    ): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        withFileSystem { (fileSystem, path) =>
            fileSystem.lock(path, first, Path.LockWait.Immediate).map { held =>
                fileSystem.tryLock(path, second).map { attempted =>
                    assert(held.mode == first)
                    assert(attempted.isDefined == expected)
                }
            }
        }

    private def assertWaiting(first: Path.LockMode, second: Path.LockMode, compatible: Boolean)(using
        kyo.test.AssertScope
    ): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        withFileSystem { (fileSystem, path) =>
            if compatible then
                Scope.run {
                    fileSystem.lock(path, first, Path.LockWait.Immediate).map { _ =>
                        fileSystem.lock(path, second, Path.LockWait.UntilAvailable).map { acquired =>
                            assert(acquired.mode == second)
                        }
                    }
                }
            else
                Scope.run {
                    fileSystem.lock(path, first, Path.LockWait.Immediate).map { _ =>
                        fileSystem.tryLock(path, second).map { unavailable =>
                            assert(unavailable.isEmpty)
                            Fiber.initUnscoped(Scope.run(fileSystem.lock(path, second, Path.LockWait.UntilAvailable)))
                                .map { waiter =>
                                    waiter.poll.map { state =>
                                        assert(state.isEmpty)
                                        waiter
                                    }
                                }
                        }
                    }
                }.map(_.get.map(acquired => assert(acquired.mode == second)))
        }
    end assertWaiting

    "immediate compatibility" - {
        "shared with shared succeeds" in {
            assertImmediate(Path.LockMode.Shared, Path.LockMode.Shared, expected = true)
        }
        "shared with exclusive conflicts" in {
            assertImmediate(Path.LockMode.Shared, Path.LockMode.Exclusive, expected = false)
        }
        "exclusive with shared conflicts" in {
            assertImmediate(Path.LockMode.Exclusive, Path.LockMode.Shared, expected = false)
        }
        "exclusive with exclusive conflicts" in {
            assertImmediate(Path.LockMode.Exclusive, Path.LockMode.Exclusive, expected = false)
        }
    }

    "waiting compatibility" - {
        "shared with shared succeeds" in {
            assertWaiting(Path.LockMode.Shared, Path.LockMode.Shared, compatible = true)
        }
        "shared with exclusive waits for release" in {
            assertWaiting(Path.LockMode.Shared, Path.LockMode.Exclusive, compatible = false)
        }
        "exclusive with shared waits for release" in {
            assertWaiting(Path.LockMode.Exclusive, Path.LockMode.Shared, compatible = false)
        }
        "exclusive with exclusive waits for release" in {
            assertWaiting(Path.LockMode.Exclusive, Path.LockMode.Exclusive, compatible = false)
        }
    }

    "lifecycle" - {
        "scope closure releases the owning claim" in {
            withFileSystem { (fileSystem, path) =>
                Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).unit).andThen {
                    Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        assert(lock.mode == Path.LockMode.Exclusive)
                    })
                }
            }
        }

        "cancelled waiter leaves no ownership claim" in {
            withFileSystem { (fileSystem, path) =>
                Scope.run {
                    fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { _ =>
                        Latch.init(1).map { started =>
                            Fiber.initUnscoped(
                                started.release.andThen(
                                    Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.UntilAvailable))
                                )
                            ).map { waiter =>
                                started.await.andThen(waiter.interrupt).map { interrupted =>
                                    assert(interrupted)
                                }
                            }
                        }
                    }
                }.andThen {
                    Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        assert(lock.mode == Path.LockMode.Exclusive)
                    })
                }
            }
        }

        "deadline timeout is driven by Clock" in {
            Clock.withTimeControl { control =>
                withFileSystem { (fileSystem, path) =>
                    Scope.run {
                        fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { _ =>
                            Clock.deadline(10.millis).map { deadline =>
                                Latch.init(1).map { started =>
                                    Fiber.initUnscoped(
                                        started.release.andThen(
                                            Abort.run[FileLockException](
                                                Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Until(deadline)))
                                            )
                                        )
                                    ).map { waiter =>
                                        started.await.andThen(Loop.repeat(11)(control.advance(1.milli))).andThen(waiter.get).map {
                                            case Result.Failure(_: FileLockTimeoutException) => assert(true)
                                            case other => assert(false, s"expected FileLockTimeoutException, got $other")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "an already-expired deadline cannot grant a free lock" in {
            Clock.withTimeControl { control =>
                withFileSystem { (fileSystem, path) =>
                    Clock.deadline(Duration.Zero).map { deadline =>
                        control.advance(1.nano).andThen {
                            Abort.run[FileLockException](
                                Scope.run(fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Until(deadline)))
                            ).map {
                                case Result.Failure(_: FileLockTimeoutException) => assert(true)
                                case other                                       => assert(false, s"expected expired deadline, got $other")
                            }
                        }
                    }
                }
            }
        }

        "expiry before a retry prevents another acquisition attempt" in {
            Clock.withTimeControl { control =>
                val path  = Path("deadline-retry.bin")
                val token = Path.LockOwnership.fresh()
                val available = new Path.Lock:
                    def mode: Path.LockMode                                          = Path.LockMode.Exclusive
                    def ownership: Path.LockOwnership                                = token
                    def check(using Frame): Unit < Sync                              = ()
                    def release(owner: Path.LockOwnership)(using Frame): Unit < Sync = ()
                Clock.deadline(1.milli).map { deadline =>
                    AtomicInt.init(0).map { attempts =>
                        Latch.init(1).map { firstAttempt =>
                            Latch.init(1).map { finishFirstAttempt =>
                                val attempt = attempts.incrementAndGet.map {
                                    case 1 => firstAttempt.release.andThen(finishFirstAttempt.await).andThen(Absent)
                                    case _ => Present(available)
                                }
                                Fiber.initUnscoped(
                                    Abort.run[FileLockException](Scope.run(FileSystem.awaitLock(
                                        path,
                                        Path.LockWait.Until(deadline)
                                    )(attempt)))
                                ).map { waiter =>
                                    firstAttempt.await.andThen(control.advance(2.millis)).andThen(finishFirstAttempt.release)
                                        .andThen(Loop.repeat(5)(control.advance(2.millis))).andThen(waiter.get).map { result =>
                                            result match
                                                case Result.Failure(_: FileLockTimeoutException) => ()
                                                case other => assert(false, s"expected expiry before retry, got $other")
                                            attempts.get.map(count => assert(count == 1))
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }

        "owning release is idempotent" in {
            withFileSystem { (fileSystem, path) =>
                Scope.run {
                    fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        lock.release(lock.ownership).andThen(lock.release(lock.ownership)).andThen {
                            fileSystem.tryLock(path, Path.LockMode.Exclusive).map(acquired => assert(acquired.isDefined))
                        }
                    }
                }
            }
        }

        "concurrent releases both terminate" in {
            // Release is reachable from two directions at once: the Scope finalizer and an explicit
            // call. Whichever loses the claim must return rather than wait on the winner, because
            // the loser can be the finalizer, and a finalizer that does not return is a scope that
            // never closes. The earlier implementation had the loser loop on an intermediate state
            // with no yield and no exit condition it could reach on its own.
            withFileSystem { (fileSystem, path) =>
                Scope.run {
                    fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        for
                            gate   <- Latch.init(1)
                            first  <- Fiber.initUnscoped(gate.await.andThen(lock.release(lock.ownership)))
                            second <- Fiber.initUnscoped(gate.await.andThen(lock.release(lock.ownership)))
                            _      <- gate.release
                            _      <- first.get
                            _      <- second.get
                            // The claim is genuinely gone, not merely reported as released.
                            reacquired <- fileSystem.tryLock(path, Path.LockMode.Exclusive)
                        yield assert(reacquired.isDefined)
                        end for
                    }
                }
            }
        }

        "wrong owner cannot release a claim" in {
            withFileSystem { (fileSystem, path) =>
                Scope.run {
                    fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        Abort.run[FileLockException](lock.release(Path.LockOwnership.fresh())).map { result =>
                            result match
                                case Result.Failure(_: FileLockOwnershipLostException) => ()
                                case other => assert(false, s"expected FileLockOwnershipLostException, got $other")
                            lock.check.andThen(fileSystem.tryLock(path, Path.LockMode.Exclusive)).map { acquired =>
                                assert(acquired.isEmpty)
                            }
                        }
                    }
                }
            }
        }

        "check reports deterministic ownership loss" in {
            withFileSystem { (fileSystem, path) =>
                Scope.run {
                    fileSystem.lock(path, Path.LockMode.Exclusive, Path.LockWait.Immediate).map { lock =>
                        lock.release(lock.ownership).andThen(Abort.run[FileLockException](lock.check)).map {
                            case Result.Failure(_: FileLockOwnershipLostException) => assert(true)
                            case other => assert(false, s"expected FileLockOwnershipLostException, got $other")
                        }
                    }
                }
            }
        }
    }

end FileSystemLockTestSuite
