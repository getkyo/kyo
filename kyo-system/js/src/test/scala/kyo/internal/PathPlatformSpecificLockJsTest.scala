package kyo.internal

import kyo.*

class PathPlatformSpecificLockJsTest extends kyo.test.Test[Any]:

    private def withTarget[A](use: String => A < Sync)(using Frame): A < Sync =
        Sync.Unsafe.defer {
            val dir = NodeFs.mkdtempSync(NodePath.join(NodeOs.tmpdir(), "kyo-lock-stale-"))
            Sync.ensure(Sync.Unsafe.defer(NodeFs.rmSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true, force = true)))) {
                use(NodePath.join(dir, "target.bin"))
            }
        }

    "proven same-host dead owner is reclaimed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val stale = target + ".kyo-lock.exclusive"
                NodeFs.writeFileSync(stale, s"${NodeOs.hostname()}\n2147483647\ndead-owner")
                val acquired = new NodePathUnsafe(target).lock(Path.LockMode.Shared)
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
                NodeFs.writeFileSync(claim, "different-host\n2147483647\nforeign-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Shared) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeFs.existsSync(claim))
                    case other                                           => assert(false, s"expected unavailable foreign claim, got $other")
            }
        }
    }

    "unreadable owner fails closed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val claim = target + ".kyo-lock.exclusive"
                NodeFs.writeFileSync(claim, "invalid-owner-record")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeFs.existsSync(claim))
                    case other => assert(false, s"expected unavailable unreadable claim, got $other")
            }
        }
    }

    "stale gate reclamation cannot delete a replacement owner" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate = target + ".kyo-lock.gate"
                NodeFs.writeFileSync(gate, s"${NodeOs.hostname()}\n2147483647\ndead-gate")
                val reclaimed = NodePathLock.reclaimIfProvenDead(
                    gate,
                    () =>
                        NodeFs.unlinkSync(gate)
                        NodeFs.writeFileSync(gate, "different-host\n1\nlive-replacement")
                )
                assert(!reclaimed)
                val prefix = NodePath.basename(gate) + ".reclaim."
                assert(NodeFs.readdirSync(NodePath.dirname(gate)).toSeq.exists(_.startsWith(prefix)))
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(true)
                    case other => assert(false, s"expected replacement gate to remain authoritative, got $other")
            }
        }
    }

    "gate acquisition errors remain typed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val missing = NodePath.join(target + "-missing", "target.bin")
                new NodePathUnsafe(missing).lock(Path.LockMode.Exclusive) match
                    case Result.Failure(_: FileLockException) => assert(true)
                    case other                                => assert(false, s"expected typed lock failure, got $other")
            }
        }
    }

    "removing an owned claim surfaces ownership loss on release" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Success(lock) =>
                        NodeFs.unlinkSync(target + ".kyo-lock.exclusive")
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
                    NodeOs.hostname(),
                    scala.scalajs.js.Dynamic.global.process.selectDynamic("pid").asInstanceOf[Int],
                    "separate-worker"
                )
                NodeFs.writeFileSync(publication, "partial")
                (0 until 64).foreach { _ =>
                    new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                        case Result.Failure(_: FileLockUnavailableException) => assert(true)
                        case other => assert(false, s"expected live worker publication to block, got $other")
                }
                val publications = NodeFs.readdirSync(NodePath.dirname(gate)).toSeq
                    .filter(_.startsWith(NodePath.basename(gate) + ".publish."))
                assert(publications == Seq(NodePath.basename(publication)))
            }
        }
    }

    "malformed and foreign publications fail closed" in {
        withTarget { target =>
            Sync.Unsafe.defer {
                val gate      = target + ".kyo-lock.gate"
                val malformed = target + ".kyo-lock.shared.partial.publish.malformed"
                NodeFs.writeFileSync(malformed, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Shared) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeFs.existsSync(malformed))
                    case other => assert(false, s"expected malformed publication to block, got $other")
                NodeFs.unlinkSync(malformed)
                val invalidPid = NodePathLock.publicationPath(gate, NodeOs.hostname(), -2147483647, "invalid-pid")
                NodeFs.writeFileSync(invalidPid, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeFs.existsSync(invalidPid))
                    case other => assert(false, s"expected invalid publication pid to block, got $other")
                NodeFs.unlinkSync(invalidPid)
                val foreign = NodePathLock.publicationPath(gate, "different-host", 1, "foreign-worker")
                NodeFs.writeFileSync(foreign, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Failure(_: FileLockUnavailableException) => assert(NodeFs.existsSync(foreign))
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
                    NodeOs.hostname(),
                    2147483647,
                    "dead-worker"
                )
                NodeFs.writeFileSync(publication, "partial-owner")
                new NodePathUnsafe(target).lock(Path.LockMode.Exclusive) match
                    case Result.Success(lock) =>
                        assert(!NodeFs.existsSync(publication))
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
                            NodeFs.unlinkSync(temporary)
                            NodeFs.mkdirSync(temporary, scala.scalajs.js.Dynamic.literal())
                )
                result match
                    case Result.Failure(_: FileIOException) =>
                        assert(!NodeFs.existsSync(target + ".kyo-lock.exclusive"))
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
                    (gate, _) =>
                        NodeFs.unlinkSync(gate)
                        NodeFs.writeFileSync(gate, "different-host\n1\nreplacement-gate")
                )
                result match
                    case Result.Failure(_: FileLockOwnershipLostException) =>
                        assert(!NodeFs.existsSync(target + ".kyo-lock.exclusive"))
                    case other => assert(false, s"expected typed gate cleanup failure, got $other")
                end match
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
                    (gate, claim) =>
                        NodeFs.unlinkSync(gate)
                        NodeFs.writeFileSync(gate, "different-host\n1\nreplacement-gate")
                        NodeFs.unlinkSync(claim)
                        NodeFs.writeFileSync(claim, "different-host\n1\nreplacement-claim")
                )
                result match
                    case Result.Failure(error: FileLockCleanupException) =>
                        assert(error.primary.isInstanceOf[FileLockOwnershipLostException])
                        assert(error.cleanup.isInstanceOf[FileLockOwnershipLostException])
                        assert(NodeFs.existsSync(target + ".kyo-lock.exclusive"))
                    case other => assert(false, s"expected combined cleanup failure, got $other")
                end match
            }
        }
    }

end PathPlatformSpecificLockJsTest
