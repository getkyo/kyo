package kyo

/** A statement changed a session variable this driver reads its bytes against, so the connection can no longer be trusted.
  *
  * MySQL sends a `TIMESTAMP` with no zone beside it and every string in whatever character set the session names, so neither means anything
  * without the pins the driver set at connect. An error rather than a warning because nothing that follows surfaces as one: reads answer a
  * shifted instant or replacement characters, and a WRITE stores bytes the server re-encodes, leaving the row wrong for every other client.
  *
  * The connection is marked unusable and discarded rather than pooled. To use a different zone or character set, open the connection
  * configured that way rather than changing it mid-session.
  *
  * @param detail
  *   the variable that changed and the value it now holds
  */
final case class MysqlSessionVariableChangedException(detail: String)(using Frame)
    extends SqlConnectionBackendException(
        s"The session changed a variable this connection decodes against ($detail). Every row read or written afterwards would be " +
            "interpreted against a different setting than the one the driver pinned, which for a character set corrupts written rows " +
            "permanently. Configure the connection for the zone or character set you want instead of changing it mid-session."
    )
