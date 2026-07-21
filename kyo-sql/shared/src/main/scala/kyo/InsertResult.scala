package kyo

/** Result of executing an INSERT statement.
  *
  * @param affectedRows
  *   per-statement row count reported by the backend (`CommandComplete` on Postgres, OK packet `affectedRows` on MySQL).
  * @param generatedKey
  *   [[GeneratedKey]] sum distinguishing three cases: a concrete value, the absence of an auto-key column in the schema, or the backend not
  *   reporting one despite the schema having an auto-key column. See [[GeneratedKey]] for per-case backend semantics.
  *
  * IMPORTANT: Multi-row INSERT id semantics differ per backend:
  *   - Postgres: returns the LAST generated id in the batch (the renderer auto-emits `RETURNING <pk>` and the driver reads the final
  *     DataRow).
  *   - MySQL: returns the FIRST generated id in the batch (the OK packet's `lastInsertId` field).
  * Single-row INSERTs are unambiguous on both backends.
  */
final case class InsertResult(affectedRows: Long, generatedKey: GeneratedKey) derives CanEqual

/** Outcome of the generated-key lookup for an INSERT.
  *
  * The previous `Maybe[Long]` field overloaded two distinct meanings (no auto-key column vs server reported zero); this sum makes the
  * distinction explicit so callers can dispatch on the root cause rather than only "present/absent".
  *
  *   - [[GeneratedKey.Value]] — backend reported a non-zero generated id. On Postgres the renderer emitted `RETURNING <pk>` for an auto-key
  *     column and decoded the DataRow's id; on MySQL the OK packet's `lastInsertId` field was non-zero.
  *   - [[GeneratedKey.NoAutoKey]] — the target table has no auto-incrementing column, so no id was requested. Postgres: no `RETURNING` was
  *     added; MySQL: schema declared no AUTO_INCREMENT.
  *   - [[GeneratedKey.Unavailable]] — the schema does have an auto-key column but the backend did not surface a value. Currently this
  *     surfaces only on MySQL when `last_insert_id == 0` despite an AUTO_INCREMENT column (e.g. when the user supplied an explicit non-zero
  *     value for the auto column, suppressing the auto-increment generation).
  */
enum GeneratedKey derives CanEqual:
    case Value(key: Long)
    case NoAutoKey
    case Unavailable
end GeneratedKey

object GeneratedKey:
    /** Predicate: a concrete id was reported. */
    def isPresent(gk: GeneratedKey): Boolean = gk match
        case Value(_)                => true
        case NoAutoKey | Unavailable => false

    /** Extracts the id when [[Value]], or returns the default-supplier's result otherwise. Mirrors the legacy `Maybe.fold` ergonomics. */
    inline def foldKey[A](gk: GeneratedKey)(ifAbsent: => A)(f: Long => A): A = gk match
        case Value(k)                => f(k)
        case NoAutoKey | Unavailable => ifAbsent
end GeneratedKey
