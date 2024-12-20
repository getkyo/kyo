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

    private enum TypeKind derives CanEqual:
        case ClassType, UnionType, IntersectionType, ParameterType

    private def isSubtype(child: String, parent: String): Boolean =
        // Helper to find end of type, handling nested brackets
        def findTypeEnd(s: String, start: Int, end: Int): Int =
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

        // Helper to check if one class is subtype of another
        def isClassSubtype(child: String, cStart: Int, cEnd: Int, parent: String, pStart: Int, pEnd: Int): Boolean =
            // Extract class names
            def extractClassName(s: String, start: Int, end: Int): String =
                val baseStart = start + 2 // Skip "C:"
                val anglePos  = s.indexOf('<', baseStart)
                val colonPos  = s.indexOf(':', baseStart)
                val nameEnd =
                    if anglePos == -1 && colonPos == -1 then end
                    else if anglePos == -1 then colonPos
                    else if colonPos == -1 then anglePos
                    else math.min(anglePos, colonPos)
                s.substring(baseStart, nameEnd)
            end extractClassName

            val childName  = extractClassName(child, cStart, cEnd)
            val parentName = extractClassName(parent, pStart, pEnd)

            if childName == parentName then
                // Same class - check type parameters if present
                val cParams = child.indexOf('<', cStart)
                val pParams = parent.indexOf('<', pStart)
                if cParams != -1 && pParams != -1 then
                    compareTypeParams(child, cParams + 1, cEnd - 1, parent, pParams + 1, pEnd - 1)
                else true
            else
                // Check inheritance chain
                var pos = child.indexOf(':', cStart)
                while pos != -1 && pos < cEnd do
                    val nextColon = child.indexOf(':', pos + 1)
                    val nextAngle = child.indexOf('<', pos + 1)
                    val segmentEnd =
                        if nextColon == -1 && nextAngle == -1 then cEnd
                        else if nextColon == -1 then nextAngle
                        else if nextAngle == -1 then nextColon
                        else math.min(nextColon, nextAngle)

                    val superName = child.substring(pos + 1, segmentEnd)
                    if superName == parentName then
                        // Found parent - check type parameters if present
                        if nextAngle != -1 && parent.indexOf('<', pStart) != -1 then
                            if compareTypeParams(
                                    child,
                                    nextAngle + 1,
                                    if nextColon == -1 then cEnd - 1 else nextColon,
                                    parent,
                                    parent.indexOf('<', pStart) + 1,
                                    pEnd - 1
                                )
                            then
                                return true
                        else return true
                    end if

                    pos = if nextColon == -1 then -1 else nextColon
                end while
                false
            end if
        end isClassSubtype

        // Helper to compare type parameters with variance
        def compareTypeParams(child: String, cStart: Int, cEnd: Int, parent: String, pStart: Int, pEnd: Int): Boolean =
            var ci = cStart
            var pi = pStart
            while ci < cEnd && pi < pEnd do
                if !child.startsWith("P:", ci) || !parent.startsWith("P:", pi) then return false

                val childVariance  = child.charAt(ci + 2)
                val childTypeStart = ci + 4
                val childTypeEnd   = findTypeEnd(child, childTypeStart, cEnd)

                val parentVariance  = parent.charAt(pi + 2)
                val parentTypeStart = pi + 4
                val parentTypeEnd   = findTypeEnd(parent, parentTypeStart, pEnd)

                val result = (childVariance, parentVariance) match
                    case ('=', '=') =>
                        // Invariant - types must be exactly equal
                        child.substring(childTypeStart, childTypeEnd) == parent.substring(parentTypeStart, parentTypeEnd)
                    case ('+', '+') =>
                        // Covariant - child must be subtype of parent
                        isSubtypeOf(child, childTypeStart, childTypeEnd, parent, parentTypeStart, parentTypeEnd)
                    case ('-', '-') =>
                        // Contravariant - parent must be subtype of child
                        isSubtypeOf(parent, parentTypeStart, parentTypeEnd, child, childTypeStart, childTypeEnd)
                    case _ => false

                if !result then return false
                ci = childTypeEnd + 1
                pi = parentTypeEnd + 1
            end while
            (ci >= cEnd) && (pi >= pEnd)
        end compareTypeParams

        // Main recursive subtype check
        def isSubtypeOf(child: String, cStart: Int, cEnd: Int, parent: String, pStart: Int, pEnd: Int): Boolean =
            // Equal types are subtypes
            if child.substring(cStart, cEnd) == parent.substring(pStart, pEnd) then return true

            // Any type is subtype of Any
            if parent.startsWith("C:scala.Any", pStart) then return true

            // Handle different type kinds
            (child.charAt(cStart), parent.charAt(pStart)) match
                case ('C', 'C') =>
                    // Class subtyping
                    isClassSubtype(child, cStart, cEnd, parent, pStart, pEnd)

                case ('I', _) =>
                    // Intersection is subtype if all members are
                    var i = cStart + 2
                    while i < cEnd do
                        val nextDelim = findTypeEnd(child, i, cEnd)
                        val memberEnd = if nextDelim == -1 then cEnd else nextDelim
                        if !isSubtypeOf(child, i, memberEnd, parent, pStart, pEnd) then return false
                        i = if nextDelim == -1 then cEnd else nextDelim + 1
                    end while
                    true

                case (_, 'U') =>
                    // Type is subtype of union if subtype of any member
                    var i = pStart + 2
                    while i < pEnd do
                        val nextDelim = findTypeEnd(parent, i, pEnd)
                        val memberEnd = if nextDelim == -1 then pEnd else nextDelim
                        if isSubtypeOf(child, cStart, cEnd, parent, i, memberEnd) then return true
                        i = if nextDelim == -1 then pEnd else nextDelim + 1
                    end while
                    false

                case ('U', _) =>
                    // Union is subtype if all members are
                    var i = cStart + 2
                    while i < cEnd do
                        val nextDelim = findTypeEnd(child, i, cEnd)
                        val memberEnd = if nextDelim == -1 then cEnd else nextDelim
                        if !isSubtypeOf(child, i, memberEnd, parent, pStart, pEnd) then return false
                        i = if nextDelim == -1 then cEnd else nextDelim + 1
                    end while
                    true

                case (_, 'I') =>
                    // Type is subtype of intersection if subtype of all members
                    var i = pStart + 2
                    while i < pEnd do
                        val nextDelim = findTypeEnd(parent, i, pEnd)
                        val memberEnd = if nextDelim == -1 then pEnd else nextDelim
                        if !isSubtypeOf(child, cStart, cEnd, parent, i, memberEnd) then return false
                        i = if nextDelim == -1 then pEnd else nextDelim + 1
                    end while
                    true

                case _ => false
            end match
        end isSubtypeOf

        if child == "C:scala.Nothing:C:scala.Any" || parent == "C:scala.Any" then true
        else isSubtypeOf(child, 0, child.length, parent, 0, parent.length)
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
end Tag
