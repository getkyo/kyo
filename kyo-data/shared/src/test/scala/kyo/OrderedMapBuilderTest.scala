package kyo

class OrderedMapBuilderTest extends kyo.test.Test[Any]:

    "add" - {
        "accumulates fields in insertion order" in {
            val b = OrderedMapBuilder.init[String, String]
            b.add("_id", "a").add("name", "b").add("version", "c")
            assert(b.result().toChunk.map(_._1) == Chunk("_id", "name", "version"))
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
            val b = OrderedMapBuilder.init[Int, Int]
            (0 to 11).foreach(i => b.add(i, i * 10))
            val m = b.result()
            assert(m.size == 12)
            assert(m.toChunk.map(_._1) == Chunk.from(0 to 11))
        }
        "dedups an already-added key at its first position" in {
            val b = OrderedMapBuilder.init[Int, Int]
            (0 to 9).foreach(i => b.add(i, i))
            // 11 raw adds cross the threshold: 10 adds for keys 0..9, plus one more add(0, 999).
            b.add(0, 999)
            val m = b.result()
            assert(m.toChunk.head == (0, 999))
            assert(m.size == 10)
        }
        "demotion from the large path preserves first-seen insertion order" in {
            val b = OrderedMapBuilder.init[Int, Int]
            (1 to 3).foreach(round => (0 to 3).foreach(i => b.add(i, i * round)))
            val m = b.result()
            assert(m.size <= 8)
            assert(m.toChunk.map(_._1) == Chunk(0, 1, 2, 3))
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
            b.add("_id", 1).add("name", 2).add("version", 3)
            assert(b.result().toChunk.map(_._1) == Chunk("_id", "name", "version"))
        }
    }

    "initTransform" - {
        "used as a foreachEntry sink preserves source order" in {
            val source = scala.collection.immutable.ListMap("a" -> 1, "b" -> 2, "c" -> 3)
            val sink   = OrderedMapBuilder.initTransform[String, Int, String, Int]((b, k, v) => discard(b.add(k, v)))
            source.foreachEntry(sink)
            assert(sink.result().toChunk.map(_._1) == Chunk("a", "b", "c"))
        }
    }

end OrderedMapBuilderTest
