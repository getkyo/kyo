package kyo.internal.sqlite

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** That the generated binding actually reaches the vendored library, end to end.
  *
  * Deliberately below the driver: no connection, no codec, no dialect. Everything above assumes these calls work, so when a higher suite
  * goes red this suite says whether the binding is the reason. It also pins the two conventions the binding cannot express in its types:
  * the one-based parameter index, and the result code living on the connection rather than travelling with the handle.
  */
class SqliteBindingsTest extends Test:

    // Unsafe: the binding tier is the unsafe one by construction, and this suite exercises it directly rather than
    // through the driver that would otherwise own the boundary.
    import AllowUnsafe.embrace.danger

    private val OpenReadWrite = 0x00000002
    private val OpenCreate    = 0x00000004
    private val Ok            = 0
    private val Row           = 100
    private val Done          = 101

    private def bindings(using Frame): SqliteBindings < Sync = Sync.Unsafe.defer(Ffi.load[SqliteBindings])

    private def open(b: SqliteBindings, path: String, flags: Int)(using Frame): Maybe[Ffi.Handle[SqliteDb]] < Async =
        // "" rather than null: a String argument is marshalled by reading its bytes, so null throws before the call.
        Sync.Unsafe.defer(b.openV2(path, flags, "")).map(_.safe.get)

    private def close(b: SqliteBindings, db: Ffi.Handle[SqliteDb])(using Frame): Unit < Async =
        Sync.Unsafe.defer(b.closeV2(db)).map(_.safe.get).unit

    private def exec(b: SqliteBindings, db: Ffi.Handle[SqliteDb], sql: String)(using Frame): Int < Async =
        Sync.Unsafe.defer(b.execSimple(db, sql)).map(_.safe.get)

    private def prepare(b: SqliteBindings, db: Ffi.Handle[SqliteDb], sql: String)(using Frame): Maybe[Ffi.Handle[SqliteStmt]] < Async =
        Sync.Unsafe.defer(b.prepareOne(db, sql, -1)).map(_.safe.get)

    private def step(b: SqliteBindings, stmt: Ffi.Handle[SqliteStmt])(using Frame): Int < Async =
        Sync.Unsafe.defer(b.step(stmt)).map(_.safe.get)

    /** Opens an in-memory database, configures it, and closes it whatever the body does. */
    private def withDb[A](f: (SqliteBindings, Ffi.Handle[SqliteDb]) => A < Async)(using Frame): A < Async =
        Scope.run {
            bindings.flatMap { b =>
                open(b, ":memory:", OpenReadWrite | OpenCreate).flatMap {
                    case Absent => Abort.panic(new AssertionError("sqlite3_open_v2 could not allocate a handle"))
                    case Present(db) =>
                        Scope.ensure(close(b, db)).andThen {
                            Sync.Unsafe.defer(b.configureConnection(db, 5000)).map(_.safe.get).flatMap(_ => f(b, db))
                        }
                }
            }
        }

    "the vendored library is the version this module pins" in {
        bindings.map { b =>
            // 3.53.4 renders as 3*1000000 + 53*1000 + 4. Pinned rather than compared loosely: the whole point of
            // vendoring is that the version is a constant, and a build that fell back to a host library answers another.
            assert(b.libversionNumber() == 3053004, s"expected the vendored 3.53.4 (3053004), got ${b.libversionNumber()}")
        }
    }

    "a connection opens, configures, and closes" in {
        withDb { (b, db) =>
            exec(b, db, "SELECT 1").map(rc => assert(rc == Ok, s"a trivial statement answered $rc"))
        }
    }

    "the open result code is readable off the connection rather than returned beside it" in {
        bindings.flatMap { b =>
            // A directory that cannot exist, so the open fails while still yielding a handle to read the failure from.
            open(b, "/nonexistent-kyo-sqlite-dir/db.sqlite", OpenReadWrite).flatMap {
                case Absent => Abort.panic(new AssertionError("expected a handle even on a failed open"))
                case Present(db) =>
                    val code = b.extendedErrcode(db)
                    val msg  = b.errmsg(db).value
                    close(b, db).map { _ =>
                        assert(code != Ok, "a failed open must leave a non-OK code on the handle")
                        assert(msg.contains("unable to open"), s"expected the open failure text, got '$msg'")
                    }
            }
        }
    }

    "configure_connection disables the double-quoted-string fallback" in {
        withDb { (b, db) =>
            for
                _ <- exec(b, db, "CREATE TABLE t(a INT)")
                // Without the DQS flags cleared this prepares and answers the STRING 'typo' for every row.
                prepared <- prepare(b, db, "SELECT \"typo\" FROM t")
            yield
                assert(prepared.isEmpty, "a quoted identifier that does not resolve must not prepare")
                assert(b.extendedErrcode(db) != Ok, "the refusal must be a real SQL error, not the multi-statement refusal")
                assert(b.errmsg(db).value.contains("no such column"), s"expected 'no such column', got '${b.errmsg(db).value}'")
        }
    }

    "configure_connection turns foreign keys on" in {
        withDb { (b, db) =>
            for
                _   <- exec(b, db, "CREATE TABLE parent(id INTEGER PRIMARY KEY)")
                _   <- exec(b, db, "CREATE TABLE child(ref INT REFERENCES parent(id))")
                rc  <- exec(b, db, "INSERT INTO child VALUES(9)")
                msg <- Sync.defer(b.errmsg(db).value)
            yield
                // Enforcement is off by default, so without the pragma this insert succeeds and the leaf that
                // asserts a referential failure would pass by enforcing nothing.
                assert(rc != Ok, "a dangling foreign key must be refused")
                assert(msg.contains("FOREIGN KEY"), s"expected a foreign-key failure, got '$msg'")
        }
    }

    "configure_connection turns on the extended result codes the constraint classes need" in {
        withDb { (b, db) =>
            for
                _  <- exec(b, db, "CREATE TABLE u(x INT UNIQUE)")
                _  <- exec(b, db, "INSERT INTO u VALUES(1)")
                rc <- exec(b, db, "INSERT INTO u VALUES(1)")
            yield
                // 2067 is SQLITE_CONSTRAINT_UNIQUE. Without extended codes this is 19 for every constraint class,
                // which cannot tell a unique violation from a check or a foreign key.
                assert(rc != Ok, "a unique violation must fail")
                assert(b.extendedErrcode(db) == 2067, s"expected SQLITE_CONSTRAINT_UNIQUE (2067), got ${b.extendedErrcode(db)}")
        }
    }

    "prepare refuses a string holding a second statement, distinguishably from a SQL error" in {
        withDb { (b, db) =>
            for
                _     <- exec(b, db, "CREATE TABLE t(a INT)")
                multi <- prepare(b, db, "SELECT a FROM t; DROP TABLE t")
                multiCode = b.extendedErrcode(db)
                // A trailing semicolon and whitespace are not a second statement.
                trailing <- prepare(b, db, "SELECT a FROM t;  ")
            yield
                assert(multi.isEmpty, "a trailing statement must be refused rather than silently dropped")
                assert(multiCode == Ok, s"the multi-statement refusal is told from a SQL error by the code staying OK, got $multiCode")
                assert(trailing.nonEmpty, "a trailing semicolon is not a second statement")
                trailing.foreach(stmt => discard(b.finalizeStmt(stmt)))
                succeed
        }
    }

    "a row round-trips through bind and column, including bytes a C string would truncate" in {
        withDb { (b, db) =>
            for
                _        <- exec(b, db, "CREATE TABLE t(i INTEGER, d REAL, s TEXT, blob BLOB)")
                inserted <- prepare(b, db, "INSERT INTO t VALUES(?, ?, ?, ?)")
                stmtIn = inserted.getOrElse(throw new AssertionError("the insert did not prepare"))
                _ = Buffer.useArray("a b".getBytes("UTF-8")) { buf =>
                    // Parameter indices are ONE-based, unlike the zero-based column indices read back below.
                    discard(b.bindInt64(stmtIn, 1, Long.MaxValue))
                    discard(b.bindDouble(stmtIn, 2, 0.1))
                    discard(b.bindTextCopy(stmtIn, 3, buf, 3))
                }
                _ = Buffer.useArray(Array[Byte](0x00, 0xff.toByte, 0x00, 0x41)) { buf =>
                    discard(b.bindBlobCopy(stmtIn, 4, buf, 4))
                }
                insertRc <- step(b, stmtIn)
                changed = b.changes64(db)
                _       = discard(b.finalizeStmt(stmtIn))
                selected <- prepare(b, db, "SELECT i, d, s, blob FROM t")
                stmt = selected.getOrElse(throw new AssertionError("the select did not prepare"))
                rc <- step(b, stmt)
            yield
                assert(insertRc == Done, s"the insert step answered $insertRc")
                assert(changed == 1L, s"changes64 answered $changed")
                assert(rc == Row, s"step answered $rc rather than SQLITE_ROW")
                assert(b.columnInt64(stmt, 0) == Long.MaxValue, s"int64 round-trip gave ${b.columnInt64(stmt, 0)}")
                assert(b.columnDouble(stmt, 1) == 0.1, s"double round-trip gave ${b.columnDouble(stmt, 1)}")

                // The embedded NUL is the point: read as a C string this is one byte, not three.
                val text = readBytes(b, stmt, 2, textual = true)
                assert(text.length == 3, s"expected 3 bytes of text, got ${text.length}")
                assert(text.sameElements("a b".getBytes("UTF-8")), "text with an embedded NUL did not round-trip")

                val blob = readBytes(b, stmt, 3, textual = false)
                assert(blob.sameElements(Array[Byte](0x00, 0xff.toByte, 0x00, 0x41)), "blob did not round-trip")

                discard(b.finalizeStmt(stmt))
                succeed
        }
    }

    "decltype tells a declared column from an expression, and storage class is per value" in {
        withDb { (b, db) =>
            for
                // The multi-word name is the type mapping's device: it carries the kind for decltype while forcing
                // TEXT affinity, so a value that looks numeric is not rewritten on the way in.
                _        <- exec(b, db, "CREATE TABLE t(dec 'DECIMAL TEXT(38,10)', plain INT)")
                _        <- exec(b, db, "INSERT INTO t VALUES('98765432109876.543210', 1)")
                selected <- prepare(b, db, "SELECT dec, plain, plain + 0 FROM t")
                stmt = selected.getOrElse(throw new AssertionError("the select did not prepare"))
                rc <- step(b, stmt)
            yield
                assert(rc == Row)
                assert(b.columnCount(stmt) == 3)
                assert(b.columnName(stmt, 0).value == "dec")

                assert(readDecltype(b, stmt, 0) == Present("DECIMAL TEXT(38,10)"), "the declared type must come back verbatim")
                assert(readDecltype(b, stmt, 1) == Present("INT"))
                // An expression is traceable to no declared column, which is the distinction the codec dispatches on.
                assert(readDecltype(b, stmt, 2) == Absent, "an expression column has no declared type")

                // The decimal kept its scale and its digits past a double, because the declared name took TEXT affinity.
                val dec = new String(readBytes(b, stmt, 0, textual = true), "UTF-8")
                assert(dec == "98765432109876.543210", s"the decimal was rewritten to '$dec'")
                assert(b.columnType(stmt, 0) == 3, s"expected the TEXT storage class, got ${b.columnType(stmt, 0)}")
                assert(b.columnType(stmt, 1) == 1, s"expected the INTEGER storage class, got ${b.columnType(stmt, 1)}")

                discard(b.finalizeStmt(stmt))
                succeed
        }
    }

    "a refused write inside a read-only transaction leaves the connection usable" in {
        // The library's own behaviour on the failure path, pinned here so a wedge in the driver above cannot be blamed on it:
        // the refusal is reported, the transaction still rolls back, and a later write on the same handle lands.
        withDb { (b, db) =>
            for
                _        <- exec(b, db, "CREATE TABLE t (id INT)")
                beginRc  <- exec(b, db, "BEGIN")
                pragmaRc <- exec(b, db, "PRAGMA query_only = ON")
                prepared <- prepare(b, db, "INSERT INTO t VALUES (1)")
                stepRc <- prepared match
                    case Present(stmt) => step(b, stmt)
                    case Absent        => Sync.defer(-1)
                errAfter = b.extendedErrcode(db)
                _        = prepared.foreach(stmt => discard(b.finalizeStmt(stmt)))
                rollbackRc <- exec(b, db, "ROLLBACK")
                offRc      <- exec(b, db, "PRAGMA query_only = OFF")
                writeRc    <- exec(b, db, "INSERT INTO t VALUES (2)")
            yield
                assert(beginRc == Ok, s"BEGIN answered $beginRc")
                assert(pragmaRc == Ok, s"PRAGMA query_only=ON answered $pragmaRc")
                // Whether the refusal lands at prepare or at step is the fact this pins.
                assert(prepared.isEmpty || stepRc != Done, s"the write must be refused, prepared=${prepared.nonEmpty} stepRc=$stepRc")
                assert(errAfter != Ok, "the refusal must leave a code on the connection")
                assert(rollbackRc == Ok, s"ROLLBACK after a refused write answered $rollbackRc")
                assert(offRc == Ok, s"PRAGMA query_only=OFF answered $offRc")
                assert(writeRc == Ok, s"the later write answered $writeRc")
            end for
        }
    }

    /** Reads a column's bytes, sized by the length the probing call reports rather than by a guess. */
    private def readBytes(b: SqliteBindings, stmt: Ffi.Handle[SqliteStmt], idx: Int, textual: Boolean): Array[Byte] =
        def read(dst: Buffer[Byte], cap: Int): Int =
            if textual then b.columnTextBytes(stmt, idx, dst, cap) else b.columnBlobBytes(stmt, idx, dst, cap)
        val size = Buffer.use[Byte, Int](0)(probe => read(probe, 0))
        if size <= 0 then Array.emptyByteArray
        else
            Buffer.use[Byte, Array[Byte]](size) { buf =>
                discard(read(buf, size))
                Array.tabulate(size)(i => buf.get(i))
            }
        end if
    end readBytes

    /** The declared type, with absent distinguished from empty: -1 is "no declared type", which is not a zero-length one. */
    private def readDecltype(b: SqliteBindings, stmt: Ffi.Handle[SqliteStmt], idx: Int): Maybe[String] =
        val size = Buffer.use[Byte, Int](0)(probe => b.columnDecltypeBytes(stmt, idx, probe, 0))
        if size < 0 then Absent
        else if size == 0 then Present("")
        else
            Present(Buffer.use[Byte, String](size) { buf =>
                discard(b.columnDecltypeBytes(stmt, idx, buf, size))
                new String(Array.tabulate(size)(i => buf.get(i)), "UTF-8")
            })
        end if
    end readDecltype

end SqliteBindingsTest
