package kyo

import kyo.internal.readField
import kyo.internal.writeField

class SchemaBridgeTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "writeField then readField round-trips a primitive through its given schema" in {
        val w = new TestWriter
        writeField(summon[Schema[Int]], 42, w)
        val r = new TestReader(w.resultTokens)
        val v = readField(summon[Schema[Int]], r)
        assert(v == 42)
    }

    "writeField then readField round-trips a derived case class through its schema" in {
        case class Pair(a: Int, b: String) derives CanEqual, Schema
        val w = new TestWriter
        writeField(summon[Schema[Pair]], Pair(7, "x"), w)
        val r = new TestReader(w.resultTokens)
        val v = readField(summon[Schema[Pair]], r)
        assert(v == Pair(7, "x"))
    }

end SchemaBridgeTest
