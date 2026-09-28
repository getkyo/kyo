package kyo

/** The version-control surface of [[Dolt]], one suite for every engine behind it.
  *
  * A caller holding a `Dolt` cannot tell which engine answers, so both must answer alike. Where they cannot, the difference is a declared
  * capability below with an assertion on each side, never a leaf one engine skips. `Dolt` has no public constructor, so the suite lives in
  * this module's tests rather than in a published artifact; each engine's module subclasses it.
  */
trait DoltConformanceTest extends kyo.test.Test[Any]:

    /** Opens a fresh, empty repository and runs `f` against it, narrowed to [[Dolt]]. */
    def withDolt[A](config: SqlConfig = SqlConfig())(
        f: Dolt => A < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): A < (Async & Abort[SqlException] & Scope)

    /** Whether the engine records who wrote a change apart from who committed it. */
    def recordsAuthor: Boolean

    /** Whether the engine keeps a total ordering over the commit graph. */
    def recordsCommitOrder: Boolean

    /** Whether a conflicted merge leaves its conflicts in the branch's working set to resolve. Where it does not, the merge is rolled back
      * after its conflicts are read, and the branch is left as it was.
      */
    def keepsConflictsPastMerge: Boolean

    /** A `file://` URL for a remote nothing else uses, as a path on the host the engine runs on. */
    def fileRemoteUrl(using Frame): String < (Sync & Scope)

    /** A table with one row, committed, which most leaves start from. */
    private def seeded(dolt: Dolt)(using Frame): Dolt.Commit < (Async & Abort[SqlException] & DB) =
        for
            _ <- dolt.executeRaw("CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)")
            _ <- dolt.executeRaw("INSERT INTO person VALUES (1, 'alice')")
            c <- dolt.commit("seed")
        yield c

    private def count(dolt: Dolt)(using Frame): Long < (Async & Abort[SqlException] & DB) =
        dolt.query("SELECT COUNT(*) FROM person").map(rows => rows(0).decode[Long](0))

    private def names(dolt: Dolt)(using Frame): Chunk[String] < (Async & Abort[SqlException] & DB) =
        dolt.query("SELECT name FROM person ORDER BY id").map(rows => Kyo.foreach(rows)(_.decode[String](0)))

    /** Commits an edit of alice's row on `left` and a different one on `right`, so merging either into the other conflicts on one row. */
    private def divergedEdits(dolt: Dolt)(using Frame): Dolt.Commit < (Async & Abort[SqlException] & DB) =
        for
            _ <- seeded(dolt)
            _ <- dolt.createBranch("left")
            _ <- dolt.createBranch("right")
            l <- dolt.onBranch("left") {
                dolt.executeRaw("UPDATE person SET name = 'alice-left' WHERE id = 1").andThen(dolt.commit("left edit"))
            }
            _ <- dolt.onBranch("right") {
                dolt.executeRaw("UPDATE person SET name = 'alice-right' WHERE id = 1").andThen(dolt.commit("right edit"))
            }
        yield l

    private def assertOneRowConflict(outcome: Dolt.Merge | Dolt.StagedMerge)(using Frame, kyo.test.AssertScope): Unit =
        outcome match
            case Dolt.Merge.Conflicted(data, schema, message) =>
                assert(data == Chunk(Dolt.ConflictSummary("person", 1L)), s"got $data")
                assert(data.head.conflictTable == "dolt_conflicts_person")
                assert(schema.isEmpty, "the shapes agree, only the rows conflict")
                assert(message.nonEmpty, "the engine's own account must be carried")
            case other => fail(s"expected a conflicted merge, got $other")

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
                    assert(made.committer.nonEmpty, s"the committer must be populated, got '${made.committer}'")
                    assert(!made.isMerge, "an ordinary commit has one parent")
                    assert(!made.isRoot, s"the seed commit follows the initial one, got parents ${made.parents}")
                    assert(
                        made.author.isDefined == recordsAuthor,
                        s"the author is recorded exactly where the engine keeps one apart from the committer, got ${made.author}"
                    )
                    assert(
                        made.order.isDefined == recordsCommitOrder,
                        s"the commit order is present exactly where the engine keeps one, got ${made.order}"
                    )
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
                    parent  <- dolt.log(Dolt.Ref.Ancestor(Dolt.Ref.Head, 1), limit = Present(1))
                yield
                    assert(limited.size == 1, s"a limit of one must answer one commit, got ${limited.size}")
                    assert(limited.head.hash == second.hash)
                    assert(parent.head.message == "seed", s"HEAD~1 must be the previous commit, got '${parent.head.message}'")
                end for
            }
        }
    }

    "the log of another branch starts at that branch's own commit" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    seed <- seeded(dolt)
                    _    <- dolt.createBranch("feature")
                    own  <- dolt.onBranch("feature")(
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                    )
                    history <- dolt.log(Dolt.Ref.Branch("feature"), Present(2))
                    commit  <- dolt.log(Dolt.Ref.Commit(own.hash), Present(1))
                yield
                    assert(history.map(_.hash) == Chunk(own.hash, seed.hash), s"got ${history.map(_.message)}")
                    assert(commit.map(_.message) == Chunk("add bob"), s"got ${commit.map(_.message)}")
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

    "add stages one table, and a commit that is not `all` takes only what was staged" in {
        Scope.run {
            withDolt() { dolt =>
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
    }

    "a diff names the rows that changed and the commits it spans" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _       <- seeded(dolt)
                    _       <- dolt.executeRaw("UPDATE person SET name = 'alice2' WHERE id = 1")
                    _       <- dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')")
                    _       <- dolt.commit("edit and add")
                    changed <- dolt.diff(Dolt.Ref.Ancestor(Dolt.Ref.Head, 1), Dolt.Ref.Head, "person")
                yield
                    assert(changed.size == 2, s"one edit and one insert is two changed rows, got ${changed.size}")
                    val kinds = changed.map(_.kind).toSet
                    assert(kinds == Set(Dolt.Diff.Kind.Modified, Dolt.Diff.Kind.Added), s"got $kinds")
                    assert(changed.forall(_.toCommit.nonEmpty), "each row must name the commit it diffed to")
                    assert(changed.forall(_.toCommitDate.isDefined), "each row must carry the resolved commit date")
                end for
            }
        }
    }

    // --- Branches ---

    "a new branch is answered whole and lists among the branches" in {
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
                    assert(byName == Set("main", "feature"), s"got $byName")
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
                    onMain    <- count(dolt)
                    onFeature <- dolt.onBranch("feature")(count(dolt))
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
                    // Read back in the opposite order, so a stale branch would be visible as the wrong rows.
                    rightNames <- dolt.onBranch("right")(names(dolt))
                    leftNames  <- dolt.onBranch("left")(names(dolt))
                    mainNames  <- names(dolt)
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
                    _ <- dolt.onBranch("outer")(dolt.executeRaw("INSERT INTO person VALUES (2, 'outer')").andThen(dolt.commit("outer")))
                    _ <- dolt.onBranch("inner")(dolt.executeRaw("INSERT INTO person VALUES (3, 'inner')").andThen(dolt.commit("inner")))
                    nested <- dolt.onBranch("outer") {
                        dolt.onBranch("inner")(names(dolt)).map(deep => names(dolt).map(back => (deep, back)))
                    }
                    after <- names(dolt)
                yield
                    assert(nested._1 == Chunk("alice", "inner"), s"the innermost scope wins, got ${nested._1}")
                    assert(nested._2 == Chunk("alice", "outer"), s"leaving the inner scope restores the outer, got ${nested._2}")
                    assert(after == Chunk("alice"), s"leaving every scope restores the URL's branch, got $after")
                end for
            }
        }
    }

    // --- Merging ---

    "a merge with nothing on the target fast-forwards" in {
        Scope.run {
            withDolt() { dolt =>
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
                    assert(merged.conflictCount == 0L)
                    assert(merged.resultingCommit.map(_.hash) == Present(bob.hash), s"the branch moves to bob's commit, got $merged")
                    assert(merged.summary.nonEmpty, "the engine's own summary must be carried")
                    assert(rows == 2L, s"the merged row must be present, got $rows")
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
                    merged <- dolt.merge(Dolt.Ref.Branch("stale"))
                yield
                    assert(merged == Dolt.Merge.UpToDate(merged.summary), s"got $merged")
                    assert(merged.resultingCommit.isEmpty, "nothing was created")
                end for
            }
        }
    }

    /** A conflict is a VALUE, never a failure. What it leaves behind is the declared difference: the conflicts to resolve, or the branch
      * rolled back to where it was.
      */
    "a conflicting merge answers the conflicts rather than failing" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    leftHead <- divergedEdits(dolt)
                    outcome  <- dolt.onBranch("left")(dolt.merge(Dolt.Ref.Branch("right")))
                    left     <- dolt.onBranch("left")(dolt.conflicts)
                    head     <- dolt.log(Dolt.Ref.Branch("left"), Present(1))
                    name     <- dolt.onBranch("left") {
                        dolt.query("SELECT name FROM person WHERE id = 1").map(rows => rows(0).decode[String](0))
                    }
                yield
                    assertOneRowConflict(outcome)
                    assert(outcome.conflictCount == 1L, s"one row is in conflict, got ${outcome.conflictCount}")
                    assert(head.head.hash == leftHead.hash, "a conflicted merge commits nothing")
                    if keepsConflictsPastMerge then
                        assert(left == Chunk(Dolt.ConflictSummary("person", 1L)), s"the conflicts must be left to resolve, got $left")
                    else
                        assert(left.isEmpty, s"the rolled-back merge must leave no conflict, got $left")
                        assert(name == "alice-left", s"the rollback restores the branch's own row, got $name")
                    end if
                end for
            }
        }
    }

    // --- Staged merges ---

    "a staged merge where a fast-forward was possible commits once, with both parents and the caller's rows" in {
        Scope.run {
            withDolt() { dolt =>
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
    }

    "a staged merge of diverged branches commits once with both parents" in {
        Scope.run {
            withDolt() { dolt =>
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
    }

    "a staged merge of a branch already contained answers up to date and stages nothing" in {
        Scope.run {
            withDolt() { dolt =>
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
    }

    "a conflicting staged merge answers the conflicts as merge does" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _      <- divergedEdits(dolt)
                    staged <- dolt.onBranch("left")(dolt.stageMerge(Dolt.Ref.Branch("right")))
                    left   <- dolt.onBranch("left")(dolt.conflicts)
                    status <- dolt.onBranch("left")(dolt.status)
                    merged <- dolt.onBranch("left") {
                        dolt.reset(Dolt.Ref.Head, Dolt.ResetMode.Hard).andThen(dolt.merge(Dolt.Ref.Branch("right")))
                    }
                yield
                    assertOneRowConflict(staged)
                    if keepsConflictsPastMerge then
                        assert(left == Chunk(Dolt.ConflictSummary("person", 1L)), s"the conflicts must be left to resolve, got $left")
                    else
                        assert(left.isEmpty, s"the rolled-back merge must leave no conflict, got $left")
                        assert(status.isEmpty, s"the rolled-back merge must leave the working set as it was, got $status")
                    end if
                    assert(
                        merged.isConflicted && merged.conflictCount == staged.conflictCount,
                        s"merge answers the same conflict, got $merged"
                    )
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
                    made   <- dolt.tag("v1", Dolt.Ref.Head, Present("first release"))
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
                    _         <- dolt.reset(Dolt.Ref.Head, Dolt.ResetMode.Mixed)
                    afterSoft <- count(dolt)
                    _         <- dolt.reset(Dolt.Ref.Head, Dolt.ResetMode.Hard)
                    afterHard <- count(dolt)
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
                    undo     <- dolt.revert(Dolt.Ref.Head)
                    rows     <- count(dolt)
                    log      <- dolt.log()
                yield
                    assert(rows == 1L, s"the reverted row must be gone, got $rows")
                    assert(undo.hash != unwanted.hash, "a revert is a new commit")
                    assert(log.map(_.hash).contains(unwanted.hash), s"the reverted commit stays in the history, got $log")
                end for
            }
        }
    }

    /** The current branch moves past the picked commit's parent first. Picked onto its own parent, the new commit can hold the same parent,
      * tree, message and second as the original, and a content-addressed engine then answers the original's hash.
      */
    "a cherry-pick applies one commit's change onto the current branch" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _      <- seeded(dolt)
                    _      <- dolt.createBranch("feature")
                    wanted <- dolt.onBranch("feature") {
                        dolt.executeRaw("INSERT INTO person VALUES (2, 'bob')").andThen(dolt.commit("add bob"))
                    }
                    head   <- dolt.executeRaw("INSERT INTO person VALUES (3, 'carol')").andThen(dolt.commit("add carol"))
                    picked <- dolt.cherryPick(Dolt.Ref.Commit(wanted.hash))
                    onMain <- names(dolt)
                yield
                    assert(picked.parents == Chunk(head.hash), s"the pick lands on the current branch's head, got ${picked.parents}")
                    assert(picked.hash != wanted.hash, "a cherry-pick creates its own commit")
                    assert(picked.message == "add bob", s"the pick carries the picked commit's message, got '${picked.message}'")
                    assert(onMain == Chunk("alice", "bob", "carol"), s"the picked row must be present on main, got $onMain")
                end for
            }
        }
    }

    // --- Remotes ---

    /** A file remote, which needs no second server. */
    "a remote is configured, answered whole, and lists among the remotes" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _      <- seeded(dolt)
                    url    <- fileRemoteUrl
                    made   <- dolt.addRemote("backup", url)
                    listed <- dolt.remotes
                yield
                    assert(made.name == "backup")
                    assert(made.url.contains(url.stripPrefix("file://")), s"the remote must carry the url it was given, got ${made.url}")
                    assert(listed.map(_.name) == Chunk("backup"), s"got $listed")
                end for
            }
        }
    }

    "a push to a file remote round-trips through a fetch" in {
        Scope.run {
            withDolt() { dolt =>
                for
                    _        <- seeded(dolt)
                    url      <- fileRemoteUrl
                    _        <- dolt.addRemote("backup", url)
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

end DoltConformanceTest
