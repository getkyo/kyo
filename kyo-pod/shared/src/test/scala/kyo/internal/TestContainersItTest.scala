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
    private def orphan(tagKey: String, tag: String, ownerKey: String, extraLabels: (String, String)*)(using
        Frame
    ): Container < (Async & Abort[ContainerException] & Scope) =
        val labelled = extraLabels.foldLeft(idle.label(tagKey, tag).label(ownerKey, deadPid))((cfg, label) => cfg.label(label._1, label._2))
        Container.initUnscoped(labelled).map { c =>
            Scope.ensure(Abort.run[ContainerException](c.remove(force = true, removeVolumes = true)).unit).andThen(c)
        }
    end orphan

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

    // A build container shares this machine's daemon but not its process table, so its test process's pid means
    // nothing here: a pid probe from this namespace would find it dead and remove a container that run is using.
    "a dead-owner container from another pid namespace is spared" - runBackends {
        val tag = uniqueName("kyo-pod-it-foreign")
        for
            c <- orphan(
                TestContainers.tagLabelKey,
                tag,
                TestContainers.ownerLabelKey,
                TestContainers.namespaceLabelKey -> "another-host/pid:[1]"
            )
            _    <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left <- listByLabel(TestContainers.tagLabelKey, tag)
        yield assert(left.map(_.id) == Chunk(c.id), s"a foreign namespace's container was reaped: ${left.map(_.id.value.take(12))}")
        end for
    }

    "a dead-owner container from this pid namespace is removed" - runBackends {
        val tag = uniqueName("kyo-pod-it-same-ns")
        for
            _ <- orphan(
                TestContainers.tagLabelKey,
                tag,
                TestContainers.ownerLabelKey,
                TestContainers.namespaceLabelKey -> TestProcessId.namespace
            )
            _    <- TestContainers.initScoped(idle, uniqueName("kyo-pod-it-sweeper"))
            left <- listByLabel(TestContainers.tagLabelKey, tag)
        yield assert(left.isEmpty, s"the same-namespace orphan survived the sweep: ${left.map(_.id.value.take(12))}")
        end for
    }

    "a container this process creates carries its namespace" - runBackends {
        val tag = uniqueName("kyo-pod-it-ns-label")
        for
            _    <- TestContainers.initScoped(idle, tag)
            left <- listByLabel(TestContainers.tagLabelKey, tag)
        yield assert(left.map(_.labels.get(TestContainers.namespaceLabelKey)) == Chunk(Present(TestProcessId.namespace)))
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

    // Shared containers outlive their scope, so each leaf below removes what it created.
    private def removeAtEnd(containers: Container*)(using Frame): Unit < (Async & Scope) =
        Scope.ensure(Kyo.foreachDiscard(containers)(c => Abort.run[ContainerException](c.remove(force = true, removeVolumes = true)).unit))

    "a shared container is created once per tag and config and handed to every later caller" - runBackends {
        val tag = uniqueName("kyo-pod-it-shared")
        for
            first  <- TestContainers.initShared(idle, tag)
            again  <- TestContainers.initShared(idle, tag)
            other  <- TestContainers.initShared(idle, uniqueName("kyo-pod-it-shared-other"))
            _      <- removeAtEnd(first, other)
            listed <- listByLabel(TestContainers.tagLabelKey, s"shared-$tag")
        yield
            assert(again.id == first.id, s"a second caller got ${again.id.value.take(12)}, not ${first.id.value.take(12)}")
            assert(other.id != first.id, "a different tag must get a container of its own")
            assert(listed.map(_.id) == Chunk(first.id), s"expected one container for the tag, got ${listed.map(_.id.value.take(12))}")
        end for
    }

    // Shared leaves create server-wide state (roles, accounts) that another process running the same suite would collide on.
    "a singleton under the same tag never adopts a shared container" - runBackends {
        val tag = uniqueName("kyo-pod-it-shared-adopt")
        for
            shared    <- TestContainers.initShared(idle, tag)
            singleton <- TestContainers.initSingleton(idle, tag)
            _         <- removeAtEnd(shared, singleton)
        yield assert(singleton.id != shared.id, "initSingleton adopted the shared container")
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
