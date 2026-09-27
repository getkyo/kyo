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

    "the log of another branch starts at that branch's own commit" in {
        withDolt { dolt =>
            for
                seed    <- seeded(dolt)
                _       <- dolt.createBranch("feature")
                own     <- dolt.onBranch("feature")(dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob")))
                history <- dolt.log(Dolt.Ref.Branch("feature"), Present(2))
                commit  <- dolt.log(Dolt.Ref.Commit(own.hash), Present(1))
            yield
                assert(history.map(_.hash) == Chunk(own.hash, seed.hash), s"got ${history.map(_.message)}")
                assert(commit.map(_.message) == Chunk("add bob"), s"got ${commit.map(_.message)}")
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

    private def count(dolt: Dolt)(using Frame): Long < (Async & Abort[SqlException] & DB) =
        dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))

    "a staged merge where a fast-forward was possible commits once, with both parents and the caller's rows" in {
        withDolt { dolt =>
            for
                seed <- seeded(dolt)
                _    <- dolt.createBranch("feature")
                bob  <- dolt.onBranch("feature") {
                    dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                }
                staged <- dolt.stageMerge(Dolt.Ref.Branch("feature"))
                head   <- dolt.log(limit = Present(1))
                _      <- dolt.executeRaw("INSERT INTO person VALUES (9, 'anchor')")
                merged <- dolt.commit("merge feature with anchor")
                added  <- dolt.diff(Dolt.Ref.Commit(seed.hash), Dolt.Ref.Commit(merged.hash), "person")
                rows   <- count(dolt)
            yield
                assert(staged == Dolt.Merge.Staged(staged.summary), s"a clean staged merge is Staged, got $staged")
                assert(head.head.hash == seed.hash, "the branch must not move until the caller commits")
                assert(merged.parents.toSet == Set(seed.hash, bob.hash), s"the commit joins both histories, got ${merged.parents}")
                assert(added.size == 2 && added.forall(_.kind == Dolt.Diff.Kind.Added), s"bob and the anchor, got $added")
                assert(rows == 3L, s"got $rows")
            end for
        }
    }

    "a staged merge of diverged branches commits once with both parents" in {
        withDolt { dolt =>
            for
                _   <- seeded(dolt)
                _   <- dolt.createBranch("feature")
                bob <- dolt.onBranch("feature") {
                    dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                }
                _      <- dolt.executeRaw("INSERT INTO person VALUES (3, 'carol')")
                carol  <- dolt.commit("add carol")
                staged <- dolt.stageMerge(Dolt.Ref.Branch("feature"))
                _      <- dolt.executeRaw("INSERT INTO person VALUES (9, 'anchor')")
                merged <- dolt.commit("merge feature with anchor")
                rows   <- count(dolt)
            yield
                assert(!staged.isConflicted && staged.conflictCount == 0L, s"got $staged")
                assert(merged.parents.toSet == Set(carol.hash, bob.hash), s"got ${merged.parents}")
                assert(rows == 4L, s"alice, bob, carol and the anchor, got $rows")
            end for
        }
    }

    "a staged merge of a branch already contained answers up to date and stages nothing" in {
        withDolt { dolt =>
            for
                _      <- seeded(dolt)
                _      <- dolt.createBranch("stale")
                staged <- dolt.stageMerge(Dolt.Ref.Branch("stale"))
                status <- dolt.status
            yield
                assert(staged == Dolt.Merge.UpToDate(staged.summary), s"got $staged")
                assert(status.isEmpty, s"nothing to commit, got $status")
            end for
        }
    }

    "a conflicting staged merge answers the conflicts as merge does" in {
        withDolt { dolt =>
            for
                _ <- seeded(dolt)
                _ <- dolt.createBranch("left")
                _ <- dolt.createBranch("right")
                _ <- dolt.onBranch("left") {
                    dolt.executeRaw("UPDATE person SET name = 'alice-left' WHERE id = 1").andThen(dolt.commit("left edit"))
                }
                _ <- dolt.onBranch("right") {
                    dolt.executeRaw("UPDATE person SET name = 'alice-right' WHERE id = 1").andThen(dolt.commit("right edit"))
                }
                staged <- dolt.onBranch("left")(dolt.stageMerge(Dolt.Ref.Branch("right")))
                left   <- dolt.onBranch("left")(dolt.status.map(status => dolt.conflicts.map((status, _))))
                merged <- dolt.onBranch("left")(dolt.merge(Dolt.Ref.Branch("right")))
            yield
                staged match
                    case Dolt.Merge.Conflicted(data, schema, _) =>
                        assert(data == Chunk(Dolt.ConflictSummary("person", 1L)), s"got $data")
                        assert(schema.isEmpty, "the shapes agree, only the rows conflict")
                    case other => fail(s"expected a conflicted merge, got $other")
                end match
                // This engine's side of the declared divergence: the conflicted merge rolled back, so nothing is left behind.
                assert(left == (Chunk.empty, Chunk.empty), s"the branch must be as it was before the merge, got $left")
                assert(merged.isConflicted && merged.conflictCount == staged.conflictCount, s"merge answers the same conflict, got $merged")
            end for
        }
    }

    "a conflicting merge answers the conflicts and leaves the branch as it was" in {
        withDolt { dolt =>
            for
                _        <- seeded(dolt)
                _        <- dolt.createBranch("left")
                _        <- dolt.createBranch("right")
                leftHead <- dolt.onBranch("left") {
                    dolt.executeRaw("UPDATE person SET name = 'alice-left' WHERE id = 1").andThen(dolt.commit("left edit"))
                }
                _ <- dolt.onBranch("right") {
                    dolt.executeRaw("UPDATE person SET name = 'alice-right' WHERE id = 1").andThen(dolt.commit("right edit"))
                }
                outcome <- dolt.onBranch("left")(dolt.merge(Dolt.Ref.Branch("right")))
                head    <- dolt.log(Dolt.Ref.Branch("left"), Present(1))
                name    <- dolt.onBranch("left")(dolt.query("SELECT name FROM person WHERE id = 1").map(rows => rows(0).decode[String](0)))
            yield
                outcome match
                    case Dolt.Merge.Conflicted(data, schema, message) =>
                        assert(data == Chunk(Dolt.ConflictSummary("person", 1L)), s"got $data")
                        assert(schema.isEmpty, "the shapes agree, only the rows conflict")
                        assert(message.nonEmpty, "the engine's own account must be carried")
                    case other => fail(s"expected a conflicted merge, got $other")
                end match
                assert(head.head.hash == leftHead.hash, "a conflicted merge commits nothing")
                assert(name == "alice-left", s"the rollback restores the branch's own row, got $name")
            end for
        }
    }

    "merging a branch already contained answers up to date" in {
        withDolt { dolt =>
            for
                _      <- seeded(dolt)
                _      <- dolt.createBranch("stale")
                merged <- dolt.merge(Dolt.Ref.Branch("stale"))
            yield
                assert(merged == Dolt.Merge.UpToDate(merged.summary), s"got $merged")
                assert(merged.resultingCommit.isEmpty, "nothing was created")
            end for
        }
    }

    "a merge with nothing on the target fast-forwards" in {
        withDolt { dolt =>
            for
                _   <- seeded(dolt)
                _   <- dolt.createBranch("feature")
                bob <- dolt.onBranch("feature") {
                    dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                }
                merged <- dolt.merge(Dolt.Ref.Branch("feature"))
                rows   <- count(dolt)
            yield
                assert(merged.isInstanceOf[Dolt.Merge.FastForward], s"got $merged")
                assert(merged.resultingCommit.map(_.hash) == Present(bob.hash), s"the branch moves to bob's commit, got $merged")
                assert(rows == 2L, s"the merged row must be present, got $rows")
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
