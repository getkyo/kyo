package kyo

/** A transaction asked for an isolation level Dolt does not deliver.
  *
  * Refused rather than accepted and ignored, because this server ACCEPTS all four levels and then runs at one regardless, so nothing fails
  * and the difference is invisible. Both halves were measured against a 2.3.4 server: under READ COMMITTED a re-read inside a transaction
  * does not see another transaction's committed write, where PostgreSQL and MySQL both do, and under SERIALIZABLE two concurrent increments
  * left the counter one short with neither writer refused.
  *
  * @param requested
  *   the level the caller named
  * @param actual
  *   the level this engine really runs at
  */
final case class DoltIsolationLevelUnsupportedException(
    requested: SqlClient.IsolationLevel,
    actual: SqlClient.IsolationLevel
)(using Frame)
    extends SqlUnsupportedBackendException(
        s"Dolt cannot run at $requested. It accepts every level and then runs at $actual regardless, so naming $requested would be a " +
            s"transaction running at a different level than it asked for: weaker levels silently get more isolation, and SERIALIZABLE " +
            s"silently gets less and has been measured losing an update. Name $actual, or name no level at all."
    )
