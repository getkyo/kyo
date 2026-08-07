package kyo.internal

import kyo.*

class PathTrieTest extends kyo.test.Test[Any]:

    private def parts(segs: String*): Chunk[String] = Chunk.from(segs.toIndexedSeq)

    private val abc = parts("a", "b", "c")

    "get returns the stored value and Absent elsewhere" in {
        val trie = PathTrie.empty[Int].updated(abc, 7)
        assert(trie.get(abc) == Present(7))
        assert(trie.get(parts("a")) == Absent)
        assert(trie.get(parts("a", "b")) == Absent)
        assert(trie.get(parts("a", "b", "d")) == Absent)
        assert(trie.get(parts("z")) == Absent)
    }

    "intermediate nodes exist structurally without carrying a value" in {
        val trie = PathTrie.empty[Int].updated(abc, 7)
        assert(trie.nodeAt(parts("a", "b")).isDefined)
        assert(trie.nodeAt(parts("a", "b")).get.value == Absent)
        assert(trie.nodeAt(parts("a", "q")) == Absent)
    }

    "updated replaces an existing value without disturbing siblings" in {
        val trie = PathTrie.empty[Int]
            .updated(abc, 1)
            .updated(parts("a", "b", "d"), 2)
            .updated(abc, 3)
        assert(trie.get(abc) == Present(3))
        assert(trie.get(parts("a", "b", "d")) == Present(2))
    }

    "removed clears the value and prunes nodes that become empty" in {
        val trie = PathTrie.empty[Int].updated(abc, 7).removed(abc)
        assert(trie.get(abc) == Absent)
        assert(trie.nodeAt(parts("a")) == Absent, "fully empty branch should be pruned")
    }

    "removed keeps a node that still has valued descendants" in {
        val trie = PathTrie.empty[Int]
            .updated(parts("a", "b"), 1)
            .updated(abc, 2)
            .removed(parts("a", "b"))
        assert(trie.get(parts("a", "b")) == Absent)
        assert(trie.get(abc) == Present(2))
    }

    "removed on an absent path leaves the trie unchanged" in {
        val trie = PathTrie.empty[Int].updated(abc, 7)
        assert(trie.removed(parts("x", "y")) == trie)
    }

    "removedSubtree drops the path and everything beneath it" in {
        val trie = PathTrie.empty[Int]
            .updated(parts("a", "b"), 1)
            .updated(abc, 2)
            .updated(parts("a", "b", "c", "d"), 3)
            .updated(parts("a", "z"), 4)
            .removedSubtree(parts("a", "b"))
        assert(trie.get(parts("a", "b")) == Absent)
        assert(trie.get(abc) == Absent)
        assert(trie.get(parts("a", "b", "c", "d")) == Absent)
        assert(trie.get(parts("a", "z")) == Present(4), "sibling must survive")
    }

    "nearestAncestorValue returns the deepest strict ancestor carrying a value" in {
        val trie = PathTrie.empty[String]
            .updated(parts("a"), "shallow")
            .updated(parts("a", "b"), "deep")
            .updated(abc, "self")
        assert(trie.nearestAncestorValue(abc) == Present("deep"))
    }

    "nearestAncestorValue skips structural nodes with no value" in {
        val trie = PathTrie.empty[String]
            .updated(parts("a"), "shallow")
            .updated(abc, "self")
        // a/b exists structurally but carries no value, so the answer is a's value.
        assert(trie.nearestAncestorValue(abc) == Present("shallow"))
    }

    "nearestAncestorValue excludes the path itself and the root" in {
        val trie = PathTrie.empty[String].updated(abc, "self").updated(Chunk.empty, "root")
        assert(trie.nearestAncestorValue(abc) == Absent)
        assert(trie.nearestAncestorValue(parts("a")) == Absent, "single segment has no strict ancestor")
        assert(trie.nearestAncestorValue(Chunk.empty) == Absent)
    }

    "nearestAncestorValue stops at a branch that does not exist" in {
        val trie = PathTrie.empty[String].updated(parts("x"), "other")
        assert(trie.nearestAncestorValue(abc) == Absent)
    }

    "childValues returns only direct children that carry a value" in {
        val trie = PathTrie.empty[Int]
            .updated(parts("a", "b"), 1)
            .updated(parts("a", "c", "deep"), 2)
            .updated(parts("a", "d"), 3)
        val got = trie.childValues(parts("a")).sortBy(_._1)
        assert(got == Chunk(("b", 1), ("d", 3)), s"c is structural only, got: $got")
    }

    "childValues on an absent or childless path is empty" in {
        val trie = PathTrie.empty[Int].updated(abc, 1)
        assert(trie.childValues(parts("nope")) == Chunk.empty)
        assert(trie.childValues(abc) == Chunk.empty)
    }

    "descendantValues returns everything strictly beneath a path" in {
        val trie = PathTrie.empty[Int]
            .updated(parts("a"), 0)
            .updated(parts("a", "b"), 1)
            .updated(abc, 2)
            .updated(parts("a", "z"), 3)
            .updated(parts("other"), 9)
        val got = trie.descendantValues(parts("a"))
        assert(got == Chunk((parts("a", "b"), 1), (abc, 2), (parts("a", "z"), 3)), s"got: $got")
    }

    "entries returns every value with its full path in stable order" in {
        val trie = PathTrie.empty[Int]
            .updated(parts("b"), 2)
            .updated(parts("a"), 1)
            .updated(parts("a", "x"), 3)
        assert(trie.entries == Chunk((parts("a"), 1), (parts("a", "x"), 3), (parts("b"), 2)))
    }

    "the empty trie has no entries and reports isEmpty" in {
        assert(PathTrie.empty[Int].entries == Chunk.empty)
        assert(PathTrie.empty[Int].isEmpty)
        assert(!PathTrie.empty[Int].updated(abc, 1).isEmpty)
    }

    "updates share structure rather than mutating the previous version" in {
        val base    = PathTrie.empty[Int].updated(abc, 1)
        val derived = base.updated(parts("a", "b", "d"), 2)
        assert(base.get(parts("a", "b", "d")) == Absent, "previous version must be unchanged")
        assert(derived.get(abc) == Present(1))
        assert(derived.get(parts("a", "b", "d")) == Present(2))
    }

    "a deep path does not overflow the stack" in {
        // Depth well past any real filesystem: Linux caps a whole path at 4096 bytes, so 2000
        // segments cannot occur in practice and only exercises recursion depth.
        // The results are bound before asserting so the assertion diagram never renders the trie
        // itself; rendering a structure this deep costs far more than the operations under test.
        val deep     = Chunk.from((1 to 2000).map(i => s"seg$i").toIndexedSeq)
        val trie     = PathTrie.empty[Int].updated(deep, 42)
        val found    = trie.get(deep)
        val ancestor = trie.nearestAncestorValue(deep)
        assert(found == Present(42))
        assert(ancestor == Absent)
    }

end PathTrieTest
