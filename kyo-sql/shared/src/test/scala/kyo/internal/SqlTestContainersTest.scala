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

/** Mechanism test for the generic id-keyed singleton holder [[SqlTestContainers.getOrInit]], driven by a stub [[SqlTestBackend]] so it needs no
  * live engine. Validates that:
  *
  *   - Concurrent callers for one id share a single init (CAS + promise), no double-init.
  *   - Two different ids get two resources; the same id shares one (id-keying).
  *   - A failed init removes the id's slot so the next caller retries and succeeds (the reset the reaper/init path relies on).
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

end SqlTestContainersTest
