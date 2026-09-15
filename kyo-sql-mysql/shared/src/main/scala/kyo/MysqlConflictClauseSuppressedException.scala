package kyo

/** A conflict-ignoring INSERT suppressed a failure that was not a conflict.
  *
  * `onConflictDoNothing` asks to skip a row that conflicts with one already there. This server implements it with a keyword that suppresses
  * far more, downgrading a foreign-key violation, a null into a NOT NULL column, and a truncated value to warnings. Silently dropping a row
  * cannot be recovered from: the insert reports success and nothing says whether the row is missing or was never written.
  *
  * A genuine conflict is still skipped. Everything else is re-raised from the warning, carrying the same property marker the hard failure
  * carries, so a handler recovering on [[SqlIntegrityViolation]] works on either engine. Match on the marker, not on this class.
  */
sealed abstract class MysqlConflictClauseSuppressedException(code: Int, detail: String)(using Frame)
    extends SqlRequestBackendException(
        s"A conflict clause suppressed a failure that was not a conflict (error $code): $detail. The clause skips a row that conflicts " +
            "with an existing one; this failure is unrelated to that and would otherwise have been silently dropped."
    )

object MysqlConflictClauseSuppressedException:

    /** The error numbers that mean a constraint was violated, so the leaf carries [[SqlIntegrityViolation]]. */
    private val integrityCodes: Set[Int] = Set(
        1048, // ER_BAD_NULL_ERROR, a null into a NOT NULL column
        1062, // ER_DUP_ENTRY reported for a key other than the one the clause names
        1451, // ER_ROW_IS_REFERENCED_2
        1452, // ER_NO_REFERENCED_ROW_2
        3819  // ER_CHECK_CONSTRAINT_VIOLATED
    )

    /** The error numbers that mean a value did not fit, so the leaf carries [[SqlValueOutOfRange]]. */
    private val rangeCodes: Set[Int] = Set(
        1264, // ER_WARN_DATA_OUT_OF_RANGE
        1265, // WARN_DATA_TRUNCATED
        1406  // ER_DATA_TOO_LONG
    )

    /** A suppressed constraint violation. */
    final case class Integrity(code: Int, detail: String)(using Frame)
        extends MysqlConflictClauseSuppressedException(code, detail) with SqlIntegrityViolation

    /** A suppressed value that did not fit the column that had to hold it. */
    final case class OutOfRange(code: Int, detail: String)(using Frame)
        extends MysqlConflictClauseSuppressedException(code, detail) with SqlValueOutOfRange

    /** A suppressed failure this module does not classify further. */
    final case class Other(code: Int, detail: String)(using Frame) extends MysqlConflictClauseSuppressedException(code, detail)

    /** The leaf for `code`, carrying the marker the same failure carries when the server raises it directly.
      *
      * @param code
      *   the server's error number for the suppressed failure, the same number it reports when the failure is not suppressed
      * @param detail
      *   the server's own description of it
      */
    def apply(code: Int, detail: String)(using Frame): MysqlConflictClauseSuppressedException =
        if integrityCodes.contains(code) then Integrity(code, detail)
        else if rangeCodes.contains(code) then OutOfRange(code, detail)
        else Other(code, detail)

end MysqlConflictClauseSuppressedException
