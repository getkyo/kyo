package kyo

import scala.util.control.NonFatal

class OrderedMapTest extends kyo.test.Test[Any]:

    def largeMap: OrderedMap[Int, Int] =
        val b = OrderedMapBuilder.init[Int, Int]
        (0 to 9).foreach(i => b.add(i, i * 10))
        b.result()
    end largeMap

    "empty" - {
        "creates an empty map" in {
            val m = OrderedMap.empty[String, Int]
            assert(m.size == 0)
            assert(m.isEmpty)
            assert(!m.nonEmpty)
        }
    }

    "apply" - {
        "constructs entries in insertion order" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            assert(m.size == 3)
            assert(m.toChunk.map(_._1) == Chunk("a", "b", "c"))
        }
        "duplicate key keeps first position and last value" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "a" -> 3)
            assert(m.toChunk == Chunk(("a", 3), ("b", 2)))
        }
        "strict-missing throws and get-absent returns Absent" in {
            val m = OrderedMap.empty[String, Int]
            interceptThrown[NoSuchElementException] {
                m("missing")
            }
            assert(m.get("missing").isEmpty)
        }
    }

    "from" - {
        "preserves source ListMap iteration order" in {
            val src = scala.collection.immutable.ListMap("x" -> 1, "y" -> 2, "z" -> 3)
            val m   = OrderedMap.from(src)
            assert(m.toChunk.map(_._1) == Chunk("x", "y", "z"))
        }
    }

    "getOrElse and contains" - {
        "returns present value, default value, and membership" in {
            val m = OrderedMap("a" -> 1)
            assert(m.getOrElse("a", 0) == 1)
            assert(m.getOrElse("z", 9) == 9)
            assert(m.contains("a"))
            assert(!m.contains("z"))
        }
    }

    "update" - {
        "keeps an existing key at its position on the small path" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            val r = m.update("a", 10)
            assert(r.toChunk.map(_._1) == Chunk("a", "b", "c"))
            assert(r("a") == 10)
        }
        "appends a new key at the end on the small path" in {
            val m = OrderedMap("a" -> 1, "b" -> 2)
            val r = m.update("c", 3)
            assert(r.toChunk == Chunk(("a", 1), ("b", 2), ("c", 3)))
        }
        "keeps position 0 on the large (TreeSeqMap) path" in {
            val large  = largeMap
            val result = large.update(0, 999)
            assert(large.size > 8)
            assert(result.toChunk.head._1 == 0)
            assert(result(0) == 999)
        }
        "promotion across the threshold preserves insertion order" in {
            var m = OrderedMap.empty[Int, Int]
            (0 to 7).foreach(i => m = m.update(i, i))
            val r = m.update(8, 8)
            assert(r.size == 9)
            assert(r.toChunk.map(_._1) == Chunk(0, 1, 2, 3, 4, 5, 6, 7, 8))
        }
    }

    "remove" - {
        "preserves survivor order and re-add appends afresh" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            val r = m.remove("a").update("a", 99)
            assert(r.toChunk.map(_._1) == Chunk("b", "c", "a"))
            assert(r("a") == 99)
        }
        "of an absent key returns self unchanged" in {
            val m = OrderedMap("a" -> 1)
            val r = m.remove("z")
            assert(r.toChunk == Chunk(("a", 1)))
        }
        "large-path remove preserves insertion order of survivors" in {
            val r = largeMap.remove(5)
            assert(r.toChunk.map(_._1) == Chunk(0, 1, 2, 3, 4, 6, 7, 8, 9))
        }
    }

    "concat" - {
        "is position-stable with last-wins value" in {
            val base  = OrderedMap("a" -> 1, "b" -> 2)
            val extra = OrderedMap("c" -> 3, "a" -> 9)
            assert(base.concat(extra).toChunk == Chunk(("a", 9), ("b", 2), ("c", 3)))
        }
        "++ delegates to concat identically" in {
            val base  = OrderedMap("a" -> 1, "b" -> 2)
            val extra = OrderedMap("c" -> 3, "a" -> 9)
            assert((base ++ extra).toChunk == base.concat(extra).toChunk)
            assert((base ++ extra).toChunk == Chunk(("a", 9), ("b", 2), ("c", 3)))
        }
        "with an empty operand short-circuits to the non-empty side" in {
            val m = OrderedMap("a" -> 1)
            val e = OrderedMap.empty[String, Int]
            assert(m.concat(e).toChunk == Chunk(("a", 1)))
            assert(e.concat(m).toChunk == Chunk(("a", 1)))
        }
    }

    "foreach and foldLeft" - {
        "traverse in insertion order" in {
            val m    = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            m.foreach((k, _) => discard(keys += k))
            assert(keys.toList == List("a", "b", "c"))
            assert(m.foldLeft(0)((acc, _, v) => acc + v) == 6)
        }
    }

    "forall, exists, count, find" - {
        "evaluate over entries" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            assert(m.forall((_, v) => v > 0))
            assert(m.exists((_, v) => v == 2))
            assert(m.count((_, v) => v > 1) == 2)
            assert(m.find((_, v) => v == 2) == Maybe(("b", 2)))
        }
    }

    "map and flatMap" - {
        "preserve source order" in {
            val m      = OrderedMap("a" -> 1, "b" -> 2)
            val mapped = m.map((k, v) => (k, v * 10))
            assert(mapped.toChunk == Chunk(("a", 10), ("b", 20)))
            val flat = m.flatMap((k, v) => OrderedMap(k -> v, (k + "!") -> v))
            assert(flat.toChunk.map(_._1) == Chunk("a", "a!", "b", "b!"))
        }
    }

    "filter, filterNot, collect, mapValues" - {
        "preserve order" in {
            val m = OrderedMap("a" -> 1, "b" -> 2, "c" -> 3)
            assert(m.filter((_, v) => v > 1).toChunk == Chunk(("b", 2), ("c", 3)))
            assert(m.filterNot((_, v) => v > 1).toChunk == Chunk(("a", 1)))
            assert(m.collect { case (k, v) if v > 1 => (k, v) }.toChunk == Chunk(("b", 2), ("c", 3)))
            assert(m.mapValues(_ * 2).toChunk == Chunk(("a", 2), ("b", 4), ("c", 6)))
        }
    }

    "keys, values, toChunk" - {
        "are insertion-ordered on the large path" in {
            val m = largeMap
            assert(m.keys.toArray.toList == (0 to 9).toList)
            assert(m.values.toArray.toList == (0 to 9).map(_ * 10).toList)
            assert(m.toChunk.map(_._1) == Chunk.from(0 to 9))
        }
    }

    "toMap" - {
        "carries entry values" in {
            val m = OrderedMap("a" -> 1, "b" -> 2)
            assert(m.toMap == Map("a" -> 1, "b" -> 2))
        }
    }

    "is" - {
        "is structural and order-independent" in {
            val m1 = OrderedMap("x" -> 1, "y" -> 2)
            val m2 = OrderedMap("y" -> 2, "x" -> 1)
            assert(m1.is(m2))
            assert(!m1.is(OrderedMap.empty[String, Int]))
        }
    }

    "mkString" - {
        "variants render in insertion order" in {
            val m = OrderedMap("a" -> 1, "b" -> 2)
            assert(m.mkString == "a -> 1b -> 2")
            assert(m.mkString(", ") == "a -> 1, b -> 2")
            assert(m.mkString("[", ",", "]") == "[a -> 1,b -> 2]")
        }
    }

    "Flag.Reader" - {
        val reader = summon[Flag.Reader[OrderedMap[String, Int]]]

        "decodes a valid key=value list in order" in {
            val result = reader("a=1,b=2")
            assert(result.isRight)
            assert(result.toOption.get.toChunk == Chunk(("a", 1), ("b", 2)))
        }

        "returns Left on a malformed segment" in {
            assert(reader("a=1,not-a-valid-entry").isLeft)
        }

        "returns Right(empty) on empty or whitespace input" in {
            val emptyResult = reader("")
            assert(emptyResult.isRight)
            assert(emptyResult.toOption.get.isEmpty)
            val whitespaceResult = reader("   ")
            assert(whitespaceResult.isRight)
            assert(whitespaceResult.toOption.get.isEmpty)
        }

        "routes hostile input to Left without an escaping throw" in {
            var escaped = false
            val result =
                try reader("=noKey,alsoBad")
                catch
                    case NonFatal(_) =>
                        escaped = true
                        Left(new IllegalStateException("unreachable"))
            assert(!escaped)
            assert(result.isLeft)
        }
    }

end OrderedMapTest
