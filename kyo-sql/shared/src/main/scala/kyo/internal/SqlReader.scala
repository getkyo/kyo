package kyo.internal

import kyo.Codec
import kyo.Frame
import kyo.Span

/** Codec reader extended with a SQL-specific escape for backend-native opaque types.
  *
  * Extends [[kyo.Codec.Reader]] to participate in schema-driven deserialization while adding [[custom]] for types that have no generic
  * representation (e.g. PostGIS geometry, hstore, pgvector). Concrete backend implementations (PostgresRowReader, MysqlRowReader) fill in
  * both the standard primitive methods and the custom escape.
  *
  * IMPORTANT: all abstract methods inherited from [[kyo.Codec.Reader]] are synchronous, they return plain `A`, not `A < Abort`. Decode
  * failures are signalled by throwing [[kyo.SqlException.Decode]]; unsupported operations are signalled by throwing
  * [[kyo.SqlException.Unsupported]]. Both are [[kyo.KyoException]]-derived and are caught at the kyo-schema decode boundary. The
  * constructor `frame` field supplies the [[kyo.Frame]] used when constructing those exceptions.
  *
  * This trait is internal infrastructure; users interact only with [[kyo.SqlSchema]].
  */
abstract class SqlReader(val frame: Frame) extends Codec.Reader:

    /** Read the next column value as raw bytes for a backend-specific opaque type.
      *
      * @param typeName
      *   the canonical PostgreSQL or MySQL type name (e.g. "geometry", "hstore", "vector"). Used for runtime backend-match validation and
      *   error reporting.
      * @return
      *   the raw wire bytes for this column value.
      */
    def custom(typeName: String): Span[Byte]

end SqlReader
