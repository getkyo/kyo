package kyo.internal

import kyo.*

/** Creation wrapper and orphan reaper for the kyo-sql test containers.
  *
  * Singleton containers are created with [[Container.initUnscoped]] and deliberately outlive every scope, so a force-killed test process
  * removes nothing: its containers and their anonymous data volumes stay on the daemon forever. Nothing in the build reaps them, and
  * nothing can, because a `Tests.Cleanup` task does not run on SIGKILL either. A scope-managed container ([[initScoped]]) is removed on
  * every normal exit and so leaks only on that same kill, which is why it goes through here too rather than through [[Container.init]]
  * directly: the labels are what make the leftovers findable.
  *
  * So the reap happens at CREATE time instead, which is the one moment a container-using run is guaranteed to reach. Each container is
  * labelled `kyo-sql-singleton=<tag>` plus `kyo-sql-owner-pid=<pid of the creating test process>`. [[initSingleton]] first removes,
  * together with its anonymous volumes, every labelled container whose owner process is dead, then creates the new container carrying both
  * labels. A container with no owner label predates the label and is removed as well.
  *
  * A LIVE owner pid means the container belongs to a concurrently running test process (another module fork, platform leg, sbt session, or
  * worktree) and it is always spared. Every uncertainty resolves toward sparing, which is what makes the predicate safe against those
  * concurrent forks: labels are applied atomically with create, a dead pid stays dead, a recycled pid spares an orphan for one more cycle,
  * a failed list aborts the sweep without removing anything, and an owner probe that fails in a way it does not recognise reports RUNNING
  * rather than dead. Only a definite "no such process" is death. Removing on an unrecognised probe failure would let one broken probe
  * delete another run's containers, so [[TestProcessId.isAlive]] is written to make that impossible on every platform.
  *
  * The two cases that DO reap without consulting a process are a missing owner label and a label whose value is not a `Long`. Both are the
  * same judgement: [[initSingleton]] is the only writer of this label and always writes `TestProcessId.pid.toString`, so neither shape can
  * have come from a live kyo-sql test process.
  *
  * All reap failures are swallowed, so the reap can never fail a fixture: a vanished container, an unreachable daemon, or a machine with no
  * daemon at all leaves the creation path unchanged. `Abort.run[ContainerException]` covers more than its type parameter suggests, which is
  * why nothing wider is needed here: it accepts panics as well as typed failures (`Abort.scala:217` and `:223`), so a thrown exception from
  * any depth of the sweep arrives as `Result.Panic` and is discarded by the `unit`.
  *
  * Steady state is one run's population per daemon: the last run's containers linger until the next container-using run sweeps them.
  */
private[kyo] object SqlTestContainers:

    /** Label key carried by every kyo-sql singleton container; its value is the fixture tag. */
    val singletonLabelKey: String = "kyo-sql-singleton"

    /** Label key carrying the pid of the test process that created the container. */
    val ownerLabelKey: String = "kyo-sql-owner-pid"

    /** Reap dead-owner singleton containers, then create a new singleton from `cfg` labelled with `tag` and this process's pid. */
    def initSingleton(cfg: Container.Config, tag: String)(using
        Frame
    ): Container < (Async & Abort[ContainerException]) =
        reapOrphans.andThen {
            Container.initUnscoped(labelled(cfg, tag))
        }

    /** Reap dead-owner containers, then create a SCOPE-MANAGED container from `cfg` carrying the same two labels.
      *
      * For a fixture that wants one container per leaf rather than one per JVM: the enclosing `Scope` removes it, with its anonymous
      * volumes, at the end of the leaf. The labels are what a scoped container still needs. A force-killed test process runs no finalizer,
      * so its container and volumes survive exactly as an unscoped one's do, and without the labels nothing can ever find them again. That
      * is the state this object exists to prevent, and it was last measured at 700 anonymous volumes and 64G on one machine.
      */
    def initScoped(cfg: Container.Config, tag: String)(using
        Frame
    ): Container < (Async & Abort[ContainerException] & Scope) =
        reapOrphans.andThen {
            Container.init(labelled(cfg, tag))
        }

    private def labelled(cfg: Container.Config, tag: String): Container.Config =
        cfg.label(singletonLabelKey, tag)
            .label(ownerLabelKey, TestProcessId.pid.toString)

    private def reapOrphans(using Frame): Unit < Async =
        Abort.run[ContainerException] {
            Container.list(all = true, filters = Dict("label" -> Chunk(singletonLabelKey))).map { found =>
                Kyo.foreachDiscard(found) { summary =>
                    summary.labels.get(ownerLabelKey) match
                        case Present(owner) =>
                            TestProcessId.isAlive(owner).map {
                                case true  => Kyo.unit
                                case false => removeWithVolumes(summary)
                            }
                        case Absent =>
                            removeWithVolumes(summary)
                }
            }
        }.unit

    // `removeVolumes = true` is the whole point: the no-arg `remove` defaults it to false, which is how the
    // abandoned containers reached 700 anonymous postgres and mysql data volumes and 64G on this machine.
    private def removeWithVolumes(summary: Container.Summary)(using Frame): Unit < Async =
        Abort.run[ContainerException] {
            summary.attach.map(_.remove(force = true, removeVolumes = true))
        }.unit

    // --- Generic id-keyed memoizing singleton holder ---

    /** The per-JVM table of singleton container fixtures, keyed by descriptor id (`"postgres"`, `"mysql"`, or a test id). A single instance so
      * a container inited by kyo-sql-tests or by a backend descriptor shares the one entry per id. Container fixtures pass this as `ref` to
      * [[getOrInit]].
      */
    // Unsafe: module-load AtomicRef init (no live Frame yet). Uses Unsafe.init().safe to construct the wrapper
    // without a Frame implicit; subsequent accesses use the safe AtomicRef API so the Promises inside are filled safely.
    private[kyo] val containers: AtomicRef[Map[String, Promise[Container, Abort[ContainerException]]]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Map[String, Promise[Container, Abort[ContainerException]]]](Map.empty).safe

    /** Returns the singleton resource for `id`, creating it once via `create` and sharing it across every later caller for that id.
      *
      * The first caller for an id wins the compare-and-set and runs `create` in a detached fiber; concurrent callers that lose the race await
      * the same [[Promise]]. A failed `create` removes the id's slot so the next caller retries rather than seeing a poisoned promise. Generic
      * over the resource so a container fixture passes [[initSingleton]] as `create` with [[containers]] as `ref`, while a mechanism test can
      * pass an observable stub, both exercising this same CAS/promise machinery. Lives here in the core test tree so both kyo-sql-tests and each
      * backend module's test tree can call it.
      */
    private[kyo] def getOrInit[A](
        ref: AtomicRef[Map[String, Promise[A, Abort[ContainerException]]]],
        id: String
    )(create: => A < (Async & Abort[ContainerException]))(using Frame): A < (Async & Abort[ContainerException]) =
        ref.use { current =>
            Maybe.fromOption(current.get(id)) match
                case Present(p) => p.get
                case Absent =>
                    Promise.init[A, Abort[ContainerException]].flatMap { p =>
                        ref.compareAndSet(current, current.updated(id, p)).flatMap {
                            case false =>
                                // Lost the race; await whoever won, or retry if the winner already reset its slot on failure.
                                ref.use { latest =>
                                    Maybe.fromOption(latest.get(id)) match
                                        case Present(winner) => winner.get
                                        case Absent          => getOrInit(ref, id)(create)
                                }
                            case true =>
                                Fiber.initUnscoped(create).flatMap { fiber =>
                                    fiber.getResult.flatMap {
                                        case Result.Success(resource) =>
                                            p.completeDiscard(Result.succeed(resource)).andThen(resource)
                                        case Result.Failure(e: ContainerException) =>
                                            // Remove this id's slot first so the next caller retries instead of seeing a poisoned Promise.
                                            ref.updateAndGet(_ - id).andThen(p.completeDiscard(Result.fail(e))).andThen(p.get)
                                        case Result.Panic(t) =>
                                            ref.updateAndGet(_ - id).andThen(p.completeDiscard(Result.panic(t))).andThen(p.get)
                                    }
                                }
                        }
                    }
        }

end SqlTestContainers
