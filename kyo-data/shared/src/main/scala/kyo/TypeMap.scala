package kyo

import kyo.internal.NotIntersection
import scala.annotation.tailrec

/** `TypeMap` provides a type-safe heterogeneous map implementation, allowing you to store and retrieve values of different types using
  * their types as keys.
  *
  * The representation is a linked list of entries with the newest modification at the head, so binding is a single allocation, exact
  * lookups scan with the tag equality fast path, and the subtype search runs in modification order (oldest first) to keep resolution
  * deterministic. Re-adding a present key removes the old entry and conses the new one, which places it last in the search order.
  *
  * The row parameter is phantom: entries are stored untyped and the node classes extend `TypeMap[Nothing]`, so values conform to any
  * row by covariance and the row exists only to prove presence at the type level.
  *
  * @tparam A
  *   The type of values in the map
  */
sealed abstract class TypeMap[+A]:
    import TypeMap.*

    def size: Int

    final def isEmpty: Boolean = size == 0

    private def fatal[T](using t: Tag[T]): Nothing =
        throw new RuntimeException(s"fatal: kyo.TypeMap of contents [${show}] missing value of type: [${t.show}].")

    /** Retrieves a value of type B from the TypeMap.
      *
      * @tparam B
      *   The type of the value to retrieve (must be a supertype of A)
      * @param t
      *   An implicit Tag for type B
      * @return
      *   The value of type B
      * @throws RuntimeException
      *   if the value is not found
      */
    final def get[B >: A](using t: Tag[B], ev: NotIntersection[B]): B =
        val te = t.erased
        @tailrec def exact(r: TypeMap[Any]): Any =
            r match
                case n: Node => if n.tag =:= te then n.value else exact(n.next)
                case _       => NotFound
        // searches in modification order, oldest entry first, so resolution is deterministic
        def search(r: TypeMap[Any]): Any =
            r match
                case n: Node =>
                    val found = search(n.next)
                    if !found.isInstanceOf[NotFound.type] then found
                    else if n.tag <:< te then n.value
                    else NotFound
                case _ => NotFound
        if isEmpty && t =:= Tag[Any] then ().asInstanceOf[B]
        else
            exact(this) match
                case _: NotFound.type =>
                    search(this) match
                        case _: NotFound.type => fatal
                        case v                => v.asInstanceOf[B]
                case v => v.asInstanceOf[B]
        end if
    end get

    /** Adds a new key-value pair to the TypeMap.
      *
      * @param b
      *   The value to add
      * @tparam B
      *   The type of the value to add
      * @param t
      *   An implicit Tag for type B
      * @return
      *   A new TypeMap with the added key-value pair
      */
    final inline def add[B](b: B)(using inline t: Tag[B]): TypeMap[A & B] =
        addErased(this, t.erased, b)

    /** Combines this TypeMap with another TypeMap.
      *
      * @param that
      *   The TypeMap to combine with
      * @tparam B
      *   The type of values in the other TypeMap
      * @return
      *   A new TypeMap containing all key-value pairs from both TypeMaps
      */
    final def union[B](that: TypeMap[B]): TypeMap[A & B] =
        // folds `that` oldest first so its entries land in modification order after this map's
        def go(r: TypeMap[Any]): TypeMap[Any] =
            r match
                case n: Node => addErased(go(n.next), n.tag, n.value)
                case _       => this
        val r =
            if that.isEmpty then this
            else if this.isEmpty then that
            else go(that)
        // phantom row recast: the entries are untyped and the row only claims presence
        r.asInstanceOf[TypeMap[A & B]]
    end union

    /** Filters the TypeMap to only include key-value pairs where the key is a subtype of the given type.
      *
      * @tparam B
      *   The type to filter by (must be a supertype of A)
      * @param t
      *   An implicit Tag for type B
      * @return
      *   A new TypeMap containing only the filtered key-value pairs
      */
    final def prune[B >: A](using t: Tag[B]): TypeMap[B] =
        val te = t.erased
        def go(r: TypeMap[Any]): TypeMap[Any] =
            r match
                case n: Node =>
                    val next = go(n.next)
                    if n.tag <:< te then
                        if next eq n.next then n else Node(n.tag, n.value, next)
                    else next
                case _ => r
        if t =:= Tag[Any] then this
        else
            // phantom row recast: the entries are untyped and the row only claims presence
            go(this).asInstanceOf[TypeMap[B]]
        end if
    end prune

    /** Returns a string representation of the TypeMap.
      *
      * @return
      *   A string describing the contents of the TypeMap
      */
    final def show: String =
        @tailrec def go(r: TypeMap[Any], acc: List[String]): List[String] =
            r match
                case n: Node => go(n.next, s"${n.tag.show} -> ${n.value}" :: acc)
                case _       => acc
        go(this, Nil).sorted.mkString("TypeMap(", ", ", ")")
    end show

    final private[kyo] def <:<[T](tag: Tag[T]): Boolean =
        @tailrec def go(r: TypeMap[Any]): Boolean =
            r match
                case n: Node => n.tag <:< tag || go(n.next)
                case _       => false
        go(this)
    end <:<
end TypeMap

object TypeMap:

    /** An empty TypeMap. */
    val empty: TypeMap[Any] = Empty

    private object NotFound

    private[kyo] object Empty extends TypeMap[Nothing]:
        def size = 0

    final private[kyo] class Node(
        val tag: Tag[Any],
        val value: Any,
        val next: TypeMap[Any]
    ) extends TypeMap[Nothing]:
        val size: Int = next.size + 1
    end Node

    // removes the exact entry for `t`, sharing the suffix when absent
    private[kyo] def removeExact(r: TypeMap[Any], t: Tag[Any]): TypeMap[Any] =
        r match
            case n: Node =>
                if n.tag =:= t then n.next
                else
                    val next = removeExact(n.next, t)
                    if next eq n.next then n else Node(n.tag, n.value, next)
            case _ => r

    private[kyo] def addErased(r: TypeMap[Any], t: Tag[Any], v: Any): TypeMap[Nothing] =
        Node(t, v, removeExact(r, t))

    /** Creates a TypeMap with a single key-value pair.
      *
      * @param a
      *   The value to add
      * @tparam A
      *   The type of the value
      * @param ta
      *   An implicit Tag for type A
      * @return
      *   A new TypeMap with the single key-value pair
      */
    def apply[A](a: A)(using ta: Tag[A]): TypeMap[A] =
        addErased(Empty, ta.erased, a)

    /** Creates a TypeMap with two key-value pairs.
      *
      * @param a
      *   The first value to add
      * @param b
      *   The second value to add
      * @tparam A
      *   The type of the first value
      * @tparam B
      *   The type of the second value
      * @param ta
      *   An implicit Tag for type A
      * @param tb
      *   An implicit Tag for type B
      * @return
      *   A new TypeMap with the two key-value pairs
      */
    def apply[A, B](a: A, b: B)(using ta: Tag[A], tb: Tag[B]): TypeMap[A & B] =
        addErased(addErased(Empty, ta.erased, a), tb.erased, b)

    /** Creates a TypeMap with three key-value pairs.
      */
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C)(using ta: Tag[A], tb: Tag[B], tc: Tag[C]): TypeMap[A & B & C] =
        addErased(addErased(addErased(Empty, ta.erased, a), tb.erased, b), tc.erased, c)

    /** Creates a TypeMap with four key-value pairs.
      */
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D)(using
        ta: Tag[A],
        tb: Tag[B],
        tc: Tag[C],
        td: Tag[D]
    ): TypeMap[A & B & C & D] =
        addErased(addErased(addErased(addErased(Empty, ta.erased, a), tb.erased, b), tc.erased, c), td.erased, d)
end TypeMap
