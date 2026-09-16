package kyo.internal.sqlite

import kyo.*

/** Covers [[SqliteError]]'s mapping from a SQLite result code to a SQLSTATE.
  *
  * Three different failures share one result code here. `SQLITE_ERROR` is returned for a syntax error, an unknown table and an unknown
  * column alike, so the code alone cannot separate them and the MESSAGE PREFIX is what does. That makes the prefixes load-bearing: a SQLite
  * release rewording one of them would silently reclassify every error of that kind as the generic state, with nothing red to say so.
  *
  * So each of the three is pinned. The mapping is asserted directly rather than through a connection, because the point is the mapping and a
  * live statement would only reach one prefix per test.
  */
class SqliteErrorTest extends Test:

    private val GenericError = 1

    "an unknown table maps to the undefined-table state" in {
        assert(
            SqliteError.sqlStateFor(GenericError, "no such table: absent") == SqliteError.UndefinedTableState,
            s"got ${SqliteError.sqlStateFor(GenericError, "no such table: absent")}"
        )
    }

    "an unknown column maps to the undefined-column state" in {
        // The one the design named and nothing pinned. It is a different SQLSTATE from the table case, and a caller acting on the
        // distinction gets it only from this prefix.
        assert(
            SqliteError.sqlStateFor(GenericError, "no such column: absent") == SqliteError.UndefinedColumnState,
            s"got ${SqliteError.sqlStateFor(GenericError, "no such column: absent")}"
        )
    }

    "a syntax error maps to the syntax state" in {
        assert(
            SqliteError.sqlStateFor(GenericError, """near "FROM": syntax error""") == SqliteError.SyntaxState,
            s"""got ${SqliteError.sqlStateFor(GenericError, """near "FROM": syntax error""")}"""
        )
    }

    "a message matching no prefix degrades to the generic state rather than guessing" in {
        // Deliberate: an unrecognised message becomes a less specific exception a caller can still act on. Guessing would send them to the
        // wrong fix, which is worse than saying less.
        assert(
            SqliteError.sqlStateFor(GenericError, "something nobody has seen before") == SqliteError.GenericState,
            s"got ${SqliteError.sqlStateFor(GenericError, "something nobody has seen before")}"
        )
    }

    "the three prefixes map to three different states" in {
        val table  = SqliteError.sqlStateFor(GenericError, "no such table: t")
        val column = SqliteError.sqlStateFor(GenericError, "no such column: c")
        val syntax = SqliteError.sqlStateFor(GenericError, """near "FROM": syntax error""")
        assert(
            Set(table, column, syntax).size == 3,
            s"the three must stay distinguishable, got table=$table column=$column syntax=$syntax"
        )
    }

end SqliteErrorTest
