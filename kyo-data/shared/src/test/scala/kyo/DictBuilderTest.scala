package kyo

class DictBuilderTest extends Test:

    "empty" in {
        val b = DictBuilder.init[String, Int]
        val d = b.result()
        assert(d.isEmpty)
    }

    "single entry" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        val d = b.result()
        assert(d.size == 1)
        assert(d("a") == 1)
    }

    "multiple entries" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        b.add("b", 2)
        b.add("c", 3)
        val d = b.result()
        assert(d.size == 3)
        assert(d("a") == 1)
        assert(d("b") == 2)
        assert(d("c") == 3)
    }

    "dedup small path keeps last" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        b.add("b", 2)
        b.add("a", 10)
        val d = b.result()
        assert(d.size == 2)
        assert(d("a") == 10)
        assert(d("b") == 2)
    }

    "dedup all same key" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        b.add("a", 2)
        b.add("a", 3)
        val d = b.result()
        assert(d.size == 1)
        assert(d("a") == 3)
    }

    "at threshold" in {
        val b = DictBuilder.init[String, Int]
        (1 to 8).foreach(i => b.add(s"k$i", i))
        val d = b.result()
        assert(d.size == 8)
        assert(d("k1") == 1)
        assert(d("k8") == 8)
    }

    "over threshold" in {
        val b = DictBuilder.init[String, Int]
        (1 to 9).foreach(i => b.add(s"k$i", i))
        val d = b.result()
        assert(d.size == 9)
        assert(d("k1") == 1)
        assert(d("k9") == 9)
    }

    "dedup large path keeps last" in {
        val b = DictBuilder.init[String, Int]
        (1 to 10).foreach(i => b.add(s"k$i", i))
        (1 to 10).foreach(i => b.add(s"k$i", i * 100))
        val d = b.result()
        assert(d.size == 10)
        assert(d("k1") == 100)
        assert(d("k10") == 1000)
    }

    "dedup large path demotes to small" in {
        val b = DictBuilder.init[String, Int]
        // 20 entries but only 5 unique keys
        (1 to 4).foreach { round =>
            (1 to 5).foreach(i => b.add(s"k$i", i * round))
        }
        val d = b.result()
        assert(d.size == 5)
        assert(d("k1") == 4) // last round wins
        assert(d("k5") == 20)
    }

    "size tracks entries before result" in {
        val b = DictBuilder.init[String, Int]
        assert(b.size == 0)
        b.add("a", 1)
        assert(b.size == 1)
        b.add("b", 2)
        assert(b.size == 2)
    }

    "clear resets builder" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        b.add("b", 2)
        b.clear()
        val d = b.result()
        assert(d.isEmpty)
    }

    "add returns this for chaining" in {
        val b  = DictBuilder.init[String, Int]
        val b2 = b.add("a", 1).add("b", 2)
        assert(b eq b2)
        val d = b.result()
        assert(d.size == 2)
    }

    "initTransform" - {
        "creates combined builder and function" in {
            val b = DictBuilder.initTransform[String, Int, String, Int]((b, k, v) => discard(b.add(k, v * 10)))
            b("a", 1)
            b("b", 2)
            val d = b.result()
            assert(d.size == 2)
            assert(d("a") == 10)
            assert(d("b") == 20)
        }
        "works with foreachEntry" in {
            val source = scala.collection.immutable.HashMap("x" -> 1, "y" -> 2)
            val b      = DictBuilder.initTransform[String, Int, String, Int]((b, k, v) => discard(b.add(k.toUpperCase, v)))
            source.foreachEntry(b)
            val d = b.result()
            assert(d.size == 2)
            assert(d("X") == 1)
            assert(d("Y") == 2)
        }
        "dedup in transform" in {
            val b = DictBuilder.initTransform[String, Int, String, Int]((b, _, v) => discard(b.add("same", v)))
            b("a", 1)
            b("b", 2)
            b("c", 3)
            val d = b.result()
            assert(d.size == 1)
            assert(d("same") == 3)
        }
    }

    "value equality dedup" in {
        case class Key(value: String)
        val k1 = Key("key")
        val k2 = Key("key")
        assert(!(k1 eq k2)) // different references
        val b = DictBuilder.init[Key, Int]
        b.add(k1, 1)
        b.add(k2, 2)
        val d = b.result()
        assert(d.size == 1)
        assert(d(Key("key")) == 2)
    }

    "reuse after result" in {
        val b = DictBuilder.init[String, Int]
        b.add("a", 1)
        val d1 = b.result()
        assert(d1.size == 1)
        assert(d1("a") == 1)
        b.add("b", 2)
        b.add("c", 3)
        val d2 = b.result()
        assert(d2.size == 2)
        assert(d2("b") == 2)
        assert(d2("c") == 3)
    }

    "nested builders" in {
        val outer = DictBuilder.init[String, Int]
        outer.add("a", 1)
        val inner = DictBuilder.init[String, Int]
        inner.add("x", 10)
        inner.add("y", 20)
        val innerDict = inner.result()
        outer.add("b", 2)
        val outerDict = outer.result()
        assert(innerDict.size == 2)
        assert(innerDict("x") == 10)
        assert(innerDict("y") == 20)
        assert(outerDict.size == 2)
        assert(outerDict("a") == 1)
        assert(outerDict("b") == 2)
    }

end DictBuilderTest
