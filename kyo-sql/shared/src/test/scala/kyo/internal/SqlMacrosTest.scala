package kyo.internal

import kyo.*
import kyo.internal.SqlMacros

/** Unit tests for the row-shape reads in [[SqlMacros]]: which fields a row type has, what they are called in SQL, and which cells an
  * INSERT decomposes a row into.
  *
  * A case class and a named tuple are both rows, and they carry their field names in different places: a case class on its field symbols,
  * a named tuple in its type. Each leaf here states the same expectation for both shapes, because a row that reads as by-name must decode
  * by name whichever shape it was written as.
  */
class SqlMacrosTest extends Test:

    type Named      = (name: String, age: Int)
    type NamedKeyed = (id: Long, label: String)

    // --- Column names ---

    "columnNames is the field names of a case class" in {
        assert(SqlMacros.columnNames[SqlMacrosTestPerson] == Chunk("name", "age"))
        succeed
    }

    "columnNames is the labels of a named tuple" in {
        assert(SqlMacros.columnNames[Named] == Chunk("name", "age"))
        succeed
    }

    "columnNames is positional for a plain tuple" in {
        assert(SqlMacros.columnNames[(String, Int)] == Chunk("_1", "_2"))
        succeed
    }

    "columnNames honors a @column rename on a case class" in {
        assert(SqlMacros.columnNames[SqlMacrosTestRenamed] == Chunk("full_name", "age"))
        succeed
    }

    // --- The SqlSchema field names ---

    "sqlFieldNames is the labels of a named tuple" in {
        assert(SqlMacros.sqlFieldNames[Named] == Seq("name", "age"))
        succeed
    }

    // --- The auto-key rule ---

    "autoKey is the first field of a named tuple when it is Long-typed" in {
        assert(SqlMacros.autoKey[NamedKeyed] == Maybe("id"))
        succeed
    }

    "autoKey is absent for a named tuple whose first field is not Long-typed" in {
        assert(SqlMacros.autoKey[Named] == Maybe.empty[String])
        succeed
    }

    // --- INSERT cell decomposition ---
    //
    // A named tuple has no member to select a field off at the term level, so its cells come from the tuple it erases to. The order is the
    // declaration order either shape is written in, which is the order columnNames emits.

    /** `rowValues` reads its rows as a repeated parameter, the shape the INSERT and VALUES builders hand it. */
    private inline def cellsOf[T](inline rows: T*): Chunk[Chunk[Sql.BoundValue[?]]] =
        SqlMacros.rowValues[T](rows)

    "rowValues decomposes a named tuple in declaration order" in {
        val cells = cellsOf[Named]((name = "alice", age = 42))
        assert(cells.size == 1)
        assert(cells(0).map(_.value.toString) == Chunk("alice", "42"))
        succeed
    }

    "rowValues decomposes a case class in declaration order" in {
        val cells = cellsOf[SqlMacrosTestPerson](SqlMacrosTestPerson("alice", 42))
        assert(cells.size == 1)
        assert(cells(0).map(_.value.toString) == Chunk("alice", "42"))
        succeed
    }

    // --- The query DSL's row shape ---
    //
    // `Sql.from[T]` stages its columns through `Fields[T]`, which reads a case class's fields; a named tuple has none to stage, and no
    // alias spelling changes that. The refusal names the shape rather than reporting a row with no fields.

    "Sql.from refuses a named tuple by naming the shape" in {
        typeCheckFailure("Sql.from[(name: String, age: Int)]")("does not accept a named tuple")
    }

end SqlMacrosTest

case class SqlMacrosTestPerson(name: String, age: Int)

case class SqlMacrosTestRenamed(@column("full_name") name: String, age: Int)
