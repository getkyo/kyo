package kyo.net.internal.util

import kyo.*
import kyo.net.Test

/** Direct correctness tests for the open-addressing primitive maps [[IntLongMap]] and [[IntRefMap]] backing the poller's confined fd tables.
  *
  * The backward-shift (Robin-Hood) removal is the bug-prone part: a wrong shift silently breaks a probe chain so a later lookup of a present
  * key returns absent. These tests force collisions into one probe chain (keys that hash to the same slot at a known small capacity), then remove
  * from the middle of the chain and assert every survivor is still findable, exercising the shift directly. Grow correctness (rehash preserves
  * all entries) and the absent sentinels are covered too.
  */
class IntMapTest extends Test:

    "IntLongMap" - {

        "put/getOrElse/contains for present and absent keys" in {
            val m = new IntLongMap(16)
            m.put(3, 30L)
            m.put(7, 70L)
            assert(m.contains(3))
            assert(m.contains(7))
            assert(!m.contains(5))
            assert(m.getOrElse(3, -1L) == 30L)
            assert(m.getOrElse(7, -1L) == 70L)
            assert(m.getOrElse(5, -999L) == -999L)
            assert(m.size == 2)
            succeed
        }

        "put overwrites an existing key without growing size" in {
            val m = new IntLongMap(16)
            m.put(3, 30L)
            m.put(3, 31L)
            assert(m.size == 1)
            assert(m.getOrElse(3, -1L) == 31L)
            succeed
        }

        "remove keeps a colliding probe chain intact (backward-shift)" in {
            // Capacity 8 -> mask 7. Keys 1, 9, 17 all hash to slot 1 and form one probe chain (slots 1,2,3). Removing the chain head must
            // shift 9 and 17 back so both stay findable; a tombstone-free remove is the property under test.
            val m = new IntLongMap(8)
            m.put(1, 100L)
            m.put(9, 900L)
            m.put(17, 1700L)
            assert(m.getOrElse(1, -1L) == 100L)
            assert(m.getOrElse(9, -1L) == 900L)
            assert(m.getOrElse(17, -1L) == 1700L)
            m.remove(1)
            assert(!m.contains(1))
            assert(m.getOrElse(9, -1L) == 900L, "9 lost after removing chain head 1")
            assert(m.getOrElse(17, -1L) == 1700L, "17 lost after removing chain head 1")
            assert(m.size == 2)
            // Remove the middle of the remaining chain too.
            m.remove(9)
            assert(!m.contains(9))
            assert(m.getOrElse(17, -1L) == 1700L, "17 lost after removing middle 9")
            assert(m.size == 1)
            succeed
        }

        "remove of an absent key is a no-op" in {
            val m = new IntLongMap(8)
            m.put(2, 20L)
            m.remove(99)
            assert(m.size == 1)
            assert(m.getOrElse(2, -1L) == 20L)
            succeed
        }

        "grow rehashes every entry past the load-factor threshold" in {
            val m = new IntLongMap(4)
            var i = 0
            while i < 100 do
                m.put(i, i.toLong * 10L)
                i += 1
            end while
            assert(m.size == 100)
            i = 0
            while i < 100 do
                assert(m.getOrElse(i, -1L) == i.toLong * 10L, s"key $i lost across grow")
                i += 1
            end while
            succeed
        }

        "put rejects the reserved empty-slot key (-1) rather than corrupting the table" in {
            // -1 is the Empty free-slot marker. Storing it would write the marker into a live slot, so the slot reads as free and every later
            // lookup of a real key in that probe chain silently fails. The contract is that fd keys are >= 0; a negative key is a caller bug
            // and is rejected, not silently accepted. This pins the enforced contract so a future change cannot regress it to silent corruption.
            val m = new IntLongMap(8)
            m.put(2, 20L)
            val ex = intercept[IllegalArgumentException](m.put(-1, 999L))
            assert(ex.getMessage.contains("must be >= 0"))
            // The pre-existing entry is intact: the rejected put did not touch the table.
            assert(m.size == 1)
            assert(m.getOrElse(2, -7L) == 20L)
            // The sentinel is still treated as absent (never spuriously present from the rejected put).
            assert(!m.contains(-1))
            assert(m.getOrElse(-1, -7L) == -7L)
            succeed
        }
    }

    "IntRefMap" - {

        "put/get/remove for present and absent keys" in {
            val m = new IntRefMap[String](16)
            m.put(3, "a")
            m.put(7, "b")
            assert(m.get(3) == "a")
            assert(m.get(7) == "b")
            assert(m.get(5) == null)
            val removed = m.remove(3)
            assert(removed == "a")
            assert(m.get(3) == null)
            assert(m.remove(5) == null)
            assert(m.size == 1)
            succeed
        }

        "remove keeps a colliding probe chain intact (backward-shift)" in {
            val m = new IntRefMap[String](8)
            m.put(1, "one")
            m.put(9, "nine")
            m.put(17, "seventeen")
            assert(m.remove(1) == "one")
            assert(m.get(9) == "nine", "9 lost after removing chain head 1")
            assert(m.get(17) == "seventeen", "17 lost after removing chain head 1")
            assert(m.size == 2)
            succeed
        }

        "foreach visits every entry exactly once" in {
            val m = new IntRefMap[String](8)
            m.put(1, "one")
            m.put(2, "two")
            m.put(3, "three")
            val seen = scala.collection.mutable.Map.empty[Int, String]
            m.foreach((k, v) => seen.update(k, v))
            assert(seen == scala.collection.mutable.Map(1 -> "one", 2 -> "two", 3 -> "three"))
            succeed
        }

        "clear drops every entry" in {
            val m = new IntRefMap[String](8)
            m.put(1, "one")
            m.put(2, "two")
            m.clear()
            assert(m.size == 0)
            assert(m.get(1) == null)
            assert(m.get(2) == null)
            succeed
        }

        "grow rehashes every reference past the load-factor threshold" in {
            val m = new IntRefMap[String](4)
            var i = 0
            while i < 100 do
                m.put(i, s"v$i")
                i += 1
            end while
            assert(m.size == 100)
            i = 0
            while i < 100 do
                assert(m.get(i) == s"v$i", s"key $i lost across grow")
                i += 1
            end while
            succeed
        }

        "put rejects the reserved empty-slot key (-1) rather than corrupting the table" in {
            // Same contract as IntLongMap: -1 is the Empty marker, fd keys are >= 0, a negative key is rejected rather than silently corrupting
            // the probe chain (which would read the live slot as free and lose every colliding key).
            val m = new IntRefMap[String](8)
            m.put(2, "two")
            val ex = intercept[IllegalArgumentException](m.put(-1, "boom"))
            assert(ex.getMessage.contains("must be >= 0"))
            assert(m.size == 1)
            assert(m.get(2) == "two")
            assert(!m.contains(-1))
            assert(m.get(-1) == null)
            succeed
        }
    }
end IntMapTest
