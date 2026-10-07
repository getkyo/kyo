package kyo.internal

import kyo.*

class LeafContainersTest extends BasePodTest:

    private val idle = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity & wait")

    // Exceeds the pid ceiling of every platform this suite runs on, so the owner probe answers "no such process" rather than "cannot
    // tell"; TestProcessIdTest pins that answer.
    private val deadPid = "999999999"

    /** A container labelled as leaf `leaf`'s, with `owner` labels, removed at the end of the leaf whatever the sweep did with it. */
    private def leafContainer(leaf: String, owner: (String, String)*)(using
        Frame
    ): Container < (Async & Abort[ContainerException] & Scope) =
        val cfg = owner.foldLeft(idle.label(LeafContainers.leafLabelKey, leaf))((c, label) => c.label(label._1, label._2))
        Container.initUnscoped(cfg).map { c =>
            Scope.ensure(Abort.run[ContainerException](c.remove(force = true, removeVolumes = true)).unit).andThen(c)
        }
    end leafContainer

    private def survivors(leaf: String)(using Frame): Chunk[Container.Id] < (Async & Abort[ContainerException]) =
        Container.list(all = true, filters = Dict("label" -> Chunk(s"${LeafContainers.leafLabelKey}=$leaf"))).map(_.map(_.id))

    private def owner(pid: String, namespace: String): Seq[(String, String)] =
        Seq(TestContainers.ownerLabelKey -> pid, TestContainers.namespaceLabelKey -> namespace)

    "selecting the containers to remove" - {
        val namespace = "this-host/pid:[1]"
        val alive     = Set("100").contains

        def listed(id: String, state: Container.State, labels: (String, String)*): Container.Summary =
            Container.Summary(
                Container.Id(id),
                Chunk.empty,
                ContainerImage("alpine", "latest"),
                ContainerImage.Id(""),
                "",
                state,
                "",
                Chunk.empty,
                Dict(labels*),
                Chunk.empty,
                Instant.Epoch
            )

        def leafOwnedBy(id: String, state: Container.State, labels: (String, String)*): Container.Summary =
            listed(id, state, (LeafContainers.leafLabelKey -> "leaf") +: labels*)

        def selected(found: Container.Summary*): Chunk[String] =
            LeafContainers.doomed(Chunk.from(found), namespace, alive).map(_.id.value)

        "a dead owner's container is selected in every state, Dead included" in {
            val found = Container.State.values.toSeq.map(s => leafOwnedBy(s.toString, s, owner("200", namespace)*))
            assert(selected(found*) == Chunk.from(Container.State.values.map(_.toString)))
        }

        "a live owner's container is spared in every state" in {
            val found = Container.State.values.toSeq.map(s => leafOwnedBy(s.toString, s, owner("100", namespace)*))
            assert(selected(found*).isEmpty)
        }

        "a container with no owner label is spared in every state" in {
            val found = Container.State.values.toSeq.map(s => leafOwnedBy(s.toString, s, TestContainers.namespaceLabelKey -> namespace))
            assert(selected(found*).isEmpty)
        }

        "a dead owner in another pid namespace, or with none, is spared" in {
            assert(selected(
                leafOwnedBy("foreign", Container.State.Dead, owner("200", "another-host/pid:[1]")*),
                leafOwnedBy("unscoped", Container.State.Dead, TestContainers.ownerLabelKey -> "200")
            ).isEmpty)
        }

        "a container without the leaf label is spared, whatever its owner" in {
            assert(selected(listed("other", Container.State.Dead, owner("200", namespace)*)).isEmpty)
        }

        "only the dead owners' containers are selected from a mixed listing, in listing order" in {
            assert(selected(
                leafOwnedBy("live", Container.State.Running, owner("100", namespace)*),
                leafOwnedBy("dead-exited", Container.State.Stopped, owner("200", namespace)*),
                leafOwnedBy("unlabelled", Container.State.Dead),
                leafOwnedBy("dead-dead", Container.State.Dead, owner("300", namespace)*)
            ) == Chunk("dead-exited", "dead-dead"))
        }
    }

    "a leaf container whose owner process is dead is removed by the sweep" - runBackends {
        val leaf = uniqueName("kyo-pod-leaf-dead")
        for
            _    <- leafContainer(leaf, owner(deadPid, TestProcessId.namespace)*)
            _    <- LeafContainers.sweep
            left <- survivors(leaf)
        yield assert(left.isEmpty, s"the dead owner's leaf container survived the sweep: ${left.map(_.value.take(12))}")
        end for
    }

    "a leaf container whose owner process is alive survives the sweep" - runBackends {
        val leaf = uniqueName("kyo-pod-leaf-live")
        for
            mine <- leafContainer(leaf, owner(TestProcessId.pid.toString, TestProcessId.namespace)*)
            _    <- LeafContainers.sweep
            left <- survivors(leaf)
        yield assert(left == Chunk(mine.id), s"a live owner's leaf container was removed: ${left.map(_.value.take(12))}")
        end for
    }

    // Other worktrees run this suite from code that labels a leaf container with its leaf alone, concurrently with this one, so a leaf
    // container with no owner label may belong to a live process.
    "a leaf container with no owner label survives the sweep" - runBackends {
        val leaf = uniqueName("kyo-pod-leaf-unlabelled")
        for
            c    <- leafContainer(leaf)
            _    <- LeafContainers.sweep
            left <- survivors(leaf)
        yield assert(left == Chunk(c.id), s"an unlabelled leaf container was removed: ${left.map(_.value.take(12))}")
        end for
    }

    // A build container shares this machine's daemon but not its process table, so its owner pid means nothing here.
    "a dead-owner leaf container from another pid namespace survives the sweep" - runBackends {
        val leaf = uniqueName("kyo-pod-leaf-foreign")
        for
            c    <- leafContainer(leaf, owner(deadPid, "another-host/pid:[1]")*)
            _    <- LeafContainers.sweep
            left <- survivors(leaf)
        yield assert(left == Chunk(c.id), s"a foreign namespace's leaf container was removed: ${left.map(_.value.take(12))}")
        end for
    }

    "a container a leaf creates carries this process as its owner" - runBackends {
        val leaf = uniqueName("kyo-pod-leaf-owner")
        Container.ambientLabels.let(LeafContainers.labels(leaf)) {
            Container.init(idle).andThen(Container.list(
                all = true,
                filters = Dict("label" -> Chunk(s"${LeafContainers.leafLabelKey}=$leaf"))
            ))
        }.map { found =>
            assert(found.map(_.labels.get(TestContainers.ownerLabelKey)) == Chunk(Present(TestProcessId.pid.toString)))
            assert(found.map(_.labels.get(TestContainers.namespaceLabelKey)) == Chunk(Present(TestProcessId.namespace)))
        }
    }

end LeafContainersTest
