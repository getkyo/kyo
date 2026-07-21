package kyo.internal.postgres

import kyo.Chunk
import kyo.Maybe
import kyo.internal.postgres.types.Format

/** Cached metadata for a server-side named prepared statement.
  *
  * @param name
  *   server-side statement name (e.g. "s_a3f1b2c4d5e6f7a8"); deterministic SHA-256 hex prefix
  * @param sql
  *   the original parameterised SQL text
  * @param paramOids
  *   parameter type OIDs returned by the server in ParameterDescription; length = number of parameters
  * @param rowDescription
  *   column descriptors from RowDescription (Absent for DML/non-SELECT statements)
  * @param resultFormats
  *   format codes requested for result columns; derived from paramOids and the binary registry
  */
final private[postgres] case class PreparedStmt(
    name: String,
    sql: String,
    paramOids: Chunk[Int],
    rowDescription: Maybe[RowDescription],
    resultFormats: Chunk[Short]
)
