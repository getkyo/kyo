package kyo.internal.sqlite

import kyo.*

/** Covers [[SqliteErrors]]'s mapping from a SQLite result code to a SQLSTATE.
  *
  * `SQLITE_ERROR` is returned for a syntax error, an unknown table and an unknown column alike, so the code alone cannot separate them and
  * the MESSAGE PREFIX is what does. A SQLite release rewording one of the three would silently reclassify every error of that kind as the
  * generic state, so each is pinned here.
  */
class SqliteErrorsTest extends Test:

    private val GenericError = 1

    "an unknown table maps to the undefined-table state" in {
        assert(
            SqliteErrors.sqlStateFor(GenericError, "no such table: absent") == SqliteErrors.UndefinedTableState,
            s"got ${SqliteErrors.sqlStateFor(GenericError, "no such table: absent")}"
        )
    }

    "an unknown column maps to the undefined-column state" in {
        assert(
            SqliteErrors.sqlStateFor(GenericError, "no such column: absent") == SqliteErrors.UndefinedColumnState,
            s"got ${SqliteErrors.sqlStateFor(GenericError, "no such column: absent")}"
        )
    }

    "a syntax error maps to the syntax state" in {
        assert(
            SqliteErrors.sqlStateFor(GenericError, """near "FROM": syntax error""") == SqliteErrors.SyntaxState,
            s"""got ${SqliteErrors.sqlStateFor(GenericError, """near "FROM": syntax error""")}"""
        )
    }

    "a message matching no prefix degrades to the generic state rather than guessing" in {
        // An unrecognised message becomes a less specific exception a caller can still act on; guessing would send them to the wrong fix.
        assert(
            SqliteErrors.sqlStateFor(GenericError, "something nobody has seen before") == SqliteErrors.GenericState,
            s"got ${SqliteErrors.sqlStateFor(GenericError, "something nobody has seen before")}"
        )
    }

    "the three prefixes map to three different states" in {
        val table  = SqliteErrors.sqlStateFor(GenericError, "no such table: t")
        val column = SqliteErrors.sqlStateFor(GenericError, "no such column: c")
        val syntax = SqliteErrors.sqlStateFor(GenericError, """near "FROM": syntax error""")
        assert(
            Set(table, column, syntax).size == 3,
            s"the three must stay distinguishable, got table=$table column=$column syntax=$syntax"
        )
    }

end SqliteErrorsTest
