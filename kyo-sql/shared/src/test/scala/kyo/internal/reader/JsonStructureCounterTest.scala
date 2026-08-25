package kyo.internal.reader

import kyo.Chunk
import kyo.Test

/** Unit tests for [[JsonStructureCounter]], the hand-rolled walkers both backends' JSON row-reader paths use.
  *
  * The walkers must track nesting and string state, so every case here puts a separator (a comma, a colon, a bracket) somewhere it must NOT count:
  * inside a nested structure, inside a string literal, behind an escape.
  */
class JsonStructureCounterTest extends Test:

    "countObjectEntries counts top-level entries only" in {
        assert(JsonStructureCounter.countObjectEntries("{}") == 0)
        assert(JsonStructureCounter.countObjectEntries("{ }") == 0)
        assert(JsonStructureCounter.countObjectEntries("""{"a":1}""") == 1)
        assert(JsonStructureCounter.countObjectEntries("""{"a":1,"b":2}""") == 2)
        assert(JsonStructureCounter.countObjectEntries("""{"a":{"b":1,"c":2}}""") == 1, "nested entries do not count")
        assert(JsonStructureCounter.countObjectEntries("""{"a":"x:y"}""") == 1, "a colon inside a string does not count")
    }

    "countArrayElements counts top-level elements only" in {
        assert(JsonStructureCounter.countArrayElements("[]") == 0)
        assert(JsonStructureCounter.countArrayElements("[ ]") == 0)
        assert(JsonStructureCounter.countArrayElements("[1]") == 1)
        assert(JsonStructureCounter.countArrayElements("[1,2,3]") == 3)
        assert(JsonStructureCounter.countArrayElements("[[1,2],[3,4]]") == 2, "nested commas do not count")
        assert(JsonStructureCounter.countArrayElements("""["a,b","c"]""") == 2, "a comma inside a string does not count")
    }

    "splitArrayElements returns each top-level element's own text" in {
        assert(JsonStructureCounter.splitArrayElements("[]") == Chunk.empty[String])
        assert(JsonStructureCounter.splitArrayElements("[ ]") == Chunk.empty[String])
        assert(JsonStructureCounter.splitArrayElements("[1,2,3]") == Chunk("1", "2", "3"))
        assert(JsonStructureCounter.splitArrayElements("[ 1 , 2 ]") == Chunk("1", "2"), "surrounding whitespace is trimmed")
    }

    "splitArrayElements keeps a nested object or array whole" in {
        assert(JsonStructureCounter.splitArrayElements("""[{"a":1},{"b":[2,3]}]""") == Chunk("""{"a":1}""", """{"b":[2,3]}"""))
        assert(JsonStructureCounter.splitArrayElements("[[1,2],[3]]") == Chunk("[1,2]", "[3]"))
    }

    "splitArrayElements does not split on a comma inside a string or behind an escape" in {
        assert(JsonStructureCounter.splitArrayElements("""["a,b","c"]""") == Chunk(""""a,b"""", """"c""""))
        assert(JsonStructureCounter.splitArrayElements("""["a\",b"]""") == Chunk(""""a\",b""""))
    }

    "splitArrayElements and countArrayElements agree" in {
        val cases = Seq("[]", "[1]", "[1,2,3]", """[{"a":1},null]""", """["x,y"]""", "[[1],[2],[3]]")
        cases.foreach { json =>
            assert(
                JsonStructureCounter.splitArrayElements(json).size == JsonStructureCounter.countArrayElements(json),
                s"the count and the split disagree for $json"
            )
        }
        succeed
    }

end JsonStructureCounterTest
