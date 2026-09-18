package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** The backend reached the way a caller reaches it: a `doltlite://` URL through `SqlClient.init`, narrowed to
  * [[Dolt]].
  *
  * Every leaf drives ONE connection over `:memory:`, which needs no filesystem. The single connection is what makes
  * that name a single database, since each connection opening it gets its own.
  */
class DoltLiteClientTest extends Test:

    private def withDolt[A](f: Dolt => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        // The engine is published for some platforms and not others, and CI runs this leg on one it is
        // absent from. Cancelling names that, where letting the load fail would report a missing native as
        // a broken driver. The unavailability contract itself is asserted by its own leaf below.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        Scope.run {
            SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client)(Dolt.use(f))
            }
        }
    end withDolt

    /** A table with one row, committed, which most leaves start from. */
    private def seeded(dolt: Dolt)(using Frame): Dolt.Commit < (Async & Abort[SqlException] & DB) =
        for
            _ <- dolt.executeRaw("CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            _ <- dolt.executeRaw("INSERT INTO person VALUES (1, 'alice')")
            c <- dolt.commit("seed")
        yield c

    "a string holding a second statement is refused" in {
        withDolt { dolt =>
            Abort.run[SqlException](dolt.query("SELECT 1; SELECT 2")).map { result =>
                assert(result.isFailure, s"a two-statement string was accepted: $result")
            }
        }
    }

    "a doltlite URL opens and narrows to the shared client type" in {
        withDolt { dolt =>
            dolt.query("SELECT 1").map { rows =>
                assert(rows.size == 1, s"expected one row, got ${rows.size}")
                assert(dolt.dialect.id == kyo.db.Idiom.Id("doltlite"), s"got ${dolt.dialect.id}")
            }
        }
    }

    "a commit answers the whole commit, and the log reads it back" in {
        withDolt { dolt =>
            for
                made <- seeded(dolt)
                log  <- dolt.log()
            yield
                assert(made.message == "seed", s"got '${made.message}'")
                assert(made.hash.nonEmpty, "a commit must carry its hash")
                assert(made.committer.nonEmpty, s"the committer must be populated, got '${made.committer}'")
                assert(made.author.isEmpty, s"this engine keeps no separate author, got ${made.author}")
                assert(made.order.isEmpty, s"this engine keeps no commit ordering, got ${made.order}")
                assert(!made.isRoot, s"the seed commit follows the initial one, got parents ${made.parents}")
                assert(log.head.hash == made.hash, s"the log head must be the commit just made, got ${log.head.hash}")
            end for
        }
    }

    "status reports uncommitted work and goes quiet once it is committed" in {
        withDolt { dolt =>
            for
                _       <- seeded(dolt)
                _       <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                dirty   <- dolt.status
                _       <- dolt.commit("add bob")
                settled <- dolt.status
            yield
                assert(dirty.exists(_.table == "person"), s"the written table must show as changed, got $dirty")
                assert(settled.isEmpty, s"a committed working set must be clean, got $settled")
            end for
        }
    }

    "add stages one table, and a commit that is not `all` takes only what was staged" in {
        withDolt { dolt =>
            for
                _      <- seeded(dolt)
                _      <- dolt.executeRaw("CREATE TABLE pet (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)")
                _      <- dolt.executeRaw("INSERT INTO pet VALUES (1, 'rex')")
                _      <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                _      <- dolt.add("person")
                staged <- dolt.status
                _      <- dolt.commit("only the staged table", all = false)
                left   <- dolt.status
            yield
                assert(staged.exists(c => c.table == "person" && c.staged), s"the added table must read as staged, got $staged")
                assert(staged.exists(c => c.table == "pet" && !c.staged), s"the table never added must read as unstaged, got $staged")
                assert(left.map(_.table) == Chunk("pet"), s"only the table left unstaged may survive the commit, got $left")
            end for
        }
    }

    "a new branch is answered whole and lists among the branches" in {
        withDolt { dolt =>
            for
                seed   <- seeded(dolt)
                made   <- dolt.createBranch("feature")
                listed <- dolt.branches
            yield
                assert(made.name == "feature")
                assert(made.hash == seed.hash, s"a branch from HEAD points at HEAD, got ${made.hash}")
                assert(listed.map(_.name).toSet == Set("main", "feature"), s"got ${listed.map(_.name)}")
            end for
        }
    }

    "writes inside onBranch land on that branch and not on the one outside it" in {
        withDolt { dolt =>
            for
                _ <- seeded(dolt)
                _ <- dolt.createBranch("feature")
                _ <- dolt.onBranch("feature") {
                    dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                }
                onMain    <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                onFeature <- dolt.onBranch("feature")(dolt.query("SELECT COUNT(*) FROM person").map(_(0).decode[Long](0)))
            yield
                assert(onMain == 1L, s"the branch outside the scope must be untouched, got $onMain rows")
                assert(onFeature == 2L, s"the branch inside the scope must hold the write, got $onFeature rows")
            end for
        }
    }

    "a tag is answered whole and lists among the tags" in {
        withDolt { dolt =>
            for
                seed   <- seeded(dolt)
                made   <- dolt.tag("v1")
                listed <- dolt.tags
            yield
                assert(made.name == "v1")
                assert(made.hash == seed.hash, s"the tag names the commit asked for, got ${made.hash}")
                assert(listed.map(_.name) == Chunk("v1"), s"got $listed")
            end for
        }
    }

    "a diff names the rows that changed" in {
        withDolt { dolt =>
            for
                _       <- seeded(dolt)
                _       <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                _       <- dolt.commit("add bob")
                changed <- dolt.diff(Dolt.Ref.Ancestor(Dolt.Ref.Head, 1), Dolt.Ref.Head, "person")
            yield assert(changed.exists(_.kind == Dolt.Diff.Kind.Added), s"the inserted row must show as added, got $changed")
            end for
        }
    }

    "a hard reset discards the working set, a mixed one keeps it" in {
        withDolt { dolt =>
            for
                _         <- seeded(dolt)
                _         <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                _         <- dolt.reset(Dolt.Ref.Head, Dolt.ResetMode.Mixed)
                afterSoft <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                _         <- dolt.reset(Dolt.Ref.Head, Dolt.ResetMode.Hard)
                afterHard <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
            yield
                assert(afterSoft == 2L, s"a mixed reset keeps the working set, got $afterSoft")
                assert(afterHard == 1L, s"a hard reset discards it, got $afterHard")
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
        }
    }

end DoltLiteClientTest
