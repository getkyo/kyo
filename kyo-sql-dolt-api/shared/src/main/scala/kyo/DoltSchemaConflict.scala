package kyo

/** One table whose SHAPE conflicts, as `dolt_schema_conflicts` reports it.
  *
  * Choosing `ours` or `theirs` row by row means nothing when the two sides disagree about what columns the table has, so this carries the
  * three schemas involved as their `CREATE TABLE` text and leaves the resolution to a caller who can read them.
  */
final case class DoltSchemaConflict(
    table: String,
    baseSchema: Maybe[String],
    ourSchema: Maybe[String],
    theirSchema: Maybe[String],
    description: Maybe[String]
) derives CanEqual
