package kyo.internal

import kyo.*

/** A test-only [[SqlTestBackend]] that provisions no container: its [[withFreshSchema]] runs `create` through the shared id-keyed holder
  * [[SqlTestContainers.getOrInit]] and hands back a [[SqlTestBackend.Schema]] whose `port` carries the singleton resource the holder created,
  * so a mechanism test can observe single-init, id-keying, and slot-reset without a live engine.
  */
final private class StubTestBackend(
    val id: String,
    ref: AtomicRef[Map[String, Promise[Int, Abort[ContainerException]]]],
    create: () => Int < (Async & Abort[ContainerException])
) extends SqlTestBackend:
    def label: String                     = id
    def urlScheme: String                 = "stub"
    def containerConfig: Container.Config = Container.Config(ContainerImage("stub:latest"))
    def quoteIdent(name: String): String  = "\"" + name + "\""
    def supportsReturning: Boolean        = false
    def supportsRecursiveCte: Boolean     = false
    def textColumnType: String            = "TEXT"
    def autoIncrementPrimaryKey: String   = "id INT PRIMARY KEY"

    // This stub never provisions an engine, so these DDL and diagnostic strings are never rendered against one.
    def columnType(key: SqlTestBackend.ColumnType): String = "TEXT"
    def tableNotFoundSqlState: String                      = "42000"
    def uniqueViolationSqlState: String                    = "23000"
    def sessionIdSql: String                               = "0"

    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        SqlTestContainers.getOrInit(ref, id)(create()).map { resource =>
            f(SqlTestBackend.Schema("stub-host", resource, id, "", id, s"stub://$id/$resource"))
        }
end StubTestBackend

/** Mechanism tests for [[SqlTestContainers]], driven by a stub [[SqlTestBackend]] and a temp-directory registry so none of them needs a
  * live engine or a container runtime. They cover:
  *
  *   - the generic id-keyed singleton holder [[SqlTestContainers.getOrInit]]: concurrent callers for one id share a single init (CAS +
  *     promise) with no double-init, two different ids get two resources while the same id shares one, and a failed init removes that id's
  *     slot so the next caller retries and succeeds rather than reading a poisoned promise;
  *   - the fixture fingerprint that decides whether a leftover container is interchangeable with the one being asked for;
  *   - the co-owner registry that keeps a container this process adopted from being reaped once its original owner dies.
  */
class SqlTestContainersTest extends kyo.Test:

    private def freshRef(using Frame): AtomicRef[Map[String, Promise[Int, Abort[ContainerException]]]] < Sync =
        AtomicRef.init(Map.empty[String, Promise[Int, Abort[ContainerException]]])

    "concurrent callers for one id share a single init" in {
        for
            ref   <- freshRef
            count <- AtomicInt.init(0)
            stub = new StubTestBackend("k", ref, () => count.incrementAndGet)
            results <- Async.fill(8, concurrency = 8) {
                Scope.run(stub.withFreshSchema(schema => schema.port))
            }
            inits <- count.get
        yield
            assert(inits == 1, s"expected a single init, got $inits")
            assert(results.size == 8, s"expected 8 results, got ${results.size}")
            assert(results.forall(_ == 1), s"expected every caller to share resource 1, got $results")
    }

    "different ids get different resources; the same id shares one" in {
        for
            ref   <- freshRef
            count <- AtomicInt.init(0)
            stubA = new StubTestBackend("a", ref, () => count.incrementAndGet)
            stubB = new StubTestBackend("b", ref, () => count.incrementAndGet)
            a1    <- Scope.run(stubA.withFreshSchema(schema => schema.port))
            b1    <- Scope.run(stubB.withFreshSchema(schema => schema.port))
            a2    <- Scope.run(stubA.withFreshSchema(schema => schema.port))
            inits <- count.get
        yield
            assert(a1 == 1, s"first id should init first, got $a1")
            assert(b1 == 2, s"a different id should init separately, got $b1")
            assert(a2 == a1, s"the same id should share one resource, got $a2 and $a1")
            assert(inits == 2, s"expected exactly two inits, one per id, got $inits")
    }

    "a failed init resets the slot so the next caller retries" in {
        for
            ref          <- freshRef
            firstAttempt <- AtomicBoolean.init(true)
            flaky = new StubTestBackend(
                "k",
                ref,
                () =>
                    firstAttempt.compareAndSet(true, false).map {
                        case true  => Abort.fail(new ContainerBackendException("intentional stub init failure"))
                        case false => 42
                    }
            )
            firstResult  <- Abort.run[SqlException | ContainerException](Scope.run(flaky.withFreshSchema(schema => schema.port)))
            secondResult <- Abort.run[SqlException | ContainerException](Scope.run(flaky.withFreshSchema(schema => schema.port)))
        yield
            assert(firstResult.isFailure, s"the first init should fail, got $firstResult")
            secondResult match
                case Result.Success(v) => assert(v == 42, s"a retry after a failed init should succeed with 42, got $v")
                case other             => fail(s"a retry after a failed init should succeed, got $other")
    }

    // --- Fixture fingerprint: what makes a leftover container reusable ---

    private val mysqlCfg = ContainerPredef.MySQL.buildContainerConfig(ContainerPredef.MySQL.Config.default)

    "fixtureFingerprint" - {
        "is stable across calls for the same config" in {
            assert(SqlTestContainers.fixtureFingerprint(mysqlCfg) == SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        "ignores label and owner differences, which every container carries its own copy of" in {
            val labelled = mysqlCfg.label("kyo-sql-owner-pid", "1234").label("kyo-sql-singleton", "mysql")
            assert(SqlTestContainers.fixtureFingerprint(labelled) == SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        "ignores the order environment variables were added in" in {
            val a = Container.Config(ContainerImage("mysql:8.0")).env("A", "1").env("B", "2")
            val b = Container.Config(ContainerImage("mysql:8.0")).env("B", "2").env("A", "1")
            assert(SqlTestContainers.fixtureFingerprint(a) == SqlTestContainers.fixtureFingerprint(b))
        }

        "separates a different image" in {
            val other = mysqlCfg.copy(image = ContainerImage("mysql:8.4"))
            assert(SqlTestContainers.fixtureFingerprint(other) != SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates different server args — the whole reason a tag alone cannot decide reuse" in {
            val tuned = ContainerPredef.MySQL.buildContainerConfig(
                ContainerPredef.MySQL.Config.default.appendServerArgs("--performance-schema=ON")
            )
            assert(SqlTestContainers.fixtureFingerprint(tuned) != SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates a different environment" in {
            val other = mysqlCfg.env("MYSQL_DATABASE", "other")
            assert(SqlTestContainers.fixtureFingerprint(other) != SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates a different published port" in {
            val other = mysqlCfg.port(9999, 0)
            assert(SqlTestContainers.fixtureFingerprint(other) != SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }

        // The TLS fixtures bind a per-run directory of generated certificates. A fingerprint blind
        // to mounts would call last run's container interchangeable with this run's.
        "separates a different bind mount" in {
            val runA = mysqlCfg.bind(Path("/tmp/certs-a"), Path("/etc/ssl-my"), readOnly = true)
            val runB = mysqlCfg.bind(Path("/tmp/certs-b"), Path("/etc/ssl-my"), readOnly = true)
            assert(SqlTestContainers.fixtureFingerprint(runA) != SqlTestContainers.fixtureFingerprint(runB))
            assert(SqlTestContainers.fixtureFingerprint(runA) != SqlTestContainers.fixtureFingerprint(mysqlCfg))
        }
    }

    "matchesFixture" - {
        val fingerprint = SqlTestContainers.fixtureFingerprint(mysqlCfg)
        val labels = Dict(
            SqlTestContainers.singletonLabelKey -> "mysql",
            SqlTestContainers.fixtureLabelKey   -> fingerprint
        )

        "accepts a container with both the tag and the fingerprint" in {
            assert(SqlTestContainers.matchesFixture(labels, "mysql", fingerprint))
        }

        "rejects a different tag" in {
            assert(!SqlTestContainers.matchesFixture(labels, "postgres", fingerprint))
        }

        "rejects a matching tag with a different fixture" in {
            assert(!SqlTestContainers.matchesFixture(labels, "mysql", "deadbeef"))
        }

        "rejects a container from before the fixture label existed" in {
            val old = Dict(SqlTestContainers.singletonLabelKey -> "mysql")
            assert(!SqlTestContainers.matchesFixture(old, "mysql", fingerprint))
        }
    }

    // --- Co-owner registry ---
    //
    // The reaper removes a container whose `kyo-sql-owner-pid` names a dead process. Once a
    // second process attaches to that container, the label alone would let the reaper delete it
    // out from under its new owner. These cases pin the predicate that prevents that.

    private def withRegistry[A](f: Path => A < (Async & Abort[Throwable]))(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & Abort[Throwable]) =
        Random.nextLong.map { token =>
            TestTempRoot.get.map {
                case Present(tmp) =>
                    val root = Path(tmp, s"kyo-sql-owner-test-${(token & Long.MaxValue).toHexString}")
                    Sync.ensure(Abort.run[FileStructureException](root.removeAll).unit)(f(root))
                case Absent => fail("no temp root on this platform; the registry cannot be exercised")
            }
        }

    private val someId = Container.Id("0123456789abcdef0123456789abcdef")

    "co-owner registry" - {
        "reports no owner for a container nobody claimed" in {
            withRegistry { root =>
                SqlTestContainers.hasLiveCoOwner(root, someId).map(live => assert(!live))
            }
        }

        "reports this process after it claims" in {
            withRegistry { root =>
                for
                    claimed <- SqlTestContainers.claimOwnership(root, someId)
                    live    <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield
                    assert(claimed, "a writable registry must report the claim as recorded")
                    assert(live, "the claiming process must count as a live owner")
            }
        }

        // The claim's return value is the whole safety interlock for adoption: the reap in
        // initSingleton runs right after an adoption, and an adoption candidate is normally one
        // whose label owner is already dead, so an unclaimed adoption has this process force-remove
        // the container it just returned. A claim that cannot be written MUST say so.
        "a claim that cannot be written reports false" in {
            withRegistry { root =>
                // A regular file where the per-container directory belongs: nothing can be created
                // under it, so the claim cannot land.
                val blocker = Path(root, someId.value.take(12))
                for
                    _       <- blocker.mkFile
                    claimed <- SqlTestContainers.claimOwnership(root, someId)
                yield assert(!claimed, "an unwritable registry must not report a recorded claim")
                end for
            }
        }

        "a claim under an unusable registry root reports false and leaves nothing to spare it" in {
            withRegistry { root =>
                // The root itself is a regular file, so neither the claim nor a later reader can see
                // anything: claim false AND hasLiveCoOwner false is exactly the combination adoption
                // must refuse to walk into.
                for
                    _       <- root.mkFile
                    claimed <- SqlTestContainers.claimOwnership(root, someId)
                    live    <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield
                    assert(!claimed, "an unusable registry root must not report a recorded claim")
                    assert(!live, "nothing spares this container, which is why the claim must be believed")
            }
        }

        "claiming is idempotent" in {
            withRegistry { root =>
                for
                    _    <- SqlTestContainers.claimOwnership(root, someId)
                    _    <- SqlTestContainers.claimOwnership(root, someId)
                    live <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield assert(live)
            }
        }

        "releasing gives the container back to the reaper" in {
            withRegistry { root =>
                for
                    _    <- SqlTestContainers.claimOwnership(root, someId)
                    _    <- SqlTestContainers.releaseOwnership(root, someId)
                    live <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live, "a released claim must not keep the container alive")
            }
        }

        "a claim from a process that has since died does not spare the container" in {
            withRegistry { root =>
                // 999999999 exceeds the pid ceiling of every platform this suite runs on, so the
                // liveness probe answers "no such process" rather than "cannot tell".
                val dead = Path(root, someId.value.take(12), "999999999")
                for
                    _    <- dead.mkFile
                    live <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live, "a dead co-owner must not keep the container alive")
                end for
            }
        }

        "a live claim outweighs a dead one" in {
            withRegistry { root =>
                for
                    _    <- Path(root, someId.value.take(12), "999999999").mkFile
                    _    <- SqlTestContainers.claimOwnership(root, someId)
                    live <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield assert(live)
            }
        }

        "claims are per container, not global" in {
            withRegistry { root =>
                val other = Container.Id("fedcba9876543210fedcba9876543210")
                for
                    _          <- SqlTestContainers.claimOwnership(root, someId)
                    otherOwned <- SqlTestContainers.hasLiveCoOwner(root, other)
                yield assert(!otherOwned, "a claim on one container must not spare another")
                end for
            }
        }

        "forgetting the owners of a removed container clears its claims" in {
            withRegistry { root =>
                for
                    _    <- SqlTestContainers.claimOwnership(root, someId)
                    _    <- SqlTestContainers.forgetOwners(root, someId)
                    live <- SqlTestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live)
            }
        }
    }

end SqlTestContainersTest
