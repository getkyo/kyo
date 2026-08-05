package kyo.postgres
import kyo.*
import kyo.Sql.*
import kyo.SqlCodec
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend
import kyo.internal.postgres.PostgresDialect

/** Postgres-only feature tests.
  *
  * Features covered:
  *   - ilike on PG (native ILIKE operator)
  *   - ++ concat on PG (rendered as `||`)
  *   - onConflictDoNothing is idempotent on PG
  *   - onConflictDoUpdate updates existing row on PG
  *   - FULL OUTER JOIN (native; not the MySQL LEFT/RIGHT UNION emulation)
  *   - RETURNING <pk> (auto-emitted by PG renderer on Insert with auto-key)
  *   - INSERT … ON CONFLICT (<col>) DO UPDATE SET … (PG upsert)
  */
class SqlPostgresOnlyTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    case class Person(id: Long, name: String, age: Int) derives SqlSchema
    case class Dept(id: Long, budget: Long) derives SqlSchema

    /** The shape an hstore column is reachable in: a derived row with a [[kyo.PostgresTypes.HStore]] field, whose column codec the
      * engine module ships.
      */
    case class Attrs(id: Int, attrs: kyo.PostgresTypes.HStore) derives SqlSchema

    /** The same reachable shape for a range column: a derived row with a [[kyo.PostgresTypes.Range]] field. */
    case class Spanned(id: Int, span: kyo.PostgresTypes.Range[BigDecimal]) derives SqlSchema

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withPgClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException] & DB))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](SqlClient.init(pgUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(DB.run(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    // ── ilike on PG uses native ILIKE ─────────────────────────────────────────

    "Leaf 16: ilike on PG returns expected rows using native ILIKE" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'Alice', 30), (2, 'BOB', 25), (3, 'carol', 28)""")
                        // ilike should match case-insensitively: 'alice%' matches 'Alice'
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.name.ilike("alice%"))
                            .select(c => c.p.name)
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 ilike match for 'alice%', got: ${rows.size}")
                        assert(rows.head == "Alice", s"Expected 'Alice', got: '${rows.head}'")
                }
            }
        }
    }

    // ── ++ concat on PG uses || operator ──────────────────────────────────────

    "Leaf 18: ++ concat on PG returns expected concatenated string using ||" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        // Verify the rendered SQL uses || for concat on PG
                        _ =
                            val rendered = Sql.from[Person]("p").select(c => c.p.name ++ " rocks")
                                .render(PostgresDialect)
                            assert(
                                rendered.onlySql.get.contains("||"),
                                s"Expected || in PG concat SQL, got: ${rendered.onlySql.get}"
                            )
                        // Execute the concat query against live PG
                        rows <- Sql
                            .from[Person]("p")
                            .where(c => c.p.id == 1L)
                            .select(c => c.p.name ++ " rocks")
                            .run
                    yield
                        assert(rows.size == 1, s"Expected 1 row, got: ${rows.size}")
                        assert(rows.head == "alice rocks", s"Expected 'alice rocks', got: '${rows.head}'")
                }
            }
        }
    }

    // ── onConflictDoNothing is idempotent on PG ──────────────────────────────

    "Leaf 20: onConflictDoNothing is idempotent on PG (duplicate row leaves table unchanged)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        // Insert duplicate with ON CONFLICT DO NOTHING, should not error or change data
                        result <- Sql
                            .insert[Person]
                            .values(Person(1L, "alice-duplicate", 99))
                            .onConflictDoNothing()
                            .run
                        rows <- Sql.from[Person]("p").run
                    yield
                        // Table must still have exactly 1 row with original data
                        assert(rows.size == 1, s"Expected 1 row after idempotent insert, got: ${rows.size}")
                        assert(rows.head.name == "alice", s"Expected original 'alice', got: '${rows.head.name}'")
                        assert(rows.head.age == 30, s"Expected original age 30, got: ${rows.head.age}")
                }
            }
        }
    }

    // ── onConflictDoUpdate updates existing row on PG ──────────────────────────

    "Leaf 22: onConflictDoUpdate updates existing row on PG via ON CONFLICT … DO UPDATE" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30)""")
                        // Upsert: conflict on id=1, update age to 31
                        _ <- Sql
                            .insert[Person]
                            .values(Person(1L, "alice-upserted", 31))
                            .onConflictDoUpdate(_.id)(c => c.age := Sql.Excluded(c.age))
                            .run
                        rows <- Sql.from[Person]("p").run
                    yield
                        assert(rows.size == 1, s"Expected 1 row after upsert, got: ${rows.size}")
                        assert(rows.head.age == 31, s"Expected updated age 31, got: ${rows.head.age}")
                }
            }
        }
    }

    // ── FULL OUTER JOIN on PG, the native keyword rather than a UNION synthesis ──

    "PG FULL OUTER JOIN returns rows from both sides" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                withPgClient(ctx) { client =>
                    for
                        _ <- client.executeRaw(
                            """CREATE TABLE person (id BIGINT PRIMARY KEY, name TEXT NOT NULL, age INT NOT NULL)"""
                        )
                        _ <- client.executeRaw(
                            """CREATE TABLE dept (id BIGINT PRIMARY KEY, budget BIGINT NOT NULL)"""
                        )
                        _ <- client.executeRaw("""INSERT INTO person VALUES (1, 'alice', 30), (3, 'carol', 28)""")
                        _ <- client.executeRaw("""INSERT INTO dept VALUES (1, 100000), (2, 200000)""")
                        // The projection reads BOTH sides, and a full outer join NULL-extends both, so each column comes back
                        // `Maybe`. That is the point of the leaf: person ids {1,3} FULL OUTER JOIN dept ids {1,2} on id yields
                        // three rows, one matched and one unmatched from each side, so two of the six cells are SQL NULL and a
                        // non-nullable decode of either raises `SqlDecodeColumnAbsentException`.
                        joined = Sql.from[Person]("p")
                            .fullOuterJoin(Sql.from[Dept]("d"))
                            .on(j => j.p.id == j.d.id)
                            .select(j => (j.p.name, j.d.budget))
                        _ = assert(
                            joined.render(PostgresDialect).onlySql.get.contains("FULL OUTER JOIN"),
                            s"Expected FULL OUTER JOIN in PG SQL, got: ${joined.render(PostgresDialect).onlySql.get}"
                        )
                        rows <- joined.run
                        // The other half of the property, and the reason the nullable typing is not a precaution. This is the SAME
                        // statement, decoded into a non-nullable `Long`. The unmatched dept row carries SQL NULL there, so the
                        // decode aborts. Read as a pair: the leaf above proves the nullable decode answers, and this one proves the
                        // non-nullable decode of the identical bytes does not.
                        budgets = Sql.from[Person]("p")
                            .fullOuterJoin(Sql.from[Dept]("d"))
                            .on(j => j.p.id == j.d.id)
                            .select(j => j.d.budget)
                        nonNullable <- Abort.run[SqlException](
                            client.internalExecuteQuery[Long](
                                budgets.render(PostgresDialect).onlySql.get,
                                Chunk.empty,
                                client.config
                            )
                        )
                    yield
                        val expected = Set(
                            (Present("alice"), Present(100000L)),
                            (Present("carol"), Absent),
                            (Absent, Present(200000L))
                        )
                        assert(rows.size == 3, s"Expected 3 FULL OUTER JOIN rows, got: ${rows.size}")
                        assert(
                            rows.toSeq.toSet == expected,
                            s"Expected the matched pair plus one unmatched row from each side, got: ${rows.toSeq}"
                        )
                        nonNullable match
                            case Result.Failure(_: SqlDecodeColumnAbsentException) => succeed
                            case other =>
                                fail(s"Expected a non-nullable decode of the NULL-extended side to abort, got: $other")
                        end match
                }
            }
        }
    }

    // ── Wire forms the decoders model rather than observe ──────────────────────
    //
    // Two decode paths are written from the PostgreSQL documentation rather than from captured traffic:
    // `bytea_output = escape` and `array_out`'s `{...}` rendering. A unit test over hand-built bytes confirms
    // the model against itself; only the server settles whether the model is the format, which is what these
    // leaves ask it.
    //
    // Every one of them runs on a single-connection pool. One of the two depends on a SESSION setting, and a
    // default pool can serve the `SET` and the `SELECT` from different connections, which would make the leaf
    // pass or fail by which connection it drew.

    private val singleConnection = SqlConfig.default.copy(maxConnections = 1, minConnections = 1)

    "a bytea column round-trips through the escape rendering, which bytea_output selects per session" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    // Every byte class the escape rendering treats differently: NUL and 0xff escape as octal,
                    // the backslash doubles, the quote and the printable bytes stay literal.
                    val payload = Span.from(Array[Byte](0x00, 0x01, 0x5c, 0x27, 0x7f, 0xff.toByte, 'a'.toByte))
                    for
                        _ <- client.executeRaw("CREATE TABLE blob_rt (id INT PRIMARY KEY, payload BYTEA NOT NULL)")
                        _ <- client.execute(sql"INSERT INTO blob_rt (id, payload) VALUES (1, $payload)")
                        _ <- client.executeRaw("SET bytea_output = escape")
                        // simpleQuery is the text-format path, so this exercises the escape rendering AND the
                        // Text arm of the bytea decoder at once, which is how a user would meet both.
                        rows    <- client.simpleQuery("SELECT payload FROM blob_rt WHERE id = 1")
                        decoded <- rows.head.decode[Span[Byte]]
                    yield
                        assert(rows.size == 1, s"expected 1 row, got ${rows.size}")
                        assert(
                            decoded.toArray.toSeq == payload.toArray.toSeq,
                            s"escape round-trip gave ${decoded.toArray.toSeq}, expected ${payload.toArray.toSeq}"
                        )
                    end for
                }
            }
        }
    }

    "a bytea column round-trips through the hex rendering too, so the dispatch covers both" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    val payload = Span.from(Array[Byte](0x00, 0x5c, 0xff.toByte))
                    for
                        _       <- client.executeRaw("CREATE TABLE blob_hex (id INT PRIMARY KEY, payload BYTEA NOT NULL)")
                        _       <- client.execute(sql"INSERT INTO blob_hex (id, payload) VALUES (1, $payload)")
                        _       <- client.executeRaw("SET bytea_output = hex")
                        rows    <- client.simpleQuery("SELECT payload FROM blob_hex WHERE id = 1")
                        decoded <- rows.head.decode[Span[Byte]]
                    yield assert(
                        decoded.toArray.toSeq == payload.toArray.toSeq,
                        s"hex round-trip gave ${decoded.toArray.toSeq}"
                    )
                    end for
                }
            }
        }
    }

    "a text[] column read through simpleQuery decodes from the server's own array rendering" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    for
                        _ <- client.executeRaw("CREATE TABLE arr_rt (id INT PRIMARY KEY, tags TEXT[] NOT NULL)")
                        // Elements array_out must quote: one holding the delimiter, one holding a quote, one
                        // empty, and the literal word NULL which must not read back as a null element.
                        _ <- client.executeRaw(
                            """INSERT INTO arr_rt VALUES (1, ARRAY['a', 'b,c', 'd"e', '', 'NULL'])"""
                        )
                        rows    <- client.simpleQuery("SELECT tags FROM arr_rt WHERE id = 1")
                        decoded <- rows.head.decode[Chunk[String]]
                    yield assert(
                        decoded == Chunk("a", "b,c", "d\"e", "", "NULL"),
                        s"text array through simpleQuery decoded as $decoded"
                    )
                    end for
                }
            }
        }
    }

    "an int4[] column read through simpleQuery decodes from the server's own array rendering" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    for
                        _       <- client.executeRaw("CREATE TABLE iarr_rt (id INT PRIMARY KEY, ns INT[] NOT NULL)")
                        _       <- client.executeRaw("INSERT INTO iarr_rt VALUES (1, ARRAY[1, -2, 2147483647])")
                        rows    <- client.simpleQuery("SELECT ns FROM iarr_rt WHERE id = 1")
                        decoded <- rows.head.decode[Chunk[Int]]
                    yield assert(decoded == Chunk(1, -2, 2147483647), s"int array through simpleQuery decoded as $decoded")
                    end for
                }
            }
        }
    }

    "an hstore column read through simpleQuery decodes from the server's own rendering" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    for
                        // hstore is a contrib extension, so the column type only exists once it is installed.
                        _    <- client.executeRaw("CREATE EXTENSION IF NOT EXISTS hstore")
                        _    <- client.executeRaw("CREATE TABLE h_rt (id INT PRIMARY KEY, attrs HSTORE NOT NULL)")
                        _    <- client.executeRaw("""INSERT INTO h_rt VALUES (1, 'name=>alice, role=>admin'::hstore)""")
                        rows <- client.simpleQuery("SELECT id, attrs FROM h_rt WHERE id = 1")
                        // The engine's own `HStore` column is how the type is reachable: core ships no column
                        // for `Map`, so a bare `Map` field would be a compile error rather than a silent
                        // structural encoding.
                        decoded <- rows.head.decode[Attrs]
                    yield assert(
                        decoded.attrs == kyo.PostgresTypes.HStore(Map("name" -> Maybe("alice"), "role" -> Maybe("admin"))),
                        s"hstore through simpleQuery decoded as ${decoded.attrs}"
                    )
                    end for
                }
            }
        }
    }

    "an hstore value binds as a parameter when PostgresConfig.typeNames declares it" in {
        // The bind is the point: hstore has no builtin oid, so the parameter's oid can only come from the
        // session type registry populated from PostgresConfig.typeNames at startup. A raw-literal insert
        // never exercises that wiring; this leaf binds an HStore value through the real client entry point
        // and reads it back.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // The extension must exist before the typed client connects, because the startup pg_type
                // lookup fails the connect for a declared name the server does not have.
                SqlClient.initWith(pgUrl(ctx), singleConnection) { boot =>
                    boot.executeRaw("CREATE EXTENSION IF NOT EXISTS hstore").andThen(
                        boot.executeRaw("CREATE TABLE h_bind (id INT PRIMARY KEY, attrs HSTORE NOT NULL)")
                    )
                }.andThen {
                    val config = singleConnection.extension(PostgresConfig(typeNames = Set("hstore")))
                    SqlClient.initWith(pgUrl(ctx), config) { client =>
                        val attrs = kyo.PostgresTypes.HStore(Map("name" -> Maybe("alice"), "note" -> Maybe.empty))
                        for
                            _       <- client.execute(sql"INSERT INTO h_bind VALUES (1, $attrs)")
                            rows    <- client.simpleQuery("SELECT id, attrs FROM h_bind WHERE id = 1")
                            decoded <- rows.head.decode[Attrs]
                        yield assert(decoded.attrs == attrs, s"hstore bound through typeNames read back as ${decoded.attrs}")
                        end for
                    }
                }
            }
        }
    }

    "a numrange binds as a parameter and reads back through the server" in {
        // The bind is the point: a standalone numeric parameter goes out in the text wire form, but a range
        // payload holds binary elements, so this exercises the element position's binary numeric encoding
        // against the real server, and the read back through the extended protocol exercises the binary
        // decode of the same payload.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    val span = kyo.PostgresTypes.Range(
                        kyo.PostgresTypes.Range.Bound.Inclusive(BigDecimal("1.25")),
                        kyo.PostgresTypes.Range.Bound.Exclusive(BigDecimal("10.5"))
                    )
                    for
                        _    <- client.executeRaw("CREATE TABLE nr_rt (id INT PRIMARY KEY, span NUMRANGE NOT NULL)")
                        _    <- client.execute(sql"INSERT INTO nr_rt VALUES (1, $span)")
                        rows <- client.query(sql"SELECT id, span FROM nr_rt WHERE id = 1")
                        // Reached as a row field, like the hstore leaf above: the engine's payload types
                        // decode through a derived row rather than a bare single-column read.
                        decoded <- rows.head.decode[Spanned]
                    yield assert(decoded.span == span, s"numrange round-tripped as ${decoded.span}")
                    end for
                }
            }
        }
    }

    // ── PostgresTypes.custom as the escape hatch for a native address column ──────────
    //
    // kyo-sql carries no address type of its own; a caller wanting a native `inet` column reaches for
    // PostgresTypes.custom with a plain String and its own wire encoding. The two leaves below prove that
    // route against a real server rather than a hand-built payload: the shape a unit test cannot settle is
    // that the server really does put the prefix in the header's second byte and that the binary arm (what
    // `client.query`, the extended protocol, always requests) is the one a caller reaches.

    private def encodeInet(addr: String): Span[Byte] =
        if addr.indexOf(':') >= 0 then
            val groups    = addr.split(':').map(g => Integer.parseInt(g, 16))
            val addrBytes = groups.flatMap(g => Array[Byte](((g >>> 8) & 0xff).toByte, (g & 0xff).toByte))
            Span.from(Array[Byte](3, 128.toByte, 0, 16) ++ addrBytes)
        else
            val octets = addr.split('.').map(s => s.toInt.toByte)
            Span.from(Array[Byte](2, 32, 0, 4) ++ octets)

    private def decodeInetBinary(bytes: Span[Byte]): String =
        val family     = bytes(0).toInt & 0xff
        val prefixBits = bytes(1).toInt & 0xff
        val addrLen    = bytes(3).toInt & 0xff
        val hostWidth  = addrLen * 8
        val address = family match
            case 2 =>
                val a = bytes(4) & 0xff
                val b = bytes(5) & 0xff
                val c = bytes(6) & 0xff
                val d = bytes(7) & 0xff
                s"$a.$b.$c.$d"
            case 3 =>
                (0 until 8).map { i =>
                    val hi = bytes(4 + i * 2) & 0xff
                    val lo = bytes(5 + i * 2) & 0xff
                    Integer.toHexString((hi << 8) | lo)
                }.mkString(":")
            case other =>
                throw new IllegalArgumentException(s"unknown inet address family: $other")
        // Both formats state a network prefix, and dropping it is a silent value change rather than a lossy
        // rendering: `10.0.0.0/8` names 16 million addresses and its address part alone names one. A String
        // carries the suffix, so the codec keeps it rather than refusing the value.
        if prefixBits < hostWidth then s"$address/$prefixBits" else address
    end decodeInetBinary

    /** The custom-type schema a caller writes for a native address column.
      *
      * The read branches on the format the carrier reports: an extended-protocol result sends the binary struct, a simple query sends the
      * server's own `192.168.1.5/24` rendering, and this suite runs against a real server that produces both.
      */
    private def inetSchema: SqlSchema.Column[String] =
        val pg = kyo.internal.postgres.types.PostgresEncoder.dialectId
        kyo.PostgresTypes.custom[String] { (addr, w) =>
            w.extension(SqlCodec.Writer.Payload(pg, "inet", SqlCodec.Format.Binary, encodeInet(addr)))
        } { r =>
            val ext = r.nextExtension(pg, "inet")
            ext.format match
                case SqlCodec.Format.Binary => decodeInetBinary(ext.bytes)
                case SqlCodec.Format.Text   => new String(ext.bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        }
    end inetSchema

    "an inet value carrying a network prefix round-trips as text, since a plain String has no reason to refuse it" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    given SqlSchema.Column[String] = inetSchema
                    for
                        _       <- client.executeRaw("CREATE TABLE net_rt (id INT PRIMARY KEY, host INET NOT NULL)")
                        _       <- client.executeRaw("INSERT INTO net_rt VALUES (1, '192.168.1.5/24')")
                        rows    <- client.query(sql"SELECT host FROM net_rt WHERE id = 1")
                        decoded <- rows.head.decode[String](0)
                    yield assert(decoded == "192.168.1.5/24", s"prefixed host decoded as $decoded")
                    end for
                }
            }
        }
    }

    "a host address with no network prefix round-trips through the custom inet schema, IPv4 and IPv6 alike" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    given SqlSchema.Column[String] = inetSchema
                    for
                        _    <- client.executeRaw("CREATE TABLE host_rt (id INT PRIMARY KEY, host INET NOT NULL)")
                        _    <- client.executeRaw("INSERT INTO host_rt VALUES (1, '192.168.1.5'), (2, '2001:db8::1')")
                        rows <- client.query(sql"SELECT host FROM host_rt ORDER BY id")
                        v4   <- rows.head.decode[String](0)
                        v6   <- rows(1).decode[String](0)
                    yield
                        assert(v4 == "192.168.1.5", s"IPv4 host decoded as $v4")
                        // No `::` compression: the escape hatch's own codec renders raw groups rather than the
                        // RFC 5952 canonical form, because the rendering is the caller's to choose.
                        assert(v6 == "2001:db8:0:0:0:0:0:1", s"IPv6 host decoded as $v6")
                    end for
                }
            }
        }
    }

    "a native TIME column round-trips through SqlSchema[LocalTime]" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.initWith(pgUrl(ctx), singleConnection) { client =>
                    // Microseconds, because PostgreSQL `time` carries them and the binary wire form is
                    // microseconds-of-day. A text spelling of this schema cannot write the column at all: the
                    // parameter would go out tagged `text` (OID 25) against a `time` column.
                    val value = java.time.LocalTime.of(13, 45, 30, 123456000)
                    for
                        _       <- client.executeRaw("CREATE TABLE clock_rt (id INT PRIMARY KEY, at TIME NOT NULL)")
                        _       <- client.execute(sql"INSERT INTO clock_rt (id, at) VALUES (1, $value)")
                        rows    <- client.query(sql"SELECT at FROM clock_rt WHERE id = 1")
                        decoded <- rows.head.decode[java.time.LocalTime]
                    yield assert(decoded.equals(value), s"TIME round-trip gave $decoded, expected $value")
                    end for
                }
            }
        }
    }

end SqlPostgresOnlyTest
