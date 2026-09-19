# kyo-sql-dolt

The Dolt driver for [kyo-sql](../kyo-sql/README.md).

[kyo-sql](../kyo-sql/README.md) is the SQL module, and it names no engine: the `sql"…"` interpolator, the typed
DSL, `SqlSchema` and row decoding, transactions, streaming, pooling, configuration, and error handling all live
there and are documented there. This artifact is what makes `dolt://` one of the URLs that module can open.

Dolt is a SQL database with a version-controlled storage layer. It speaks the MySQL wire protocol, so everything
portable works exactly as it does against MySQL, and what it adds is git: branches, commits, merges, diffs and
tags over the data itself, reachable through `Dolt`.

```scala
val recent: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("dolt://app:secret@localhost:3306/inventory") {
        sql"SELECT sku FROM item ORDER BY id".as[String].run
    }
```

Nothing in that body names Dolt. Point it at a `mysql://` URL and it runs against MySQL.

The examples below run over a small inventory: an `item` table that a team edits on branches before merging.

## Adding the driver

Depend on kyo-sql and this artifact. There is nothing to wire:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"      % "<latest version>",
    "io.getkyo" %% "kyo-sql-dolt" % "<latest version>"
)
```

The artifact carries one `META-INF/services/kyo.db.Backend` entry naming `DoltBackendFactory`, which claims the
scheme `dolt` and nothing else. It deliberately does not claim `mysql`, even though this engine speaks that
protocol: a URL saying `mysql` should reach the MySQL backend, and claiming it here would make which backend
answers depend on classpath order.

This module depends on `kyo-sql-mysql`, which it reuses whole. The connection, the authentication exchange, the
codecs and the prepared-statement cache are all MySQL's, because that is genuinely what this engine speaks.

## Two things to know before the API

### A Dolt commit is not a SQL commit

They are different operations at different levels, and a program uses both.

A SQL transaction writes to the branch's **working set**, which is git's working directory. `commit` turns that
working set into a commit in the history. So the ordinary composition is what it looks like:

```scala
Dolt.use { dolt =>
    for
        _      <- dolt.transaction { sql"INSERT INTO item (sku) VALUES ('KYO-1')".execute }
        commit <- dolt.commit("add the first item")
    yield commit
}
```

`@@dolt_transaction_commit` couples them, so that every SQL COMMIT also produces a Dolt commit, and it is a
setting rather than a shape this API imposes.

### A working set belongs to the branch, not to a connection

Once a transaction commits, its writes are visible to every session on that branch, and a `commit` from any of
them captures the lot. A caller wanting a commit that holds exactly its own writes makes them on a branch of its
own, which is what `onBranch` is for.

## Branches

The branch a statement runs against is **session state** on the server: one connection running a checkout leaves
a second connection still on `main`. Since kyo-sql pools connections, a checkout that simply mutated the client
would hand the next borrower somebody else's branch.

So there is no free-standing `checkout`. `onBranch` scopes the revision instead:

```scala
Dolt.use { dolt =>
    for
        _ <- dolt.createBranch("restock")
        _ <- dolt.onBranch("restock") {
            for
                _ <- sql"UPDATE item SET count = count + 10 WHERE sku = 'KYO-1'".execute
                _ <- dolt.commit("restock KYO-1")
            yield ()
        }
        // Back on the branch the URL named, where nothing above happened.
        current <- sql"SELECT count FROM item WHERE sku = 'KYO-1'".as[Int].run
    yield current
}
```

The revision is carried **per fiber**, and each connection moves itself onto it before running anything. Two
fibers can therefore work on two branches through one pool at the same time, and neither sees the other's. The
reconciliation is one `USE` and only happens when the connection is not already there, so a computation that
stays on one branch pays for it once per connection.

Scopes nest and the innermost wins. `onBranch` takes any revision, so a branch name, a tag, or a commit hash all
work.

The URL carries the default: `dolt://host:3306/inventory/main` starts every connection on `main`, and Dolt
accepts that `db/branch` form in the handshake, so it costs no extra round trip.

**Reads across branches need no scope at all.** Dolt resolves another branch inline, and the typed read surface
takes a ref:

```scala
// Both of these run on whatever branch the session is already on.
sql"SELECT sku FROM item AS OF 'restock'".as[String].run
sql"SELECT sku FROM `inventory/restock`.item".as[String].run
```

## Merging, where a conflict is a value

A conflict is an ordinary outcome of merging rather than a failure, so `merge` answers a value:

```scala
Dolt.use { dolt =>
    dolt.merge(Dolt.Ref.Branch("restock")).map {
        case Dolt.Merge.UpToDate(_)            => "nothing to do"
        case Dolt.Merge.FastForward(c, _)      => s"moved to ${c.hash}"
        case Dolt.Merge.Merged(c, _)           => s"merged as ${c.hash}"
        case Dolt.Merge.Conflicted(data, _, _) => s"${data.map(_.count).sum} rows need a decision"
    }
}
```

That shape is forced by the engine as much as by taste. Measured against a 2.3.4 server, a conflicting merge
under autocommit raises an error and rolls the whole merge back, and even inside a transaction the COMMIT is
refused while conflicts are present. So the driver runs the merge in a transaction and sets
`@@dolt_allow_commit_conflicts` on every connection, which is what the server's own error names as the way to
allow it. The conflicts then survive the commit and are there to read.

A `Conflicted` result arrives with the conflicts sitting in the branch's working set. Resolve them by keeping one
side wholesale, or read the three sides of each row and decide:

```scala
Dolt.use { dolt =>
    for
        outcome <- dolt.merge(Dolt.Ref.Branch("restock"))
        // Ascribed because one branch commits and the other does not: without it the two infer as a
        // union rather than as one effectful `Maybe`.
        settled <- (outcome match
            case Dolt.Merge.Conflicted(tables, _, _) =>
                Kyo.foreach(tables)(t => dolt.resolveConflicts(t.table, Dolt.Resolution.Ours))
                    .andThen(dolt.commit("merge restock, keeping ours").map(Maybe(_)))
            // Absent where the merge was already up to date, which produced no commit to name.
            case _ => outcome.resultingCommit
        ): Maybe[Dolt.Commit] < (Async & Abort[SqlException])
    yield settled
}
```

Each `Dolt.ConflictSummary` names the table holding its rows through `conflictTable`, whose columns are the
conflicted table's own prefixed `base_`, `our_` and `their_`. A **schema** conflict is reported separately,
because choosing a side row by row means nothing when the two sides disagree about what columns the table has.

## Reading history

Every read answers the whole row the server has, so showing who changed what needs no follow-up query:

```scala
Dolt.use { dolt =>
    for
        history  <- dolt.log(limit = Present(10))
        branches <- dolt.branches
        pending  <- dolt.status
        changed  <- dolt.diff(Dolt.Ref.Ancestor(Dolt.Ref.Head, 1), Dolt.Ref.Head, "item")
    yield (history, branches, pending, changed)
}
```

`Dolt.Commit` carries the author and the committer with a date each, because git keeps them separate and a revert
or a cherry-pick writes a new committer while preserving the original author. It also carries the refs pointing
at it, its signature, and the server's own commit ordering, which is what makes "newest first" well defined
across branches.

`diff` lifts what is the same for every table, the commits spanned and what happened to each row, into fields,
and leaves the table's own `from_`/`to_` columns in the row for a caller to decode against their own type.

## Where this engine is not MySQL

Dolt runs go-mysql-server rather than MySQL, so wire compatibility is not SQL compatibility. Dolt is registered
in kyo-sql's cross-engine conformance battery, and everything below is a difference that battery found rather
than a reading of documentation.

| Difference | What this driver does |
|---|---|
| No `GROUP BY ... WITH ROLLUP` | The dialect refuses it while rendering, naming the feature, instead of letting a parse error name a column position |
| Every failure carries `HY000` | The typed family is classified from the error NUMBER, so a handler matching an integrity violation works here as elsewhere. The server's own SQLSTATE is relayed untouched |
| `TIMESTAMP` refuses an offset-suffixed literal | Only affects literal SQL a caller writes; bound values are unaffected |
| Sub-second binds are stored wrong | Temporal parameters are sent as text. See below |
| JSON object keys come back sorted | Nothing, objects being unordered by the spec |
| A conflict clause swallows a foreign-key violation | Nothing, the row still does not land |

The temporal one is worth spelling out, because it is silent. The MySQL binary protocol carries a datetime's
sub-second part as a count of microseconds; Dolt reads that integer as the fractional digits, so a bound
`.000001` is stored as `.1`. A value survives exactly when its microsecond count has no leading zero at six
digits, so everything under 100000 microseconds is corrupted with nothing red. This driver therefore sends every
temporal bind as text, which the server parses correctly. Nothing about the read path changes.

## Remotes

Remotes work as they do in git, and a remote's address is whatever its scheme means: for DoltHub an
`owner/database` name, for a file remote a path.

```scala
Dolt.use { dolt =>
    for
        _ <- dolt.addRemote("origin", "acme/inventory")
        _ <- dolt.push("origin", "main")
        m <- dolt.pull("origin", Present("main"))
    yield m
}
```

`pull` answers a `Dolt.Merge` for the same reason `merge` does, and by the same mechanism. `fetch` downloads
without touching any branch, so nothing a caller is working on moves.

## Configuration

Dolt declares no `SqlConfig.Extension`. Every setting it honors is one the MySQL transport beneath it already
honors, and the one piece of state that is Dolt's own, which revision a session runs against, is scoped per
fiber by `onBranch` rather than configured per client.

```scala
val skus: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("dolt://app:secret@localhost:3306/inventory/main", SqlConfig.default.maxConnections(8)) {
        sql"SELECT sku FROM item".as[String].run
    }
```

The pool sizes, timeouts, retry schedule, TLS settings, prepared-statement cache, and URL options are documented
in [kyo-sql](../kyo-sql/README.md#engines-and-configuration).
