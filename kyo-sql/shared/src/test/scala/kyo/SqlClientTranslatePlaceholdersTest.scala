package kyo

/** Unit tests for [[SqlClient.translatePlaceholders]].
  *
  * Verifies that `?` placeholders inside dollar-quoted strings, single-quoted literals, and double-quoted identifiers are preserved
  * verbatim and that normal `?` outside those contexts are correctly rewritten to `$N`.
  */
class SqlClientTranslatePlaceholdersTest extends Test:

    // --- Existing / baseline behaviour ---

    "no question marks — input returned unchanged" in {
        val sql = "SELECT * FROM t WHERE x = 1"
        assert(SqlClient.translatePlaceholders(sql) == sql)
    }

    "single ? is rewritten to $1" in {
        val sql = "SELECT * FROM t WHERE x = ?"
        assert(SqlClient.translatePlaceholders(sql) == "SELECT * FROM t WHERE x = $1")
    }

    "multiple ? are rewritten to $1 $2 $3 in order" in {
        val sql = "INSERT INTO t (a, b, c) VALUES (?, ?, ?)"
        assert(SqlClient.translatePlaceholders(sql) == "INSERT INTO t (a, b, c) VALUES ($1, $2, $3)")
    }

    // --- Single-quoted string literals ---

    "translatePlaceholders preserves ? inside single-quoted string 'a?b'" in {
        val sql    = "SELECT * FROM t WHERE x = 'a?b'"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT * FROM t WHERE x = 'a?b'")
    }

    "translatePlaceholders rewrites ? after single-quoted literal" in {
        val sql    = "SELECT 'a?b' FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT 'a?b' FROM t WHERE x = $1")
    }

    "translatePlaceholders handles '' escape inside single-quoted literal" in {
        val sql    = "SELECT * FROM t WHERE x = 'it''s a ? test' AND y = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT * FROM t WHERE x = 'it''s a ? test' AND y = $1")
    }

    // --- Double-quoted identifiers ---

    "translatePlaceholders preserves ? inside double-quoted identifier \"a?b\"" in {
        val sql    = "SELECT \"a?b\" FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT \"a?b\" FROM t WHERE x = $1")
    }

    "translatePlaceholders handles \"\" escape inside double-quoted identifier" in {
        val sql    = "SELECT \"col\"\"?\"\"name\" FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT \"col\"\"?\"\"name\" FROM t WHERE x = $1")
    }

    // --- Dollar-quoted strings (new in Phase 5) ---

    "translatePlaceholders preserves ? inside dollar-quoted body ($$...$$)" in {
        val sql    = "SELECT $$some ? text$$ FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT $$some ? text$$ FROM t WHERE x = $1")
    }

    "translatePlaceholders preserves ? inside tagged dollar-quoted body ($body$...$body$)" in {
        val sql    = "SELECT $body$some ? text$body$ FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT $body$some ? text$body$ FROM t WHERE x = $1")
    }

    "nested dollar-quoted tags with different names" in {
        // $outer$ body contains an $inner$…$inner$ tag — both are plain text in PG dollar-quote semantics.
        // The outer tag ends at the first $outer$ match; $inner$…$inner$ is part of its body.
        val sql    = "SELECT $outer$hello $inner$ ? $inner$ world?$outer$ FROM t WHERE y = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT $outer$hello $inner$ ? $inner$ world?$outer$ FROM t WHERE y = $1")
    }

    "bare $ in plain text is not treated as a tag opener" in {
        // A lone '$' not followed by a valid tag pattern should be emitted verbatim.
        val sql    = "SELECT a + b FROM t WHERE x = ? AND note = 'cost $100'"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT a + b FROM t WHERE x = $1 AND note = 'cost $100'")
    }

    "translatePlaceholders handles a SQL that mixes dollar-quoted and parameterized fragments" in {
        val sql    = "DO $$ BEGIN RAISE NOTICE 'hello?'; END $$; UPDATE t SET x = ? WHERE y = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "DO $$ BEGIN RAISE NOTICE 'hello?'; END $$; UPDATE t SET x = $1 WHERE y = $2")
    }

    "dollar-quoted empty tag ($$) with no question mark inside — input unchanged fast path skipped" in {
        // Force the slow path: there IS a ? outside the dollar-quoted body.
        val sql    = "SELECT $$ literal $$ FROM t WHERE x = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT $$ literal $$ FROM t WHERE x = $1")
    }

    // --- Comments (pre-existing, regression guard) ---

    "? inside line comment is preserved" in {
        val sql    = "SELECT * FROM t -- where x = ?\nWHERE y = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT * FROM t -- where x = ?\nWHERE y = $1")
    }

    "? inside block comment is preserved" in {
        val sql    = "SELECT /* x = ? */ * FROM t WHERE y = ?"
        val result = SqlClient.translatePlaceholders(sql)
        assert(result == "SELECT /* x = ? */ * FROM t WHERE y = $1")
    }

end SqlClientTranslatePlaceholdersTest
