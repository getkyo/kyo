package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** The engine driven from WebAssembly instead of a loaded native library.
  *
  * The leaves are the cases where the WebAssembly path rebuilds one of this build's own C wrappers: an embedded NUL in
  * text, a blob of arbitrary bytes, an int64 past a double's exact range, a declared type that is absent rather than
  * empty, a two-statement string. Each is wrong in a way a `SELECT 1` would not show.
  */
class DoltLiteWasmTest extends Test:

    /** Forces the WebAssembly transport, loads the module, and runs `f` against an ordinary client. The property MUST be
      * set before anything touches the binding: a generated companion builds its dispatch table once, in a `val`, the
      * first time it is loaded, so setting it afterwards silently does nothing.
      */
    private def withWasm[A](f: Dolt => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException | DoltLiteWasmUnavailableException]) =
        // Both transports read what the staging step placed, so where the engine is not published for this
        // platform neither the native nor the WebAssembly one has anything to reach.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        Sync.defer(java.lang.System.setProperty("kyo.ffi.js.transport", "wasm")).andThen {
            DoltLiteWasm.init.andThen {
                Scope.run {
                    SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                        DB.run(client)(Dolt.use(f))
                    }
                }
            }
        }
    end withWasm

    "the engine runs from WebAssembly, with version control intact" in {
        withWasm { dolt =>
            for
                _      <- dolt.executeRaw("CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
                _      <- dolt.executeRaw("INSERT INTO person VALUES (1, 'alice')")
                commit <- dolt.commit("seed")
                _      <- dolt.createBranch("work")
                _      <- dolt.onBranch("work")(
                    dolt.executeRaw("UPDATE person SET name = 'bob' WHERE id = 1").andThen(dolt.commit("rename"))
                )
                onWork <- dolt.onBranch("work")(dolt.query("SELECT name FROM person").map(_(0).decode[String](0)))
                onMain <- dolt.query("SELECT name FROM person").map(_(0).decode[String](0))
            yield
                assert(commit.message == "seed", commit.message)
                assert(onWork == "bob", s"on the branch, got $onWork")
                assert(onMain == "alice", s"back on main, got $onMain")
        }
    }

    "text carrying an embedded NUL survives the heap round trip" in {
        withWasm { dolt =>
            // A C-string read truncates at the first NUL, so the length has to come from sqlite3_column_bytes.
            for
                _     <- dolt.executeRaw("CREATE TABLE t (v TEXT)")
                _     <- dolt.execute(sql"INSERT INTO t VALUES (${"a\u0000b"})")
                value <- dolt.query("SELECT v FROM t").map(_(0).decode[String](0))
            yield assert(value == "a\u0000b", s"got ${value.map(_.toInt).mkString(",")}")
        }
    }

    "a blob of arbitrary bytes survives the heap round trip" in {
        withWasm { dolt =>
            val bytes = Span.fromUnsafe(Array[Byte](0, -1, 17, 0, 126))
            for
                _    <- dolt.executeRaw("CREATE TABLE b (v BLOB)")
                _    <- dolt.execute(sql"INSERT INTO b VALUES ($bytes)")
                back <- dolt.query("SELECT v FROM b").map(_(0).decode[Span[Byte]](0))
            yield assert(back.is(bytes), s"got ${back.toArray.mkString(",")}")
            end for
        }
    }

    "an int64 beyond a double's exact range round-trips" in {
        withWasm { dolt =>
            // Longs cross this boundary as BigInt. A transport that dropped them onto a double would lose this
            // value's low bit.
            val big = 9007199254740993L
            for
                _    <- dolt.executeRaw("CREATE TABLE n (v INTEGER)")
                _    <- dolt.execute(sql"INSERT INTO n VALUES ($big)")
                back <- dolt.query("SELECT v FROM n").map(_(0).decode[Long](0))
            yield assert(back == big, s"got $back")
            end for
        }
    }

    "a declared type that is absent is not the empty string" in {
        withWasm { dolt =>
            // kyo_sqlite3_column_decltype_bytes answers -1 for a column with no declared type, which every
            // expression has. Collapsing that to "" would send the codec down the wrong branch.
            for
                _        <- dolt.executeRaw("CREATE TABLE d (v TEXT)")
                _        <- dolt.executeRaw("INSERT INTO d VALUES ('x')")
                rows     <- dolt.query("SELECT v, v || 'y' FROM d")
                declared <- rows(0).decode[String](0)
                computed <- rows(0).decode[String](1)
            yield
                assert(declared == "x", declared)
                assert(computed == "xy", computed)
        }
    }

    "a string holding a second statement is refused" in {
        withWasm { dolt =>
            // The WebAssembly rebuild of kyo_sqlite3_prepare_one walks the tail pointer in the heap to decide this.
            // Matched on the exact exception, not on `isFailure`, which also passes when the connection never opened.
            Abort.run[SqlException](dolt.query("SELECT 1; SELECT 2")).map {
                case Result.Failure(_: SqliteMultipleStatementsException) => assert(true)
                case other => assert(false, s"expected SqliteMultipleStatementsException, got $other")
            }
        }
    }

    "a trailing semicolon and whitespace are not a second statement" in {
        withWasm { dolt =>
            dolt.query("SELECT 1;  ").map(rows => assert(rows.size == 1, s"got ${rows.size} rows"))
        }
    }

end DoltLiteWasmTest
