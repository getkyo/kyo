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
  * labelled `kyo-sql-singleton=<tag>`, `kyo-sql-owner-pid=<pid of the creating test process>`, and `kyo-sql-fixture=<fingerprint of the
  * config>`. [[initSingleton]] removes, together with its anonymous volumes, every labelled container that no live process owns. A
  * container with no owner label predates the label and is removed as well.
  *
  * A LIVE owner means the container belongs to a concurrently running test process (another module fork, platform leg, sbt session, or
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
  *
  * ==Cross-process reuse==
  *
  * The singleton table is per process, and a test binary is not one process per run: the Native runner is one process per module and the
  * JVM is one per forked test group, so a plain per-process singleton provisions the same database once per process. [[initSingleton]]
  * therefore looks for a live labelled container matching the requested fixture BEFORE creating one, and attaches to it instead. Reuse is
  * gated on three things, in order: the fixture fingerprint label must match (a different image, command, environment, port set or mount
  * is a different fixture), the container must be Running with every published port accepting a host-side connection, and the requested
  * config's own health check must pass against it. Any doubt at any step provisions fresh.
  *
  * An adopted container's `kyo-sql-owner-pid` label still names the process that created it, which may already be dead, so the label alone
  * would let the next reaper delete a container this process is using. A CO-OWNER REGISTRY closes that: adopting writes an empty file named
  * for this process's pid under `<tmpdir>/kyo-sql-container-owners/<short id>/`, and the reaper spares any container with a live co-owner
  * even when its label owner is gone. The claim is written before the health probe and removed again when the probe declines, so a
  * container this process rejected does not stay un-reapable for the rest of the run.
  *
  * A claim that cannot be written declines the candidate: adoption without a claim is not a small risk to accept, because the reap that
  * follows adoption in [[initSingleton]] would then remove the very container just handed to the caller. Every OTHER failure in the
  * registry path resolves toward sparing, the same direction as the owner-pid predicate.
  */
private[kyo] object SqlTestContainers:

    /** Label key carried by every kyo-sql singleton container; its value is the fixture tag. */
    val singletonLabelKey: String = "kyo-sql-singleton"

    /** Label key carrying the pid of the test process that created the container. */
    val ownerLabelKey: String = "kyo-sql-owner-pid"

    /** Label key carrying [[fixtureFingerprint]] of the config the container was created from. Two fixtures may share a tag and still be
      * incompatible (different server args, different auth plugin), so the tag alone cannot decide reuse.
      */
    val fixtureLabelKey: String = "kyo-sql-fixture"

    /** How long an adoption candidate gets to accept a host-side connection. Short on purpose: a healthy container answers immediately, and
      * anything slower is cheaper to replace than to wait for.
      */
    private val adoptProbeTimeout: Duration = 5.seconds

    /** Attach to a live, healthy container for `tag`+`cfg` if there is one, otherwise reap dead-owner containers and create a fresh
      * singleton labelled with `tag`, this process's pid, and the fixture fingerprint.
      *
      * The reap runs AFTER the adoption attempt so an adopted container's co-owner claim is already registered when the sweep reads it.
      */
    def initSingleton(cfg: Container.Config, tag: String)(using
        Frame
    ): Container < (Async & Abort[ContainerException]) =
        adoptLive(cfg, tag).map {
            case Present(adopted) => reapOrphans.andThen(adopted)
            case Absent =>
                reapOrphans.andThen {
                    // A failed claim is harmless here, unlike on the adoption path: this container's own
                    // owner label already carries this process's live pid, so every reaper spares it.
                    Container.initUnscoped(labelled(cfg, tag)).map(c => claimOwnership(c.id).andThen(c))
                }
        }

    // --- Adoption ---

    /** A stable digest of the parts of a container config that decide whether two fixtures are interchangeable: image, command,
      * environment, published ports and mounts. Sorted so `Dict` iteration order cannot change the value, and rendered from
      * `String.hashCode`, whose result is fixed by the language spec on every platform this test tree runs on.
      *
      * Mounts are in it because the TLS fixtures bind a per-run directory of generated certificates: without them a container holding the
      * previous run's certificates would look interchangeable with one holding this run's.
      *
      * A digest collision would mean adopting a container built from a different config; the health check the caller supplies still runs
      * against it, so the failure mode is a rejected candidate rather than a silently wrong fixture.
      */
    private[kyo] def fixtureFingerprint(cfg: Container.Config): String =
        val mounts = cfg.mounts.toSeq.map {
            case Container.Config.Mount.Bind(source, target, readOnly) => s"mount:bind:$source:$target:$readOnly"
            case Container.Config.Mount.Volume(name, target, readOnly) => s"mount:volume:$name:$target:$readOnly"
            case Container.Config.Mount.Tmpfs(target, sizeBytes)       => s"mount:tmpfs:$target:${sizeBytes.getOrElse(0L)}"
        }.sorted
        val parts =
            Seq(cfg.image.reference) ++
                cfg.command.map(_.args.mkString(" ")).toList ++
                cfg.env.toMap.toSeq.map((k, v) => s"env:$k=$v").sorted ++
                cfg.ports.toSeq.map(p => s"port:${p.containerPort}/${p.protocol.cliName}").sorted ++
                mounts
        Integer.toHexString(parts.mkString(" ").hashCode)
    end fixtureFingerprint

    /** Whether a listed container's labels describe the fixture `tag`+`fingerprint` asks for. */
    private[kyo] def matchesFixture(labels: Dict[String, String], tag: String, fingerprint: String): Boolean =
        labels.get(singletonLabelKey).contains(tag) && labels.get(fixtureLabelKey).contains(fingerprint)

    private def adoptLive(cfg: Container.Config, tag: String)(using Frame): Maybe[Container] < Async =
        val fingerprint = fixtureFingerprint(cfg)
        Abort.run[ContainerException] {
            // Running containers only: a stopped one carries no port and no engine to probe.
            Container.list(all = false, filters = Dict("label" -> Chunk(s"$singletonLabelKey=$tag"))).map { found =>
                firstAdoptable(found.filter(s => matchesFixture(s.labels, tag, fingerprint)), cfg)
            }
        }.map {
            case Result.Success(adopted) => adopted
            case _                       => Absent
        }
    end adoptLive

    private def firstAdoptable(candidates: Chunk[Container.Summary], cfg: Container.Config)(using
        Frame
    ): Maybe[Container] < Async =
        if candidates.isEmpty then Absent
        else
            adoptCandidate(candidates.head, cfg).map {
                case Present(adopted) => Present(adopted)
                case Absent           => firstAdoptable(candidates.tail, cfg)
            }

    /** Claim, verify, and either keep or release one candidate.
      *
      * The claim precedes the verification because the verification takes time, and a concurrent reaper reading an unclaimed container
      * during that window would remove it out from under this process. Releasing on a declined candidate is what keeps that ordering from
      * pinning a container nobody wants.
      *
      * A claim that could not be written declines the candidate outright. Continuing unclaimed is not a small risk to accept: the reap in
      * [[initSingleton]] runs immediately after adoption, and an adoption candidate is usually one whose label owner is already dead, so an
      * unclaimed adoption would have this very process force-remove the container it just returned to the caller.
      */
    private def adoptCandidate(summary: Container.Summary, cfg: Container.Config)(using Frame): Maybe[Container] < Async =
        Abort.run[ContainerException] {
            summary.attach.map { attached =>
                claimOwnership(attached.id).map {
                    case false => Absent
                    case true =>
                        attached.state.map {
                            case Container.State.Running =>
                                Container.awaitPortsReachableWithin(attached, adoptProbeTimeout)
                                    .andThen(cfg.healthCheck.check(attached))
                                    .andThen(Present(attached))
                            case _ => Absent
                        }
                }
            }
        }.map {
            case Result.Success(Present(adopted)) => Present(adopted)
            case Result.Success(Absent)           => releaseOwnership(summary.id).andThen(Absent)
            case _                                => releaseOwnership(summary.id).andThen(Absent)
        }

    // --- Co-owner registry ---

    /** Root of the co-owner registry, `Absent` when the platform reports no temp directory (which disables adoption's reap protection and
      * leaves the owner-pid label as the only predicate, exactly as before this registry existed).
      */
    private[kyo] def ownerRoot(using Frame): Maybe[Path] < Sync =
        TestTempRoot.get.map(_.map(tmp => Path(tmp, "kyo-sql-container-owners")))

    private def ownerDir(root: Path, id: Container.Id): Path = Path(root, id.value.take(12))

    /** Record this process as a co-owner of `id`, reporting whether the claim is now readable by a reaper. A caller about to rely on the
      * claim must treat `false` as "do not adopt": an unclaimed container is one every reaper is free to remove.
      */
    private[kyo] def claimOwnership(root: Path, id: Container.Id)(using Frame): Boolean < Async =
        // `mkFile` creates missing parents, so the per-container directory needs no separate step.
        Abort.run[FileStructureException](Path(ownerDir(root, id), TestProcessId.pid.toString).mkFile).map(_.isSuccess)

    /** Drop this process's co-ownership of `id`.
      *
      * A removal that fails leaves this process's pid in the registry, which spares the container for the rest of the run and lets a later
      * run reap it once the pid is dead. That is the same direction every other uncertainty here takes, so the failure is discarded rather
      * than raised: declining to reap costs one cycle, and the alternative would fail a leaf over registry hygiene.
      */
    private[kyo] def releaseOwnership(root: Path, id: Container.Id)(using Frame): Unit < Async =
        Abort.run[FileStructureException](Path(ownerDir(root, id), TestProcessId.pid.toString).remove).unit

    /** Whether any process that adopted `id` is still running. An unreadable registry reports true, which spares the container: the same
      * direction every other uncertainty in this object takes.
      */
    private[kyo] def hasLiveCoOwner(root: Path, id: Container.Id)(using Frame): Boolean < Async =
        val dir = ownerDir(root, id)
        Abort.run[FileReadException](dir.exists).map {
            case Result.Success(false) => false
            case Result.Success(true) =>
                Abort.run[FileStructureException] {
                    dir.list.map { entries =>
                        Kyo.foreach(entries)(entry => TestProcessId.isAlive(entry.name.getOrElse("")))
                            .map(_.exists(identity))
                    }
                }.map {
                    case Result.Success(live) => live
                    case _                    => true
                }
            case _ => true
        }
    end hasLiveCoOwner

    /** Forget every co-owner of `id`, called once the container itself is gone.
      *
      * A removal that fails leaves a directory keyed by a container id that no longer exists, and `hasLiveCoOwner` is only ever asked about
      * ids the daemon still lists, so nothing reads it again. The failure is therefore discarded: it litters a few empty files in the temp
      * directory and cannot change a reap decision.
      */
    private[kyo] def forgetOwners(root: Path, id: Container.Id)(using Frame): Unit < Async =
        Abort.run[FileStructureException](ownerDir(root, id).removeAll).unit

    /** Claim `id` for this process, reporting whether a reaper can now see the claim.
      *
      * A platform with no temp directory has no registry, so no claim can be recorded and `false` is the truthful answer: it disables
      * adoption there and leaves the owner-pid label as the only predicate, which is how this object behaved before the registry existed.
      */
    private def claimOwnership(id: Container.Id)(using Frame): Boolean < Async =
        ownerRoot.map {
            case Present(root) => claimOwnership(root, id)
            case Absent        => false
        }

    private def releaseOwnership(id: Container.Id)(using Frame): Unit < Async =
        ownerRoot.map {
            case Present(root) => releaseOwnership(root, id)
            case Absent        => Kyo.unit
        }

    private def hasLiveCoOwner(id: Container.Id)(using Frame): Boolean < Async =
        ownerRoot.map {
            case Present(root) => hasLiveCoOwner(root, id)
            case Absent        => false
        }

    private def forgetOwners(id: Container.Id)(using Frame): Unit < Async =
        ownerRoot.map {
            case Present(root) => forgetOwners(root, id)
            case Absent        => Kyo.unit
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

    /** [[initScoped]] for a [[ContainerPredef.MySQL]] fixture.
      *
      * `ContainerPredef.MySQL.initWith` reaches `Container.init` directly, so its containers carry none of these labels and no reaper can
      * ever find them. That is exactly the shape the per-leaf auth and TLS suites used, and on a runtime whose teardown fails they became
      * permanent, invisible corpses: 21 leaked mysqld from one module in one CI job. Routing them through here costs nothing and makes them
      * findable.
      */
    def initScopedMysql(cfg: ContainerPredef.MySQL.Config, tag: String)(using
        Frame
    ): ContainerPredef.MySQL < (Async & Abort[ContainerException] & Scope) =
        initScoped(ContainerPredef.MySQL.buildContainerConfig(cfg), tag).map(c => new ContainerPredef.MySQL(c, cfg))

    /** [[initScoped]] for a [[ContainerPredef.Postgres]] fixture. @see [[initScopedMysql]] */
    def initScopedPostgres(cfg: ContainerPredef.Postgres.Config, tag: String)(using
        Frame
    ): ContainerPredef.Postgres < (Async & Abort[ContainerException] & Scope) =
        initScoped(ContainerPredef.Postgres.buildContainerConfig(cfg), tag).map(c => new ContainerPredef.Postgres(c, cfg))

    private def labelled(cfg: Container.Config, tag: String): Container.Config =
        cfg.label(singletonLabelKey, tag)
            .label(ownerLabelKey, TestProcessId.pid.toString)
            .label(fixtureLabelKey, fixtureFingerprint(cfg))

    private def reapOrphans(using Frame): Unit < Async =
        Abort.run[ContainerException] {
            Container.list(all = true, filters = Dict("label" -> Chunk(singletonLabelKey))).map { found =>
                Kyo.foreachDiscard(found) { summary =>
                    summary.labels.get(ownerLabelKey) match
                        case Present(owner) =>
                            TestProcessId.isAlive(owner).map {
                                case true  => Kyo.unit
                                case false => reapIfUnowned(summary)
                            }
                        case Absent =>
                            reapIfUnowned(summary)
                }
            }
        }.unit

    /** The label owner is gone; the container still belongs to whichever process adopted it. */
    private def reapIfUnowned(summary: Container.Summary)(using Frame): Unit < Async =
        hasLiveCoOwner(summary.id).map {
            case true  => Kyo.unit
            case false => removeWithVolumes(summary)
        }

    // `removeVolumes = true` is the whole point: the no-arg `remove` defaults it to false, which is how the
    // abandoned containers reached 700 anonymous postgres and mysql data volumes and 64G on this machine.
    private def removeWithVolumes(summary: Container.Summary)(using Frame): Unit < Async =
        Abort.run[ContainerException] {
            summary.attach.map(_.remove(force = true, removeVolumes = true))
        }.andThen(forgetOwners(summary.id))

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
