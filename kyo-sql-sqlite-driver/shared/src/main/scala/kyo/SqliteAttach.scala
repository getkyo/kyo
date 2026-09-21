package kyo

/** Databases attached to every connection this client opens, so a schema-qualified name resolves on all of them.
  *
  * A schema qualifier on this engine names an attached database, and `ATTACH` is per CONNECTION. Run as an ordinary statement it reaches
  * whichever pooled connection served it and no other, so the next statement lands on a different connection and answers "no such table"
  * for a name that worked a moment earlier. Attaching at connect is what makes the qualifier mean one thing across the pool.
  *
  * An extension rather than a `SqlConfig` field, because the concept is this lineage's own: a server holds its schemas in the database,
  * where every session already sees them, and attaches nothing.
  *
  * {{{
  * val config = SqlConfig.default.extension(SqliteAttach(Map("archive" -> "/var/db/archive.sqlite")))
  * SqlClient.init("sqlite://app.db", config)
  *
  * Sql.from[Invoice](alias = "i", schemaName = "archive", tableName = "invoice")
  * }}}
  *
  * `main` and `temp` are always present and need no entry here. A path that cannot be attached fails the connection rather than leaving it
  * open with a name that resolves nowhere, so a pool never holds connections that disagree about which schemas exist.
  *
  * What is attached is part of what a connection IS, so two configs naming different maps do not share pooled connections: the pool keys on
  * the config's extensions.
  *
  * @param databases
  *   schema name to database path. The path is whatever `ATTACH` accepts, a filename or `:memory:` for a private database of its own.
  */
final case class SqliteAttach(databases: Map[String, String]) extends SqlConfig.Extension derives CanEqual:

    /** The `ATTACH` statements, in a stable order so every connection attaches the same databases the same way.
      *
      * The path is escaped as a SQL string literal and the schema as an identifier, each doubling its own quote character. An unescaped
      * path carrying an apostrophe would close the literal early and leave the rest of it to be parsed as SQL.
      */
    private[kyo] def statements: Chunk[String] =
        Chunk.from(databases.toSeq.sortBy(_._1)).map { case (schema, path) =>
            val quotedPath   = "'" + path.replace("'", "''") + "'"
            val quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\""
            s"ATTACH DATABASE $quotedPath AS $quotedSchema"
        }

end SqliteAttach

object SqliteAttach:

    /** The statements `config` asks for, empty when it names no attachment. */
    private[kyo] def statementsFor(config: SqlConfig): Chunk[String] =
        config.extensionFor[SqliteAttach].fold(Chunk.empty)(_.statements)
end SqliteAttach
