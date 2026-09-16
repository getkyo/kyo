package kyo

/** A NaN reached a bind, and SQLite has no representation for it.
  *
  * Refused rather than bound: `sqlite3_bind_double` accepts a NaN, stores NULL, and reports success, so binding it would turn a value into
  * an absent one with nothing red. The infinities are not refused, since SQLite holds them and reads them back as `Inf` and `-Inf`.
  */
final case class SqliteNaNNotStorableException()(using Frame)
    extends SqlRequestBackendException(
        "SQLite cannot store NaN: binding it would succeed and store NULL, turning the value into an absent one. " +
            "The infinities are storable and are not refused here."
    ) with SqlValueOutOfRange
