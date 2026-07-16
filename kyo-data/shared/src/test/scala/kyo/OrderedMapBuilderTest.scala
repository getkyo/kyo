package kyo

class OrderedMapBuilderTest extends kyo.test.Test[Any]:

    "add" - {
        "accumulates fields in insertion order" in {
            val b = OrderedMapBuilder.init[String, String]
            b.add("version", "a").add("_id", "b").add("name", "c")
            assert(b.result().toChunk.map(_._1) == Chunk("version", "_id", "name"))
        }
        "of an already-added key replaces value in place keeping position" in {
            val b = OrderedMapBuilder.init[String, Int]
            b.add("a", 1).add("b", 2).add("a", 3)
            assert(b.result().toChunk == Chunk(("a", 3), ("b", 2)))
        }
    }

    "size and clear" - {
        "size counts added entries and clear empties the builder" in {
            val b = OrderedMapBuilder.init[String, Int]
            b.add("a", 1)
            b.add("b", 2)
            assert(b.size == 2)
            b.clear()
            assert(b.size == 0)
        }
    }

    "result" - {
        "resets the builder so successive results do not alias" in {
            val b = OrderedMapBuilder.init[String, Int]
            b.add("a", 1)
            val m1 = b.result()
            b.add("b", 2)
            val m2 = b.result()
            assert(m1.size == 1)
            assert(m2.size == 1)
            assert(m1.toChunk == Chunk(("a", 1)))
            assert(m2.toChunk == Chunk(("b", 2)))
        }
    }

    "large build" - {
        "preserves insertion order above the threshold" in {
            val b           = OrderedMapBuilder.init[Int, Int]
            val insertOrder = Seq(7, 2, 11, 0, 5, 9, 1, 8, 3, 10, 4, 6)
            insertOrder.foreach(i => b.add(i, i * 10))
            val m = b.result()
            assert(m.size == 12)
            assert(m.toChunk.map(_._1) == Chunk.from(insertOrder))
        }
        "dedups an already-added key at its first position" in {
            val b           = OrderedMapBuilder.init[Int, Int]
            val insertOrder = Seq(3, 1, 4, 0, 9, 2, 6, 5, 8, 7)
            insertOrder.foreach(i => b.add(i, i))
            // 11 raw adds cross the threshold: 10 adds for the distinct keys, plus one more add(0, 999).
            b.add(0, 999)
            val m = b.result()
            assert(m.size == 10)
            assert(m.toChunk.map(_._1) == Chunk.from(insertOrder))
            assert(m(0) == 999)
        }
        "demotion from the large path preserves first-seen insertion order" in {
            val b           = OrderedMapBuilder.init[Int, Int]
            val firstSeen   = Seq(3, 1, 0, 2)
            val roundOrders = Seq(firstSeen, Seq(0, 2, 3, 1), Seq(1, 3, 2, 0))
            roundOrders.zipWithIndex.foreach { case (order, idx) =>
                val round = idx + 1
                order.foreach(i => b.add(i, i * round))
            }
            val m = b.result()
            assert(m.size == 4)
            assert(m.toChunk.map(_._1) == Chunk.from(firstSeen))
            assert(m.toChunk.map(_._2) == Chunk.from(firstSeen.map(_ * 3)))
        }
    }

    "pool reentrancy" - {
        "nested/interleaved builders do not alias" in {
            val b1 = OrderedMapBuilder.init[String, Int]
            val b2 = OrderedMapBuilder.init[String, Int]
            b1.add("a", 1)
            b2.add("x", 9)
            b1.add("b", 2)
            b2.add("y", 8)
            assert(b1.result().toChunk == Chunk(("a", 1), ("b", 2)))
            assert(b2.result().toChunk == Chunk(("x", 9), ("y", 8)))
        }
    }

    "document shape" - {
        "builder + toChunk yields stable field-name order" in {
            val b = OrderedMapBuilder.init[String, Int]
            b.add("version", 1).add("_id", 2).add("name", 3)
            assert(b.result().toChunk.map(_._1) == Chunk("version", "_id", "name"))
        }
    }

    "initTransform" - {
        "used as a foreachEntry sink preserves source order" in {
            val source = scala.collection.immutable.ListMap("mike" -> 1, "yankee" -> 2, "delta" -> 3)
            val sink   = OrderedMapBuilder.initTransform[String, Int, String, Int]((b, k, v) => discard(b.add(k, v)))
            source.foreachEntry(sink)
            assert(sink.result().toChunk.map(_._1) == Chunk("mike", "yankee", "delta"))
        }
    }

end OrderedMapBuilderTest
