package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlServerException
import kyo.Test

/** Unit tests for the predicate that decides which failed Bind is worth re-preparing.
  *
  * The narrowing is the safety property, not the matching: a code matched too widely retries work the server already did. So each leaf here
  * names a failure that LOOKS close and must not qualify.
  */
class StalePreparedStatementTest extends Test:

    private def serverError(sqlState: String, routine: Maybe[String])(using Frame): SqlServerException =
        SqlServerException(
            sqlState,
            "ERROR",
            "message",
            Absent,
            Absent,
            Absent,
            routine.fold(Map.empty[String, String])(r => Map("routine" -> r)),
            Absent,
            0,
            Absent
        )

    "a 26000 raised by FetchPreparedStatement qualifies" in {
        assert(StalePreparedStatement.matches(serverError("26000", Present("FetchPreparedStatement"))))
    }

    "an 0A000 raised by RevalidateCachedQuery qualifies" in {
        assert(StalePreparedStatement.matches(serverError("0A000", Present("RevalidateCachedQuery"))))
    }

    "an 0A000 raised somewhere else does not qualify" in {
        // 0A000 is feature_not_supported, raised all over the server. Only the plan-revalidation site is
        // known to run before the portal exists.
        assert(!StalePreparedStatement.matches(serverError("0A000", Present("standard_ProcessUtility"))))
    }

    "a 26000 raised somewhere else does not qualify" in {
        assert(!StalePreparedStatement.matches(serverError("26000", Present("ExecuteQuery"))))
    }

    "a 42804 from transformAssignedExpr does not qualify" in {
        // Raised during re-analysis, which runs for plansources SPI created without fixed_result too, so an
        // UPDATE cached inside a PL/pgSQL function can raise it while executing the outer call, after rows
        // have gone to the client. Retrying it would repeat work the caller has already seen.
        assert(!StalePreparedStatement.matches(serverError("42804", Present("transformAssignedExpr"))))
    }

    "a server error carrying no routine does not qualify" in {
        // The R field is optional on the wire, and a build without it cannot tell the safe raise site from
        // the unsafe one.
        assert(!StalePreparedStatement.matches(serverError("26000", Absent)))
    }

    "a failure that is not the server's does not qualify" in {
        assert(!StalePreparedStatement.matches(SqlConnectionClosedException("Bind")))
    }

end StalePreparedStatementTest
