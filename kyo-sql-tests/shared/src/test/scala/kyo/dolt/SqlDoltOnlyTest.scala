package kyo.dolt

import kyo.*
import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackends

/** The version-control surface, run against a real Dolt server.
  *
  * None of it is reachable through a neutral type, so it lives here rather than in the shared battery, which covers everything portable
  * about Dolt through the descriptor registering it as a fourth engine.
  */
class SqlDoltOnlyTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String) derives SqlSchema, CanEqual

    private def descriptor(using Frame): SqlTestBackend < Any =
        SqlTestBackends.available.find(_.id == "dolt") match
            case Some(b) => b
            case None    => throw new AssertionError("the Dolt descriptor is not registered, so this suite would assert nothing")

    /** Opens a client on a fresh database and hands it to `f`, narrowed to this engine's own type. */
    private def withDolt[A](config: SqlConfig = SqlConfig())(
        f: DoltClient => A < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): A < (Async & Abort[SqlException | ContainerException] & Scope) =
        descriptor.map { backend =>
            backend.withFreshSchema { schema =>
                SqlClient.initUnscoped(schema.url, config).flatMap { client =>
                    Scope.ensure(client.close).andThen {
                        DB.run(client)(DoltClient.use(f))
                    }
                }
            }
        }

    /** A table with one row, committed, which most leaves start from. */
    private def seeded(dolt: DoltClient)(using Frame): DoltCommit < (Async & Abort[SqlException] & DB) =
        for
            _ <- dolt.executeRaw("CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)")
            _ <- dolt.executeRaw("INSERT INTO person VALUES (1, 'alice')")
            c <- dolt.commit("seed")
        yield c

    // --- History ---

    "a commit answers the whole commit, and the log reads it back" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    made <- seeded(dolt)
                    log  <- dolt.log()
                yield
                    assert(made.message == "seed", s"the commit must carry its message, got '${made.message}'")
                    assert(made.hash.nonEmpty, "a commit must carry its hash")
                    assert(made.author.nonEmpty, s"the author must be populated, got '${made.author}'")
                    assert(!made.isMerge, "an ordinary commit has one parent")
                    assert(!made.isRoot, "this commit follows the repository's initial one")
                    assert(log.head.hash == made.hash, s"the log head must be the commit just made, got ${log.head.hash}")
                    assert(log.head.message == "seed")
                    assert(log.sizeIs >= 2, s"the seed commit follows the initial one, so the log holds at least two, got ${log.size}")
                end for
            }
        }
    }

    "the log honours its limit and its ref" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _       <- seeded(dolt)
                    _       <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                    second  <- dolt.commit("add bob")
                    limited <- dolt.log(limit = Present(1))
                    parent  <- dolt.log(DoltRef.Ancestor(DoltRef.Head, 1), limit = Present(1))
                yield
                    assert(limited.size == 1, s"a limit of one must answer one commit, got ${limited.size}")
                    assert(limited.head.hash == second.hash)
                    assert(parent.head.message == "seed", s"HEAD~1 must be the previous commit, got '${parent.head.message}'")
                end for
            }
        }
    }

    "status reports uncommitted work and goes quiet once it is committed" in {
        Scope.run {
            withDolt() { dolt =>
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
    }

    "a diff names the rows that changed and the commits it spans" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _       <- seeded(dolt)
                    _       <- dolt.executeRaw("UPDATE person SET name = 'alice2' WHERE id = 1")
                    _       <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                    _       <- dolt.commit("edit and add")
                    changed <- dolt.diff(DoltRef.Ancestor(DoltRef.Head, 1), DoltRef.Head, "person")
                yield
                    assert(changed.size == 2, s"one edit and one insert is two changed rows, got ${changed.size}")
                    val kinds = changed.map(_.kind).toSet
                    assert(kinds == Set(DoltDiff.Kind.Modified, DoltDiff.Kind.Added), s"got $kinds")
                    assert(changed.forall(_.toCommit.nonEmpty), "each row must name the commit it diffed to")
                    assert(changed.forall(_.toCommitDate.isDefined), "each row must carry the resolved commit date")
                end for
            }
        }
    }

    // --- Branches ---

    "a new branch is answered whole, and lists among the branches" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    seed   <- seeded(dolt)
                    made   <- dolt.createBranch("feature")
                    listed <- dolt.branches
                    byName = listed.map(_.name).toSet
                yield
                    assert(made.name == "feature")
                    assert(made.hash == seed.hash, s"a branch from HEAD points at HEAD, got ${made.hash} against ${seed.hash}")
                    assert(!made.isTracking, "a local branch tracks no remote")
                    assert(byName.contains("feature") && byName.contains("main"), s"got $byName")
                    assert(listed.find(_.name == "feature").exists(_.latestMessage == Present("seed")), s"got $listed")
                end for
            }
        }
    }

    "writes inside onBranch land on that branch and not on the one outside it" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _ <- seeded(dolt)
                    _ <- dolt.createBranch("feature")
                    _ <- dolt.onBranch("feature") {
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                    }
                    onMain    <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                    onFeature <- dolt.onBranch("feature")(dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0)))
                yield
                    assert(onMain == 1L, s"the branch outside the scope must be untouched, got $onMain rows")
                    assert(onFeature == 2L, s"the branch inside the scope must hold the write, got $onFeature rows")
                end for
            }
        }
    }

    /** The active branch is SESSION state, so a pool handing a checked-out connection to the next borrower would run its statements against
      * another branch. `maxConnections = 1` forces both bodies onto the same connection, so an unreconciled revision would read the other's
      * branch here.
      */
    "two branch scopes over one pooled connection each see their own branch" in {
        Scope.run {
            withDolt(SqlConfig(maxConnections = 1)) { dolt =>
                for
                    _ <- seeded(dolt)
                    _ <- dolt.createBranch("left")
                    _ <- dolt.createBranch("right")
                    _ <- dolt.onBranch("left") {
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("left"))
                    }
                    _ <- dolt.onBranch("right") {
                        dolt.executeRaw("INSERT INTO person VALUES (3, 'carol')").andThen(dolt.commit("right"))
                    }
                    // Read back in the opposite order, so a stale branch would be visible as the wrong count.
                    right      <- dolt.onBranch("right")(dolt.query("SELECT name FROM person ORDER BY id"))
                    left       <- dolt.onBranch("left")(dolt.query("SELECT name FROM person ORDER BY id"))
                    main       <- dolt.query("SELECT name FROM person ORDER BY id")
                    rightNames <- Kyo.foreach(right)(_.decode[String](0))
                    leftNames  <- Kyo.foreach(left)(_.decode[String](0))
                    mainNames  <- Kyo.foreach(main)(_.decode[String](0))
                yield
                    assert(leftNames == Chunk("alice", "bob"), s"left saw $leftNames")
                    assert(rightNames == Chunk("alice", "carol"), s"right saw $rightNames")
                    assert(mainNames == Chunk("alice"), s"main saw $mainNames")
                end for
            }
        }
    }

    "a branch scope restores the branch outside it, even after nesting" in {
        Scope.run {
            withDolt(SqlConfig(maxConnections = 1)) { dolt =>
                for
                    _ <- seeded(dolt)
                    _ <- dolt.createBranch("outer")
                    _ <- dolt.createBranch("inner")
                    nested <- dolt.onBranch("outer") {
                        dolt.onBranch("inner")(dolt.query("SELECT active_branch()").map(rows => rows(0).decode[String](0))).map { deep =>
                            dolt.query("SELECT active_branch()").map(rows => rows(0).decode[String](0)).map(back => (deep, back))
                        }
                    }
                    after <- dolt.query("SELECT active_branch()").map(rows => rows(0).decode[String](0))
                yield
                    assert(nested._1 == "inner", s"the innermost scope wins, got ${nested._1}")
                    assert(nested._2 == "outer", s"leaving the inner scope restores the outer, got ${nested._2}")
                    assert(after == "main", s"leaving every scope restores the URL's branch, got $after")
                end for
            }
        }
    }

    // --- Merging ---

    "a merge with nothing on the target fast-forwards" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _ <- seeded(dolt)
                    _ <- dolt.createBranch("feature")
                    _ <- dolt.onBranch("feature") {
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                    }
                    merged <- dolt.merge(DoltRef.Branch("feature"))
                    count  <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                yield
                    assert(!merged.isConflicted, s"a one-sided merge is clean, got $merged")
                    assert(merged.conflictCount == 0L)
                    assert(merged.resultingCommit.isDefined, s"a merge that moved the branch names a commit, got $merged")
                    assert(merged.summary.nonEmpty, "the server's own summary must be carried")
                    assert(count == 2L, s"the merged row must be present, got $count")
                end for
            }
        }
    }

    "merging a branch already contained answers up to date" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _      <- seeded(dolt)
                    _      <- dolt.createBranch("stale")
                    merged <- dolt.merge(DoltRef.Branch("stale"))
                yield
                    assert(merged == DoltMerge.UpToDate(merged.summary), s"got $merged")
                    assert(merged.resultingCommit.isEmpty, "nothing was created")
                end for
            }
        }
    }

    /** A conflict is a VALUE here: under autocommit the same merge raises and rolls back, and only inside a transaction does it report the
      * conflicts, so `merge` opens that transaction itself.
      */
    "a conflicting merge answers the conflicts rather than failing" in {
        Scope.run {
            withDolt() { dolt =>
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
                    outcome <- dolt.onBranch("left")(dolt.merge(DoltRef.Branch("right")))
                yield
                    assert(outcome.isConflicted, s"both sides edited one row, so this must conflict, got $outcome")
                    assert(outcome.conflictCount == 1L, s"one row is in conflict, got ${outcome.conflictCount}")
                    outcome match
                        case DoltMerge.Conflicted(data, schema, _) =>
                            assert(data.map(_.table) == Chunk("person"), s"got $data")
                            assert(data.head.conflictTable == "dolt_conflicts_person")
                            assert(schema.isEmpty, "the shapes agree, only the rows conflict")
                        case other => fail(s"expected a conflicted merge, got $other")
                    end match
                end for
            }
        }
    }

    // --- Tags, reset, revert, cherry-pick ---

    "a tag is answered whole and lists among the tags" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    seed   <- seeded(dolt)
                    made   <- dolt.tag("v1", DoltRef.Head, Present("first release"))
                    listed <- dolt.tags
                yield
                    assert(made.name == "v1")
                    assert(made.hash == seed.hash, s"the tag names the commit asked for, got ${made.hash}")
                    assert(made.message == Present("first release"), s"got ${made.message}")
                    assert(listed.map(_.name) == Chunk("v1"), s"got $listed")
                end for
            }
        }
    }

    "a hard reset discards the working set, a mixed one keeps it" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _         <- seeded(dolt)
                    _         <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                    _         <- dolt.reset(DoltRef.Head, DoltResetMode.Mixed)
                    afterSoft <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                    _         <- dolt.reset(DoltRef.Head, DoltResetMode.Hard)
                    afterHard <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                yield
                    assert(afterSoft == 2L, s"a mixed reset keeps the working set, got $afterSoft")
                    assert(afterHard == 1L, s"a hard reset discards it, got $afterHard")
                end for
            }
        }
    }

    "a revert undoes a commit and leaves it in the history" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _        <- seeded(dolt)
                    _        <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                    unwanted <- dolt.commit("add bob")
                    undo     <- dolt.revert(DoltRef.Head)
                    count    <- dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))
                    log      <- dolt.log()
                yield
                    assert(count == 1L, s"the reverted row must be gone, got $count")
                    assert(undo.hash != unwanted.hash, "a revert is a new commit")
                    assert(log.map(_.hash).contains(unwanted.hash), s"the reverted commit stays in the history, got $log")
                end for
            }
        }
    }

    "a cherry-pick applies one commit's change onto the current branch" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _ <- seeded(dolt)
                    _ <- dolt.createBranch("feature")
                    wanted <- dolt.onBranch("feature") {
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                    }
                    picked <- dolt.cherryPick(DoltRef.Commit(wanted.hash))
                    names  <- dolt.query("SELECT name FROM person ORDER BY id").map(rows => Kyo.foreach(rows)(_.decode[String](0)))
                yield
                    assert(picked.hash != wanted.hash, "a cherry-pick creates its own commit")
                    assert(names == Chunk("alice", "bob"), s"the picked row must be present on main, got $names")
                end for
            }
        }
    }

    // --- Remotes ---

    /** A file remote, which needs no second server. The database directory lives inside the container, so the path is the server's rather
      * than this process's.
      */
    "a remote is configured, answered whole, and lists among the remotes" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _      <- seeded(dolt)
                    made   <- dolt.addRemote("backup", "file:///tmp/kyo-dolt-remote")
                    listed <- dolt.remotes
                yield
                    assert(made.name == "backup")
                    assert(made.url.contains("kyo-dolt-remote"), s"got ${made.url}")
                    assert(listed.map(_.name) == Chunk("backup"), s"got $listed")
                end for
            }
        }
    }

    "a push to a file remote round-trips through a fetch" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    seed     <- seeded(dolt)
                    _        <- dolt.addRemote("backup", s"file:///tmp/kyo-dolt-remote-${seed.hash}")
                    _        <- dolt.push("backup", "main")
                    _        <- dolt.fetch("backup")
                    branches <- dolt.branches
                yield
                    // The push succeeded if the fetch found the branch the push created on the remote.
                    assert(branches.exists(_.name == "main"), s"got $branches")
                end for
            }
        }
    }

end SqlDoltOnlyTest
