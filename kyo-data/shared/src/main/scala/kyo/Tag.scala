package kyo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kyo.internal.TagMacro
import scala.annotation.tailrec
import scala.collection.immutable.HashSet
import scala.collection.immutable.ListMap
import scala.util.Using
import scala.util.hashing.MurmurHash3

/** Tag provides a lightweight, efficient representation of types that supports operations like equality checking, subtype testing, and type
  * composition.
  *
  * Tag combines compile-time type derivation with runtime type operations, offering both performance and flexibility. At compile time, it
  * attempts to derive static type representations which are encoded as string constants in the bytecode. At runtime, these encoded
  * representations are decoded only once and cached for efficiency.
  *
  * Tags can be derived automatically for most types using the `derive` method. For types that cannot be statically analyzed at compile
  * time, the derivation has a fallback that constructs type information at runtime.
  *
  * Tag supports a rich set of operations:
  *   - Type equality testing with =:= and =!=
  *   - Subtype relationship testing with <:< and >:>
  *   - Type composition with & (intersection) and | (union)
  *   - Type structure inspection through the `show` method
  *
  * Internally, Tag uses a sophisticated caching mechanism to optimize performance. The subtype checking cache uses thread-specific arrays
  * to minimize contention while providing fast lookups. The type decoding cache ensures that each unique type structure is only
  * deserialized once.
  *
  * The implementation uses an opaque type that can be either a String (for encoded type information) or a Type object (for decoded type
  * structures). This dual representation allows deferring the expensive deserialization operation until the type information is actually
  * needed.
  *
  * @tparam A
  *   The type for which this Tag provides runtime type information
  */
opaque type Tag[A] >: Tag.Type[A] = String | Tag.Type[A]

object Tag:

    import internal.*

    /** Retrieves a Tag for type A using an implicit Tag parameter. This method is useful for passing Tags as parameters.
      *
      * @tparam A
      *   The type for which to retrieve a Tag
      * @return
      *   The Tag for type A
      */
    def apply[A: Tag as tag]: Tag[A] = tag

    /** Derives a Tag for type A at compile time. This method attempts to statically analyze the structure of type A and encode it as a
      * string constant in the bytecode. This approach offers optimal performance as the type information is available without allocations
      * but it can fall back to dynamic runtime derivation if the tag construction requires other runtime `Tag` values, which requires
      * allocation.
      *
      * Within the `kyo` package, this method fails instead of falling back to a dynamic tag for performance reasons.
      *
      * @tparam A
      *   The type for which to derive a Tag
      * @return
      *   A Tag for type A
      */
    inline given derive[A]: Tag[A] = ${ TagMacro.deriveImpl[A](false) }

    /** Derives a Tag for type A with runtime resolution support. Unlike the static derive method, this allows for dynamic type resolution
      * at runtime when static analysis is insufficient. This is useful for complex types or types that depend on runtime information. Only
      * necessary for code in the `kyo` package since `derive` does the fallback automatically for non-kyo packages.
      *
      * @tparam A
      *   The type for which to derive a Tag
      * @return
      *   A Tag for type A
      */
    private[kyo] inline def deriveDynamic[A]: Tag[A] = ${ TagMacro.deriveImpl[A](true) }

    extension [A](self: Tag[A])

        /** Tests if this Tag represents the same type as another Tag. This operation checks type equality, which is more specific than
          * subtype relationships.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if the types are exactly the same, false otherwise
          */
        infix def =:=[B](that: Tag[B]): Boolean =
            (self eq that) || self.equals(that) ||
                (((self.isInstanceOf[Type[?]] && that.isInstanceOf[String]) ||
                    (self.isInstanceOf[String] && that.isInstanceOf[Type[?]])) &&
                    self.tpe =:= that.tpe)

        /** Tests if this Tag represents a different type than another Tag. This is the negation of the =:= operation.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if the types are different, false if they are the same
          */
        infix def =!=[B](that: Tag[B]): Boolean =
            !(self =:= that)

        /** Tests if this Tag represents a subtype of another Tag. This operation uses a sophisticated caching mechanism to optimize
          * repeated checks.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if this type is a subtype of the other type, false otherwise
          */
        infix def <:<[B](that: Tag[B]): Boolean =
            (self eq that) || self.equals(that) || isSubtype(self, that)

        /** Tests if this Tag represents a supertype of another Tag. This is the reverse of the <:< operation.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if this type is a supertype of the other type, false otherwise
          */
        infix def >:>[B](that: Tag[B]): Boolean =
            that <:< self

        /** Creates a Tag representing the intersection of this type and another type. The resulting Tag represents values that conform to
          * both type A and type B.
          *
          * @param that
          *   The Tag to intersect with
          * @return
          *   A Tag representing the intersection type A & B
          */
        infix def &[B](that: Tag[B]): Tag[A & B] =
            Type.IntersectionType(self.tpe, that.tpe)

        /** Creates a Tag representing the union of this type and another type. The resulting Tag represents values that conform to either
          * type A or type B.
          *
          * @param that
          *   The Tag to union with
          * @return
          *   A Tag representing the union type A | B
          */
        infix def |[B](that: Tag[B]): Tag[A | B] =
            Type.UnionType(self.tpe, that.tpe)

        /** Erases the specific type information, returning a Tag for Any. This operation is useful when you need to work with Tags of
          * different types uniformly.
          *
          * @return
          *   A Tag[Any] representing the same type structure but with type parameter erased
          */
        def erased: Tag[Any] = self.asInstanceOf[Tag[Any]]

        /** Computes a hash code for this Tag based on its type structure. This hash is used in the caching system for subtype checking.
          *
          * @return
          *   A hash code for this Tag
          */
        def hash: Int =
            self.tpe.hashCode()

        /** Retrieves the decoded Type representation of this Tag. If the Tag is already a Type, it is returned directly. If it's an encoded
          * string, it is decoded (with caching) and then returned.
          *
          * @return
          *   The Type representation of this Tag
          */
        def tpe: Type[A] =
            self match
                case self: String  => decode(self)
                case self: Type[A] => self

        /** Returns a human-readable representation of the type structure. This is useful for debugging and understanding complex type
          * relationships.
          *
          * @return
          *   A string representation of the type
          */
        def show: String =
            self.tpe.toString()
    end extension

    /** Base class for all type representations in the Tag system. Type provides a structured representation of Scala types that can be
      * manipulated at runtime for operations like subtype checking and type composition.
      *
      * @tparam A
      *   The Scala type represented by this Type
      */
    sealed abstract class Type[A] extends Serializable with Product:
        final override lazy val hashCode = MurmurHash3.productHash(this)

    object Type:

        given [A <: Type[?], B <: Type[?]]: CanEqual[A, B] = CanEqual.derived

        /** Represents the bottom type scala.Nothing. Nothing is a subtype of all other types.
          */
        case object NothingType extends Type[Nothing]:
            override def toString = "scala.Nothing"

        /** Represents the top type scala.Any. Any is a supertype of all other types.
          */
        case object AnyType extends Type[Any]:
            override def toString = "scala.Any"

        /** Represents the null type scala.Null. Null is a subtype of all reference types.
          */
        case object NullType extends Type[Null]:
            override def toString = "scala.Null"

        /** Represents a literal type, which is a type with a specific value. Examples include singleton types like 42, "hello", or true.
          *
          * @param tag
          *   The base type of the literal
          * @param value
          *   The specific value of the literal
          * @tparam A
          *   The type of the literal
          */
        final case class LiteralType[A](tag: Type[A], value: A) extends Type[A]:
            override def toString = value.toString()

        /** Represents an intersection type (A & B). An intersection type requires values to conform to all component types.
          *
          * @param set
          *   The set of component types in this intersection
          * @tparam A
          *   The resulting intersection type
          */
        final case class IntersectionType[A] private (set: Chunk.Indexed[Type[?]]) extends Type[A]:
            override def toString = s"(${set.mkString(" & ")})"

        object IntersectionType:
            /** Creates an intersection type from two component types. This method handles nested intersections by flattening them.
              *
              * @param a
              *   The first type
              * @param b
              *   The second type
              * @return
              *   An intersection type representing A & B
              */
            def apply[A, B](a: Type[A], b: Type[B]): IntersectionType[A & B] =
                val types =
                    a match
                        case IntersectionType(e1) =>
                            b match
                                case IntersectionType(e2) => e1.concat(e2)
                                case b                    => e1.append(b)
                        case a =>
                            b match
                                case IntersectionType(e2) => e2.append(a)
                                case b                    => Chunk(a, b)
                IntersectionType(types.distinct.sortBy(_.hash).toIndexed)
            end apply
        end IntersectionType

        /** Represents a union type (A | B). A union type allows values to conform to any one of the component types.
          *
          * @param set
          *   The set of component types in this union
          * @tparam A
          *   The resulting union type
          */
        final case class UnionType[A] private (set: Chunk.Indexed[Type[?]]) extends Type[A]:
            override def toString = s"(${set.mkString(" | ")})"

        object UnionType:
            /** Creates a union type from two component types. This method handles nested unions by flattening them.
              *
              * @param a
              *   The first type
              * @param b
              *   The second type
              * @return
              *   A union type representing A | B
              */
            def apply[A, B](a: Type[A], b: Type[B]): UnionType[A | B] =
                val types =
                    a match
                        case UnionType(e1) =>
                            b match
                                case UnionType(e2) => e1.concat(e2)
                                case b             => e1.append(b)
                        case a =>
                            b match
                                case UnionType(e2) => e2.append(a)
                                case b             => Chunk(a, b)
                UnionType(types.distinct.sortBy(_.hash).toIndexed)
            end apply
        end UnionType

        /** Represents the variance of a type parameter. Variance affects subtyping relationships for parameterized types.
          *
          * @param toString
          *   String representation of the variance
          */
        enum Variance(override val toString: String) extends Serializable derives CanEqual:
            /** Invariant parameters require exact type matches */
            case Invariant extends Variance("")

            /** Covariant parameters allow subtypes (+) */
            case Covariant extends Variance("+")

            /** Contravariant parameters allow supertypes (-) */
            case Contravariant extends Variance("-")
        end Variance

        /** Represents a class or trait type, potentially with type parameters. This includes all named types in Scala like List, Option,
          * etc.
          *
          * @param id
          *   A unique identifier for this class type
          * @param bases
          *   The base classes/traits of this type, including itself
          * @tparam A
          *   The Scala type represented by this class type
          */
        final case class ClassType[A](
            id: String,
            bases: Chunk.Indexed[ClassType.Base]
        ) extends Type[A]:
            def className: String                  = bases(0).className
            def variances: Chunk.Indexed[Variance] = bases(0).variances
            def params: Chunk.Indexed[Type[?]]     = bases(0).params

            override def toString =
                val str = variances.zip(params).map((v, p) => s"$v$p").mkString(", ")
                val p =
                    if str.isBlank() then ""
                    else s"[$str]"
                s"$className$p"
            end toString
        end ClassType

        object ClassType:
            /** Represents a base class or trait of a class type. This includes information about the class name, type parameter variances,
              * and the actual type arguments.
              *
              * @param className
              *   The fully qualified name of the class
              * @param variances
              *   The variances of the type parameters
              * @param params
              *   The actual type arguments
              */
            final case class Base(
                className: String,
                variances: Chunk.Indexed[Variance],
                params: Chunk.Indexed[Type[?]]
            )
        end ClassType

        /** Represents a recursive type reference. This is used to handle recursive types without infinite recursion.
          *
          * @param id
          *   The identifier of the referenced type
          * @tparam A
          *   The Scala type represented by this recursive reference
          */
        case class Recursive[A](id: String) extends Type[A]

    end Type

    import Type.*

    private[kyo] object internal:

        private val threadSlots  = Runtime.getRuntime().availableProcessors() * 8
        private val cacheEntries = 64
        private val cacheSlots   = new Array[Array[Long]](threadSlots)
        (0 until threadSlots).foreach(idx => cacheSlots(idx) = new Array(cacheEntries))

        /** Determines if one type is a subtype of another, with caching for performance.
          *
          * This method uses a thread-local caching strategy to optimize repeated subtype checks. The cache is implemented as an array of
          * longs for efficiency, where each entry represents a specific subtype check pair (a <:< b):
          *
          *   - Each long value packs both type hash codes together: subtype hash in the upper 32 bits and supertype hash in the lower 32
          *     bits
          *   - This combined hash is then scrambled using xor-shift operations to improve distribution
          *   - The sign of the stored long indicates the result: positive for true, negative for false
          *   - Zero indicates an unused cache entry
          *
          * Thread-specific cache arrays minimize contention while maintaining good performance. Each thread gets a specific cache slot
          * based on its hash code but multiple threads may conflict on the same slot.
          *
          * The implementation prioritizes speed over perfect cache accuracy. Threads collinding in the same cache slot may occur but only
          * affect performance, not correctness. The cache deliberately avoids synchronization mechanisms, as any race conditions would only
          * result in redundant calculations rather than incorrect results.
          *
          * @param a
          *   The potential subtype
          * @param b
          *   The potential supertype
          * @return
          *   true if a is a subtype of b, false otherwise
          */
        def isSubtype[A, B](a: Tag[A], b: Tag[B]): Boolean =
            val cache = cacheSlots(Thread.currentThread().hashCode & (threadSlots - 1))
            var hash  = ((a.hashCode.toLong << 32) | (b.hashCode.toLong & 0xffffffffL))
            hash ^= (hash << 21)
            hash ^= (hash >>> 35)
            hash ^= (hash << 4)
            hash = hash.abs
            val idx    = (hash & (cacheEntries - 1)).toInt
            val cached = cache(idx)
            if hash == cached then
                true
            else if hash == -cached then
                false
            else
                val res = isSubtype(a.tpe, b.tpe, ListMap.empty)
                if !res then
                    cache(idx) = -hash
                else
                    cache(idx) = hash
                end if
                res
            end if
        end isSubtype

        private def isSubtype(a: Type[?], b: Type[?], seen: Map[String, Type[?]]): Boolean =
            a match
                case NothingType => true
                case AnyType     => b eq AnyType
                case NullType    => true
                case IntersectionType(aSet) =>
                    b match
                        case IntersectionType(bset) =>
                            forall(bset)(belem => exists(aSet)(aelem => isSubtype(aelem, belem, seen)))
                        case _ =>
                            exists(aSet)(elem => isSubtype(elem, b, seen))
                case UnionType(set) =>
                    forall(set)(elem => isSubtype(elem, b, seen))
                case LiteralType(tag, v) =>
                    b match
                        case LiteralType(tag2, v2) => v.equals(v2)
                        case _                     => isSubtype(tag, b, seen)
                case Recursive(id) => isSubtype(resolve(id, seen), b, seen)
                case a: ClassType[?] =>
                    b match
                        case NothingType           => false
                        case AnyType               => true
                        case NullType              => false
                        case IntersectionType(set) => forall(set)(elem => isSubtype(a, elem, seen))
                        case UnionType(set)        => exists(set)(elem => isSubtype(a, elem, seen))
                        case LiteralType(tag, _)   => false
                        case Recursive(id)         => isSubtype(a, resolve(id, seen), seen)
                        case b: ClassType[?] =>
                            val updatedSeen = seen.updated(a.id, a)
                            exists(a.bases) { base =>
                                base.className == b.className && {
                                    val size = base.params.size
                                    @tailrec def loopParams(paramIdx: Int): Boolean =
                                        paramIdx == size || {
                                            val variance = base.variances(paramIdx)
                                            require(variance == b.variances(paramIdx))
                                            val aParam = resolve(base.params(paramIdx), updatedSeen)
                                            val bParam = b.params(paramIdx)
                                            val ok =
                                                variance match
                                                    case Variance.Invariant     => aParam == bParam
                                                    case Variance.Covariant     => isSubtype(aParam, bParam, updatedSeen)
                                                    case Variance.Contravariant => isSubtype(bParam, aParam, updatedSeen)
                                            ok && loopParams(paramIdx + 1)
                                        }
                                    loopParams(0)
                                }
                            }
        end isSubtype

        // avoids regular exists since it allocates an iterator
        private inline def exists[A](c: Chunk.Indexed[A])(inline f: A => Boolean): Boolean =
            val size = c.size
            @tailrec def loop(idx: Int): Boolean =
                idx != size && (f(c(idx)) || loop(idx + 1))
            loop(0)
        end exists

        // avoids regular forall since it allocates an iterator
        private inline def forall[A](c: Chunk.Indexed[A])(inline f: A => Boolean): Boolean =
            val size = c.size
            @tailrec def loop(idx: Int): Boolean =
                idx == size || (f(c(idx)) && loop(idx + 1))
            loop(0)
        end forall

        private def resolve(tpe: Type[?], seen: Map[String, Type[?]]): Type[?] =
            tpe match
                case Recursive(id) => resolve(id, seen)
                case _             => tpe

        private def resolve(id: String, seen: Map[String, Type[?]]): Type[?] =
            require(seen.contains(id), s"Invalid recursive type id: $id")
            seen(id)

        /** Cache for decoded type structures. This cache ensures that each unique encoded type string is only deserialized once,
          * significantly improving performance for repeated operations on the same types. Unlike the subtype cache, this cache is fully
          * thread-safe using ConcurrentHashMap and never evicts entries.
          *
          * Since the set of unique types in a program is determined at compile time and is typically much smaller than the set of possible
          * subtype relationships, this cache can safely grow without bounds. Each unique Tag is only decoded once during the lifetime of
          * the application, regardless of how many times it's used.
          */
        private val decodeCache = new ConcurrentHashMap[String, Any]()

        def encode[A](obj: Type[A]): String =
            Using.resource(new ByteArrayOutputStream()) { baos =>
                Using.resource(new GZIPOutputStream(baos)) { gzos =>
                    Using.resource(new ObjectOutputStream(gzos)) { oos =>
                        oos.writeObject(obj)
                    }
                }
                Base64.getEncoder.encodeToString(baos.toByteArray)
            }

        def decode[A](encoded: String): Type[A] =
            decodeCache.computeIfAbsent(
                encoded,
                s =>
                    val bytes = Base64.getDecoder.decode(s)
                    Using.resource(new ByteArrayInputStream(bytes)) { bais =>
                        Using.resource(new GZIPInputStream(bais)) { gzis =>
                            Using.resource(new ObjectInputStream(gzis)) { ois =>
                                ois.readObject()
                            }
                        }
                    }
            ).asInstanceOf[Type[A]]
    end internal

end Tag
