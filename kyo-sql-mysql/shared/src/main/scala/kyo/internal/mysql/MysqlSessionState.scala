package kyo.internal.mysql

import kyo.*

/** One connection's view of the server session, written on every status-carrying packet rather than captured once at handshake.
  *
  * Session state captured once and assumed static is what makes a connection lie: unread result sets leave it one response ahead of its next
  * borrower, a changed charset turns reads into replacement characters, and a lost `time_zone` pin shifts every instant.
  *
  * Single-fiber by contract, like the sequence id beside it: a connection is never used concurrently, which is what lets these be plain
  * fields rather than atomics.
  */
final private[mysql] class MysqlSessionState:

    private var _statusFlags: Int  = 0
    private var _lastWarnings: Int = 0
    private var _violated: Boolean = false

    /** The status flags the most recent OK or EOF packet carried. */
    def statusFlags: Int = _statusFlags

    /** The warning count the most recent OK or EOF packet carried.
      *
      * Read from the packet rather than asked for: `SELECT @@warning_count` CLEARS the diagnostics area, so querying the count to decide
      * whether the detail is worth fetching destroys that detail.
      */
    def lastWarnings: Int = _lastWarnings

    /** Whether the server said the statement has more result sets nobody has read yet. */
    def moreResultsExpected: Boolean = MysqlServerStatus.has(_statusFlags, MysqlServerStatus.MoreResultsExists)

    /** Folds one packet's status flags and session-state block into this view, answering a CHARACTER SET the caller changed the first time
      * one is seen and [[Absent]] every time after.
      *
      * Reported once because the caller acts on it once: the connection is marked unusable on the first report.
      *
      * Only the character set. A changed one leaves no working use of the connection at all. A changed `time_zone` is different: the driver
      * decodes against its own pin, and a caller may legitimately set a zone and restore it, so losing the pin is closed by re-applying it as
      * part of any reset instead.
      */
    def observe(statusFlags: Int, warnings: Int, sessionStateBlock: Maybe[Span[Byte]]): Maybe[String] =
        _statusFlags = statusFlags
        _lastWarnings = warnings
        if _violated || !MysqlServerStatus.has(statusFlags, MysqlServerStatus.SessionStateChanged) then Absent
        else
            var found: Maybe[String] = Absent
            var seen                 = false
            sessionStateBlock match
                case Present(block) =>
                    MysqlSessionTracking.read(block).foreach { change =>
                        if !seen then
                            change.name match
                                case "character_set_client" | "character_set_connection" | "character_set_results" =>
                                    found = Present(s"${change.name} changed to '${change.value}'")
                                    seen = true
                                case _ => ()
                            end match
                        end if
                    }
                case Absent => ()
            end match
            if seen then _violated = true
            found
        end if
    end observe

end MysqlSessionState
