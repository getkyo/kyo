package kyo

import kyo.Sql.*

/** How a schema-qualified table name is rendered, regardless of SQL flavor.
  *
  * The property is the shape of the walk, not any engine's spelling: the schema is emitted before the table, the two are quoted
  * SEPARATELY, and a period joins them. Being flavor-independent it belongs here, over [[IdiomRenderStub]], rather than beside each
  * dialect, where the only difference would be the quote character those dialects already pin exhaustively. Whether a real server accepts
  * the result is a separate claim, and the conformance battery makes it against every backend.
  *
  * The stub quotes with `[ident]`, so nothing rendered here can be mistaken for real SQL and the separation of the two names is visible at
  * a glance.
  */
class IdiomSchemaRenderTest extends Test:

    case class Invoice(id: Long, total: BigDecimal) derives SqlSchema
    case class Customer(id: Long, email: String) derives SqlSchema

    private def sqlOf(q: Query[?]): String      = q.render(IdiomRenderStub).onlySql.get
    private def sqlOf(s: Executable[?]): String = s.render(IdiomRenderStub).onlySql.get

    "SELECT emits the schema and table as two identifiers, column references staying on the alias" in {
        val q = Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")
        assert(sqlOf(q) == "SELECT [i].[id], [i].[total] FROM [app].[invoice] [i]")
    }

    "INSERT emits the schema and table as two identifiers" in {
        val s = Sql.insert[Invoice](schemaName = "app", tableName = "invoice").values(Invoice(1L, BigDecimal(2)))
        assert(sqlOf(s) == "INSERT INTO [app].[invoice] ([id], [total]) VALUES (?1, ?2) RETURNING [id]")
    }

    "UPDATE emits the schema and table as two identifiers" in {
        val s = Sql.update[Invoice](schemaName = "app", tableName = "invoice").set(_.total := BigDecimal(1)).build
        assert(sqlOf(s) == "UPDATE [app].[invoice] SET [total] = ?1")
    }

    "DELETE emits the schema and table as two identifiers" in {
        val s = Sql.delete[Invoice](schemaName = "app", tableName = "invoice").build
        assert(sqlOf(s) == "DELETE FROM [app].[invoice]")
    }

    "the two names are never quoted as one" in {
        // `[app.invoice]` would be one relation whose name contains a period, which no server resolves.
        val q = Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")
        assert(!sqlOf(q).contains("[app.invoice]"))
    }

    "the schema is escaped, not only the table" in {
        val q = Sql.from[Invoice](alias = "i", schemaName = "we]ird", tableName = "invoice")
        assert(sqlOf(q) == "SELECT [i].[id], [i].[total] FROM [we]]ird].[invoice] [i]")
    }

    "a period inside a schema name stays one identifier" in {
        // Quoting exists so an identifier may contain a period. Splitting a name on one at render time
        // would reinterpret this schema as a two-part qualification of something else.
        val q = Sql.from[Invoice](alias = "i", schemaName = "od.d", tableName = "invoice")
        assert(sqlOf(q) == "SELECT [i].[id], [i].[total] FROM [od.d].[invoice] [i]")
    }

    "an unqualified table renders as a bare name, with no empty qualifier" in {
        val q = Sql.from[Invoice]("i")
        assert(sqlOf(q) == "SELECT [i].[id], [i].[total] FROM [invoice] [i]")
    }

    "named arguments in any order render one statement" in {
        val a = Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")
        val b = Sql.from[Invoice](tableName = "invoice", schemaName = "app", alias = "i")
        assert(sqlOf(a) == sqlOf(b))
    }

    "two schemas meet in one statement" in {
        val q =
            Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")
                .innerJoin(Sql.from[Customer](alias = "c", schemaName = "crm", tableName = "customer"))
                .on(j => j.i.id == j.c.id)
                .select(j => (j.i.total, j.c.email))
        assert(sqlOf(q).contains("[app].[invoice] [i]"))
        assert(sqlOf(q).contains("[crm].[customer] [c]"))
    }

    "re-aliasing drops the schema with the table name it belongs to" in {
        // The alias-only overload is documented as a fresh construction that does not carry an overridden
        // table name. The schema follows that name rather than outliving it.
        val q = Sql.from[Invoice](alias = "i", schemaName = "app", tableName = "invoice")("j")
        assert(sqlOf(q) == "SELECT [j].[id], [j].[total] FROM [invoice] [j]")
    }

end IdiomSchemaRenderTest
