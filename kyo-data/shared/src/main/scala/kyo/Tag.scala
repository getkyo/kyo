package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.Tag.Type.Entry.*
import kyo.internal.TagMacro
import scala.annotation.tailrec
import scala.collection.immutable.HashMap
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
  * The implementation uses an opaque type that can be either a String (for statically inferred tags) or a Dynamic object (for dynamic tags
  * that depend on other runtime-defined tags). This dual representation allows deferring the expensive deserialization operation until the
  * type information is actually needed.
  *
  * @tparam A
  *   The type for which this Tag provides runtime type information
  */
opaque type Tag[A] = String | Tag.internal.Dynamic

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

    /** Derives a Tag for type A. This method attempts to statically analyze the structure of type A and encode it as a string constant in
      * the bytecode. This approach offers optimal performance as the type information is available without allocations but it can fall back
      * to dynamic runtime derivation if the tag construction requires other runtime `Tag` values, which requires allocation of Dynamic
      * objects.
      *
      * Within the `kyo` package, this method fails instead of falling back to a dynamic tag for performance reasons.
      *
      * @tparam A
      *   The type for which to derive a Tag
      * @return
      *   A Tag for type A
      */
    inline given derive[A]: Tag[A] = ${ TagMacro.deriveImpl[A] }

    extension [A](self: Tag[A])

        /** Tests if this Tag represents the same type as another Tag. This operation checks type equality, which is more specific than
          * subtype relationships. It uses a sophisticated caching mechanism to optimize repeated checks.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if the types are exactly the same, false otherwise
          */
        infix def =:=[B](that: Tag[B]): Boolean =
            (self eq that) ||
                (self.hashCode == that.hashCode && self.equals(that)) ||
                checkTypes(self, that, "equality")

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
            (self eq that) || (self.hashCode == that.hashCode && self.equals(that)) || checkTypes(self, that, "subtype")

        /** Tests if this Tag represents a supertype of another Tag. This is the reverse of the <:< operation.
          *
          * @param that
          *   The Tag to compare with
          * @return
          *   true if this type is a supertype of the other type, false otherwise
          */
        infix def >:>[B](that: Tag[B]): Boolean =
            that <:< self

        /** Erases the specific type information, returning a Tag[Any]. This operation is useful when you need to work with Tags of
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
                case self: String      => Type(decode(self), Map.empty)
                case Dynamic(tag, ddb) => Type(decode(tag), ddb.asInstanceOf[Map[Type.Entry.Id, Tag[Any]]])

        /** Returns a human-readable representation of the type structure. This is useful for debugging and understanding complex type
          * relationships.
          *
          * @return
          *   A string representation of the type
          */
        def show: String =
            self.tpe.toString()

    end extension

    /** Base class for type representations in the Tag system. Type provides a structured representation of Scala types that can be
      * manipulated at runtime.
      *
      * @tparam A
      *   The Scala type represented by this Type
      */
    final case class Type[A](staticDB: Map[Type.Entry.Id, Type.Entry], dynamicDB: Map[Type.Entry.Id, Tag[Any]]):
        override val hashCode = MurmurHash3.productHash(this)

        def entryId: Type.Entry.Id = "0"

        def narrowOwner(owner: Type[?], id: Type.Entry.Id): (Type[?], Type.Entry.Id) =
            if staticDB.contains(id) then
                (owner, id)
            else if dynamicDB.contains(id) then
                val tpe = dynamicDB(id).tpe
                (tpe, tpe.entryId)
            else
                bug("")

        private def render(owner: Type[?], id: Type.Entry.Id): String =
            val (nowner, nid) = narrowOwner(owner, id)
            if nowner ne owner then
                render(nowner, nid)
            else
                owner.staticDB(id) match
                    case AnyEntry                     => "scala.Any"
                    case NothingEntry                 => "scala.Nothing"
                    case NullEntry                    => "scala.Null"
                    case LiteralEntry(widened, value) => value
                    case IntersectionEntry(set)       => "(" + set.map(render(owner, _)).mkString(" & ") + ")"
                    case UnionEntry(set)              => "(" + set.map(render(owner, _)).mkString(" | ") + ")"
                    case LambdaEntry(params, _, _, body) =>
                        s"[${params.mkString(", ")}] => ${render(owner, body)}"
                    case OpaqueEntry(name, lower, upper, variances, params) =>
                        if params.isEmpty then
                            s"($name >: ${render(owner, lower)} <: ${render(owner, upper)})"
                        else
                            val p = variances.zip(params).map {
                                case (Variance.Invariant, p)     => render(owner, p)
                                case (Variance.Covariant, p)     => s"+${render(owner, p)}"
                                case (Variance.Contravariant, p) => s"-${render(owner, p)}"
                            }
                            s"($name[${p.mkString(", ")}] >: ${render(owner, lower)} <: ${render(owner, upper)})"
                    case ClassEntry(className, variances, params, parents) =>
                        if params.isEmpty then
                            className
                        else
                            val p = variances.zip(params).map {
                                case (Variance.Invariant, p)     => render(owner, p)
                                case (Variance.Covariant, p)     => s"+${render(owner, p)}"
                                case (Variance.Contravariant, p) => s"-${render(owner, p)}"
                            }
                            s"$className[${p.mkString(", ")}]"
            end if
        end render

        override def toString = render(this, entryId)

    end Type

    object Type:

        sealed abstract class Entry extends Serializable with Product derives CanEqual

        object Entry:
            type Id = String

            case object AnyEntry     extends Entry
            case object NothingEntry extends Entry
            case object NullEntry    extends Entry

            final case class IntersectionEntry(set: Chunk.Indexed[Id]) extends Entry

            final case class UnionEntry(set: Chunk.Indexed[Id]) extends Entry

            final case class LiteralEntry(widened: Id, value: String) extends Entry

            final case class ClassEntry(
                className: String,
                variances: Chunk.Indexed[Variance],
                params: Chunk.Indexed[Id],
                parents: Chunk.Indexed[Id]
            ) extends Entry

            enum Variance extends Serializable derives CanEqual:
                case Invariant
                case Covariant
                case Contravariant
            end Variance

            final case class LambdaEntry(
                params: Chunk.Indexed[String],
                lowerBounds: Chunk.Indexed[Id],
                upperBounds: Chunk.Indexed[Id],
                body: Id
            ) extends Entry

            final case class OpaqueEntry(
                name: String,
                lowerBound: Id,
                upperBound: Id,
                variances: Chunk.Indexed[Variance],
                params: Chunk.Indexed[Id]
            ) extends Entry

        end Entry
    end Type

    object internal:

        import Type.*
        import Type.Entry.*

        private val threadSlots  = Runtime.getRuntime().availableProcessors() * 8
        private val cacheEntries = 64
        private val cacheSlots   = Array.ofDim[Long](threadSlots, cacheEntries)

        case class Dynamic(tag: String, map: Map[Entry.Id, Any]):
            override val hashCode = MurmurHash3.productHash(this)

        /** Determines if one type is a subtype or equal to another, with caching for performance.
          *
          * This method uses a thread-local caching strategy to optimize repeated subtype checks. The cache is implemented as an array of
          * longs for efficiency, where each entry represents a specific subtype check pair (a <:< b or a =:= b):
          *
          *   - Each long value packs both type hash codes together: subtype hash in the upper 32 bits and supertype hash in the lower 32
          *     bits
          *   - This combined hash is then scrambled using xor-shift operations to improve distribution and specialize it to either equality
          *     or sub type checking.
          *   - The sign of the stored long indicates the result: positive for true, negative for false
          *   - Zero indicates an unused cache entry
          *
          * The implementation has two distinct types of potential collisions:
          *
          *   1. Thread slot collisions: Multiple threads may map to the same cache slot based on thread hash code. These collisions only
          *      affect performance through cache thrashing, not correctness. The cache deliberately avoids synchronization mechanisms, as
          *      any race conditions would only result in redundant calculations rather than incorrect results.
          *   2. Type pair hash collisions: Different (tagA, tagB) pairs could theoretically generate the same 64-bit hash. The risk of
          *      these true hash conflicts is extremely low due to:
          *      - The large 63-bit effective hash space with over 9 quintillion possible values (1 bit reserved for the result flag)
          *      - Effective xor-shift mixing that distributes bits throughout the hash
          *      - The composite nature of the hash (requiring collisions in both subtype and supertype components)
          *
          * In the extremely rare case of a true hash collision between different type pairs, an incorrect cached result could be returned.
          * However, the probability is negligible in practical applications, making this a reasonable tradeoff for the significant
          * performance benefits of the caching system.
          *
          * @param a
          *   The potential subtype
          * @param b
          *   The potential supertype
          * @return
          *   true if a is a subtype of b, false otherwise
          */
        def checkTypes[A, B](a: Tag[A], b: Tag[B], mode: "equality" | "subtype"): Boolean =
            val cache = cacheSlots(Thread.currentThread().hashCode & (threadSlots - 1))
            var hash  = ((a.hashCode.toLong << 32) | (b.hashCode.toLong & 0xffffffffL))
            mode match
                case "equality" =>
                    hash ^= (hash >>> 12)
                    hash ^= (hash << 25)
                case "subtype" =>
                    hash ^= (hash >>> 15)
                    hash ^= (hash << 17)
            end match
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
                val aTpe = a.tpe
                val bTpe = b.tpe
                val res =
                    mode match
                        case "equality" => isSameType(aTpe, bTpe, aTpe.entryId, bTpe.entryId)
                        case "subtype"  => isSubtype(aTpe, bTpe, aTpe.entryId, bTpe.entryId)
                cache(idx) = if res then hash else -hash
                res
            end if
        end checkTypes

        private def isSubtype(aOwner: Type[?], bOwner: Type[?], aId: Entry.Id, bId: Entry.Id): Boolean =
            if !aOwner.staticDB.contains(aId) then
                val aType = aOwner.dynamicDB(aId).tpe
                isSubtype(aType, bOwner, aType.entryId, bId)
            else if !bOwner.staticDB.contains(bId) then
                val bType = bOwner.dynamicDB(bId).tpe
                isSubtype(aOwner, bType, aId, bType.entryId)
            else
                val aEntry = aOwner.staticDB(aId)
                val bEntry = bOwner.staticDB(bId)

                aEntry match
                    case NothingEntry => true
                    case AnyEntry     => bEntry == AnyEntry
                    case NullEntry    => true

                    case IntersectionEntry(aSet) =>
                        bEntry match
                            case IntersectionEntry(bSet) =>
                                forall(bSet) { bElemId =>
                                    exists(aSet) { aElemId =>
                                        isSubtype(aOwner, bOwner, aElemId, bElemId)
                                    }
                                }
                            case _ =>
                                exists(aSet) { aElemId =>
                                    isSubtype(aOwner, bOwner, aElemId, bId)
                                }

                    case UnionEntry(aSet) =>
                        forall(aSet) { aElemId =>
                            isSubtype(aOwner, bOwner, aElemId, bId)
                        }

                    case LiteralEntry(widenedId, value) =>
                        bEntry match
                            case LiteralEntry(_, bValue) => value.equals(bValue)
                            case _                       => isSubtype(aOwner, bOwner, widenedId, bId)

                    case LambdaEntry(aParams, aLower, aUpper, aBody) =>
                        bEntry match
                            case AnyEntry => true
                            case LambdaEntry(bParams, bLower, bUpper, bBody) =>
                                if aParams.size != bParams.size then false
                                else
                                    @tailrec def checkBounds(paramIdx: Int): Boolean =
                                        if paramIdx == aParams.size then true
                                        else
                                            isSubtype(bOwner, aOwner, bLower(paramIdx), aLower(paramIdx)) &&
                                            isSubtype(aOwner, bOwner, aUpper(paramIdx), bUpper(paramIdx)) &&
                                            checkBounds(paramIdx + 1)
                                    checkBounds(0) && isSubtype(aOwner, bOwner, aBody, bBody)
                            case _ => false

                    case OpaqueEntry(aName, _, aUpper, aVariances, aParams) =>
                        bEntry match
                            case OpaqueEntry(bName, bLower, _, bVariances, bParams) if aName == bName =>
                                if aParams.size != bParams.size then false
                                else
                                    @tailrec def loopParams(paramIdx: Int): Boolean =
                                        paramIdx == aParams.size || {
                                            val variance = aVariances(paramIdx)
                                            val aParamId = aParams(paramIdx)
                                            val bParamId = bParams(paramIdx)
                                            val ok =
                                                variance match
                                                    case Variance.Invariant =>
                                                        isSubtype(aOwner, bOwner, aParamId, bParamId) &&
                                                        isSubtype(bOwner, aOwner, bParamId, aParamId)
                                                    case Variance.Covariant =>
                                                        isSubtype(aOwner, bOwner, aParamId, bParamId)
                                                    case Variance.Contravariant =>
                                                        isSubtype(bOwner, aOwner, bParamId, aParamId)
                                            ok && loopParams(paramIdx + 1)
                                        }
                                    loopParams(0)
                            case _ =>
                                isSubtype(aOwner, bOwner, aUpper, bId)

                    case aClass: ClassEntry =>
                        bEntry match
                            case NothingEntry => false
                            case AnyEntry     => true
                            case NullEntry    => false

                            case IntersectionEntry(bSet) =>
                                forall(bSet) { bElemId =>
                                    isSubtype(aOwner, bOwner, aId, bElemId)
                                }

                            case UnionEntry(bSet) =>
                                exists(bSet) { bElemId =>
                                    isSubtype(aOwner, bOwner, aId, bElemId)
                                }

                            case _: LiteralEntry => false

                            case _: LambdaEntry => false

                            case OpaqueEntry(_, lower, upper, variances, params) =>
                                isSubtype(aOwner, bOwner, aId, lower) && {
                                    @tailrec def loopParams(paramIdx: Int): Boolean =
                                        paramIdx == params.size || {
                                            val variance = variances(paramIdx)
                                            val paramId  = params(paramIdx)
                                            val ok = variance match
                                                case Variance.Invariant =>
                                                    isSameType(aOwner, bOwner, aId, paramId)
                                                case Variance.Covariant =>
                                                    isSubtype(aOwner, bOwner, aId, paramId)
                                                case Variance.Contravariant =>
                                                    isSubtype(bOwner, aOwner, paramId, aId)
                                            ok && loopParams(paramIdx + 1)
                                        }
                                    loopParams(0)
                                }

                            case bClass: ClassEntry =>
                                isSubclassOf(aOwner, bOwner, aId, bId, aClass, bClass)
                end match
        end isSubtype

        private def isSameType(aOwner: Type[?], bOwner: Type[?], aId: Entry.Id, bId: Entry.Id): Boolean =
            if !aOwner.staticDB.contains(aId) then
                val aType = aOwner.dynamicDB(aId).tpe
                isSameType(aType, bOwner, aType.entryId, bId)
            else if !bOwner.staticDB.contains(bId) then
                val bType = bOwner.dynamicDB(bId).tpe
                isSameType(aOwner, bType, aId, bType.entryId)
            else
                val aEntry = aOwner.staticDB(aId)
                val bEntry = bOwner.staticDB(bId)

                aEntry match
                    case NothingEntry => bEntry == NothingEntry
                    case AnyEntry     => bEntry == AnyEntry
                    case NullEntry    => bEntry == NullEntry

                    case IntersectionEntry(aSet) =>
                        bEntry match
                            case IntersectionEntry(bSet) =>
                                def checkSet(idx: Int): Boolean =
                                    if idx == aSet.size then true
                                    else isSameType(aOwner, bOwner, aSet(idx), bSet(idx)) && checkSet(idx + 1)

                                aSet.size == bSet.size && checkSet(0)
                            case _ => false

                    case UnionEntry(aSet) =>
                        bEntry match
                            case UnionEntry(bSet) =>
                                def checkSet(idx: Int): Boolean =
                                    if idx == aSet.size then true
                                    else isSameType(aOwner, bOwner, aSet(idx), bSet(idx)) && checkSet(idx + 1)

                                aSet.size == bSet.size && checkSet(0)
                            case _ => false

                    case LiteralEntry(aId, value) =>
                        bEntry match
                            case LiteralEntry(bId, `value`) => isSameType(aOwner, bOwner, aId, bId)
                            case _                          => false

                    case LambdaEntry(aParams, aLower, aUpper, aBody) =>
                        bEntry match
                            case LambdaEntry(bParams, bLower, bUpper, bBody) =>
                                @tailrec def checkBounds(paramIdx: Int): Boolean =
                                    if paramIdx == aParams.size then true
                                    else
                                        isSameType(bOwner, aOwner, bLower(paramIdx), aLower(paramIdx)) &&
                                        isSameType(aOwner, bOwner, aUpper(paramIdx), bUpper(paramIdx)) &&
                                        checkBounds(paramIdx + 1)

                                aParams.size == bParams.size && checkBounds(0) && isSameType(aOwner, bOwner, aBody, bBody)
                            case _ => false

                    case OpaqueEntry(name, aLower, aUpper, aVariances, aParams) =>
                        bEntry match
                            case OpaqueEntry(`name`, bLower, bUpper, bVariances, bParams) =>
                                isSameType(aOwner, bOwner, aLower, bLower) &&
                                isSameType(aOwner, bOwner, aUpper, bUpper) &&
                                aParams.size == bParams.size && {
                                    @tailrec def checkParams(idx: Int): Boolean =
                                        idx == aParams.size ||
                                            (isSameType(aOwner, bOwner, aParams(idx), bParams(idx)) && checkParams(idx + 1))
                                    checkParams(0)
                                }
                            case _ =>
                                false

                    case ClassEntry(name, variances, aParams, aParents) =>
                        bEntry match
                            case ClassEntry(`name`, `variances`, bParams, bParents) =>

                                def checkParams(idx: Int): Boolean =
                                    if idx == aParams.size then true
                                    else isSameType(aOwner, bOwner, aParams(idx), bParams(idx)) && checkParams(idx + 1)

                                def checkParents(idx: Int): Boolean =
                                    if idx == aParents.size then true
                                    else isSameType(aOwner, bOwner, aParents(idx), bParents(idx)) && checkParents(idx + 1)

                                checkParams(0)
                            case _ => false
                end match

        private def isSubclassOf(
            aOwner: Type[?],
            bOwner: Type[?],
            aId: Entry.Id,
            bId: Entry.Id,
            aClass: ClassEntry,
            bClass: ClassEntry
        ): Boolean =
            if aClass.className == bClass.className then
                val size = aClass.params.size
                if size != bClass.params.size then false
                else
                    @tailrec def loopParams(paramIdx: Int): Boolean =
                        paramIdx == size || {
                            val variance = aClass.variances(paramIdx)
                            val aParamId = aClass.params(paramIdx)
                            val bParamId = bClass.params(paramIdx)
                            val ok =
                                variance match
                                    case Variance.Invariant =>
                                        isSubtype(aOwner, bOwner, aParamId, bParamId) &&
                                        isSubtype(bOwner, aOwner, bParamId, aParamId)
                                    case Variance.Covariant =>
                                        isSubtype(aOwner, bOwner, aParamId, bParamId)
                                    case Variance.Contravariant =>
                                        isSubtype(bOwner, aOwner, bParamId, aParamId)
                            ok && loopParams(paramIdx + 1)
                        }
                    loopParams(0)
                end if
            else
                exists(aClass.parents) { parentId =>
                    isSubtype(aOwner, bOwner, parentId, bId)
                }
        end isSubclassOf

        // avoids regular exists since it allocates an iterator
        private def exists[A](c: Chunk.Indexed[A])(f: A => Boolean): Boolean =
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

        def encodeEntry(entry: Entry): String =
            entry match
                case AnyEntry     => "A"
                case NothingEntry => "N"
                case NullEntry    => "U"

                case LiteralEntry(widened, value) => s"L:$widened:$value"
                case IntersectionEntry(set)       => s"I:${set.mkString(":")}"
                case UnionEntry(set)              => s"U:${set.mkString(":")}"

                case LambdaEntry(params, lower, upper, body) =>
                    s"M:$body:${params.size}:${params.mkString(":")}:${lower.mkString(":")}:${upper.mkString(":")}"

                case OpaqueEntry(name, lower, upper, variances, params) =>
                    s"O:$name:$lower:$upper:${params.size}:${variances.map(_.ordinal).mkString(":")}:${params.mkString(":")}"

                case ClassEntry(className, variances, params, parents) =>
                    require(params.size == variances.size)
                    val sanitized = className.replaceAll(":", "_colon_")
                    s"C:$sanitized:${params.size}:${variances.map(_.ordinal).mkString(":")}:" +
                        s"${params.mkString(":")}:${parents.mkString(":")}"
        end encodeEntry

        def encode[A](staticDB: Map[Type.Entry.Id, Type.Entry]): String =
            staticDB.map { (id, entry) =>
                s"$id:${encodeEntry(entry)}"
            }.mkString("\n")
        end encode

        /** Cache for decoded type structures. This cache ensures that each unique encoded type string is only deserialized once,
          * significantly improving performance for repeated operations on the same types. Unlike the subtype cache, this cache is fully
          * thread-safe using ConcurrentHashMap and never evicts entries.
          *
          * Since the set of unique types in a program is determined at compile time and is typically much smaller than the set of possible
          * subtype relationships, this cache can safely grow without bounds. Each unique Tag is only decoded once during the lifetime of
          * the application, regardless of how many times it's used.
          */
        private val decodeCache = new ConcurrentHashMap[String, Map[Type.Entry.Id, Type.Entry]]()

        def decode[A](encoded: String): Map[Type.Entry.Id, Type.Entry] =
            decodeCache.computeIfAbsent(
                encoded,
                _ =>
                    val lines = encoded.linesIterator
                    HashMap.empty[Entry.Id, Entry] ++
                        lines.map { encoded =>
                            val fields = encoded.split(":", -1).toList
                            fields.head -> decodeEntry(fields.tail)
                        }
            )
        end decode

        def decodeEntry(fields: List[String]): Entry =
            fields match
                case "A" :: Nil                     => AnyEntry
                case "N" :: Nil                     => NothingEntry
                case "U" :: Nil                     => NullEntry
                case "L" :: widened :: value :: Nil => LiteralEntry(widened, value)
                case "I" :: set                     => IntersectionEntry(Chunk.Indexed.from(set))
                case "U" :: set                     => UnionEntry(Chunk.Indexed.from(set))

                case "M" :: body :: size :: rest =>
                    val n  = size.toInt
                    val it = rest.iterator
                    LambdaEntry(
                        body = body,
                        params = Chunk.Indexed.from(it.take(n)),
                        lowerBounds = Chunk.Indexed.from(it.take(n)),
                        upperBounds = Chunk.Indexed.from(it.take(n))
                    )

                case "O" :: name :: lower :: upper :: size :: rest =>
                    val n         = size.toInt
                    val chunk     = Chunk.from(rest)
                    val variances = chunk.take(n).map(v => Variance.fromOrdinal(v.toInt))
                    val params    = chunk.drop(n).take(n)
                    OpaqueEntry(name, lower, upper, variances.toIndexed, params.toIndexed)

                case "C" :: name :: size :: rest =>
                    size.toInt match
                        case 0 =>
                            ClassEntry(
                                name,
                                Chunk.Indexed.empty,
                                Chunk.Indexed.empty,
                                Chunk.Indexed.from(rest.drop(2).filter(_.nonEmpty))
                            )
                        case size =>
                            val chunk     = Chunk.from(rest)
                            val variances = chunk.take(size).map(v => Variance.fromOrdinal(v.toInt))
                            val params    = chunk.drop(size).take(size)
                            val parents   = chunk.drop(size * 2)
                            ClassEntry(name, variances.toIndexed, params.toIndexed, parents.toIndexed)

                case fields =>
                    bug("Invalid tag payload! " + fields.mkString(":"))
            end match
        end decodeEntry
    end internal

end Tag
