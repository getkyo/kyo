package kyo.internal.doltlite

import kyo.*

/** Every statement DoltLite's version-control surface sends, and every decode of what comes back.
  *
  * Deliberately not shared with the server backend's statements object: the two engines expose the same operations
  * through different shapes (scalar functions rather than procedures, hex rather than base-32 hashes, a five-column
  * `dolt_log` with parents in `dolt_commit_ancestors`, per-table diff virtual tables rather than a diff function). What
  * is shared is the vocabulary they answer in, `kyo-sql-dolt-api`.
  */
private[kyo] object DoltLiteStatements:

    private def text(value: String): Sql.BoundValue[String] =
        Sql.BoundValue(value, summon[SqlSchema.Column[String]], "TEXT")

    private def query(client: SqlClient, sql: String, args: Chunk[String] = Chunk.empty)(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        client.routedWith(client.config)(_.extendedQuery(sql, args.map(text)))

    /** Calls a version-control function and answers its single scalar. Every operation here is a SQL function rather than
      * a procedure, so the call is a `SELECT` answering one row of one column.
      */
    private def call(client: SqlClient, function: String, args: Chunk[String])(using
        Frame
    ): String < (Async & Abort[SqlException]) =
        val placeholders = Chunk.fill(args.size)("?").mkString(", ")
        query(client, s"SELECT $function($placeholders)", args).map { rows =>
            if rows.isEmpty then Abort.fail(DoltLiteFunctionAnsweredNothingException(function))
            else lifted(rows.head.decode[Maybe[String]](0)).map(_.getOrElse(""))
        }
    end call

    private def callUnit(client: SqlClient, function: String, args: Chunk[String])(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        call(client, function, args).unit

    private def lifted[A](decode: A < Abort[SqlDecodeException])(using Frame): A < Abort[SqlException] =
        Abort.recover[SqlDecodeException](
            (e: SqlDecodeException) => Abort.fail(e: SqlException),
            t => Abort.error(Result.Panic(t))
        )(decode)

    private def decoded[A](rows: Chunk[SqlRow])(decode: SqlRow => A < Abort[SqlDecodeException])(using
        Frame
    ): Chunk[A] < Abort[SqlException] =
        Kyo.foreach(rows)(row => lifted(decode(row)))

    private def one[A](rows: Chunk[SqlRow], what: String)(decode: SqlRow => A < Abort[SqlDecodeException])(using
        Frame
    ): A < Abort[SqlException] =
        if rows.isEmpty then Abort.fail(DoltLiteFunctionAnsweredNothingException(what))
        else lifted(decode(rows.head))

    /** A single-quoted SQL literal with embedded quotes doubled, for the positions a bind cannot reach. */
    private def literal(value: String): String =
        "'" + value.replace("'", "''") + "'"

    def hashOf(client: SqlClient, ref: String)(using Frame): DoltCommitHash < (Async & Abort[SqlException]) =
        call(client, "dolt_hashof", Chunk(ref)).map(DoltCommitHash.apply)

    // --- Commits ---

    /** Every column this engine's log carries. It has no author, signature or ordering columns. */
    private val logColumns = "commit_hash, committer, email, date, message"

    private def readCommit(client: SqlClient, row: SqlRow)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        lifted {
            for
                hash      <- row.decode[String]("commit_hash")
                committer <- row.decode[String]("committer")
                email     <- row.decode[Maybe[String]]("email")
                date      <- row.decode[Instant]("date")
                message   <- row.decode[String]("message")
            yield (hash, committer, email.getOrElse(""), date, message)
        }.map { (hash, committer, email, date, message) =>
            parentsOf(client, hash).map { parents =>
                refsAt(client, hash).map { refs =>
                    DoltCommit(
                        DoltCommitHash(hash),
                        message,
                        author = Absent,
                        authorEmail = Absent,
                        authorDate = Absent,
                        committer = committer,
                        committerEmail = email,
                        date = date,
                        parents = parents,
                        refs = refs,
                        signature = Absent,
                        order = Absent
                    )
                }
            }
        }

    /** Parents come from a side table rather than a log column. They must be read: an empty `parents` is what
      * [[kyo.DoltCommit.isRoot]] and `isMerge` are defined in terms of.
      */
    private def parentsOf(client: SqlClient, hash: String)(using
        Frame
    ): Chunk[DoltCommitHash] < (Async & Abort[SqlException]) =
        query(
            client,
            "SELECT parent_hash FROM dolt_commit_ancestors WHERE commit_hash = ? AND parent_hash IS NOT NULL " +
                "ORDER BY parent_index",
            Chunk(hash)
        ).map { rows =>
            decoded(rows)(_.decode[String](0)).map(_.map(DoltCommitHash.apply))
        }

    /** Refs are derived from the branch and tag lists; this engine's log has no `refs` column. */
    private def refsAt(client: SqlClient, hash: String)(using Frame): Chunk[String] < (Async & Abort[SqlException]) =
        query(
            client,
            "SELECT name FROM dolt_branches WHERE hash = ? UNION SELECT tag_name FROM dolt_tags WHERE tag_hash = ? " +
                "ORDER BY 1",
            Chunk(hash, hash)
        ).map(rows => decoded(rows)(_.decode[String](0)))

    def commit(client: SqlClient, message: String, all: Boolean)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        val args = if all then Chunk("-Am", message) else Chunk("-m", message)
        // NOT wrapped in a transaction, unlike the server backend's. This engine's version-control functions end the
        // SQL transaction themselves, so an enclosing `COMMIT` answers "cannot commit - no transaction is active".
        // The read-back is by the hash the call returned rather than by HEAD, so no other writer can be mistaken for it.
        call(client, "dolt_commit", args).map(hash => commitAt(client, hash))
    end commit

    def commitAt(client: SqlClient, ref: String)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        query(client, s"SELECT $logColumns FROM dolt_log WHERE commit_hash = ?", Chunk(ref)).map { rows =>
            if rows.isEmpty then Abort.fail(DoltLiteFunctionAnsweredNothingException(s"dolt_log($ref)"))
            else readCommit(client, rows.head)
        }

    def log(client: SqlClient, ref: DoltRef, limit: Maybe[Int])(using
        Frame
    ): Chunk[DoltCommit] < (Async & Abort[SqlException]) =
        // dolt_log takes no ref argument and reports the CURRENT branch's history, so a ref is honoured by resolving
        // it to a hash and walking from there.
        val tail = limit.fold("")(n => s" LIMIT $n")
        hashOf(client, ref.render).map { head =>
            query(
                client,
                // Ordered by DISTANCE from the head, not by date: commits made in the same second tie on date, which
                // makes the first row arbitrary rather than the commit just made.
                s"""WITH RECURSIVE reachable(h, depth) AS (
                   |  SELECT ?, 0
                   |  UNION
                   |  SELECT a.parent_hash, r.depth + 1 FROM dolt_commit_ancestors a JOIN reachable r
                   |  ON a.commit_hash = r.h WHERE a.parent_hash IS NOT NULL
                   |)
                   |SELECT $logColumns FROM dolt_log
                   |JOIN (SELECT h, MIN(depth) AS d FROM reachable GROUP BY h) walk ON dolt_log.commit_hash = walk.h
                   |ORDER BY walk.d ASC$tail""".stripMargin,
                Chunk(head)
            ).map(rows => Kyo.foreach(rows)(row => readCommit(client, row)))
        }
    end log

    def status(client: SqlClient)(using Frame): Chunk[DoltChange] < (Async & Abort[SqlException]) =
        query(client, "SELECT table_name, staged, status FROM dolt_status ORDER BY table_name, staged").map { rows =>
            decoded(rows) { row =>
                for
                    table  <- row.decode[String]("table_name")
                    staged <- row.decode[Boolean]("staged")
                    state  <- row.decode[String]("status")
                yield DoltChange(table, staged, state)
            }
        }

    /** A diff between two commits of one table, read from that table's own `dolt_diff_<table>` virtual table. */
    def diff(client: SqlClient, from: DoltRef, to: DoltRef, table: String)(using
        Frame
    ): Chunk[DoltDiff] < (Async & Abort[SqlException]) =
        // The WHOLE identifier is quoted, not just the table part: `dolt_diff_"person"` parses as the table
        // `dolt_diff_` followed by a separate identifier, and the engine answers "no such table: dolt_diff_".
        val quoted = "\"dolt_diff_" + table.replace("\"", "\"\"") + "\""
        hashOf(client, from.render).map { fromHash =>
            hashOf(client, to.render).map { toHash =>
                // The TWO-ARGUMENT form, a snapshot comparison between the endpoints. The no-argument form walks the
                // CURRENT BRANCH's history, so it cannot see a commit that is not an ancestor of where the session is
                // pointed, and a diff between two branches comes back empty on it.
                //
                // The hashes are inlined and escaped rather than bound: this engine refuses bind parameters inside a
                // table function's argument list.
                query(
                    client,
                    s"SELECT * FROM $quoted(${literal(fromHash)}, ${literal(toHash)})",
                    Chunk.empty
                ).map { rows =>
                    decoded(rows) { row =>
                        for
                            kind     <- row.decode[String]("diff_type")
                            fromRef  <- row.decode[Maybe[String]]("from_commit")
                            fromDate <- row.decode[Maybe[Instant]]("from_commit_date")
                            toRef    <- row.decode[Maybe[String]]("to_commit")
                            toDate   <- row.decode[Maybe[Instant]]("to_commit_date")
                        yield DoltDiff(
                            DoltDiff.Kind.parse(kind).getOrElse(DoltDiff.Kind.Modified),
                            fromRef.getOrElse(fromHash),
                            fromDate,
                            toRef.getOrElse(toHash),
                            toDate,
                            row
                        )
                    }
                }
            }
        }
    end diff

    // --- Branches ---

    private val branchColumns =
        "name, hash, dirty, latest_commit_message, latest_committer, latest_committer_email, latest_commit_date, remote, branch"

    private def readBranch(row: SqlRow)(using Frame): DoltBranch < Abort[SqlDecodeException] =
        for
            name       <- row.decode[String]("name")
            hash       <- row.decode[String]("hash")
            dirty      <- row.decode[Maybe[Boolean]]("dirty")
            message    <- row.decode[Maybe[String]]("latest_commit_message")
            committer  <- row.decode[Maybe[String]]("latest_committer")
            commitMail <- row.decode[Maybe[String]]("latest_committer_email")
            commitDate <- row.decode[Maybe[Instant]]("latest_commit_date")
            remote     <- row.decode[Maybe[String]]("remote")
            rbranch    <- row.decode[Maybe[String]]("branch")
        yield DoltBranch(
            name,
            DoltCommitHash(hash),
            dirty.getOrElse(false),
            message.filter(_.nonEmpty),
            committer.filter(_.nonEmpty),
            commitMail.filter(_.nonEmpty),
            commitDate,
            // This engine's branch list carries no author columns, only committer ones.
            latestAuthor = Absent,
            latestAuthorEmail = Absent,
            latestAuthorDate = Absent,
            remote.filter(_.nonEmpty),
            rbranch.filter(_.nonEmpty)
        )

    def branches(client: SqlClient)(using Frame): Chunk[DoltBranch] < (Async & Abort[SqlException]) =
        query(client, s"SELECT $branchColumns FROM dolt_branches ORDER BY name").map(rows => decoded(rows)(readBranch))

    def branchNamed(client: SqlClient, name: String)(using Frame): DoltBranch < (Async & Abort[SqlException]) =
        query(client, s"SELECT $branchColumns FROM dolt_branches WHERE name = ?", Chunk(name)).map { rows =>
            one(rows, s"dolt_branches($name)")(readBranch)
        }

    def createBranch(client: SqlClient, name: String, from: DoltRef)(using
        Frame
    ): DoltBranch < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_branch", Chunk(name, from.render)).andThen(branchNamed(client, name))

    def deleteBranch(client: SqlClient, name: String, force: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_branch", Chunk(if force then "-D" else "-d", name))

    def checkout(client: SqlClient, revision: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_checkout", Chunk(revision))

    // --- Integration ---

    /** Merges and derives what happened, since `dolt_merge` answers only a human message. The conflict tables say
      * whether it conflicted; comparing HEAD before and after against the merged ref's hash separates an up-to-date
      * merge from a fast-forward from a merge commit.
      */
    def merge(client: SqlClient, from: DoltRef)(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        locally {
            hashOf(client, DoltRef.Head.render).map { before =>
                hashOf(client, from.render).map { incoming =>
                    call(client, "dolt_merge", Chunk(from.render)).map { message =>
                        conflicts(client).map { data =>
                            schemaConflicts(client).map { schema =>
                                if data.nonEmpty || schema.nonEmpty then DoltMerge.Conflicted(data, schema, message)
                                else
                                    hashOf(client, DoltRef.Head.render).map { after =>
                                        if after == before then DoltMerge.UpToDate(message)
                                        else if after == incoming then
                                            commitAt(client, after).map(DoltMerge.FastForward(_, message))
                                        else commitAt(client, after).map(DoltMerge.Merged(_, message))
                                    }
                            }
                        }
                    }
                }
            }
        }

    def conflicts(client: SqlClient)(using Frame): Chunk[DoltConflictSummary] < (Async & Abort[SqlException]) =
        query(client, "SELECT \"table\", num_conflicts FROM dolt_conflicts ORDER BY \"table\"").map { rows =>
            decoded(rows) { row =>
                for
                    table <- row.decode[String](0)
                    count <- row.decode[Long](1)
                yield DoltConflictSummary(table, count)
            }
        }

    def schemaConflicts(client: SqlClient)(using Frame): Chunk[DoltSchemaConflict] < (Async & Abort[SqlException]) =
        query(
            client,
            "SELECT table_name, base_schema, our_schema, their_schema, description FROM dolt_schema_conflicts ORDER BY table_name"
        ).map { rows =>
            decoded(rows) { row =>
                for
                    table       <- row.decode[String]("table_name")
                    base        <- row.decode[Maybe[String]]("base_schema")
                    ours        <- row.decode[Maybe[String]]("our_schema")
                    theirs      <- row.decode[Maybe[String]]("their_schema")
                    description <- row.decode[Maybe[String]]("description")
                yield DoltSchemaConflict(
                    table,
                    base.filter(_.nonEmpty),
                    ours.filter(_.nonEmpty),
                    theirs.filter(_.nonEmpty),
                    description.filter(_.nonEmpty)
                )
            }
        }

    def resolveConflicts(client: SqlClient, table: String, keeping: DoltResolution)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        conflicts(client).map { before =>
            val pending = before.find(_.table == table).fold(0L)(_.count)
            callUnit(client, "dolt_conflicts_resolve", Chunk(keeping.flag, table)).andThen(pending)
        }

    def reset(client: SqlClient, to: DoltRef, mode: DoltResetMode)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_reset", mode.flag.fold(Chunk(to.render))(flag => Chunk(flag, to.render)))

    /** Neither can hold a transaction across the call, for the reason [[commit]] gives, and neither is handed a hash, so
      * both read HEAD back afterwards.
      */
    def revert(client: SqlClient, ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_revert", Chunk(ref.render)).andThen {
            hashOf(client, DoltRef.Head.render).map(hash => commitAt(client, hash))
        }

    def cherryPick(client: SqlClient, ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_cherry_pick", Chunk(ref.render)).andThen {
            hashOf(client, DoltRef.Head.render).map(hash => commitAt(client, hash))
        }

    // --- Tags ---

    private val tagColumns = "tag_name, tag_hash, tagger, email, date, message"

    private def readTag(row: SqlRow)(using Frame): DoltTag < Abort[SqlDecodeException] =
        for
            name    <- row.decode[String]("tag_name")
            hash    <- row.decode[String]("tag_hash")
            tagger  <- row.decode[String]("tagger")
            email   <- row.decode[Maybe[String]]("email")
            date    <- row.decode[Instant]("date")
            message <- row.decode[Maybe[String]]("message")
        yield DoltTag(name, DoltCommitHash(hash), tagger, email.getOrElse(""), date, message.filter(_.nonEmpty))

    def tag(client: SqlClient, name: String, ref: DoltRef, message: Maybe[String])(using
        Frame
    ): DoltTag < (Async & Abort[SqlException]) =
        val args = message.fold(Chunk(name, ref.render))(m => Chunk(name, ref.render, "-m", m))
        callUnit(client, "dolt_tag", args).andThen {
            query(client, s"SELECT $tagColumns FROM dolt_tags WHERE tag_name = ?", Chunk(name)).map { rows =>
                one(rows, s"dolt_tags($name)")(readTag)
            }
        }
    end tag

    def tags(client: SqlClient)(using Frame): Chunk[DoltTag] < (Async & Abort[SqlException]) =
        query(client, s"SELECT $tagColumns FROM dolt_tags ORDER BY tag_name").map(rows => decoded(rows)(readTag))

    def deleteTag(client: SqlClient, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_tag", Chunk("-d", name))

    // --- Remotes ---

    private def readRemote(row: SqlRow)(using Frame): DoltRemote < Abort[SqlDecodeException] =
        for
            name   <- row.decode[String]("name")
            url    <- row.decode[String]("url")
            specs  <- row.decode[Maybe[String]]("fetch_specs")
            params <- row.decode[Maybe[String]]("params")
        yield DoltRemote(name, url, specs.fold(Chunk.empty[String])(jsonStringArray), params.filter(p => p.nonEmpty && p != "{}"))

    /** The elements of a JSON array of strings, which is the only JSON shape this module reads. */
    private def jsonStringArray(raw: String): Chunk[String] =
        val trimmed = raw.trim
        val inner   = if trimmed.startsWith("[") && trimmed.endsWith("]") then trimmed.substring(1, trimmed.length - 1) else trimmed
        Chunk.from(inner.split(",").toSeq).map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)
    end jsonStringArray

    def remotes(client: SqlClient)(using Frame): Chunk[DoltRemote] < (Async & Abort[SqlException]) =
        query(client, "SELECT name, url, fetch_specs, params FROM dolt_remotes ORDER BY name").map { rows =>
            decoded(rows)(readRemote)
        }

    def addRemote(client: SqlClient, name: String, url: String)(using Frame): DoltRemote < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_remote", Chunk("add", name, url)).andThen {
            query(client, "SELECT name, url, fetch_specs, params FROM dolt_remotes WHERE name = ?", Chunk(name)).map {
                rows => one(rows, s"dolt_remotes($name)")(readRemote)
            }
        }

    def removeRemote(client: SqlClient, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_remote", Chunk("remove", name))

    def push(client: SqlClient, remote: String, branch: String, force: Boolean)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_push", if force then Chunk("--force", remote, branch) else Chunk(remote, branch))

    def fetch(client: SqlClient, remote: String, branch: Maybe[String])(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "dolt_fetch", branch.fold(Chunk(remote))(b => Chunk(remote, b)))

    def pull(client: SqlClient, remote: String, branch: Maybe[String])(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        locally {
            hashOf(client, DoltRef.Head.render).map { before =>
                call(client, "dolt_pull", branch.fold(Chunk(remote))(b => Chunk(remote, b))).map { message =>
                    conflicts(client).map { data =>
                        schemaConflicts(client).map { schema =>
                            if data.nonEmpty || schema.nonEmpty then DoltMerge.Conflicted(data, schema, message)
                            else
                                hashOf(client, DoltRef.Head.render).map { after =>
                                    if after == before then DoltMerge.UpToDate(message)
                                    else commitAt(client, after).map(DoltMerge.Merged(_, message))
                                }
                        }
                    }
                }
            }
        }

end DoltLiteStatements
