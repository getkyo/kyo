package kyo.internal

import kyo.*
import kyo.test.HostFilter

class PathPlatformSpecificLockJsTest extends kyo.test.Test[Any]:

    // Takes node:fs file locks, which a browser has not.
    override protected def hostFilters = Chunk(HostFilter.NotBrowser)

    /** Runs `f` with `process` deleted from the global object, the state a browser is in. Restored in a `finally` because the test runner
      * talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = scala.scalajs.js.Dynamic.global.globalThis
        val saved  = scala.scalajs.js.Dynamic.global.process
        scala.scalajs.js.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    private def withTarget[A](use: String => A < Sync)(using Frame): A < Sync =
        Sync.Unsafe.defer {
            val dir = NodeModules.fs.mkdtempSync(NodeModules.path.join(NodeModules.os.tmpdir(), "kyo-lock-stale-"))
            Sync.ensure(Sync.Unsafe.defer(NodeModules.fs.rmSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true, force = true)))) {
                use(NodeModules.path.join(dir, "target.bin"))
            }
        }

    "proven same-host dead owner is reclaimed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val stale = target + ".kyo-lock.exclusive"
                NodeModules.fs.writeFileSync(stale, s"${NodeModules.os.hostname()}\n2147483647\ndead-owner")
                val acquired = new NodePathUnsafe(target).lock(Path.LockMode.Shared, Path.defaultLockSuffix)
                acquired match
                    case Result.Success(lock) =>
                        assert(!lock.isExclusive)
                        assert(lock.release().isSuccess)
                    case other => assert(false, s"expected reclaimed shared lock, got $other")
                end match
            }
        }
    }

    "foreign-host owner fails closed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val claim = target + ".kyo-lock.exclusive"
                NodeModules.fs.writeFileSync(claim, "different-host\n2147483647\nforeign-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Shared, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeModules.fs.existsSync(claim))
                    case other                                           => assert(false, s"expected unavailable foreign claim, got $other")
            }
        }
    }

    "unreadable owner fails closed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val claim = target + ".kyo-lock.exclusive"
                NodeModules.fs.writeFileSync(claim, "invalid-owner-record")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeModules.fs.existsSync(claim))
                    case other => assert(false, s"expected unavailable unreadable claim, got $other")
            }
        }
    }

    "stale gate reclamation cannot delete a replacement owner" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate = target + ".kyo-lock.gate"
                NodeModules.fs.writeFileSync(gate, s"${NodeModules.os.hostname()}\n2147483647\ndead-gate")
                val reclaimed = NodePathLock.reclaimIfProvenDead(
                    gate,
                    () =>
                        NodeModules.fs.unlinkSync(gate)
                        NodeModules.fs.writeFileSync(gate, "different-host\n1\nlive-replacement")
                )
                assert(!reclaimed)
                val prefix = NodeModules.path.basename(gate) + ".reclaim."
                assert(NodeModules.fs.readdirSync(NodeModules.path.dirname(gate)).toSeq.exists(_.startsWith(prefix)))
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(true)
                    case other => assert(false, s"expected replacement gate to remain authoritative, got $other")
            }
        }
    }

    "gate acquisition errors remain typed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val missing = NodeModules.path.join(target + "-missing", "target.bin")
                new NodePathUnsafe(missing).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockException) => assert(true)
                    case other                                => assert(false, s"expected typed lock failure, got $other")
            }
        }
    }

    "removing an owned claim surfaces ownership loss on release" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Success(lock) =>
                        NodeModules.fs.unlinkSync(target + ".kyo-lock.exclusive")
                        assert(lock.check().isFailure)
                        lock.release() match
                            case Result.Failure(_: FileLockOwnershipLostException) => assert(true)
                            case other => assert(false, s"expected ownership loss from missing claim, got $other")
                    case other => assert(false, s"expected acquired lock, got $other")
            }
        }
    }

    "a publication from a separate live worker blocks without serial litter growth" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate = target + ".kyo-lock.gate"
                val publication = NodePathLock.publicationPath(
                    gate,
                    NodeModules.os.hostname(),
                    scala.scalajs.js.Dynamic.global.process.selectDynamic("pid").asInstanceOf[Int],
                    "separate-worker"
                )
                NodeModules.fs.writeFileSync(publication, "partial")
                (0 until 64).foreach { _ =>
                    new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                        case Result.Failure(_: FileLockUnavailableException) => assert(true)
                        case other => assert(false, s"expected live worker publication to block, got $other")
                }
                val publications = NodeModules.fs.readdirSync(NodeModules.path.dirname(gate)).toSeq
                    .filter(_.startsWith(NodeModules.path.basename(gate) + ".publish."))
                assert(publications == Seq(NodeModules.path.basename(publication)))
            }
        }
    }

    "malformed and foreign publications fail closed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate      = target + ".kyo-lock.gate"
                val malformed = target + ".kyo-lock.shared.partial.publish.malformed"
                NodeModules.fs.writeFileSync(malformed, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Shared, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeModules.fs.existsSync(malformed))
                    case other => assert(false, s"expected malformed publication to block, got $other")
                NodeModules.fs.unlinkSync(malformed)
                val invalidPid = NodePathLock.publicationPath(gate, NodeModules.os.hostname(), -2147483647, "invalid-pid")
                NodeModules.fs.writeFileSync(invalidPid, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeModules.fs.existsSync(invalidPid))
                    case other => assert(false, s"expected invalid publication pid to block, got $other")
                NodeModules.fs.unlinkSync(invalidPid)
                val foreign = NodePathLock.publicationPath(gate, "different-host", 1, "foreign-worker")
                NodeModules.fs.writeFileSync(foreign, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeModules.fs.existsSync(foreign))
                    case other => assert(false, s"expected foreign publication to block, got $other")
            }
        }
    }

    "a publication owned by a proven dead process is reclaimed from its filename" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate = target + ".kyo-lock.gate"
                val publication = NodePathLock.publicationPath(
                    gate,
                    NodeModules.os.hostname(),
                    2147483647,
                    "dead-worker"
                )
                NodeModules.fs.writeFileSync(publication, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix) match
                    case Result.Success(lock) =>
                        assert(!NodeModules.fs.existsSync(publication))
                        assert(lock.release().isSuccess)
                    case other => assert(false, s"expected dead publication reclamation, got $other")
                end match
            }
        }
    }

    "live publication cleanup failure is typed and rolls back the claim" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val result = NodePathLock.acquire(
                    Path(target),
                    target,
                    Path.LockMode.Exclusive,
                    beforePublishCleanup = temporary =>
                        if temporary.contains(".exclusive.publish.") then
                            NodeModules.fs.unlinkSync(temporary)
                            NodeModules.fs.mkdirSync(temporary, scala.scalajs.js.Dynamic.literal())
                )
                result match
                    case Result.Failure(_: FileIOException) =>
                        assert(!NodeModules.fs.existsSync(target + ".kyo-lock.exclusive"))
                    case other => assert(false, s"expected typed publication cleanup failure, got $other")
                end match
            }
        }
    }

    "gate cleanup failure cannot return a healthy claim" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val result = NodePathLock.acquire(
                    Path(target),
                    target,
                    Path.LockMode.Exclusive,
                    beforeGateRelease = (gate, _) =>
                        NodeModules.fs.unlinkSync(gate)
                        NodeModules.fs.writeFileSync(gate, "different-host\n1\nreplacement-gate")
                )
                result match
                    case Result.Failure(_: FileLockOwnershipLostException) =>
                        assert(!NodeModules.fs.existsSync(target + ".kyo-lock.exclusive"))
                    case other => assert(false, s"expected typed gate cleanup failure, got $other")
                end match
            }
        }
    }

    "with no process global" - {
        "acquiring a lock fails naming the host instead of throwing ReferenceError" in {
            withTarget { target =>
                Sync.Unsafe.defer {
                    withoutProcessGlobal(new NodePathUnsafe(target).lock(Path.LockMode.Exclusive, Path.defaultLockSuffix)) match
                        case Result.Failure(error: FileSystemUnsupportedOnHostException) =>
                            assert(error.operation == FileSystemOperation.Lock)
                            assert(!NodeModules.fs.existsSync(target + ".kyo-lock.gate"))
                        case other => fail(s"expected a typed unsupported-host failure, got $other")
                }
            }
        }
    }

    "gate cleanup and claim rollback failures are both reported" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val result = NodePathLock.acquire(
                    Path(target),
                    target,
                    Path.LockMode.Exclusive,
                    beforeGateRelease = (gate, claim) =>
                        NodeModules.fs.unlinkSync(gate)
                        NodeModules.fs.writeFileSync(gate, "different-host\n1\nreplacement-gate")
                        NodeModules.fs.unlinkSync(claim)
                        NodeModules.fs.writeFileSync(claim, "different-host\n1\nreplacement-claim")
                )
                result match
                    case Result.Failure(error: FileLockCleanupException) =>
                        assert(error.primary.isInstanceOf[FileLockOwnershipLostException])
                        assert(error.cleanup.isInstanceOf[FileLockOwnershipLostException])
                        assert(NodeModules.fs.existsSync(target + ".kyo-lock.exclusive"))
                    case other => assert(false, s"expected combined cleanup failure, got $other")
                end match
            }
        }
    }

end PathPlatformSpecificLockJsTest
