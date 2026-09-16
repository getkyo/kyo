package kyo.internal.sqlite

import kyo.*

/** Maps a SQLite result code and message onto the driver's exception vocabulary, by synthesising a SQLSTATE.
  *
  * Synthesising one rather than adding a parallel set of SQLite leaves. `SqlServerException.apply` already dispatches a SQLSTATE to the
  * typed leaf a caller matches on, and the conformance battery's descriptor answers in SQLSTATEs too, so producing one puts SQLite on the
  * same vocabulary as the other two engines instead of beside it. SQLite has no SQLSTATE of its own, which makes this a mapping rather than
  * a translation.
  *
  * Two tiers, because SQLite discriminates two tiers. The CONSTRAINT family carries a distinct extended code per class, so those map on the
  * code alone and nothing is read from the text. Everything else collapses into `SQLITE_ERROR`, which a syntax error, an unknown table and
  * an unknown column all answer with, so telling them apart means reading the message.
  *
  * Reading the message is deliberate, and it is not the message-sniffing the other backends had removed. There a stable code existed and
  * the text was read INSTEAD of it. Here no code exists, so the text is the only carrier, and refusing to read it would discard information
  * the engine does give: a missing table and a syntax error are different bugs with different fixes. The prefixes are pinned by a test, so
  * a future SQLite that rewords them turns that suite red rather than silently reclassifying every error as generic.
  */
private[sqlite] object SqliteError:

    // Each constraint class has its own extended code, all with primary code 19. That is why the extended codes are
    // switched on at connect: without them a unique violation and a check violation are indistinguishable.
    private val ConstraintCheck      = 275
    private val ConstraintForeignKey = 787
    private val ConstraintNotNull    = 1299
    private val ConstraintPrimaryKey = 1555
    private val ConstraintTrigger    = 1811
    private val ConstraintUnique     = 2067
    private val ConstraintDatatype   = 3091

    private val GenericError = 1
    private val Busy         = 5
    private val Locked       = 6
    private val ReadOnly     = 8
    private val Interrupt    = 9
    private val TooBig       = 18
    private val Mismatch     = 20

    /** The SQLSTATEs synthesised here, each the standard's own class code for the condition.
      *
      * Class codes rather than invented values, so a caller already matching on `23` for an integrity violation matches SQLite's without
      * learning anything new.
      */
    val IntegrityState: String       = "23000"
    val NotNullState: String         = "23502"
    val ForeignKeyState: String      = "23503"
    val UniqueState: String          = "23505"
    val CheckState: String           = "23514"
    val SyntaxState: String          = "42601"
    val UndefinedTableState: String  = "42P01"
    val UndefinedColumnState: String = "42703"
    val ValueRangeState: String      = "22003"
    val ReadOnlyState: String        = "25006"
    val LockTimeoutState: String     = "55P03"
    val GenericState: String         = "HY000"

    /** @param connectionId
      *   the connection the failure came from. SQLite has no server session, so this is the driver's own connection id; it is what lets two
      *   errors be told apart as coming from different sessions, which is the whole use a caller has for it.
      */
    def toException(extendedCode: Int, message: String, sql: Maybe[String], connectionId: Maybe[Long])(using Frame): SqlException =
        server(sqlStateFor(extendedCode, message), message, sql, connectionId)

    /** The SQLSTATE a code and message map to, split out so a test can pin the mapping without building an exception. */
    def sqlStateFor(extendedCode: Int, message: String): String =
        extendedCode match
            case ConstraintUnique | ConstraintPrimaryKey => UniqueState
            case ConstraintNotNull                       => NotNullState
            case ConstraintForeignKey                    => ForeignKeyState
            case ConstraintCheck | ConstraintTrigger     => CheckState
            // A STRICT table refusing a wrong-typed value is an integrity violation rather than a range one: the value
            // is not too large, it is the wrong kind.
            case ConstraintDatatype => IntegrityState
            case TooBig | Mismatch  => ValueRangeState
            case ReadOnly           => ReadOnlyState
            case Busy | Locked      => LockTimeoutState
            case Interrupt          => GenericState
            case GenericError       => genericStateFor(message)
            case _                  => GenericState
        end match
    end sqlStateFor

    /** Syntax, unknown table and unknown column all answer `SQLITE_ERROR`, so the prefix is what separates them.
      *
      * Falls back to the generic state when no prefix matches, rather than guessing. An unrecognised message degrades to a less specific
      * exception, which a caller can still act on; misclassifying it would send them to the wrong fix.
      */
    private def genericStateFor(message: String): String =
        val lower = message.toLowerCase
        if lower.startsWith("no such table") then UndefinedTableState
        else if lower.startsWith("no such column") then UndefinedColumnState
        else if lower.contains("syntax error") then SyntaxState
        else GenericState
        end if
    end genericStateFor

    private def server(sqlState: String, message: String, sql: Maybe[String], connectionId: Maybe[Long])(using Frame): SqlException =
        SqlServerException(
            sqlState = sqlState,
            severity = "ERROR",
            message = message,
            detail = Absent,
            hint = Absent,
            position = Absent,
            extra = Map.empty,
            sqlText = sql,
            paramCount = 0,
            connectionId = connectionId
        )

end SqliteError
