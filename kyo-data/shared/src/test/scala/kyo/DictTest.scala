package kyo

class DictTest extends Test:

    def largeDict: Dict[String, Int] =
        var d = Dict.empty[String, Int]
        (1 to 20).foreach(i => d = d.update(s"k$i", i))
        d
    end largeDict

    def smallDict: Dict[String, Int] =
        Dict("a" -> 1, "b" -> 2, "c" -> 3)

    "empty" - {
        "creates empty dict" in {
            val d = Dict.empty[String, Int]
            assert(d.size == 0)
            assert(d.isEmpty)
            assert(!d.nonEmpty)
        }
    }

    "apply" - {
        "creates from varargs" in {
            val d = Dict("a" -> 1, "b" -> 2, "c" -> 3)
            assert(d.size == 3)
            assert(d("a") == 1)
            assert(d("b") == 2)
            assert(d("c") == 3)
        }
        "creates empty from no args" in {
            val d = Dict[String, Int]()
            assert(d.isEmpty)
        }
        "creates large dict" in {
            val entries = (1 to 20).map(i => s"k$i" -> i)
            val d       = Dict(entries*)
            assert(d.size == 20)
            assert((1 to 20).forall(i => d(s"k$i") == i))
        }
        "single entry" in {
            val d = Dict("x" -> 42)
            assert(d.size == 1)
            assert(d("x") == 42)
        }
        "duplicate keys keep last" in {
            val d = Dict("a" -> 1, "a" -> 2)
            assert(d.size == 1)
            assert(d("a") == 2)
        }
        "multiple duplicate keys" in {
            val d = Dict("a" -> 1, "b" -> 2, "a" -> 10, "b" -> 20, "c" -> 3)
            assert(d.size == 3)
            assert(d("a") == 10)
            assert(d("b") == 20)
            assert(d("c") == 3)
        }
        "many dups demotes from large to small" in {
            // 12 entries but only 4 unique keys
            val entries = (1 to 3).flatMap(round => (1 to 4).map(i => s"k$i" -> (i * round)))
            val d       = Dict(entries*)
            assert(d.size == 4)
            assert(d("k1") == 3) // last round wins
            assert(d("k4") == 12)
        }
        "returns value for existing key" in {
            assert(smallDict("a") == 1)
        }
        "throws for missing key small" in {
            assertThrows[NoSuchElementException] {
                smallDict("z")
            }
        }
        "throws for missing key large" in {
            assertThrows[NoSuchElementException] {
                largeDict("missing")
            }
        }
        "throws on empty" in {
            assertThrows[NoSuchElementException] {
                Dict.empty[String, Int]("x")
            }
        }
        "large" in {
            assert(largeDict("k1") == 1)
            assert(largeDict("k20") == 20)
        }
    }

    "from" - {
        "creates from Map" in {
            val d = Dict.from(Map("a" -> 1, "b" -> 2))
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("b") == 2)
        }
        "creates from empty Map" in {
            val d = Dict.from(Map.empty[String, Int])
            assert(d.isEmpty)
        }
        "reuses HashMap directly" in {
            val hm = scala.collection.immutable.HashMap("a" -> 1, "b" -> 2)
            val d  = Dict.from(hm)
            assert(d.size == 2)
            assert(d("a") == 1)
        }
        "creates from non-HashMap Map" in {
            val d = Dict.from(scala.collection.immutable.TreeMap("b" -> 2, "a" -> 1))
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("b") == 2)
        }
    }

    "size" - {
        "empty" in {
            assert(Dict.empty[String, Int].size == 0)
        }
        "small" in {
            assert(smallDict.size == 3)
        }
        "large" in {
            assert(largeDict.size == 20)
        }
    }

    "isEmpty/nonEmpty" - {
        "empty" in {
            assert(Dict.empty[String, Int].isEmpty)
            assert(!Dict.empty[String, Int].nonEmpty)
        }
        "non-empty small" in {
            assert(!smallDict.isEmpty)
            assert(smallDict.nonEmpty)
        }
        "non-empty large" in {
            assert(!largeDict.isEmpty)
            assert(largeDict.nonEmpty)
        }
    }

    "get" - {
        "returns Present for existing key" in {
            assert(smallDict.get("a") == Maybe(1))
        }
        "returns Absent for missing key" in {
            assert(smallDict.get("z").isEmpty)
        }
        "empty dict" in {
            assert(Dict.empty[String, Int].get("a").isEmpty)
        }
        "large present" in {
            assert(largeDict.get("k5") == Maybe(5))
        }
        "large absent" in {
            assert(largeDict.get("missing").isEmpty)
        }
        "first and last key small" in {
            assert(smallDict.get("a") == Maybe(1))
            assert(smallDict.get("c") == Maybe(3))
        }
    }

    "getOrElse" - {
        "returns value for existing key" in {
            assert(smallDict.getOrElse("a", 99) == 1)
        }
        "returns default for missing key" in {
            assert(smallDict.getOrElse("z", 99) == 99)
        }
        "does not evaluate default when key exists small" in {
            var evaluated = false
            smallDict.getOrElse("a", { evaluated = true; 99 })
            assert(!evaluated)
        }
        "does not evaluate default when key exists large" in {
            var evaluated = false
            largeDict.getOrElse("k1", { evaluated = true; 99 })
            assert(!evaluated)
        }
        "empty dict" in {
            assert(Dict.empty[String, Int].getOrElse("a", 42) == 42)
        }
        "large present" in {
            assert(largeDict.getOrElse("k1", 99) == 1)
        }
        "large absent" in {
            assert(largeDict.getOrElse("missing", 99) == 99)
        }
    }

    "contains" - {
        "true for existing key" in {
            assert(smallDict.contains("a"))
        }
        "false for missing key" in {
            assert(!smallDict.contains("z"))
        }
        "empty dict" in {
            assert(!Dict.empty[String, Int].contains("a"))
        }
        "large present" in {
            assert(largeDict.contains("k1"))
        }
        "large absent" in {
            assert(!largeDict.contains("missing"))
        }
        "all keys present small" in {
            assert(smallDict.contains("a"))
            assert(smallDict.contains("b"))
            assert(smallDict.contains("c"))
        }
    }

    "update" - {
        "on empty" in {
            val d = Dict.empty[String, Int].update("a", 1)
            assert(d.size == 1)
            assert(d("a") == 1)
        }
        "adds new key to small" in {
            val d = smallDict.update("d", 4)
            assert(d.size == 4)
            assert(d("d") == 4)
            assert(d("a") == 1)
        }
        "replaces existing key in small" in {
            val d = smallDict.update("a", 99)
            assert(d.size == 3)
            assert(d("a") == 99)
            assert(d("b") == 2) // others unchanged
        }
        "replaces last key in small" in {
            val d = smallDict.update("c", 99)
            assert(d.size == 3)
            assert(d("c") == 99)
        }
        "promotes to large at threshold" in {
            var d = Dict.empty[String, Int]
            (1 to 9).foreach(i => d = d.update(s"k$i", i))
            assert(d.size == 9)
            assert((1 to 9).forall(i => d(s"k$i") == i))
        }
        "updates existing key in large" in {
            val d = largeDict.update("k1", 99)
            assert(d("k1") == 99)
            assert(d.size == 20)
        }
        "adds new key to large" in {
            val d = largeDict.update("new", 99)
            assert(d("new") == 99)
            assert(d.size == 21)
        }
        "replace with same value" in {
            val d = smallDict.update("a", 1)
            assert(d.size == 3)
            assert(d("a") == 1)
        }
        "does not affect original" in {
            val original = smallDict
            val updated  = original.update("a", 99)
            assert(original("a") == 1)
            assert(updated("a") == 99)
        }
    }

    "remove" - {
        "removes existing key from small" in {
            val d = smallDict.remove("b")
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("c") == 3)
            assert(!d.contains("b"))
        }
        "removes first key from small" in {
            val d = smallDict.remove("a")
            assert(d.size == 2)
            assert(!d.contains("a"))
            assert(d("b") == 2)
            assert(d("c") == 3)
        }
        "removes last key from small" in {
            val d = smallDict.remove("c")
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("b") == 2)
        }
        "no-op for missing key in small" in {
            val d = smallDict.remove("z")
            assert(d.size == 3)
        }
        "removes to empty" in {
            val d = Dict("a" -> 1).remove("a")
            assert(d.isEmpty)
        }
        "removes from large" in {
            val d = largeDict.remove("k1")
            assert(!d.contains("k1"))
            assert(d.size == 19)
        }
        "no-op for missing key in large" in {
            val d = largeDict.remove("missing")
            assert(d.size == 20)
        }
        "no-op on empty" in {
            val d = Dict.empty[String, Int].remove("a")
            assert(d.isEmpty)
        }
        "multiple removes from large" in {
            var d = largeDict
            (1 to 12).foreach(i => d = d.remove(s"k$i"))
            assert(d.size == 8)
            assert((13 to 20).forall(i => d(s"k$i") == i))
        }
        "does not affect original" in {
            val original = smallDict
            val removed  = original.remove("a")
            assert(original.contains("a"))
            assert(!removed.contains("a"))
        }
    }

    "concat" - {
        "small ++ small" in {
            val d = Dict("a" -> 1) ++ Dict("b" -> 2)
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("b") == 2)
        }
        "empty ++ small" in {
            val d = Dict.empty[String, Int] ++ smallDict
            assert(d.size == 3)
            assert(d("a") == 1)
        }
        "small ++ empty" in {
            val d = smallDict ++ Dict.empty[String, Int]
            assert(d.size == 3)
            assert(d("a") == 1)
        }
        "empty ++ empty" in {
            val d = Dict.empty[String, Int] ++ Dict.empty[String, Int]
            assert(d.isEmpty)
        }
        "produces large when exceeding threshold" in {
            val a = Dict((1 to 5).map(i => s"a$i" -> i)*)
            val b = Dict((1 to 5).map(i => s"b$i" -> i)*)
            val d = a ++ b
            assert(d.size == 10)
        }
        "small ++ large" in {
            val d = smallDict ++ largeDict
            assert(d.size == 23)
            assert(d("a") == 1)
            assert(d("k1") == 1)
        }
        "large ++ small" in {
            val d = largeDict ++ smallDict
            assert(d.size == 23)
            assert(d("a") == 1)
            assert(d("k1") == 1)
        }
        "large ++ large" in {
            val d = largeDict ++ Dict("extra" -> 99)
            assert(d.size == 21)
            assert(d("extra") == 99)
        }
        "dedup overlapping keys small" in {
            val d = Dict("a" -> 1, "b" -> 2) ++ Dict("b" -> 99, "c" -> 3)
            assert(d.size == 3)
            assert(d("a") == 1)
            assert(d("b") == 99) // right side wins
            assert(d("c") == 3)
        }
        "dedup overlapping keys large" in {
            val d = largeDict ++ largeDict.map((k, v) => (k, v * 10))
            assert(d.size == 20)
            assert(d("k1") == 10)
            assert(d("k20") == 200)
        }
        "dedup many dups stays small" in {
            val entries = (1 to 3).map(i => s"k$i" -> i) ++ (1 to 7).map(i => s"k$i" -> (i * 10))
            val d       = Dict(entries*)
            assert(d.size == 7)
            assert(d("k1") == 10)
            assert(d("k3") == 30)
        }
    }

    "foreach" - {
        "iterates all entries small" in {
            var sum = 0
            smallDict.foreach((_, v) => sum += v)
            assert(sum == 6)
        }
        "collects all keys small" in {
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            smallDict.foreach((k, _) => discard(keys += k))
            assert(keys.sorted == Seq("a", "b", "c"))
        }
        "iterates all entries large" in {
            var sum = 0
            largeDict.foreach((_, v) => sum += v)
            assert(sum == (1 to 20).sum)
        }
        "empty" in {
            var count = 0
            Dict.empty[String, Int].foreach((_, _) => count += 1)
            assert(count == 0)
        }
    }

    "foreachKey" - {
        "iterates keys small" in {
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            smallDict.foreachKey(k => discard(keys += k))
            assert(keys.sorted == Seq("a", "b", "c"))
        }
        "iterates keys large" in {
            val keys = scala.collection.mutable.ArrayBuffer.empty[String]
            largeDict.foreachKey(k => discard(keys += k))
            assert(keys.size == 20)
            assert(keys.contains("k1"))
            assert(keys.contains("k20"))
        }
        "empty" in {
            var count = 0
            Dict.empty[String, Int].foreachKey(_ => count += 1)
            assert(count == 0)
        }
    }

    "foreachValue" - {
        "iterates values small" in {
            val vals = scala.collection.mutable.ArrayBuffer.empty[Int]
            smallDict.foreachValue(v => discard(vals += v))
            assert(vals.sorted == Seq(1, 2, 3))
        }
        "iterates values large" in {
            val vals = scala.collection.mutable.ArrayBuffer.empty[Int]
            largeDict.foreachValue(v => discard(vals += v))
            assert(vals.size == 20)
            assert(vals.contains(1))
            assert(vals.contains(20))
        }
        "empty" in {
            var count = 0
            Dict.empty[String, Int].foreachValue(_ => count += 1)
            assert(count == 0)
        }
    }

    "forall" - {
        "true when all match small" in {
            assert(smallDict.forall((_, v) => v > 0))
        }
        "false when some don't match small" in {
            assert(!smallDict.forall((_, v) => v > 2))
        }
        "true on empty" in {
            assert(Dict.empty[String, Int].forall((_, _) => false))
        }
        "true when all match large" in {
            assert(largeDict.forall((_, v) => v > 0))
        }
        "false when some don't match large" in {
            assert(!largeDict.forall((_, v) => v > 10))
        }
        "checks keys too" in {
            assert(smallDict.forall((k, _) => k.length == 1))
            assert(!smallDict.forall((k, _) => k == "a"))
        }
    }

    "exists" - {
        "true when some match small" in {
            assert(smallDict.exists((_, v) => v == 2))
        }
        "false when none match small" in {
            assert(!smallDict.exists((_, v) => v > 10))
        }
        "false on empty" in {
            assert(!Dict.empty[String, Int].exists((_, _) => true))
        }
        "true when match in large" in {
            assert(largeDict.exists((_, v) => v == 15))
        }
        "false when no match large" in {
            assert(!largeDict.exists((_, v) => v > 100))
        }
        "checks keys too" in {
            assert(smallDict.exists((k, _) => k == "b"))
            assert(!smallDict.exists((k, _) => k == "z"))
        }
    }

    "count" - {
        "counts matching entries small" in {
            assert(smallDict.count((_, v) => v > 1) == 2)
        }
        "counts all" in {
            assert(smallDict.count((_, _) => true) == 3)
        }
        "counts none" in {
            assert(smallDict.count((_, _) => false) == 0)
        }
        "zero on empty" in {
            assert(Dict.empty[String, Int].count((_, _) => true) == 0)
        }
        "large" in {
            assert(largeDict.count((_, v) => v > 10) == 10)
        }
        "large count all" in {
            assert(largeDict.count((_, _) => true) == 20)
        }
    }

    "find" - {
        "finds matching entry small" in {
            val r = smallDict.find((_, v) => v == 2)
            assert(r == Maybe(("b", 2)))
        }
        "returns empty when no match small" in {
            assert(smallDict.find((_, v) => v > 10).isEmpty)
        }
        "finds matching entry large" in {
            val r = largeDict.find((_, v) => v == 15)
            assert(r == Maybe(("k15", 15)))
        }
        "returns empty when no match large" in {
            assert(largeDict.find((_, v) => v > 100).isEmpty)
        }
        "empty dict" in {
            assert(Dict.empty[String, Int].find((_, _) => true).isEmpty)
        }
        "finds by key" in {
            val r = smallDict.find((k, _) => k == "c")
            assert(r == Maybe(("c", 3)))
        }
    }

    "foldLeft" - {
        "accumulates values small" in {
            val sum = smallDict.foldLeft(0)((acc, _, v) => acc + v)
            assert(sum == 6)
        }
        "accumulates keys small" in {
            val keys = smallDict.foldLeft("")((acc, k, _) => acc + k)
            assert(keys.sorted == "abc")
        }
        "large" in {
            val sum = largeDict.foldLeft(0)((acc, _, v) => acc + v)
            assert(sum == (1 to 20).sum)
        }
        "empty" in {
            val sum = Dict.empty[String, Int].foldLeft(0)((acc, _, v) => acc + v)
            assert(sum == 0)
        }
        "preserves initial value on empty" in {
            val r = Dict.empty[String, Int].foldLeft(42)((acc, _, v) => acc + v)
            assert(r == 42)
        }
    }

    "map" - {
        "transforms entries small" in {
            val d = smallDict.map((k, v) => (k.toUpperCase, v * 10))
            assert(d.size == 3)
            assert(d("A") == 10)
            assert(d("B") == 20)
            assert(d("C") == 30)
        }
        "transforms entries large" in {
            val d = largeDict.map((k, v) => (k, v * 2))
            assert(d("k1") == 2)
            assert(d("k20") == 40)
            assert(d.size == 20)
        }
        "empty" in {
            val d = Dict.empty[String, Int].map((k, v) => (k, v))
            assert(d.isEmpty)
        }
        "dedup when mapping to same key keeps last" in {
            val d = Dict("a" -> 1, "b" -> 2, "c" -> 3).map((_, v) => ("same", v))
            assert(d.size == 1)
            assert(d("same") == 3)
        }
    }

    "flatMap" - {
        "expands entries small" in {
            val d = smallDict.flatMap((k, v) => Dict(k -> v, (k + k) -> (v * 2)))
            assert(d.size == 6)
            assert(d("a") == 1)
            assert(d("aa") == 2)
        }
        "expands entries large" in {
            val d = largeDict.flatMap((k, v) => Dict(k -> (v * 10)))
            assert(d.size == 20)
            assert(d("k1") == 10)
        }
        "empty inner" in {
            val d = smallDict.flatMap((_, _) => Dict.empty[String, Int])
            assert(d.isEmpty)
        }
        "empty outer" in {
            val d = Dict.empty[String, Int].flatMap((k, v) => Dict(k -> v))
            assert(d.isEmpty)
        }
        "dedup overlapping keys" in {
            val d = Dict("a" -> 1, "b" -> 2).flatMap((_, v) => Dict("x" -> v))
            assert(d.size == 1)
            assert(d("x") == 2)
        }
    }

    "filter" - {
        "filters entries small" in {
            val d = smallDict.filter((_, v) => v > 1)
            assert(d.size == 2)
            assert(!d.contains("a"))
            assert(d("b") == 2)
            assert(d("c") == 3)
        }
        "keeps all" in {
            val d = smallDict.filter((_, _) => true)
            assert(d.size == 3)
            assert(d("a") == 1)
        }
        "filters to empty" in {
            val d = smallDict.filter((_, v) => v > 10)
            assert(d.isEmpty)
        }
        "filters large" in {
            val d = largeDict.filter((_, v) => v <= 5)
            assert(d.size == 5)
            assert(d("k1") == 1)
            assert(d("k5") == 5)
            assert(!d.contains("k6"))
        }
        "filters large to empty" in {
            val d = largeDict.filter((_, _) => false)
            assert(d.isEmpty)
        }
        "filters large keeps all" in {
            val d = largeDict.filter((_, _) => true)
            assert(d.size == 20)
        }
        "empty" in {
            val d = Dict.empty[String, Int].filter((_, _) => true)
            assert(d.isEmpty)
        }
        "filter by key" in {
            val d = smallDict.filter((k, _) => k != "b")
            assert(d.size == 2)
            assert(d("a") == 1)
            assert(d("c") == 3)
        }
    }

    "filterNot" - {
        "filters out matching small" in {
            val d = smallDict.filterNot((_, v) => v == 2)
            assert(d.size == 2)
            assert(!d.contains("b"))
            assert(d("a") == 1)
            assert(d("c") == 3)
        }
        "filters out matching large" in {
            val d = largeDict.filterNot((_, v) => v > 10)
            assert(d.size == 10)
            assert(d.contains("k1"))
            assert(d.contains("k10"))
            assert(!d.contains("k11"))
        }
        "filters none" in {
            val d = smallDict.filterNot((_, _) => false)
            assert(d.size == 3)
        }
        "filters all" in {
            val d = smallDict.filterNot((_, _) => true)
            assert(d.isEmpty)
        }
    }

    "collect" - {
        "collects matching entries" in {
            val d = smallDict.collect { case (k, v) if v > 1 => (k.toUpperCase, v * 10) }
            assert(d.size == 2)
            assert(d("B") == 20)
            assert(d("C") == 30)
        }
        "no matches returns empty" in {
            val d = smallDict.collect { case (k, v) if v > 100 => (k, v) }
            assert(d.isEmpty)
        }
        "large" in {
            val d = largeDict.collect { case (k, v) if v <= 3 => (k, v * 100) }
            assert(d.size == 3)
            assert(d("k1") == 100)
            assert(d("k2") == 200)
            assert(d("k3") == 300)
        }
        "all match" in {
            val d = smallDict.collect { case (k, v) => (k, v) }
            assert(d.size == 3)
        }
        "empty" in {
            val d = Dict.empty[String, Int].collect { case (k, v) => (k, v) }
            assert(d.isEmpty)
        }
    }

    "mapValues" - {
        "transforms values small" in {
            val d = smallDict.mapValues(_ * 10)
            assert(d.size == 3)
            assert(d("a") == 10)
            assert(d("b") == 20)
            assert(d("c") == 30)
        }
        "large" in {
            val d = largeDict.mapValues(_ * 2)
            assert(d.size == 20)
            assert(d("k1") == 2)
            assert(d("k20") == 40)
        }
        "empty" in {
            val d = Dict.empty[String, Int].mapValues(_ * 2)
            assert(d.isEmpty)
        }
        "preserves keys" in {
            val d = smallDict.mapValues(_ => 0)
            assert(d.contains("a"))
            assert(d.contains("b"))
            assert(d.contains("c"))
            assert(d("a") == 0)
        }
    }

    "keys" - {
        "returns keys small" in {
            val k = smallDict.keys
            assert(k.size == 3)
            assert(k.contains("a"))
            assert(k.contains("b"))
            assert(k.contains("c"))
        }
        "returns keys large" in {
            val k = largeDict.keys
            assert(k.size == 20)
            assert(k.contains("k1"))
            assert(k.contains("k20"))
        }
        "empty" in {
            val k = Dict.empty[String, Int].keys
            assert(k.size == 0)
        }
    }

    "values" - {
        "returns values small" in {
            val v = smallDict.values
            assert(v.size == 3)
            assert(v.contains(1))
            assert(v.contains(2))
            assert(v.contains(3))
        }
        "returns values large" in {
            val v = largeDict.values
            assert(v.size == 20)
            assert(v.contains(1))
            assert(v.contains(20))
        }
        "empty" in {
            val v = Dict.empty[String, Int].values
            assert(v.size == 0)
        }
    }

    "toMap" - {
        "small" in {
            assert(smallDict.toMap == Map("a" -> 1, "b" -> 2, "c" -> 3))
        }
        "large" in {
            val m = largeDict.toMap
            assert(m.size == 20)
            assert(m("k1") == 1)
            assert(m("k20") == 20)
        }
        "empty" in {
            assert(Dict.empty[String, Int].toMap == Map.empty[String, Int])
        }
    }

    "is" - {
        "equal small dicts" in {
            assert(smallDict.is(Dict("a" -> 1, "b" -> 2, "c" -> 3)))
        }
        "different values" in {
            assert(!smallDict.is(Dict("a" -> 1, "b" -> 2, "c" -> 99)))
        }
        "different sizes" in {
            assert(!smallDict.is(Dict("a" -> 1)))
        }
        "different keys same size" in {
            assert(!smallDict.is(Dict("a" -> 1, "b" -> 2, "x" -> 3)))
        }
        "empty dicts" in {
            assert(Dict.empty[String, Int].is(Dict.empty[String, Int]))
        }
        "empty vs non-empty" in {
            assert(!Dict.empty[String, Int].is(smallDict))
            assert(!smallDict.is(Dict.empty[String, Int]))
        }
        "large vs large equal" in {
            val d1 = largeDict
            val d2 = largeDict
            assert(d1.is(d2))
        }
        "large vs large different value" in {
            val d1 = largeDict
            val d2 = largeDict.update("k1", 999)
            assert(!d1.is(d2))
        }
        "large vs large different key" in {
            val d1 = largeDict
            val d2 = largeDict.remove("k1").update("extra", 1)
            assert(!d1.is(d2))
        }
        "small vs large with same entries" in {
            val entries = (1 to 8).map(i => s"k$i" -> i)
            val s       = Dict(entries*)
            var l       = Dict.empty[String, Int]
            (1 to 20).foreach(i => l = l.update(s"k$i", i))
            (9 to 20).foreach(i => l = l.remove(s"k$i"))
            assert(s.is(l))
        }
    }

    "mkString" - {
        "with separator" in {
            val d = Dict("a" -> 1)
            assert(d.mkString(", ") == "a -> 1")
        }
        "with start/sep/end" in {
            val d = Dict("a" -> 1)
            assert(d.mkString("{", ", ", "}") == "{a -> 1}")
        }
        "no args" in {
            val d = Dict("a" -> 1)
            assert(d.mkString == "a -> 1")
        }
        "empty" in {
            assert(Dict.empty[String, Int].mkString(", ") == "")
        }
        "empty with start/end" in {
            assert(Dict.empty[String, Int].mkString("{", ", ", "}") == "{}")
        }
        "multiple entries contain separator" in {
            val s = smallDict.mkString(", ")
            assert(s.contains("a -> 1"))
            assert(s.contains("b -> 2"))
            assert(s.contains("c -> 3"))
            assert(s.contains(", "))
        }
    }

    "threshold boundary" - {
        "exactly at threshold" in {
            val d = Dict((1 to 8).map(i => s"k$i" -> i)*)
            assert(d.size == 8)
            assert(d("k1") == 1)
            assert(d("k8") == 8)
        }
        "one over threshold" in {
            val d = Dict((1 to 9).map(i => s"k$i" -> i)*)
            assert(d.size == 9)
            assert(d("k1") == 1)
            assert(d("k9") == 9)
        }
        "update at threshold boundary" in {
            var d = Dict.empty[String, Int]
            (1 to 8).foreach(i => d = d.update(s"k$i", i))
            assert(d.size == 8)
            // adding one more crosses threshold
            val d2 = d.update("k9", 9)
            assert(d2.size == 9)
            assert(d2("k1") == 1)
            assert(d2("k9") == 9)
        }
    }

    "roundtrip" - {
        "toMap then from small" in {
            val d  = smallDict
            val d2 = Dict.from(d.toMap)
            assert(d.is(d2))
        }
        "toMap then from large" in {
            val d  = largeDict
            val d2 = Dict.from(d.toMap)
            assert(d.is(d2))
        }
    }

    "identity-based key lookup" - {
        "finds key by reference equality" in {
            val key = new String("test") // avoid interning
            val d   = Dict(key -> 1)
            assert(d(key) == 1)
        }
        "finds key by value equality" in {
            val k1 = new String("test")
            val k2 = new String("test") // different reference, same value
            val d  = Dict(k1 -> 1)
            assert(d(k2) == 1)
        }
    }

end DictTest
