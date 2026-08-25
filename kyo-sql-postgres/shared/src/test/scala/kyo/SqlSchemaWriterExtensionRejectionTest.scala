package kyo

import kyo.db.Idiom
import kyo.internal.postgres.PostgresParamWriter
import kyo.internal.postgres.TypeRegistry
import kyo.internal.postgres.types.PostgresEncoder

/** The consumer test for the second half of the portability invariant: a dialect-only type aborts typed on the wrong backend.
  *
  * A column codec is written once per type and works with every backend on the classpath. For the types only one dialect has, that promise
  * cannot be kept, so the extension channel makes the failure typed and immediate instead of shipping bytes the server cannot read. Every
  * codec in [[PostgresTypes]] goes through that channel, so a writer for another dialect rejects all of them.
  *
  * The accepting side runs against the real [[kyo.internal.postgres.PostgresParamWriter]]. The rejecting side needs a writer this module
  * cannot see (kyo-sql-postgres never depends on kyo-sql-mysql), so it runs against core's recording writer constructed with the MySQL
  * dialect id, which implements the same dialect check.
  */
class SqlSchemaWriterExtensionRejectionTest extends Test:

    private val postgres: Idiom.Id = PostgresEncoder.dialectId
    private val mysql: Idiom.Id    = Idiom.Id("mysql")

    /** "hstore" has no builtin OID, so the session has to resolve it; the six range names resolve without a registry. */
    private val registry: TypeRegistry = TypeRegistry(Map("hstore" -> 90001))

    private def hstore = PostgresTypes.HStore(Map("k" -> Maybe("v")))

    private def range = PostgresTypes.Range(PostgresTypes.Range.Bound.Inclusive(1), PostgresTypes.Range.Bound.Exclusive(10))

    private def payload(bytes: Span[Byte]) =
        SqlCodec.Writer.Payload(postgres, "hstore", SqlCodec.Format.Binary, bytes)

    "a PostgreSQL-owned payload is accepted by a PostgreSQL writer" in {
        val writer = new PostgresParamWriter(registry)
        writer.extension(payload(Span.from(Array[Byte](1))))
        // `params` drains the accumulator, so it is read once, like the production collection point.
        val params = writer.params
        assert(params.size == 1)
        assert(params(0).encoder.oid == 90001, "the payload's type name resolved through the session registry")
    }

    "a PostgreSQL-owned payload is rejected by a writer for another dialect, naming the type and both dialects" in {
        val writer = SqlSchemaWriterMock.mysqlMock
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            writer.extension(payload(Span.from(Array[Byte](1))))
        }
        assert(ex.dialect == postgres)
        assert(ex.activeDialect == mysql)
        assert(ex.typeName == "hstore")
        assert(ex.getMessage.contains("hstore"))
        assert(writer.calls.isEmpty, "a rejected payload must not reach the wire")
    }

    "the dialect is checked before the format, so a matching format does not rescue a foreign payload" in {
        // The two coordinates are independent: the format says how to read the bytes, the dialect says whether this
        // writer may emit them at all, and only the second one decides acceptance.
        val writer = SqlSchemaWriterMock.mysqlMock
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            writer.extension(SqlCodec.Writer.Payload(postgres, "hstore", SqlCodec.Format.Text, Span.empty))
        }
        assert(ex.typeName == "hstore")
        assert(writer.calls.isEmpty)
    }

    "the HStore column writes on PostgreSQL and aborts on another dialect" in {
        val column = summon[SqlSchema.Column[PostgresTypes.HStore]]

        val pg = new PostgresParamWriter(registry)
        column.write(hstore, pg)
        assert(pg.params.size == 1, "the hstore reaches a PostgreSQL writer as one extension column")

        val my = SqlSchemaWriterMock.mysqlMock
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            column.write(hstore, my)
        }
        assert(ex.typeName == "hstore")
        assert(ex.activeDialect == mysql)
        assert(my.calls.isEmpty)
    }

    "the Range[Int] column writes on PostgreSQL and aborts on another dialect" in {
        val column = summon[SqlSchema.Column[PostgresTypes.Range[Int]]]

        val pg = new PostgresParamWriter(registry)
        column.write(range, pg)
        assert(pg.params.size == 1, "the range reaches a PostgreSQL writer as one extension column")

        val my = SqlSchemaWriterMock.mysqlMock
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            column.write(range, my)
        }
        assert(ex.typeName == "int4range")
        assert(ex.dialect == postgres)
        assert(ex.activeDialect == mysql)
        assert(my.calls.isEmpty)
    }

    "a reader for the wrong dialect rejects the read too" in {
        val reader = SqlSchemaReaderMock.mysqlMock(Chunk.empty)
        val ex = intercept[SqlUnsupportedTypeOnBackendException] {
            val _ = reader.nextExtension(postgres, "hstore")
        }
        assert(ex.dialect == postgres)
        assert(ex.activeDialect == mysql)
    }

end SqlSchemaWriterExtensionRejectionTest
