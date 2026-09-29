package kyo.internal

import kyo.*

/** Mechanism tests for [[TestContainers]], driven by stub resources and a temp-directory registry so none of them needs a live engine or
  * a container runtime. They cover:
  *
  *   - the generic id-keyed singleton holder [[TestContainers.getOrInit]]: concurrent callers for one id share a single init (CAS +
  *     promise) with no double-init, two different ids get two resources while the same id shares one, and a failed init removes that id's
  *     slot so the next caller retries and succeeds rather than reading a poisoned promise;
  *   - the fixture fingerprint that decides whether a leftover container is interchangeable with the one being asked for;
  *   - the co-owner registry that keeps a container this process adopted from being reaped once its original owner dies;
  *   - the label sets: the one every created container carries, and the legacy `kyo-sql-*` set the sweep still reaps.
  *
  * [[TestContainersItTest]] drives the sweep itself against a live daemon.
  */
class TestContainersTest extends BasePodTest:

    private def freshRef(using Frame): AtomicRef[Map[String, Promise[Int, Abort[ContainerException]]]] < Sync =
        AtomicRef.init(Map.empty[String, Promise[Int, Abort[ContainerException]]])

    "concurrent callers for one id share a single init" in {
        for
            ref     <- freshRef
            count   <- AtomicInt.init(0)
            results <- Async.fill(8, concurrency = 8) {
                TestContainers.getOrInit(ref, "k")(count.incrementAndGet)
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
            a1    <- TestContainers.getOrInit(ref, "a")(count.incrementAndGet)
            b1    <- TestContainers.getOrInit(ref, "b")(count.incrementAndGet)
            a2    <- TestContainers.getOrInit(ref, "a")(count.incrementAndGet)
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
            flaky =
                TestContainers.getOrInit(ref, "k") {
                    firstAttempt.compareAndSet(true, false).map {
                        case true  => Abort.fail(new ContainerBackendException("intentional stub init failure"))
                        case false => 42
                    }
                }
            firstResult  <- Abort.run[ContainerException](flaky)
            secondResult <- Abort.run[ContainerException](flaky)
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
            assert(TestContainers.fixtureFingerprint(mysqlCfg) == TestContainers.fixtureFingerprint(mysqlCfg))
        }

        "ignores label and owner differences, which every container carries its own copy of" in {
            val labelled = mysqlCfg.label("kyo-test-owner-pid", "1234").label("kyo-test-container", "mysql")
            assert(TestContainers.fixtureFingerprint(labelled) == TestContainers.fixtureFingerprint(mysqlCfg))
        }

        "ignores the order environment variables were added in" in {
            val a = Container.Config(ContainerImage("mysql:8.0")).env("A", "1").env("B", "2")
            val b = Container.Config(ContainerImage("mysql:8.0")).env("B", "2").env("A", "1")
            assert(TestContainers.fixtureFingerprint(a) == TestContainers.fixtureFingerprint(b))
        }

        "separates a different image" in {
            val other = mysqlCfg.copy(image = ContainerImage("mysql:8.4"))
            assert(TestContainers.fixtureFingerprint(other) != TestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates different server args, the whole reason a tag alone cannot decide reuse" in {
            val tuned = ContainerPredef.MySQL.buildContainerConfig(
                ContainerPredef.MySQL.Config.default.appendServerArgs("--performance-schema=ON")
            )
            assert(TestContainers.fixtureFingerprint(tuned) != TestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates a different environment" in {
            val other = mysqlCfg.env("MYSQL_DATABASE", "other")
            assert(TestContainers.fixtureFingerprint(other) != TestContainers.fixtureFingerprint(mysqlCfg))
        }

        "separates a different published port" in {
            val other = mysqlCfg.port(9999, 0)
            assert(TestContainers.fixtureFingerprint(other) != TestContainers.fixtureFingerprint(mysqlCfg))
        }

        // The TLS fixtures bind a per-run directory of generated certificates. A fingerprint blind
        // to mounts would call last run's container interchangeable with this run's.
        "separates a different bind mount" in {
            val runA = mysqlCfg.bind(Path("/tmp/certs-a"), Path("/etc/ssl-my"), readOnly = true)
            val runB = mysqlCfg.bind(Path("/tmp/certs-b"), Path("/etc/ssl-my"), readOnly = true)
            assert(TestContainers.fixtureFingerprint(runA) != TestContainers.fixtureFingerprint(runB))
            assert(TestContainers.fixtureFingerprint(runA) != TestContainers.fixtureFingerprint(mysqlCfg))
        }
    }

    "matchesFixture" - {
        val fingerprint = TestContainers.fixtureFingerprint(mysqlCfg)
        val labels      = Dict(
            TestContainers.tagLabelKey       -> "mysql",
            TestContainers.fixtureLabelKey   -> fingerprint,
            TestContainers.namespaceLabelKey -> TestProcessId.namespace
        )

        "accepts a container with both the tag and the fingerprint" in {
            assert(TestContainers.matchesFixture(labels, "mysql", fingerprint))
        }

        "rejects a different tag" in {
            assert(!TestContainers.matchesFixture(labels, "postgres", fingerprint))
        }

        "rejects a matching tag with a different fixture" in {
            assert(!TestContainers.matchesFixture(labels, "mysql", "deadbeef"))
        }

        "rejects a matching fixture created in another pid namespace" in {
            val foreign = labels.concat(Dict(TestContainers.namespaceLabelKey -> "another-host/pid:[1]"))
            assert(!TestContainers.matchesFixture(foreign, "mysql", fingerprint))
        }

        "rejects a matching fixture with no namespace label" in {
            assert(!TestContainers.matchesFixture(labels.remove(TestContainers.namespaceLabelKey), "mysql", fingerprint))
        }

        "ownedElsewhere is true only for another namespace's label" in {
            assert(TestContainers.ownedElsewhere(Dict(TestContainers.namespaceLabelKey -> "another-host/pid:[1]")))
            assert(!TestContainers.ownedElsewhere(Dict(TestContainers.namespaceLabelKey -> TestProcessId.namespace)))
            assert(!TestContainers.ownedElsewhere(Dict(TestContainers.tagLabelKey -> "mysql")))
        }

        "rejects a container from before the fixture label existed" in {
            val old = Dict(TestContainers.tagLabelKey -> "mysql")
            assert(!TestContainers.matchesFixture(old, "mysql", fingerprint))
        }

        // A legacy container carries the fixture label under the same key, so the tag key alone keeps it out.
        "rejects a legacy-labelled container with a matching fixture" in {
            val legacy = Dict(TestContainers.legacyTagLabelKey -> "mysql", TestContainers.fixtureLabelKey -> fingerprint)
            assert(!TestContainers.matchesFixture(legacy, "mysql", fingerprint))
        }
    }

    // --- Label sets ---
    //
    // The keys are the contract with every daemon a test process has ever run against: a created container is
    // findable by the current set, and the legacy set names exactly what an older run left behind. A typo in
    // either leaves containers no sweep will ever remove.

    "labels" - {
        "a created container carries the current set and none of the legacy set" in {
            val labels = TestContainers.labelled(mysqlCfg, "mysql").labels
            assert(labels.get("kyo-test-container") == Present("mysql"))
            assert(labels.get("kyo-test-owner-pid") == Present(TestProcessId.pid.toString))
            assert(labels.get("kyo-test-owner-ns") == Present(TestProcessId.namespace))
            assert(labels.get("kyo-test-fixture") == Present(TestContainers.fixtureFingerprint(mysqlCfg)))
            assert(labels.get(TestContainers.legacyTagLabelKey).isEmpty)
            assert(labels.get(TestContainers.legacyOwnerLabelKey).isEmpty)
        }

        "the legacy set is the one kyo-sql's sweep wrote" in {
            assert(TestContainers.legacyTagLabelKey == "kyo-sql-singleton")
            assert(TestContainers.legacyOwnerLabelKey == "kyo-sql-owner-pid")
            assert(TestContainers.legacyRegistryDir == "kyo-sql-container-owners")
        }

        "the registries are distinct directories under the one temp root" in {
            TestTempRoot.get.map {
                case Present(tmp) =>
                    for
                        current <- TestContainers.ownerRoot(TestContainers.registryDir)
                        legacy  <- TestContainers.ownerRoot(TestContainers.legacyRegistryDir)
                    yield
                        assert(current == Present(Path(tmp, "kyo-test-container-owners")))
                        assert(legacy == Present(Path(tmp, "kyo-sql-container-owners")))
                case Absent => fail("no temp root on this platform; the registry cannot be exercised")
            }
        }
    }

    // --- Co-owner registry ---
    //
    // The reaper removes a container whose `kyo-test-owner-pid` names a dead process. Once a
    // second process attaches to that container, the label alone would let the reaper delete it
    // out from under its new owner. These cases pin the predicate that prevents that.

    private def withRegistry[A](f: Path => A < (Async & Abort[Throwable]))(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & Abort[Throwable]) =
        Random.nextLong.map { token =>
            TestTempRoot.get.map {
                case Present(tmp) =>
                    val root = Path(tmp, s"kyo-test-owner-test-${(token & Long.MaxValue).toHexString}")
                    Sync.ensure(Abort.run[FileSystemException](Path.run(root.removeAll)).unit)(f(root))
                case Absent => fail("no temp root on this platform; the registry cannot be exercised")
            }
        }

    private val someId = Container.Id("0123456789abcdef0123456789abcdef")

    "co-owner registry" - {
        "reports no owner for a container nobody claimed" in {
            withRegistry { root =>
                TestContainers.hasLiveCoOwner(root, someId).map(live => assert(!live))
            }
        }

        "reports this process after it claims" in {
            withRegistry { root =>
                for
                    claimed <- TestContainers.claimOwnership(root, someId)
                    live    <- TestContainers.hasLiveCoOwner(root, someId)
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
                    _       <- Path.run(blocker.mkFile)
                    claimed <- TestContainers.claimOwnership(root, someId)
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
                    _       <- Path.run(root.mkFile)
                    claimed <- TestContainers.claimOwnership(root, someId)
                    live    <- TestContainers.hasLiveCoOwner(root, someId)
                yield
                    assert(!claimed, "an unusable registry root must not report a recorded claim")
                    assert(!live, "nothing spares this container, which is why the claim must be believed")
            }
        }

        "claiming is idempotent" in {
            withRegistry { root =>
                for
                    _    <- TestContainers.claimOwnership(root, someId)
                    _    <- TestContainers.claimOwnership(root, someId)
                    live <- TestContainers.hasLiveCoOwner(root, someId)
                yield assert(live)
            }
        }

        "releasing gives the container back to the reaper" in {
            withRegistry { root =>
                for
                    _    <- TestContainers.claimOwnership(root, someId)
                    _    <- TestContainers.releaseOwnership(root, someId)
                    live <- TestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live, "a released claim must not keep the container alive")
            }
        }

        "a claim from a process that has since died does not spare the container" in {
            withRegistry { root =>
                // 999999999 exceeds the pid ceiling of every platform this suite runs on, so the
                // liveness probe answers "no such process" rather than "cannot tell".
                val dead = Path(root, someId.value.take(12), "999999999")
                for
                    _    <- Path.run(dead.mkFile)
                    live <- TestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live, "a dead co-owner must not keep the container alive")
                end for
            }
        }

        "a live claim outweighs a dead one" in {
            withRegistry { root =>
                for
                    _    <- Path.run(Path(root, someId.value.take(12), "999999999").mkFile)
                    _    <- TestContainers.claimOwnership(root, someId)
                    live <- TestContainers.hasLiveCoOwner(root, someId)
                yield assert(live)
            }
        }

        "claims are per container, not global" in {
            withRegistry { root =>
                val other = Container.Id("fedcba9876543210fedcba9876543210")
                for
                    _          <- TestContainers.claimOwnership(root, someId)
                    otherOwned <- TestContainers.hasLiveCoOwner(root, other)
                yield assert(!otherOwned, "a claim on one container must not spare another")
                end for
            }
        }

        "forgetting the owners of a removed container clears its claims" in {
            withRegistry { root =>
                for
                    _    <- TestContainers.claimOwnership(root, someId)
                    _    <- TestContainers.forgetOwners(root, someId)
                    live <- TestContainers.hasLiveCoOwner(root, someId)
                yield assert(!live)
            }
        }
    }

end TestContainersTest
