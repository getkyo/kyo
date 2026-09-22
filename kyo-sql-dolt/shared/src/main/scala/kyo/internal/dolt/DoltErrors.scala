package kyo.internal.dolt

import kyo.*

/** Puts a Dolt server error in the right typed family, which its SQLSTATE cannot do.
  *
  * Dolt files EVERY failure under `HY000`, the general-error state, measured against a 2.3.4 server: a missing table (1146), a duplicate
  * key (1062) and a syntax error (1105) all arrive with it, where MySQL sends `42S02`, `23000` and `42000`. The shared dispatcher
  * classifies on the SQLSTATE prefix, so left alone every Dolt failure would land in the generic leaf and a caller recovering on
  * [[kyo.SqlIntegrityViolation]] would miss here the duplicate key they handle on MySQL.
  *
  * The rule followed here is the one [[kyo.internal.mysql.exchange.MysqlErrors]] already states for MySQL's own `HY000` cases:
  * classification reads the error NUMBER, and the server's own SQLSTATE is relayed untouched rather than rewritten to something the server
  * never sent.
  *
  * The numbers above are MySQL's own, but Dolt does not always reuse them: for a prepared statement the server no longer holds it answers
  * `2014 statement ID is not found from record` where MySQL answers `1243 Unknown prepared statement handler`. So a number belongs in the
  * sets below only once it has been observed from a Dolt server, never because MySQL is documented to send it.
  */
private[kyo] object DoltErrors:

    /** Numbers that mean a constraint was violated. */
    private val IntegrityCodes: Set[Int] = Set(
        1062, // ER_DUP_ENTRY
        1048, // ER_BAD_NULL_ERROR
        1216, // ER_NO_REFERENCED_ROW
        1217, // ER_ROW_IS_REFERENCED
        1451, // ER_ROW_IS_REFERENCED_2
        1452, // ER_NO_REFERENCED_ROW_2
        1557, // ER_FOREIGN_DUPLICATE_KEY
        3819  // ER_CHECK_CONSTRAINT_VIOLATED
    )

    /** Numbers that mean the statement could not be understood or named something absent. */
    private val SyntaxCodes: Set[Int] = Set(
        1064, // ER_PARSE_ERROR
        1146, // ER_NO_SUCH_TABLE
        1054, // ER_BAD_FIELD_ERROR
        1049, // ER_BAD_DB_ERROR
        1051, // ER_BAD_TABLE_ERROR
        1050, // ER_TABLE_EXISTS_ERROR
        1305, // ER_SP_DOES_NOT_EXIST
        1370  // ER_PROCACCESS_DENIED_ERROR
    )

    /** Numbers that mean a value did not fit. */
    private val ValueRangeCodes: Set[Int] = Set(
        1264, // ER_WARN_DATA_OUT_OF_RANGE
        1406, // ER_DATA_TOO_LONG
        1690  // ER_DATA_OUT_OF_RANGE
    )

    /** Numbers that mean the transaction lost a race and may be retried. */
    private val DeadlockCodes: Set[Int] = Set(
        1213, // ER_LOCK_DEADLOCK
        1205  // ER_LOCK_WAIT_TIMEOUT
    )

    /** 1105 is Dolt's catch-all, and its message is the only thing that separates a parse error from anything else.
      *
      * Matched anywhere in the message rather than at its start, measured: the server prefixes its own text, so a malformed statement
      * arrives as `unknown error: Code: INVALID_ARGUMENT` followed by `syntax error at position 6 near 'SLECT'`. A message matching nothing
      * stays in the generic leaf, since a misclassified failure sends a caller to the wrong fix where a less specific one does not.
      */
    private val GeneralCode: Int = 1105

    private def generalIsSyntax(message: String): Boolean =
        message.toLowerCase.nn.contains("syntax error")

    private def generalIsIntegrity(message: String): Boolean =
        val lower = message.toLowerCase.nn
        lower.contains("check constraint") || lower.contains("duplicate primary key") || lower.contains("duplicate key")
    end generalIsIntegrity

    /** Re-raises `error` in the family its error number names, or returns it unchanged when the number says nothing new.
      *
      * Only a [[kyo.SqlServerErrorException]] is reconsidered, so a Dolt release that starts sending proper states needs no change here:
      * the generic leaf simply stops arriving and this stops firing.
      */
    def reclassify(error: SqlException)(using Frame): SqlException =
        error match
            case e: SqlServerErrorException =>
                codeOf(e) match
                    case Absent        => e
                    case Present(code) =>
                        // serverMessage, not `message`: the latter is the fully rendered exception text, and rebuilding
                        // from it nests one rendering inside the next.
                        val message = e.serverMessage
                        if IntegrityCodes.contains(code) || (code == GeneralCode && generalIsIntegrity(message)) then
                            rebuild(e, SqlServerConstraintViolationException.apply)
                        else if SyntaxCodes.contains(code) || (code == GeneralCode && generalIsSyntax(message)) then
                            rebuild(e, SqlServerSyntaxException.apply)
                        else if ValueRangeCodes.contains(code) then rebuild(e, SqlServerValueRangeException.apply)
                        else if DeadlockCodes.contains(code) then rebuild(e, SqlServerDeadlockException.apply)
                        else e
                        end if
            case other => other

    /** The error number the connection layer records under `code`, absent when the error did not come from a server packet. */
    private def codeOf(e: SqlServerErrorException): Maybe[Int] =
        Maybe.fromOption(e.extra.get("code")).flatMap(raw => Maybe.fromOption(raw.toIntOption))

    /** Builds the target leaf from an existing error, carrying every field across so only the type changes. */
    private def rebuild[A <: SqlServerException](
        e: SqlServerErrorException,
        make: (
            String,
            String,
            String,
            Maybe[String],
            Maybe[String],
            Maybe[Int],
            Map[String, String],
            Maybe[String],
            Int,
            Maybe[Long]
        ) => A
    )(using Frame): A =
        make(
            // The server's own state, relayed rather than rewritten.
            e.sqlState,
            e.severity,
            e.serverMessage,
            e.detail,
            e.hint,
            e.position,
            e.extra,
            e.sqlText,
            e.paramCount,
            e.connectionId
        )

end DoltErrors
