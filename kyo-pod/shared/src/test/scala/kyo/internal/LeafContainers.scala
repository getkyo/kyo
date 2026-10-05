package kyo.internal

import kyo.*

/** Ownership labels and the orphan sweep for the containers kyo-pod's own test leaves create.
  *
  * A leaf's containers are removed by its `Scope`, which a test process that dies (SIGINT, an sbt cancel, a fork timeout) never runs, so they
  * stay on the daemon. Every container a leaf causes therefore carries the creating process's pid and pid namespace under the same keys and
  * with the same meaning as [[TestContainers]]'s, and the first leaf a process runs against a backend removes the leaf containers whose owner
  * process is gone.
  *
  * The predicate is [[TestContainers]]'s, and every uncertainty spares: a live or recycled pid, an owner probe that cannot tell, a container
  * from another process table, and a failed list all leave the container alone. One case is stricter than there: a leaf container with no
  * owner label is spared, not reaped, because a checkout whose leaves label their containers with the leaf alone may be running this suite
  * against the same daemon, and nothing tells its live containers from dead ones.
  */
private[kyo] object LeafContainers:

    /** Label key carried by every container a leaf causes; its value is the leaf's random id. */
    val leafLabelKey: String = "kyo.pod.test.leaf"

    /** The labels every container leaf `leaf` causes carries. */
    def labels(leaf: String): Dict[String, String] =
        Dict(
            leafLabelKey                     -> leaf,
            TestContainers.ownerLabelKey     -> TestProcessId.pid.toString,
            TestContainers.namespaceLabelKey -> TestProcessId.namespace
        )

    /** The leaf containers among `found` whose owner, in pid namespace `namespace`, `alive` reports as no longer running, whatever their
      * state.
      */
    private[kyo] def doomed(found: Chunk[Container.Summary], namespace: String, alive: String => Boolean): Chunk[Container.Summary] =
        found.filter { summary =>
            summary.labels.contains(leafLabelKey) &&
            summary.labels.get(TestContainers.namespaceLabelKey).contains(namespace) &&
            summary.labels.get(TestContainers.ownerLabelKey).exists(owner => !alive(owner))
        }

    /** Remove every leaf container whose owner process is dead. Failures are swallowed: a sweep never fails a leaf. */
    def sweep(using Frame): Unit < Async =
        Abort.run[ContainerException] {
            Container.list(all = true, filters = Dict("label" -> Chunk(leafLabelKey))).map { found =>
                val owners = found
                    .filter(_.labels.get(TestContainers.namespaceLabelKey).contains(TestProcessId.namespace))
                    .flatMap(_.labels.get(TestContainers.ownerLabelKey).toChunk)
                    .distinct
                Kyo.filter(owners)(TestProcessId.isAlive).map { live =>
                    Kyo.foreachDiscard(doomed(found, TestProcessId.namespace, live.toSet.contains)) { summary =>
                        Abort.run[ContainerException](summary.attach.map(_.remove(force = true, removeVolumes = true))).unit
                    }
                }
            }
        }.unit

    // Unsafe: module-load AtomicRef init (no live Frame yet); later accesses go through the safe API.
    private val swept: AtomicRef[Set[String]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init(Set.empty[String]).safe

    /** [[sweep]] once per process for each backend, keyed by its description: one listing per daemon rather than one per leaf. */
    def sweepOnce(using Frame): Unit < Async =
        Abort.run[ContainerException](Container.currentBackendDescription).map {
            case Result.Success(backend) =>
                swept.getAndUpdate(_ + backend).map(before => if before.contains(backend) then Kyo.unit else sweep)
            case _ => Kyo.unit
        }

end LeafContainers
