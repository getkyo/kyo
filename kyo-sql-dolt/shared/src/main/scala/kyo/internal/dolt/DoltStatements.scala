package kyo.internal.dolt

import kyo.*

/** Every statement the version-control surface sends, and every decode of what comes back.
  *
  * Procedure arguments are BOUND rather than interpolated, so a branch named with a quote is a branch name and not a statement fragment,
  * and a commit message containing one needs no escaping. Two things cannot be bound and are escaped instead, each noted where it happens:
  * a database and revision reach `USE` as an identifier rather than a value, and Dolt's TABLE functions refuse bind parameters outright.
  */
private[kyo] object DoltStatements:

    private def text(value: String): Sql.BoundValue[String] =
        Sql.BoundValue(value, summon[SqlSchema.Column[String]], "TEXT")

    /** Runs a procedure with bound arguments and hands back its result rows. */
    private def call(client: SqlClient, procedure: String, args: Chunk[String])(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        val placeholders = Chunk.fill(args.size)("?").mkString(", ")
        client.routedWith(client.config)(_.extendedQuery(s"CALL $procedure($placeholders)", args.map(text)))
    end call

    /** Runs a procedure whose only result is whether it worked, which the failure channel already carries. */
    private def callUnit(client: SqlClient, procedure: String, args: Chunk[String])(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        call(client, procedure, args).unit

    private def query(client: SqlClient, sql: String, args: Chunk[String] = Chunk.empty)(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        client.routedWith(client.config)(_.extendedQuery(sql, args.map(text)))

    /** A single-quoted SQL literal with embedded quotes doubled.
      *
      * Needed because Dolt's TABLE FUNCTIONS refuse bind parameters: `DOLT_DIFF(?, ?, ?)` answers `Invalid argument to dolt_diff: :v3`,
      * measured. A ref can still carry a quote, so inlining without escaping would be a statement a caller could break. The procedures take
      * ordinary binds and still use them.
      */
    private def literal(value: String): String =
        "'" + value.replace("'", "''") + "'"

    private def lifted[A](decode: A < Abort[SqlDecodeException])(using Frame): A < Abort[SqlException] =
        Abort.recover[SqlDecodeException](
            (e: SqlDecodeException) => Abort.fail(e: SqlException),
            t => Abort.error(Result.Panic(t))
        )(decode)

    private def one[A](rows: Chunk[SqlRow], what: String)(decode: SqlRow => A < Abort[SqlDecodeException])(using
        Frame
    ): A < Abort[SqlException] =
        if rows.isEmpty then Abort.fail(DoltProcedureAnsweredNothingException(what))
        else lifted(decode(rows.head))

    private def decoded[A](rows: Chunk[SqlRow])(decode: SqlRow => A < Abort[SqlDecodeException])(using
        Frame
    ): Chunk[A] < Abort[SqlException] =
        Kyo.foreach(rows)(row => lifted(decode(row)))

    // --- Commits ---

    private val commitColumns =
        "commit_hash, message, author, author_email, author_date, committer, email, date, parents, refs, signature, commit_order"

    private def readCommit(row: SqlRow)(using Frame): DoltCommit < Abort[SqlDecodeException] =
        for
            hash       <- row.decode[String]("commit_hash")
            message    <- row.decode[String]("message")
            author     <- row.decode[String]("author")
            authorMail <- row.decode[String]("author_email")
            authorDate <- row.decode[Instant]("author_date")
            committer  <- row.decode[String]("committer")
            commitMail <- row.decode[String]("email")
            date       <- row.decode[Instant]("date")
            parents    <- row.decode[Maybe[String]]("parents")
            refs       <- row.decode[Maybe[String]]("refs")
            signature  <- row.decode[Maybe[String]]("signature")
            order      <- row.decode[Long]("commit_order")
        yield DoltCommit(
            DoltCommitHash(hash),
            message,
            // This engine records an author apart from the committer; the embedded one does not.
            Present(author),
            Present(authorMail),
            Present(authorDate),
            committer,
            commitMail,
            date,
            // Comma separated, and absent at the initial commit, which is the one commit with no parent.
            splitList(parents).map(DoltCommitHash.apply),
            splitList(refs),
            signature.filter(_.nonEmpty),
            Present(order)
        )

    /** Splits one of the server's comma-separated text columns, treating absent and empty alike as no entries. */
    private def splitList(value: Maybe[String]): Chunk[String] =
        value.fold(Chunk.empty[String])(v => Chunk.from(v.split(",").toSeq).map(_.trim).filter(_.nonEmpty))

    /** The full commit for a hash, for the procedures that answer only a hash or only a status. */
    private def commitAt(client: SqlClient, ref: String)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        query(client, s"SELECT $commitColumns FROM DOLT_LOG(${literal(ref)}, '--parents') ORDER BY commit_order DESC LIMIT 1").map {
            rows => one(rows, s"DOLT_LOG($ref)")(readCommit)
        }

    def commit(client: SqlClient, message: String, all: Boolean)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        // `-Am` is one flag pair; `-m` alone commits what a previous DOLT_ADD staged.
        val args = if all then Chunk("-Am", message) else Chunk("-m", message)
        // In a transaction so another writer's commit cannot land between the call and the read-back of its own row.
        client.transaction {
            call(client, "DOLT_COMMIT", args).map { rows =>
                one(rows, "DOLT_COMMIT")(_.decode[String]("hash")).map(hash => commitAt(client, hash))
            }
        }
    end commit

    def log(client: SqlClient, ref: DoltRef, limit: Maybe[Int])(using Frame): Chunk[DoltCommit] < (Async & Abort[SqlException]) =
        // DOLT_LOG is a table function, so the ref is an argument to it. The dolt_log system table only reports the
        // CURRENT branch's history and would silently ignore the ref.
        val tail = limit.fold("")(n => s" LIMIT $n")
        // `--parents` is load-bearing: without it this function leaves `parents` and `refs` NULL, measured, so every
        // commit would read as a root with nothing naming it.
        query(client, s"SELECT $commitColumns FROM DOLT_LOG(${literal(ref.render)}, '--parents') ORDER BY commit_order DESC$tail").map {
            rows => decoded(rows)(readCommit)
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

    def diff(client: SqlClient, from: DoltRef, to: DoltRef, table: String)(using
        Frame
    ): Chunk[DoltDiff] < (Async & Abort[SqlException]) =
        query(client, s"SELECT * FROM DOLT_DIFF(${literal(from.render)}, ${literal(to.render)}, ${literal(table)})").map { rows =>
            decoded(rows) { row =>
                for
                    kind     <- row.decode[String]("diff_type")
                    fromRef  <- row.decode[Maybe[String]]("from_commit")
                    fromDate <- row.decode[Maybe[Instant]]("from_commit_date")
                    toRef    <- row.decode[Maybe[String]]("to_commit")
                    toDate   <- row.decode[Maybe[Instant]]("to_commit_date")
                yield DoltDiff(
                    // An unrecognised kind reads as Modified rather than failing the decode, which would lose the row.
                    DoltDiff.Kind.parse(kind).getOrElse(DoltDiff.Kind.Modified),
                    fromRef.getOrElse(from.render),
                    fromDate,
                    toRef.getOrElse(to.render),
                    toDate,
                    row
                )
            }
        }

    // --- Branches ---

    private val branchColumns =
        "name, hash, dirty, latest_commit_message, latest_committer, latest_committer_email, latest_commit_date, " +
            "latest_author, latest_author_email, latest_author_date, remote, branch"

    private def readBranch(row: SqlRow)(using Frame): DoltBranch < Abort[SqlDecodeException] =
        for
            name       <- row.decode[String]("name")
            hash       <- row.decode[String]("hash")
            dirty      <- row.decode[Maybe[Boolean]]("dirty")
            message    <- row.decode[Maybe[String]]("latest_commit_message")
            committer  <- row.decode[Maybe[String]]("latest_committer")
            commitMail <- row.decode[Maybe[String]]("latest_committer_email")
            commitDate <- row.decode[Maybe[Instant]]("latest_commit_date")
            author     <- row.decode[Maybe[String]]("latest_author")
            authorMail <- row.decode[Maybe[String]]("latest_author_email")
            authorDate <- row.decode[Maybe[Instant]]("latest_author_date")
            remote     <- row.decode[Maybe[String]]("remote")
            rbranch    <- row.decode[Maybe[String]]("branch")
        yield DoltBranch(
            name,
            DoltCommitHash(hash),
            // Absent reads as clean: the column is nullable.
            dirty.getOrElse(false),
            message.filter(_.nonEmpty),
            committer.filter(_.nonEmpty),
            commitMail.filter(_.nonEmpty),
            commitDate,
            author.filter(_.nonEmpty),
            authorMail.filter(_.nonEmpty),
            authorDate,
            // The server writes the empty string for a branch tracking nothing, which is not a remote named "".
            remote.filter(_.nonEmpty),
            rbranch.filter(_.nonEmpty)
        )

    def branches(client: SqlClient)(using Frame): Chunk[DoltBranch] < (Async & Abort[SqlException]) =
        query(client, s"SELECT $branchColumns FROM dolt_branches ORDER BY name").map(rows => decoded(rows)(readBranch))

    def branchNamed(client: SqlClient, name: String)(using Frame): DoltBranch < (Async & Abort[SqlException]) =
        query(client, s"SELECT $branchColumns FROM dolt_branches WHERE name = ?", Chunk(name)).map { rows =>
            one(rows, s"dolt_branches($name)")(readBranch)
        }

    def createBranch(client: SqlClient, name: String, from: DoltRef)(using Frame): DoltBranch < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_BRANCH", Chunk(name, from.render)).andThen(branchNamed(client, name))

    def deleteBranch(client: SqlClient, name: String, force: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_BRANCH", Chunk(if force then "-D" else "-d", name))

    // --- Integration ---

    def merge(client: SqlClient, from: DoltRef)(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        // The transaction is what makes a conflict a VALUE. Measured against 2.3.4: under autocommit a conflicting
        // merge raises and rolls back, and only inside an explicit transaction does it return `conflicts found`.
        client.transaction {
            call(client, "DOLT_MERGE", Chunk(from.render)).map(rows => interpretMerge(client, rows, "DOLT_MERGE"))
        }

    /** Reads the one row a merge or a pull answers with, and fills in whichever side of the outcome it implies. */
    private def interpretMerge(client: SqlClient, rows: Chunk[SqlRow], what: String)(using
        Frame
    ): DoltMerge < (Async & Abort[SqlException]) =
        one(rows, what) { row =>
            for
                hash        <- row.decode[Maybe[String]]("hash")
                fastForward <- row.decode[Boolean]("fast_forward")
                conflicts   <- row.decode[Long]("conflicts")
                message     <- row.decode[Maybe[String]]("message")
            yield (hash.getOrElse(""), fastForward, conflicts, message.getOrElse(""))
        }.map { (hash, fastForward, conflictCount, message) =>
            if conflictCount > 0 then
                conflicts(client).map { data =>
                    schemaConflicts(client).map(schema => DoltMerge.Conflicted(data, schema, message))
                }
            // An empty hash is how the server says it created no commit, which is the up-to-date case.
            else if hash.isEmpty then DoltMerge.UpToDate(message)
            else
                commitAt(client, hash).map { commit =>
                    if fastForward then DoltMerge.FastForward(commit, message) else DoltMerge.Merged(commit, message)
                }
        }

    def conflicts(client: SqlClient)(using Frame): Chunk[DoltConflictSummary] < (Async & Abort[SqlException]) =
        query(client, "SELECT `table`, num_conflicts FROM dolt_conflicts ORDER BY `table`").map { rows =>
            decoded(rows) { row =>
                for
                    table <- row.decode[String]("table")
                    count <- row.decode[Long]("num_conflicts")
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

    /** Resolves one table's conflicts and answers how many rows that cleared, which the procedure itself does not report. */
    def resolveConflicts(client: SqlClient, table: String, keeping: DoltResolution)(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        client.transaction {
            conflicts(client).map { before =>
                val pending = before.find(_.table == table).fold(0L)(_.count)
                callUnit(client, "DOLT_CONFLICTS_RESOLVE", Chunk(keeping.flag, table)).andThen(pending)
            }
        }

    def reset(client: SqlClient, to: DoltRef, mode: DoltResetMode)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_RESET", mode.flag.fold(Chunk(to.render))(flag => Chunk(flag, to.render)))

    def revert(client: SqlClient, ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        // Revert answers a status rather than a hash, so the new commit is read back from the log it just extended,
        // in a transaction so another writer's commit cannot land between the two.
        client.transaction {
            callUnit(client, "DOLT_REVERT", Chunk(ref.render)).andThen(commitAt(client, DoltRef.Head.render))
        }

    def cherryPick(client: SqlClient, ref: DoltRef)(using Frame): DoltCommit < (Async & Abort[SqlException]) =
        client.transaction {
            call(client, "DOLT_CHERRY_PICK", Chunk(ref.render)).map { rows =>
                one(rows, "DOLT_CHERRY_PICK")(_.decode[String]("hash")).map(hash => commitAt(client, hash))
            }
        }

    // --- Tags ---

    private val tagColumns = "tag_name, tag_hash, tagger, email, date, message"

    private def readTag(row: SqlRow)(using Frame): DoltTag < Abort[SqlDecodeException] =
        for
            name    <- row.decode[String]("tag_name")
            hash    <- row.decode[String]("tag_hash")
            tagger  <- row.decode[String]("tagger")
            email   <- row.decode[String]("email")
            date    <- row.decode[Instant]("date")
            message <- row.decode[Maybe[String]]("message")
        yield DoltTag(name, DoltCommitHash(hash), tagger, email, date, message.filter(_.nonEmpty))

    def tag(client: SqlClient, name: String, ref: DoltRef, message: Maybe[String])(using
        Frame
    ): DoltTag < (Async & Abort[SqlException]) =
        val args = message.fold(Chunk(name, ref.render))(m => Chunk(name, ref.render, "-m", m))
        callUnit(client, "DOLT_TAG", args).andThen {
            query(client, s"SELECT $tagColumns FROM dolt_tags WHERE tag_name = ?", Chunk(name)).map { rows =>
                one(rows, s"dolt_tags($name)")(readTag)
            }
        }
    end tag

    def tags(client: SqlClient)(using Frame): Chunk[DoltTag] < (Async & Abort[SqlException]) =
        query(client, s"SELECT $tagColumns FROM dolt_tags ORDER BY tag_name").map(rows => decoded(rows)(readTag))

    def deleteTag(client: SqlClient, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_TAG", Chunk("-d", name))

    // --- Remotes ---

    private def readRemote(row: SqlRow)(using Frame): DoltRemote < Abort[SqlDecodeException] =
        for
            name   <- row.decode[String]("name")
            url    <- row.decode[String]("url")
            specs  <- row.decode[Maybe[String]]("fetch_specs")
            params <- row.decode[Maybe[String]]("params")
        yield DoltRemote(
            name,
            url,
            // Stored as a JSON array of strings.
            specs.fold(Chunk.empty[String])(jsonStringArray),
            params.filter(p => p.nonEmpty && p != "{}")
        )

    /** The elements of a JSON array of strings, hand-split because this flat shape is the only JSON this module reads. */
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
        callUnit(client, "DOLT_REMOTE", Chunk("add", name, url)).andThen {
            query(client, "SELECT name, url, fetch_specs, params FROM dolt_remotes WHERE name = ?", Chunk(name)).map { rows =>
                one(rows, s"dolt_remotes($name)")(readRemote)
            }
        }

    def removeRemote(client: SqlClient, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_REMOTE", Chunk("remove", name))

    def push(client: SqlClient, remote: String, branch: String, force: Boolean)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        val args = if force then Chunk("--force", remote, branch) else Chunk(remote, branch)
        callUnit(client, "DOLT_PUSH", args)
    end push

    def fetch(client: SqlClient, remote: String, branch: Maybe[String])(using Frame): Unit < (Async & Abort[SqlException]) =
        callUnit(client, "DOLT_FETCH", branch.fold(Chunk(remote))(b => Chunk(remote, b)))

    def pull(client: SqlClient, remote: String, branch: Maybe[String])(using Frame): DoltMerge < (Async & Abort[SqlException]) =
        // Same reason merge opens one: a pull that conflicts has to leave the conflicts readable rather than raise.
        client.transaction {
            call(client, "DOLT_PULL", branch.fold(Chunk(remote))(b => Chunk(remote, b))).map { rows =>
                interpretMerge(client, rows, "DOLT_PULL")
            }
        }

end DoltStatements
