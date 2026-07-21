package kyo.internal

import kyo.Codec
import kyo.Frame
import kyo.Span
import kyo.internal.postgres.types.Format

/** Codec writer extended with a SQL-specific escape for backend-native opaque types.
  *
  * Extends [[kyo.Codec.Writer]] to participate in schema-driven serialization while adding [[custom]] for types that have no generic
  * representation (e.g. PostGIS geometry, hstore, pgvector). Concrete backend implementations (PostgresParamWriter, MysqlParamWriter) fill
  * in both the standard primitive methods and the custom escape.
  *
  * IMPORTANT: all abstract methods inherited from [[kyo.Codec.Writer]] are synchronous, they return plain `Unit`, not `Unit < Abort`.
  * Unsupported encode operations are signalled by throwing [[kyo.SqlException.Unsupported]], which is [[kyo.KyoException]]-derived and is
  * caught at the kyo-schema encode boundary. The constructor `frame` field supplies the [[kyo.Frame]] used when constructing those
  * exceptions.
  *
  * This trait is internal infrastructure; users interact only with [[kyo.SqlSchema]].
  */
abstract class SqlWriter(val frame: Frame) extends Codec.Writer:

    /** Emit an opaque backend-specific value as raw bytes.
      *
      * @param typeName
      *   the canonical PostgreSQL or MySQL type name (e.g. "geometry", "hstore", "vector"). Used for runtime backend-match validation and
      *   error reporting.
      * @param bytes
      *   the pre-encoded wire bytes for this value.
      * @param format
      *   whether `bytes` are in text or binary format.
      */
    def custom(typeName: String, bytes: Span[Byte], format: Format): Unit

end SqlWriter
