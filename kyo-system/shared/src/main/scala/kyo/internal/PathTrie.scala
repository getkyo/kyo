package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** A persistent trie keyed by path segments, carrying an optional value at every node.
  *
  * Filesystem backends stage entries in a hierarchical namespace and then ask hierarchical questions
  * of it: what is at this path, what are its direct children, what lies beneath it, and does any
  * ancestor shadow it. A flat `Map[Chunk[String], A]` answers the first cheaply and the rest by
  * scanning, because the key structure the questions rely on is not represented.
  *
  * This trie represents it. A lookup descends one segment at a time, so an ancestor walk reuses the
  * descent it was already performing, listing a directory reads one node's children, and a subtree
  * is a node. Updates share structure with the previous version, so a value of this type is safe to
  * publish through an `AtomicRef` and advance with a compare-and-set loop.
  *
  * Interior nodes may hold no value: inserting `a/b/c` creates `a` and `a/b` as structural nodes
  * with [[Absent]] values. Operations that report entries distinguish the two, so a caller sees only
  * segments that were actually inserted.
  *
  * @tparam A
  *   the value carried at a node
  */
final private[kyo] case class PathTrie[A](value: Maybe[A], children: Map[String, PathTrie[A]]):

    /** Returns the value stored at `parts`, or [[Absent]] when no value was inserted there. */
    def get(parts: Chunk[String]): Maybe[A] =
        nodeAt(parts).flatMap(_.value)

    /** Returns the node at `parts`, including structural nodes that carry no value. */
    def nodeAt(parts: Chunk[String]): Maybe[PathTrie[A]] =
        @tailrec def loop(node: PathTrie[A], i: Int): Maybe[PathTrie[A]] =
            if i >= parts.size then Present(node)
            else
                node.children.get(parts(i)) match
                    case Some(child) => loop(child, i + 1)
                    case None        => Absent
        loop(this, 0)
    end nodeAt

    /** Stores `a` at `parts`, creating any missing intermediate nodes without values. */
    def updated(parts: Chunk[String], a: A): PathTrie[A] =
        def loop(node: PathTrie[A], i: Int): PathTrie[A] =
            if i >= parts.size then node.copy(value = Present(a))
            else
                val seg   = parts(i)
                val child = node.children.getOrElse(seg, PathTrie.empty[A])
                node.copy(children = node.children.updated(seg, loop(child, i + 1)))
        loop(this, 0)
    end updated

    /** Clears the value at `parts`, keeping the node when it still has children and pruning it
      * otherwise. Pruning keeps an ancestor walk from stopping at a node that no longer carries
      * anything.
      */
    def removed(parts: Chunk[String]): PathTrie[A] =
        def loop(node: PathTrie[A], i: Int): PathTrie[A] =
            if i >= parts.size then node.copy(value = Absent)
            else
                val seg = parts(i)
                node.children.get(seg) match
                    case None => node
                    case Some(child) =>
                        val updatedChild = loop(child, i + 1)
                        if updatedChild.value.isEmpty && updatedChild.children.isEmpty then
                            node.copy(children = node.children - seg)
                        else node.copy(children = node.children.updated(seg, updatedChild))
                end match
        loop(this, 0)
    end removed

    /** Removes `parts` and everything beneath it. */
    def removedSubtree(parts: Chunk[String]): PathTrie[A] =
        if parts.isEmpty then PathTrie.empty[A]
        else
            def loop(node: PathTrie[A], i: Int): PathTrie[A] =
                val seg = parts(i)
                if i == parts.size - 1 then node.copy(children = node.children - seg)
                else
                    node.children.get(seg) match
                        case None => node
                        case Some(child) =>
                            val updatedChild = loop(child, i + 1)
                            if updatedChild.value.isEmpty && updatedChild.children.isEmpty then
                                node.copy(children = node.children - seg)
                            else node.copy(children = node.children.updated(seg, updatedChild))
                end if
            end loop
            loop(this, 0)
    end removedSubtree

    /** Returns the value of the deepest strict ancestor of `parts` that carries one.
      *
      * Strict ancestors are the prefixes of length 1 to `parts.size - 1`; neither the root nor
      * `parts` itself is considered. Backends use this to detect a shadowing entry (a whiteout or an
      * opaque directory) above a path without re-deriving each prefix.
      */
    def nearestAncestorValue(parts: Chunk[String]): Maybe[A] =
        @tailrec def loop(node: PathTrie[A], i: Int, best: Maybe[A]): Maybe[A] =
            if i >= parts.size - 1 then best
            else
                node.children.get(parts(i)) match
                    case None => best
                    case Some(child) =>
                        loop(child, i + 1, if child.value.isDefined then child.value else best)
        loop(this, 0, Absent)
    end nearestAncestorValue

    /** Returns the direct children of `parts` that carry a value, as segment and value pairs. */
    def childValues(parts: Chunk[String]): Chunk[(String, A)] =
        nodeAt(parts) match
            case Absent => Chunk.empty
            case Present(node) =>
                Chunk.from(node.children.toIndexedSeq.collect {
                    case (seg, child) if child.value.isDefined => (seg, child.value.get)
                })
    end childValues

    /** Returns every value strictly beneath `parts`, paired with its full segment path. */
    def descendantValues(parts: Chunk[String]): Chunk[(Chunk[String], A)] =
        nodeAt(parts) match
            case Absent        => Chunk.empty
            case Present(node) => node.collectInto(parts, includeSelf = false)

    /** Returns every value in the trie, paired with its full segment path. */
    def entries: Chunk[(Chunk[String], A)] = collectInto(Chunk.empty, includeSelf = true)

    private def collectInto(prefix: Chunk[String], includeSelf: Boolean): Chunk[(Chunk[String], A)] =
        val self =
            if includeSelf then value.fold(Chunk.empty[(Chunk[String], A)])(a => Chunk((prefix, a)))
            else Chunk.empty[(Chunk[String], A)]
        children.toIndexedSeq.sortBy(_._1).foldLeft(self) { case (acc, (seg, child)) =>
            acc.concat(child.collectInto(prefix.append(seg), includeSelf = true))
        }
    end collectInto

    /** True when no value is stored anywhere in the trie. */
    def isEmpty: Boolean = value.isEmpty && children.forall(_._2.isEmpty)

end PathTrie

private[kyo] object PathTrie:
    private val _empty                            = PathTrie[Nothing](Absent, Map.empty)
    def empty[A]: PathTrie[A]                     = _empty.asInstanceOf[PathTrie[A]]
    given [A]: CanEqual[PathTrie[A], PathTrie[A]] = CanEqual.derived
end PathTrie
