package kyo

import kyo.internal.TagMacro

/** Tag provides compile-time type encoding for Scala's type system.
  *
  * The encoding format represents types as strings with the following grammar:
  *
  * {{{
  * Type := ClassType | UnionType | IntersectionType | RecursiveType
  *
  * ClassType := "C:" QualifiedName TypeParams? InheritanceChain?
  * QualifiedName := [fully qualified class name]
  *
  * TypeParams := "<" TypeParam ("," TypeParam)* ">"
  * TypeParam := "P:" Variance ":" Type
  * Variance := "+" | "-" | "="
  *
  * InheritanceChain := (":" Type)*
  *
  * UnionType := "U:" Type "|" Type
  * IntersectionType := "I:" Type "&" Type
  * RecursiveType := "R:" QualifiedName
  * }}}
  *
  * Examples:
  *   - Recursive type (class extending parameterized parent with self-reference):
  *     ```scala
  *     class String extends Comparator[String]
  *     String => C:java.lang.String<>:C:java.lang.Comparator<P:=:R:java.lang.String>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Mutually recursive types:
  *     ```scala
  *     class Tree[A] extends Seq[Node[A]]
  *     class Node[A] extends Seq[Tree[A]]
  *
  *     Tree[Int] => C:Tree<P:=:C:scala.Int>:C:scala.collection.Seq<P:=:C:Node<P:=:C:scala.Int>:C:scala.collection.Seq<P:=:R:Tree>>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     Node[Int] => C:Node<P:=:C:scala.Int>:C:scala.collection.Seq<P:=:C:Tree<P:=:C:scala.Int>:C:scala.collection.Seq<P:=:R:Node>>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Simple class with type parameter:
  *     ```scala
  *     class Box[+A]
  *     Box[String] => C:Box<P:+:C:java.lang.String>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Class extending parameterized parent:
  *     ```scala
  *     class Parent[A]
  *     class Child[B] extends Parent[String]
  *     Child[Int] => C:Child<P:=:C:scala.Int>:C:Parent<P:=:C:java.lang.String>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Multiple inheritance:
  *     ```scala
  *     trait B[X]
  *     trait C[Y]
  *     class A[T] extends B[String] with C[T]
  *     A[Int] => C:A<P:=:C:scala.Int>:C:B<P:=:C:java.lang.String>:C:C<P:=:C:scala.Int>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Nested type parameters:
  *     ```scala
  *     class Box[A]
  *     Box[List[Int]] => C:Box<P:=:C:scala.collection.immutable.List<P:=:C:scala.Int>>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Parameterized inheritance:
  *     ```scala
  *     class Super[A]
  *     class Sub[B] extends Super[B]
  *     Sub[Int] => C:Sub<P:=:C:scala.Int>:C:Super<P:=:C:scala.Int>:C:java.lang.Object:C:scala.Matchable:C:scala.Any
  *     ```
  *
  *   - Union and intersection types:
  *     ```scala
  *     A | B => U:C:A|C:B
  *     A & B => I:C:A&C:B
  *     (A | B) & C => I:U:C:A|C:B&C:C
  *     ```
  *
  * This encoding preserves:
  *   - Unambiguous encoding of type relationships
  *   - Declaration-site type parameter binding
  *   - Efficient subtype checking
  *   - Support for variance, unions, intersections
  *   - Complete inheritance chain preservation
  */
opaque type Tag[A] = String

object Tag:
    inline given apply[A]: Tag[A]                    = ${ TagMacro.derive[A] }
    private[kyo] def fromRaw[A](tag: String): Tag[A] = tag

    extension [A](self: Tag[A])
        inline def erased: Tag[Any]  = self
        private[kyo] def raw: String = self

        infix def <:<[B](other: Tag[B]): Boolean = isSubtype(self, other)
        infix def >:>[B](other: Tag[B]): Boolean = isSubtype(other, self)
        infix def =:=[B](other: Tag[B]): Boolean = self == other
        infix def =!=[B](other: Tag[B]): Boolean = self != other

        def show: String    = s"Tag[$showTpe]"
        def showTpe: String = showType(self, self)
    end extension

    private def extractBaseAndParams(typeStr: String, start: Int, end: Int): (String, (Int, Int)) =
        var paramStart = -1
        var i          = start
        while i < end do
            if typeStr(i) == '<' then
                paramStart = i + 1
                i = end // exit loop
            else if typeStr(i) == ':' then
                i = end // exit loop
            else
                i += 1
        end while

        if paramStart == -1 then
            // No type parameters
            (typeStr.substring(start, i), (end, end))
        else
            // Has type parameters
            (typeStr.substring(start, paramStart - 1), (paramStart, end - 1))
        end if
    end extractBaseAndParams

    private def findTypeEnd(s: String, start: Int, end: Int): Int =
        var depth = 0
        var i     = start
        while i < end do
            s.charAt(i) match
                case '<'                           => depth += 1
                case '>'                           => depth -= 1
                case '|' | ',' | '&' if depth == 0 => return i
                case _                             =>
            end match
            i += 1
        end while
        end
    end findTypeEnd

    /** Subtype checking follows these formal rules:
      *
      * Base Rules: T <: T (Reflexivity) T <: Any (Top) Nothing <: T (Bottom)
      *
      * Variance Rules: [+T] A <: B => C[A] <: C[B] (Covariance) [-T] B <: A => C[A] <: C[B] (Contravariance) [=T] A = B => C[A] <: C[B]
      * (Invariance)
      *
      * Union/Intersection Rules: A <: T & U <=> A <: T ∧ A <: U (Intersection) T | U <: A <=> T <: A ∧ U <: A (Union)
      *
      * Inheritance: C extends P => C <: P (Inheritance)
      */
    private def isSubtype(child: String, parent: String): Boolean =
        if child == parent then return true
        if child == "C:scala.Nothing:C:scala.Any" then return true
        if parent == "C:scala.Any" then return true

        def isSubtypeOf(child: String, cStart: Int, cEnd: Int, parent: String, pStart: Int, pEnd: Int, variance: Char = '='): Boolean =
            assert(cStart >= 0 && cEnd <= child.length, s"Invalid child bounds: $cStart, $cEnd")
            assert(pStart >= 0 && pEnd <= parent.length, s"Invalid parent bounds: $pStart, $pEnd")
            assert(variance == '+' || variance == '-' || variance == '=', s"Invalid variance: $variance")

            if substringEquals(child, cStart, cEnd, "C:scala.Nothing:C:scala.Any", 0, 23) then return true
            if substringEquals(parent, pStart, pEnd, "C:scala.Any", 0, 10) then return true
            if substringEquals(child, cStart, cEnd, parent, pStart, pEnd) then return true

            (child.charAt(cStart), parent.charAt(pStart)) match
                case ('C', 'C') =>
                    assert(cStart + 2 < cEnd, "Invalid class type format in child")
                    assert(pStart + 2 < pEnd, "Invalid class type format in parent")

                    var ci = cStart + 2
                    var pi = pStart + 2

                    var cNameEnd = ci
                    var pNameEnd = pi
                    while cNameEnd < cEnd && child.charAt(cNameEnd) != '<' && child.charAt(cNameEnd) != ':' do cNameEnd += 1
                    while pNameEnd < pEnd && parent.charAt(pNameEnd) != '<' && parent.charAt(pNameEnd) != ':' do pNameEnd += 1

                    assert(cNameEnd > ci, "Empty class name in child")
                    assert(pNameEnd > pi, "Empty class name in parent")

                    if substringEquals(child, ci, cNameEnd, parent, pi, pNameEnd) then
                        if child.charAt(cNameEnd) == '<' && parent.charAt(pNameEnd) == '<' then
                            assert(cNameEnd + 1 < cEnd, "Invalid type parameter format in child")
                            assert(pNameEnd + 1 < pEnd, "Invalid type parameter format in parent")
                            compareTypeParams(
                                child,
                                cNameEnd + 1,
                                findTypeEnd(child, cNameEnd + 1, cEnd - 1),
                                parent,
                                pNameEnd + 1,
                                findTypeEnd(parent, pNameEnd + 1, pEnd - 1),
                                variance
                            )
                        else true
                    else
                        var i = cNameEnd
                        while i < cEnd do
                            if child.charAt(i) == ':' then
                                val nextEnd = findTypeEnd(child, i + 1, cEnd)
                                if isSubtypeOf(child, i + 1, nextEnd, parent, pStart, pEnd, variance) then return true
                                i = nextEnd
                            else i += 1
                        end while
                        false
                    end if

                case ('I', _) =>
                    // A & B <:< T  iff  A <:< T && B <:< T
                    val parts = splitTypes(child, cStart + 2, cEnd, '&')
                    parts.forall(p => isSubtypeOf(child, p._1, p._2, parent, pStart, pEnd))

                case (_, 'I') =>
                    // T <:< A & B  iff  T <:< A && T <:< B
                    val parts = splitTypes(parent, pStart + 2, pEnd, '&')
                    parts.forall(p => isSubtypeOf(child, cStart, cEnd, parent, p._1, p._2))

                case ('U', _) =>
                    // A | B <:< T  iff  A <:< T && B <:< T
                    val parts = splitTypes(child, cStart + 2, cEnd, '|')
                    parts.forall(p => isSubtypeOf(child, p._1, p._2, parent, pStart, pEnd))

                case (_, 'U') =>
                    // T <:< A | B  iff  T <:< A || T <:< B
                    val parts = splitTypes(parent, pStart + 2, pEnd, '|')
                    parts.exists(p => isSubtypeOf(child, cStart, cEnd, parent, p._1, p._2))

                case _ => false
            end match
        end isSubtypeOf

        // Helper method to split union/intersection types while respecting nested type parameters
        def splitTypes(str: String, start: Int, end: Int, separator: Char): List[(Int, Int)] =
            var result  = List.empty[(Int, Int)]
            var depth   = 0
            var current = start
            var i       = start
            while i < end do
                str.charAt(i) match
                    case '<' => depth += 1
                    case '>' => depth -= 1
                    case c if c == separator && depth == 0 =>
                        result = (current, i) :: result
                        current = i + 1
                    case _ =>
                end match
                i += 1
            end while
            result = (current, end) :: result
            result.reverse
        end splitTypes

        isSubtypeOf(child, 0, child.length, parent, 0, parent.length)
    end isSubtype

    /** Show type with type parameters
      *
      * @param tag
      *   used to propegate the Tag within the error message
      * @param c
      *   the current tag string. May be a substring of the original tag.
      * @return
      *   a pretty string representation of the type
      */
    private def showType(tag: Tag[Any], c: String): String =
        val result =
            if c.isBlank() then ""
            else
                c.charAt(0) match
                    case 'C' =>
                        var i     = 2 // skip "C:"
                        val start = i
                        while i < c.length && c(i) != '<' && c(i) != ':' do i += 1
                        val fullName = c.substring(start, i)

                        if i >= c.length || c(i) != '<' then fullName
                        else
                            // Has type parameters
                            var depth      = 1
                            val paramStart = i + 1
                            i += 1
                            while i < c.length && depth > 0 do
                                c(i) match
                                    case '<' => depth += 1
                                    case '>' => depth -= 1
                                    case _   =>
                                end match
                                i += 1
                            end while

                            val params = c.substring(paramStart, i - 1).split(",")
                                .map(p => if p.startsWith("P:") then showType(tag, p.substring(4)) else showType(tag, p))
                                .mkString(", ")
                            s"$fullName[$params]"
                        end if

                    case 'U' => c.substring(2).split("\\|").map(t => showType(tag, t)).mkString(" | ")
                    case 'I' => c.substring(2).split("&").map(t => showType(tag, t)).mkString(" & ")
                    case 'R' => c.substring(2)
                    case _   => bug(tag, s"Invalid type kind in showType: ${c.charAt(0)}")

        result.replaceAll("_\\$", "")
    end showType

    private def bug(tag: Tag[Any], msg: String): Nothing =
        throw new IllegalArgumentException(s"Tag Parsing Error: $msg; Please open an issue at https://github.com/getkyo/kyo/issues - $tag")
    end bug

    private def substringEquals(s1: String, start1: Int, end1: Int, s2: String, start2: Int, end2: Int): Boolean =
        if end1 - start1 != end2 - start2 then return false
        var i = 0
        while i < end1 - start1 do
            if s1.charAt(start1 + i) != s2.charAt(start2 + i) then return false
            i += 1
        true
    end substringEquals
end Tag
