package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** What is this engine's own rather than version control's: how a `doltlite://` URL opens, what the embedded statement API refuses, how a
  * merge's conflicts live only inside the transaction that made them, and how a platform without the engine answers. The version-control
  * surface is [[DoltConformanceTest]]'s.
  */
class DoltLiteClientTest extends Test:

    private def withClient[A](f: SqlClient => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        // The engine is published for some platforms and not others, and CI runs this leg on one it is
        // absent from. Cancelling names that, where letting the load fail would report a missing native as
        // a broken driver. The unavailability contract itself is asserted by its own leaf below.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        Scope.run {
            SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client)(f(client))
            }
        }
    end withClient

    "a string holding a second statement is refused" in {
        withClient { client =>
            Abort.run[SqlException](client.query("SELECT 1; SELECT 2")).map { result =>
                assert(result.isFailure, s"a two-statement string was accepted: $result")
            }
        }
    }

    "a doltlite URL opens and narrows to the shared client type" in {
        withClient { client =>
            Dolt.use { dolt =>
                dolt.query("SELECT 1").map { rows =>
                    assert(rows.size == 1, s"expected one row, got ${rows.size}")
                    assert(dolt.dialect.id == kyo.db.Idiom.Id("doltlite"), s"got ${dolt.dialect.id}")
                }
            }
        }
    }

    private def withDolt[A](f: Dolt => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        withClient(_ => Dolt.use(f))

    private def seeded(dolt: Dolt)(using Frame): Dolt.Commit < (Async & Abort[SqlException] & DB) =
        for
            _ <- dolt.executeRaw("CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)")
            _ <- dolt.executeRaw("INSERT INTO person VALUES (1, 'alice')")
            c <- dolt.commit("seed")
        yield c

    /** `feature` renames person 1 to bob and main renames it to carol, both committed, so merging `feature` into main conflicts. */
    private def diverged(dolt: Dolt)(using Frame): Dolt.Commit < (Async & Abort[SqlException] & DB) =
        for
            _ <- seeded(dolt)
            _ <- dolt.createBranch("feature")
            _ <- dolt.onBranch("feature") {
                dolt.executeRaw("UPDATE person SET name = 'bob' WHERE id = 1").andThen(dolt.commit("bob"))
            }
            _    <- dolt.executeRaw("UPDATE person SET name = 'carol' WHERE id = 1")
            head <- dolt.commit("carol")
        yield head

    private def nameOfOne(dolt: Dolt)(using Frame): String < (Async & Abort[SqlException] & DB) =
        dolt.query("SELECT name FROM person WHERE id = 1").map(rows => rows(0).decode[String](0))

    "a conflicting merge inside a transaction answers Conflicted, and resolving a side lets it commit there" in {
        withDolt { dolt =>
            for
                _                                    <- diverged(dolt)
                (merged, pending, cleared, resolved) <- dolt.transaction {
                    for
                        merged   <- dolt.merge(Dolt.Ref.Branch("feature"))
                        pending  <- dolt.conflicts
                        cleared  <- dolt.resolveConflicts("person", Dolt.Resolution.Theirs)
                        resolved <- dolt.commit("take feature")
                    yield (merged, pending, cleared, resolved)
                }
                name <- nameOfOne(dolt)
                log  <- dolt.log(limit = Present(1))
            yield
                assert(merged.isConflicted && merged.conflictCount == 1L, s"expected one conflicted row, got $merged")
                assert(pending == Chunk(Dolt.ConflictSummary("person", 1L)), s"got $pending")
                assert(cleared == 1L, s"resolving must clear the one conflict, got $cleared")
                assert(resolved.parents.size == 2, s"the resolved merge commits with both parents, got ${resolved.parents}")
                assert(log.head.hash == resolved.hash, s"the resolution outlives the transaction, got ${log.head}")
                assert(name == "bob", s"keeping theirs takes the feature's row, got '$name'")
            end for
        }
    }

    "a conflicting merge in a nested transaction fails typed, and the outer transaction still commits" in {
        withDolt { dolt =>
            for
                _      <- diverged(dolt)
                nested <- dolt.transaction {
                    Abort.run[SqlException](dolt.transaction(dolt.merge(Dolt.Ref.Branch("feature")))).map { nested =>
                        dolt.executeRaw("INSERT INTO person VALUES (3, 'dan')").andThen(nested)
                    }
                }
                count <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                name  <- nameOfOne(dolt)
            yield
                assert(nested.failure.exists(_.isInstanceOf[DoltLiteNestedMergeConflictException]), s"got $nested")
                assert(count == 2L, s"the outer transaction's own write commits, got $count rows")
                assert(name == "carol", s"the discarded merge changed nothing, got '$name'")
            end for
        }
    }

    /** The other side, so no platform is merely skipped: where the engine is absent, opening one fails as the
      * typed unavailability rather than as a panic out of the native loader, and the message names where it looked.
      */
    "a platform without the engine refuses to open one, typed" in {
        assume(!DoltLiteEngineProbe.available, "the DoltLite engine IS published for this platform")
        Abort.run[SqlException](Scope.run(SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)))).map {
            result =>
                assert(
                    result.failure.exists(_.isInstanceOf[DoltLiteEngineUnavailableException]),
                    s"expected a typed unavailability, got $result"
                )
                // The type alone leaves a caller reading a bare property name where the runtime says nothing more,
                // so the sentence that holds on every runtime is asserted rather than assumed.
                val message = result.failure.fold("")(_.getMessage)
                assert(
                    message.contains("not published for this platform"),
                    s"the failure does not say why: $message"
                )
        }
    }

end DoltLiteClientTest
