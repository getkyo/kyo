package kyo.internal

import kyo.*

/** Unit tests for [[SqlFieldMatcher]], the one place that decides which row field the column at a given index belongs to.
  *
  * The decision is a pure function of the row codec's field names, the row's column names, the query-scope casing, and the statement's
  * [[SqlRow.FieldMatch]] mode. A DSL statement is positional by construction, a fragment matches strictly by name (a missing name is a
  * decode failure the codec raises through [[SqlFieldMatcher.missingByName]]), and the manual decode API keeps the verbatim contract:
  * by name when every field matches, positional otherwise. `SqlRowDecodeTest` covers what that means for a decoded value; these leaves
  * cover the rule itself, including the verbatim boundary where one mode becomes the other.
  */
class SqlFieldMatcherTest extends Test:

    /** The verbatim-mode matcher for reading `A` out of a row whose columns are `columns`, with no query-scope casing. */
    private def matcher[A: SqlSchema](columns: String*): (Int, String) => Boolean =
        SqlFieldMatcher.of(summon[SqlSchema[A]].fieldNames, Chunk.from(columns), Maybe.empty, SqlRow.FieldMatch.Verbatim)

    /** The strict by-name matcher, under the query-scope casing `naming`. */
    private def casedMatcher[A: SqlSchema](naming: SqlNaming, columns: String*): (Int, String) => Boolean =
        SqlFieldMatcher.of(summon[SqlSchema[A]].fieldNames, Chunk.from(columns), Maybe(naming), SqlRow.FieldMatch.ByName)

    // ── by name, when the row carries every name the codec expects ─────────────

    "a row carrying the codec's names matches each field to its own column" in {
        val m = matcher[MatcherPerson]("id", "name")
        assert(m(0, "id"))
        assert(m(1, "name"))
        assert(!m(0, "name"), "field `name` must not claim column 0")
        assert(!m(1, "id"), "field `id` must not claim column 1")
    }

    // The property positional matching must not cost. A caller's own SELECT chooses the column order, and each value
    // still has to reach its own field.
    "a row whose columns are in a different order still matches by name" in {
        val m = matcher[MatcherPerson]("name", "id")
        assert(m(0, "name"))
        assert(m(1, "id"))
        assert(!m(0, "id"))
    }

    // Casing is query-scoped, so it arrives as an argument rather than being baked into the codec: the probes are the
    // codec's own field names and the server reports the cased ones.
    "a naming strategy resolves the probe before comparing" in {
        val m = casedMatcher[MatcherSnake](SqlNaming.SnakeCase, "id", "first_name")
        assert(m(1, "firstName"), "the probe is the Scala name, the column is the resolved one")
        assert(m(1, "first_name"), "the resolved name matches verbatim too")
        assert(!m(0, "firstName"), "resolution does not let a field claim another column")
    }

    // A rename is baked into the codec's field names, so the matcher never sees the Scala name at all.
    "a renamed field is matched under its SQL name" in {
        assert(summon[SqlSchema[MatcherRenamed]].fieldNames == Chunk("id", "nick"))
        val m = matcher[MatcherRenamed]("id", "nick")
        assert(m(1, "nick"))
        assert(!m(1, "nickname"), "the Scala name is not what reaches the row")
    }

    // ── by position, when it does not ──────────────────────────────────────────

    "a row sharing no name with the codec matches by position" in {
        val m = matcher[MatcherPerson]("fullName", "years")
        assert(m(0, "id"), "the first field takes the first column")
        assert(m(1, "name"), "the second field takes the second column")
        assert(!m(1, "id"), "position still separates the fields")
        assert(!m(0, "name"))
    }

    // The verbatim boundary: one missing name is enough, because a projection can easily share a name with the
    // target by accident and matching half by name and half by position would pair columns with fields arbitrarily.
    "verbatim mode: one expected name missing from the row is enough to switch to position" in {
        val m = matcher[MatcherPerson]("id", "years")
        assert(m(0, "id"))
        assert(m(1, "name"), "field 1 takes column 1 even though the column is named `years`")
    }

    // ── the statement-carried modes ─────────────────────────────────────────────

    "positional mode never consults the column names" in {
        val m = SqlFieldMatcher.of(
            summon[SqlSchema[MatcherPerson]].fieldNames,
            Chunk("name", "id"),
            Maybe.empty,
            SqlRow.FieldMatch.Positional
        )
        assert(m(0, "id"), "the first field takes the first column even though a column named `id` exists elsewhere")
        assert(m(1, "name"))
        assert(!m(0, "name"))
    }

    "strict by-name mode reports the first unresolvable field instead of switching to position" in {
        assert(
            SqlFieldMatcher.missingByName(
                summon[SqlSchema[MatcherPerson]].fieldNames,
                Chunk("id", "years"),
                Maybe.empty
            ) == Maybe("name"),
            "`name` resolves to no column, so the strict mode must surface it"
        )
        assert(
            SqlFieldMatcher.missingByName(
                summon[SqlSchema[MatcherPerson]].fieldNames,
                Chunk("name", "id"),
                Maybe.empty
            ) == Maybe.empty,
            "column order does not matter to the strict check"
        )
    }

    "strict by-name mode resolves fields through the casing before deciding what is missing" in {
        assert(
            SqlFieldMatcher.missingByName(
                summon[SqlSchema[MatcherSnake]].fieldNames,
                Chunk("id", "first_name"),
                Maybe(SqlNaming.SnakeCase)
            ) == Maybe.empty
        )
        assert(
            SqlFieldMatcher.missingByName(
                summon[SqlSchema[MatcherSnake]].fieldNames,
                Chunk("id", "firstname"),
                Maybe(SqlNaming.SnakeCase)
            ) == Maybe("first_name"),
            "the missing name is reported in its resolved spelling, the one the caller must alias to"
        )
    }

    "strict by-name mode never counts a tuple element name as missing" in {
        assert(SqlFieldMatcher.missingByName(Chunk("_1", "_2"), Chunk("sum", "?column?"), Maybe.empty) == Maybe.empty)
    }

    // ── tuple elements ─────────────────────────────────────────────────────────

    "a tuple element probe matches its own position in either mode" in {
        val byName = matcher[MatcherPerson]("id", "name")
        assert(byName(1, "_2"), "an aliased column still accepts the tuple element at its position")
        assert(!byName(0, "_2"))
        val byPosition = matcher[MatcherPerson]("sum", "?column?")
        assert(byPosition(1, "_2"))
        assert(!byPosition(0, "_2"))
    }

    "an index past the row matches nothing" in {
        val m = matcher[MatcherPerson]("id", "name")
        assert(!m(2, "id"))
        assert(!m(2, "_3"))
    }

end SqlFieldMatcherTest

case class MatcherPerson(id: Long, name: String) derives SqlSchema
case class MatcherSnake(id: Long, firstName: String) derives SqlSchema
case class MatcherRenamed(id: Long, @column("nick") nickname: String) derives SqlSchema
