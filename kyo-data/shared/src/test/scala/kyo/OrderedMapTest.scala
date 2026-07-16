package kyo

class OrderedMapTest extends kyo.test.Test[Any]:

    def largeMapInsertOrder: Seq[Int] = Seq(3, 1, 4, 0, 9, 2, 6, 5, 8, 7)

    def largeMap: OrderedMap[Int, Int] =
        val b = OrderedMapBuilder.init[Int, Int]
        largeMapInsertOrder.foreach(i => b.add(i, i * 10))
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
            val m = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3)
            assert(m.size == 3)
            assert(m.toChunk.map(_._1) == Chunk("zeta", "alpha", "mike"))
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
        "returns present entries and throws for a missing key on the large path" in {
            assert(largeMap(3) == 30)
            assert(largeMap(7) == 70)
            interceptThrown[NoSuchElementException] {
                largeMap(100)
            }
        }
    }

    "from" - {
        "preserves source ListMap iteration order" in {
            val src = scala.collection.immutable.ListMap("bravo" -> 1, "yankee" -> 2, "delta" -> 3)
            val m   = OrderedMap.from(src)
            assert(m.toChunk.map(_._1) == Chunk("bravo", "yankee", "delta"))
        }
    }

    "get" - {
        "returns Present for an existing key and Absent for a missing key" in {
            val m = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3)
            assert(m.get("zeta") == Maybe(1))
            assert(m.get("missing").isEmpty)
        }
        "returns Present for an existing key and Absent for a missing key on the large path" in {
            assert(largeMap.get(9) == Maybe(90))
            assert(largeMap.get(100).isEmpty)
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
        "returns present value, default value, and membership on the large path" in {
            assert(largeMap.getOrElse(9, -1) == 90)
            assert(largeMap.getOrElse(100, -1) == -1)
            assert(largeMap.contains(6))
            assert(!largeMap.contains(100))
        }
    }

    "isEmpty and nonEmpty" - {
        "are false and true, respectively, for a non-empty small map" in {
            val m = OrderedMap("zeta" -> 1)
            assert(!m.isEmpty)
            assert(m.nonEmpty)
        }
        "are false and true, respectively, on the large path" in {
            assert(!largeMap.isEmpty)
            assert(largeMap.nonEmpty)
        }
    }

    "update" - {
        "keeps an existing key at its position on the small path" in {
            val m = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4, "yankee" -> 5)
            val r = m.update("alpha", 20)
            assert(r.toChunk.map(_._1) == Chunk("zeta", "alpha", "mike", "bravo", "yankee"))
            assert(r("alpha") == 20)
        }
        "appends a new key at the end on the small path" in {
            val m = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4)
            val r = m.update("yankee", 5)
            assert(r.toChunk == Chunk(("zeta", 1), ("alpha", 2), ("mike", 3), ("bravo", 4), ("yankee", 5)))
        }
        "keeps position 0 on the large (TreeSeqMap) path" in {
            val large    = largeMap
            val firstKey = largeMapInsertOrder.head
            assert(large.size > 8)
            assert(large.toChunk.head._1 == firstKey)
            val result = large.update(firstKey, 999)
            assert(result.toChunk.head._1 == firstKey)
            assert(result(firstKey) == 999)
        }
        "promotion across the threshold preserves insertion order" in {
            var m           = OrderedMap.empty[Int, Int]
            val insertOrder = Seq(5, 2, 7, 0, 6, 1, 4, 3)
            insertOrder.foreach(i => m = m.update(i, i))
            val r = m.update(8, 8)
            assert(r.size == 9)
            assert(r.toChunk.map(_._1) == Chunk.from(insertOrder :+ 8))
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
            assert(r.toChunk.map(_._1) == Chunk.from(largeMapInsertOrder.filterNot(_ == 5)))
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
        "is position-stable with last-wins value on the large path" in {
            val extra = OrderedMap(9 -> 999, 11 -> 110, 10 -> 100)
            val r     = largeMap.concat(extra)
            assert(r.toChunk.map(_._1) == Chunk.from(largeMapInsertOrder ++ Seq(11, 10)))
            assert(r(9) == 999)
        }
    }

    "foreach and foldLeft" - {
        "traverse in insertion order" in {
            val m    = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4, "yankee" -> 5)
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            m.foreach((k, _) => discard(keys += k))
            assert(keys.toList == List("zeta", "alpha", "mike", "bravo", "yankee"))
            assert(m.foldLeft(0)((acc, _, v) => acc + v) == 15)
        }
        "traverse in insertion order on the large path" in {
            val keys = scala.collection.mutable.ArrayBuffer.empty[Int]
            largeMap.foreach((k, _) => discard(keys += k))
            assert(keys.toList == largeMapInsertOrder.toList)
            assert(largeMap.foldLeft(0)((acc, _, v) => acc + v) == largeMapInsertOrder.map(_ * 10).sum)
        }
    }

    "foreachKey and foreachValue" - {
        "iterate in insertion order" in {
            val m    = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4, "yankee" -> 5)
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            m.foreachKey(k => discard(keys += k))
            assert(keys.toList == List("zeta", "alpha", "mike", "bravo", "yankee"))
            val values = scala.collection.mutable.ArrayBuffer.empty[Int]
            m.foreachValue(v => discard(values += v))
            assert(values.toList == List(1, 2, 3, 4, 5))
        }
        "iterate in insertion order on the large path" in {
            val keys = scala.collection.mutable.ArrayBuffer.empty[Int]
            largeMap.foreachKey(k => discard(keys += k))
            assert(keys.toList == largeMapInsertOrder.toList)
            val values = scala.collection.mutable.ArrayBuffer.empty[Int]
            largeMap.foreachValue(v => discard(values += v))
            assert(values.toList == largeMapInsertOrder.map(_ * 10).toList)
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
        "evaluate over entries on the large path" in {
            assert(largeMap.forall((_, v) => v >= 0))
            assert(!largeMap.forall((k, _) => k < 5))
            assert(largeMap.exists((_, v) => v == 90))
            assert(!largeMap.exists((_, v) => v == 999))
            assert(largeMap.count((_, v) => v >= 50) == 5)
            assert(largeMap.find((_, v) => v == 90) == Maybe((9, 90)))
        }
    }

    "map and flatMap" - {
        "preserve source order" in {
            val m      = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4, "yankee" -> 5)
            val mapped = m.map((k, v) => (k, v * 10))
            assert(mapped.toChunk == Chunk(("zeta", 10), ("alpha", 20), ("mike", 30), ("bravo", 40), ("yankee", 50)))
            val m2   = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4)
            val flat = m2.flatMap((k, v) => OrderedMap(k -> v, (k + "!") -> v))
            assert(flat.toChunk.map(_._1) == Chunk("zeta", "zeta!", "alpha", "alpha!", "mike", "mike!", "bravo", "bravo!"))
        }
        "preserve source order on the large path" in {
            val mapped = largeMap.map((k, v) => (k, v + 1))
            assert(mapped.toChunk.map(_._1) == Chunk.from(largeMapInsertOrder))
            assert(mapped.toChunk.map(_._2) == Chunk.from(largeMapInsertOrder.map(_ * 10 + 1)))
            val flat = largeMap.flatMap((k, v) => OrderedMap(k -> (v + 1)))
            assert(flat.toChunk.map(_._1) == Chunk.from(largeMapInsertOrder))
            assert(flat.toChunk.map(_._2) == Chunk.from(largeMapInsertOrder.map(_ * 10 + 1)))
        }
    }

    "filter, filterNot, collect, mapValues" - {
        "preserve order" in {
            val m = OrderedMap(
                "zeta"    -> 1,
                "alpha"   -> 2,
                "mike"    -> 3,
                "bravo"   -> 4,
                "yankee"  -> 5,
                "delta"   -> 6,
                "hotel"   -> 7,
                "foxtrot" -> 8
            )
            assert(m.filter((_, v) => v > 3).toChunk == Chunk(("bravo", 4), ("yankee", 5), ("delta", 6), ("hotel", 7), ("foxtrot", 8)))
            assert(m.filterNot((_, v) => v > 5).toChunk == Chunk(("zeta", 1), ("alpha", 2), ("mike", 3), ("bravo", 4), ("yankee", 5)))
            assert(
                m.collect { case (k, v) if v > 2 => (k, v * 2) }.toChunk ==
                    Chunk(("mike", 6), ("bravo", 8), ("yankee", 10), ("delta", 12), ("hotel", 14), ("foxtrot", 16))
            )
            assert(
                m.mapValues(_ * 100).toChunk ==
                    Chunk(
                        ("zeta", 100),
                        ("alpha", 200),
                        ("mike", 300),
                        ("bravo", 400),
                        ("yankee", 500),
                        ("delta", 600),
                        ("hotel", 700),
                        ("foxtrot", 800)
                    )
            )
        }
        "preserve order on the large path, including demotion below the threshold" in {
            val kept    = largeMapInsertOrder.filter(_ % 2 == 0)    // 4,0,2,6,8 -> 5 entries, demotes
            val keptNot = largeMapInsertOrder.filterNot(_ % 2 == 0) // 3,1,9,5,7 -> 5 entries, demotes
            assert(largeMap.filter((k, _) => k % 2 == 0).toChunk.map(_._1) == Chunk.from(kept))
            assert(largeMap.filterNot((k, _) => k % 2 == 0).toChunk.map(_._1) == Chunk.from(keptNot))
            assert(
                largeMap.collect { case (k, v) if k % 2 == 0 => (k, v + 1) }.toChunk.map(_._1) == Chunk.from(kept)
            )
            assert(largeMap.mapValues(_ + 1).toChunk.map(_._2) == Chunk.from(largeMapInsertOrder.map(_ * 10 + 1)))
        }
    }

    "keys, values, toChunk" - {
        "are insertion-ordered on the large path" in {
            val m = largeMap
            assert(m.keys.toArray.toList == largeMapInsertOrder.toList)
            assert(m.values.toArray.toList == largeMapInsertOrder.map(_ * 10).toList)
            assert(m.toChunk.map(_._1) == Chunk.from(largeMapInsertOrder))
        }
    }

    "toMap" - {
        "carries entry values" in {
            val m = OrderedMap("a" -> 1, "b" -> 2)
            assert(m.toMap == Map("a" -> 1, "b" -> 2))
        }
        "carries entry values on the large path" in {
            val m = largeMap.toMap
            assert(m.size == 10)
            assert(m == largeMapInsertOrder.map(k => (k, k * 10)).toMap)
        }
    }

    "is" - {
        "is structural and order-independent" in {
            val m1 = OrderedMap("x" -> 1, "y" -> 2)
            val m2 = OrderedMap("y" -> 2, "x" -> 1)
            assert(m1.is(m2))
            assert(!m1.is(OrderedMap.empty[String, Int]))
        }
        "is structural and order-independent on the large path" in {
            val b = OrderedMapBuilder.init[Int, Int]
            largeMapInsertOrder.reverse.foreach(k => discard(b.add(k, k * 10)))
            val differentlyOrdered = b.result()
            assert(largeMap.is(differentlyOrdered))
            assert(!largeMap.is(differentlyOrdered.update(3, 999)))
            assert(!largeMap.is(differentlyOrdered.remove(3)))
        }
    }

    "mkString" - {
        "variants render in insertion order" in {
            val m = OrderedMap("zeta" -> 1, "alpha" -> 2, "mike" -> 3, "bravo" -> 4, "yankee" -> 5)
            assert(m.mkString == "zeta -> 1alpha -> 2mike -> 3bravo -> 4yankee -> 5")
            assert(m.mkString(", ") == "zeta -> 1, alpha -> 2, mike -> 3, bravo -> 4, yankee -> 5")
            assert(m.mkString("[", ",", "]") == "[zeta -> 1,alpha -> 2,mike -> 3,bravo -> 4,yankee -> 5]")
        }
        "renders in insertion order on the large path" in {
            assert(
                largeMap.mkString(", ") ==
                    "3 -> 30, 1 -> 10, 4 -> 40, 0 -> 0, 9 -> 90, 2 -> 20, 6 -> 60, 5 -> 50, 8 -> 80, 7 -> 70"
            )
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
            val outcome = Result.catching[Exception](reader("=noKey,alsoBad"))
            assert(outcome.isSuccess)
            assert(outcome.getOrThrow.isLeft)
        }
    }

end OrderedMapTest
