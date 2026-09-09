package kyo

// Regression matrix for issue #1747: the Protobuf codec must round-trip empty
// collection values, nested collections, and optional-valued maps, keeping
// distinct values distinct on the wire.

class ProtobufCollectionsTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def roundTrip[A](value: A)(using Schema[A], Frame, kyo.test.AssertScope): Unit =
        val bytes   = Protobuf.encode(value)
        val decoded = Protobuf.decode[A](bytes)
        assert(
            decoded == Result.succeed(value),
            s"round-trip mismatch: expected $value, got $decoded, bytes ${bytes.toArray.map(b => f"$b%02x").mkString}"
        )
    end roundTrip

    // Dict and OrderedDict are opaque types without structural ==; compare their contents.
    private def roundTripDict[K, V](value: Dict[K, V])(using Schema[Dict[K, V]], Frame, kyo.test.AssertScope): Unit =
        val bytes   = Protobuf.encode(value)
        val decoded = Protobuf.decode[Dict[K, V]](bytes)
        assert(
            decoded.map(_.toMap) == Result.succeed(value.toMap),
            s"round-trip mismatch: expected ${value.toMap}, got ${decoded.map(_.toMap)}, bytes ${bytes.toArray.map(b => f"$b%02x").mkString}"
        )
    end roundTripDict

    private def roundTripOrderedDict[K, V](value: OrderedDict[K, V])(using
        Schema[OrderedDict[K, V]],
        Frame,
        kyo.test.AssertScope
    ): Unit =
        val bytes   = Protobuf.encode(value)
        val decoded = Protobuf.decode[OrderedDict[K, V]](bytes)
        assert(
            decoded.map(_.toChunk) == Result.succeed(value.toChunk),
            s"round-trip mismatch: expected ${value.toChunk}, got ${decoded.map(_.toChunk)}, bytes ${bytes.toArray.map(b => f"$b%02x").mkString}"
        )
    end roundTripOrderedDict

    "empty collection map values" - {
        "first position" in {
            roundTrip(Map(1 -> Chunk.empty[String], 2 -> Chunk("b"), 3 -> Chunk("c")))
        }
        "middle position" in {
            roundTrip(Map(1 -> Chunk("a"), 2 -> Chunk.empty[String], 3 -> Chunk("c")))
        }
        "last position" in {
            roundTrip(Map(1 -> Chunk("a"), 2 -> Chunk.empty[String]))
        }
        "multiple empties" in {
            roundTrip(Map(1 -> Chunk.empty[String], 2 -> Chunk("b"), 3 -> Chunk.empty[String]))
        }
        "all empty" in {
            roundTrip(Map(1 -> Chunk.empty[String], 2 -> Chunk.empty[String]))
        }
        "string-keyed map" in {
            roundTrip(Map("a" -> Chunk.empty[Int], "b" -> Chunk(1)))
        }
        "Dict string-keyed" in {
            roundTripDict(Dict("a" -> Chunk.empty[Int], "b" -> Chunk(1)))
        }
        "Dict non-string-keyed" in {
            roundTripDict(Dict(1 -> Chunk.empty[String], 2 -> Chunk("b")))
        }
        "OrderedDict non-string-keyed" in {
            roundTripOrderedDict(OrderedDict(1 -> Chunk.empty[String], 2 -> Chunk("b")))
        }
    }

    "nested collections keep boundaries" - {
        "map value: empty vs one-empty vs two-empties are distinct" in {
            val a = Map(1 -> Chunk.empty[Chunk[String]])
            val b = Map(1 -> Chunk(Chunk.empty[String]))
            val c = Map(1 -> Chunk(Chunk.empty[String], Chunk.empty[String]))
            roundTrip(a)
            roundTrip(b)
            roundTrip(c)
            assert(Protobuf.encode(a) != Protobuf.encode(b))
            assert(Protobuf.encode(b) != Protobuf.encode(c))
        }
        "map value: non-empty nested" in {
            roundTrip(Map(1 -> Chunk(Chunk("a"), Chunk("b", "c"))))
        }
        "field: List[List[Int]] boundaries" in {
            roundTrip(PCBoxII(List(List(1, 2), List(3))))
            roundTrip(PCBoxII(List(List(1, 2, 3))))
            assert(Protobuf.encode(PCBoxII(List(List(1, 2), List(3)))) != Protobuf.encode(PCBoxII(List(List(1, 2, 3)))))
        }
        "field: List[List[String]] boundaries" in {
            roundTrip(PCBoxSS(List(List("a"), List("b", "c"))))
            roundTrip(PCBoxSS(List(List("a", "b"), List("c"))))
            assert(Protobuf.encode(PCBoxSS(List(List("a"), List("b", "c")))) != Protobuf.encode(PCBoxSS(List(List("a", "b"), List("c")))))
        }
        "field: empty inner lists survive" in {
            roundTrip(PCBoxII(List(List.empty, List(1))))
            roundTrip(PCBoxII(List(List.empty)))
            roundTrip(PCBoxII(List.empty))
            assert(Protobuf.encode(PCBoxII(List(List.empty))) != Protobuf.encode(PCBoxII(List.empty)))
        }
        "triple nesting" in {
            roundTrip(PCBoxTriple(List(List(List(1), List.empty), List.empty)))
        }
        "nested collection of messages" in {
            roundTrip(PCBoxMsg(List(List(PCItem(1)), List.empty, List(PCItem(2), PCItem(3)))))
        }
    }

    "optional values" - {
        "Maybe-valued map" in {
            roundTrip(Map("a" -> Maybe(1), "b" -> Maybe.empty[Int]))
        }
        "Option-valued map" in {
            roundTrip(Map("a" -> Option(1), "b" -> Option.empty[Int]))
        }
    }

    "collection-valued maps still round-trip" - {
        "non-empty values" in {
            roundTrip(Map("a" -> List(1, 2), "b" -> List(3)))
        }
        "message values" in {
            roundTrip(Map(1 -> Chunk(PCItem(1), PCItem(2)), 2 -> Chunk.empty[PCItem]))
        }
    }

    "dict with non-string keys round-trips" - {
        "Dict[Int, String]" in {
            roundTripDict(Dict(1 -> "one", 2 -> "two"))
        }
        "Dict[Int, Int] (both packable)" in {
            roundTripDict(Dict(1 -> 10, 2 -> 20))
        }
    }
end ProtobufCollectionsTest

// Top-level fixtures: `derives` clauses on locals cannot summon the enclosing
// test's givens cleanly, and shared fixtures keep each case terse.
case class PCItem(n: Int) derives Schema, CanEqual
case class PCBoxII(xs: List[List[Int]]) derives Schema, CanEqual
case class PCBoxSS(xs: List[List[String]]) derives Schema, CanEqual
case class PCBoxTriple(xs: List[List[List[Int]]]) derives Schema, CanEqual
case class PCBoxMsg(xs: List[List[PCItem]]) derives Schema, CanEqual
