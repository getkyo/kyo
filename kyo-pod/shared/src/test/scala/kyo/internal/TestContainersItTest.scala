package kyo.internal

import kyo.*

/** Drives [[TestContainers]]'s sweep against a live daemon: the creation of one container removes every labelled container whose owner
  * process is gone, on the current label set and on the legacy one, and spares the ones a live process owns or co-owns.
  *
  * [[TestContainersTest]] covers the predicate's pieces without a daemon; this is the one place they run together over real containers.
  *
  * Both backends, because the sweep's list filter and force-remove take different code paths through the HTTP API and the CLI, and
  * because a podman machine can expose two stores: the API socket and the CLI's default connection have listed different containers on
  * one host, so a leftover reachable through one backend was invisible to a sweep through the other.
  */
class TestContainersItTest extends BasePodTest:

    private val idle = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity & wait")

    // Exceeds the pid ceiling of every platform this suite runs on, so the owner probe answers "no such
    // process" rather than "cannot tell"; TestProcessIdTest pins that answer.
    private val deadPid = "999999999"

    private def listByLabel(key: String, value: String)(using Frame): Chunk[Container.Summary] < (Async & Abort[ContainerException]) =
        Container.list(all = true, filters = Dict("label" -> Chunk(s"$key=$value")))

    /** A labelled container whose owner is dead, removed at the end of the leaf in case the sweep under test left it behind. */
    private def orphan(tagKey: String, tag: String, ownerKey: String)(using
        Frame
    ): Container < (Async & Abort[ContainerException] & Scope) =
        Container.initUnscoped(idle.label(tagKey, tag).label(ownerKey, deadPid)).map { c =>
            Scope.ensure(Abort.run[ContainerException](c.remove(force = true, removeVolumes = true)).unit).andThen(c)
        }

    "a dead-owner container is removed by the next creation" - runBackends {
        val tag = uniqueName("kyo-pod-it-dead")
        for
            _    <- orphan(TestContainers.tagLabelKey, tag, TestContainers.ownerLabelKey)
            _    <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left <- listByLabel(TestContainers.tagLabelKey, tag)
        yield assert(left.isEmpty, s"the orphan survived the sweep: ${left.map(_.id.value.take(12))}")
        end for
    }

    // The usual leftover is not running: a killed test process leaves its database container to exit on its
    // own, and the sweep has to list and remove it in that state.
    "an exited dead-owner container is removed" - runBackends {
        val tag = uniqueName("kyo-pod-it-exited")
        for
            c     <- orphan(TestContainers.tagLabelKey, tag, TestContainers.ownerLabelKey)
            _     <- c.stop
            state <- c.state
            _     <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left  <- listByLabel(TestContainers.tagLabelKey, tag)
        yield
            assert(state == Container.State.Stopped, s"the orphan should have stopped before the sweep, was $state")
            assert(left.isEmpty, s"the exited orphan survived the sweep: ${left.map(_.id.value.take(12))}")
        end for
    }

    "a container this process owns is spared" - runBackends {
        val tag = uniqueName("kyo-pod-it-live")
        for
            mine <- TestContainers.initScoped(idle, tag)
            _    <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left <- listByLabel(TestContainers.tagLabelKey, tag)
        yield assert(left.map(_.id) == Chunk(mine.id), s"expected only ${mine.id.value.take(12)}, got ${left.map(_.id.value.take(12))}")
        end for
    }

    "a legacy-labelled dead-owner container is removed" - runBackends {
        val tag = uniqueName("kyo-pod-it-legacy")
        for
            _    <- orphan(TestContainers.legacyTagLabelKey, tag, TestContainers.legacyOwnerLabelKey)
            _    <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left <- listByLabel(TestContainers.legacyTagLabelKey, tag)
        yield assert(left.isEmpty, s"the legacy orphan survived the sweep: ${left.map(_.id.value.take(12))}")
        end for
    }

    // A process that adopted a legacy container recorded its claim under the legacy registry, so the legacy
    // sweep has to read that registry and not the current one.
    "a legacy-labelled container with a live co-owner is spared" - runBackends {
        val tag = uniqueName("kyo-pod-it-legacy-owned")
        TestContainers.ownerRoot(TestContainers.legacyRegistryDir).map {
            case Absent        => fail("no temp root on this platform; the registry cannot be exercised")
            case Present(root) =>
                for
                    c       <- orphan(TestContainers.legacyTagLabelKey, tag, TestContainers.legacyOwnerLabelKey)
                    claimed <- TestContainers.claimOwnership(root, c.id)
                    _       <- Scope.ensure(TestContainers.forgetOwners(root, c.id))
                    _       <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
                    left    <- listByLabel(TestContainers.legacyTagLabelKey, tag)
                yield
                    assert(claimed, "the legacy registry must accept the claim")
                    assert(left.map(_.id) == Chunk(c.id), s"the co-owned legacy container was reaped: ${left.map(_.id.value.take(12))}")
        }
    }

end TestContainersItTest
