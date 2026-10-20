package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.Tag.internal.Type.Entry.*
import kyo.internal.Platform
import kyo.internal.SafePublish
import kyo.internal.TagHash
import kyo.internal.TagMacro
import kyo.internal.XXHash
import scala.annotation.tailrec
import scala.collection.immutable.HashMap

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
  *
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

object Tag extends kyo.internal.TagPlatformSpecific:

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
      * Inside the template that declares an opaque type, and inside its companion object, the compiler substitutes the underlying type for
      * the opaque one wherever it has to infer, and it does so before this macro runs. A tag derived there would describe the underlying
      * type while every derivation outside the scope describes the opaque type, so the two would disagree and a value handed across that
      * boundary would be looked up under a different key than it was stored under. Nothing at that point can say which type was meant, so
      * a derivation whose type mentions the underlying type of an opaque type transparent there is refused, whether it was inferred or
      * written. Naming the opaque type explicitly in `Tag.derive[X]` always survives the substitution and derives the same tag as
      * anywhere else. An implicit query such as `Tag[X]` or a `using Tag[X]` parameter may or may not survive, depending on how the
      * compiler resolves it; when it does not, the derivation is refused rather than misnamed. A `given Tag[X]` must not be defined in
      * that scope either: it is a `Tag` for the underlying type there and would answer every such query. The remedy for a refusal is to
      * pass `Tag.derive[X]` explicitly, or to move the derivation out of the scope.
      *
      * @tparam A
      *   The type for which to derive a Tag
      * @return
      *   A Tag for type A
      */
    inline given derive[A]: Tag[A] = ${ TagMacro.deriveImpl[A](allowDynamic = false) }

    private[kyo] inline def dynamic[A]: Tag[A] = ${ TagMacro.deriveImpl[A](allowDynamic = true) }

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
            self.fastPathEqual(that) || {
                // if any of the tags is concrete, then fastPathEqual should
                // already have returned true in case the tags are equal
                !self.isConcrete && !that.isConcrete &&
                checkTypes(self, that, Mode.Equality)
            }

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
            self.fastPathEqual(that) || checkTypes(self, that, Mode.Subtype)

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

        /** A content-stable XXH32 hash of this Tag's type, stable across JVM processes.
          *
          * Static tags hash the encoded `String` form. Dynamic tags hash their encoded string and
          * sorted dynamic sub-tag hashes. This is deliberately NOT the decoded `Type`'s `hashCode`,
          * which is identity-influenced by the `Array`-backed `Span` fields and so is stable only
          * within a single JVM. Cross-JVM stability is required because kyo-aeron derives aeron stream
          * ids from this hash, so a publish and a subscribe of the same type in separate JVMs must
          * hash identically.
          *
          * @return
          *   A content-stable hash code for this Tag's type
          */
        def hash: Int =
            self match
                case self: String  => XXHash.hash32(self)
                case self: Dynamic => self.hashCode

        /** Retrieves the decoded Type representation of this Tag. If the Tag is already a Type, it is returned directly. If it's an encoded
          * string, it is decoded (with caching) and then returned.
          *
          * @return
          *   The Type representation of this Tag
          */
        def tpe: Type[A] =
            self match
                case self: String  => decode(self).asInstanceOf[Type[A]]
                case self: Dynamic => self.tpe.asInstanceOf[Type[A]]

        /** Returns a human-readable representation of the type structure. This is useful for debugging and understanding complex type
          * relationships.
          *
          * @return
          *   A string representation of the type
          */
        def show: String =
            self.tpe.toString()

        /** Compare two encoded tags exactly. Which pre-check is worth making before reading the contents differs by an order of magnitude
          * between platforms, so the comparison itself lives in `TagPlatformSpecific`, whose two halves carry the measurements.
          *
          * `eq` answers equal tags before that: a statically derived tag is a string literal, so equal encodings are the same interned
          * object, and only unequal pairs reach the comparison. It cannot answer alone, because it means value equality on JS and
          * reference identity on the JVM.
          */
        private def fastPathEqual[B](that: Tag[B]): Boolean = (self eq that) || {
            self match
                case self: String =>
                    that match
                        case that: String =>
                            equalEncodings(self, that)
                        case _ =>
                            false
                case _ =>
                    false
        }

        /** Checks if this Tag represents a concrete class type (without type parameters).
          *
          * This is an optimization for type equality checks. For a concrete class type (marked with an asterisk '*' prefix during
          * encoding), the only way another tag can be equal is if it represents exactly the same class, since there are no type parameters
          * that could require more expensive comparison with dynamic tags.
          */
        private def isConcrete: Boolean =
            self match
                case self: String => self.charAt(0) == '*'
                case _            => false

    end extension

    private[kyo] object internal:

        import Type.*
        import Type.Entry.*

        /** Base class for type representations in the Tag system. Type provides a structured representation of Scala types that can be
          * manipulated at runtime. Every entry a type names is one it holds: a tag built at run time holds its parts filled in (see
          * [[Dynamic]]).
          *
          * @tparam A
          *   The Scala type represented by this Type
          */
        final case class Type[A](staticDB: Map[Type.Entry.Id, Type.Entry]):

            private[kyo] def entryId: Type.Entry.Id = "0"

            private[kyo] def render(id: Type.Entry.Id): String =
                staticDB(id) match
                    case AnyEntry                     => "scala.Any"
                    case NothingEntry                 => "scala.Nothing"
                    case NullEntry                    => "scala.Null"
                    case LiteralEntry(widened, value) => value
                    case BoundsEntry(lower, upper)    =>
                        val low  = staticDB.get(lower).exists(_ eq NothingEntry)
                        val high = staticDB.get(upper).exists(_ eq AnyEntry)
                        if low && high then "?"
                        else if low then s"? <: ${render(upper)}"
                        else if high then s"? >: ${render(lower)}"
                        else s"? >: ${render(lower)} <: ${render(upper)}"
                        end if
                    case IntersectionEntry(set) =>
                        val b = new ChunkBuilder[String]
                        set.foreach(id => b.addOne(render(id)))
                        "(" + b.result().sorted.mkString(" & ") + ")"
                    case UnionEntry(set) =>
                        val b = new ChunkBuilder[String]
                        set.foreach(id => b.addOne(render(id)))
                        "(" + b.result().sorted.mkString(" | ") + ")"
                    case LambdaEntry(params, _, _, body) =>
                        s"[${params.mkString(", ")}] => ${render(body)}"
                    case OpaqueEntry(name, lower, upper, variances, params) =>
                        if params.isEmpty then
                            s"($name >: ${render(lower)} <: ${render(upper)})"
                        else
                            val size                                                = variances.size
                            @tailrec def loop(idx: Int, acc: Chunk[String]): String =
                                if idx == size then acc.mkString(", ")
                                else loop(idx + 1, acc.append(variances(idx).show + render(params(idx))))
                            s"($name[${loop(0, Chunk.empty)}] >: ${render(lower)} <: ${render(upper)})"
                    case ClassEntry(className, variances, params, parents) =>
                        if params.isEmpty then
                            className
                        else
                            val size                                                = params.size
                            @tailrec def loop(idx: Int, acc: Chunk[String]): String =
                                if idx == size then acc.mkString(", ")
                                else loop(idx + 1, acc.append(variances(idx).show + render(params(idx))))
                            s"$className[${loop(0, Chunk.empty)}]"
            end render

            override def toString = render(entryId)

        end Type

        object Type:

            sealed abstract class Entry extends Serializable with Product derives CanEqual

            object Entry:
                type Id = String

                case object AnyEntry     extends Entry
                case object NothingEntry extends Entry
                case object NullEntry    extends Entry

                /** A wildcard type argument, `? >: lower <: upper`: it stands for every type within its bounds, so a type conforms to
                  * it when it is within them, as the variance of the parameter it fills reads them.
                  */
                final case class BoundsEntry(lower: Id, upper: Id) extends Entry

                final case class IntersectionEntry(set: Span[Id]) extends Entry

                final case class UnionEntry(set: Span[Id]) extends Entry

                final case class LiteralEntry(widened: Id, value: String) extends Entry

                final case class ClassEntry(
                    className: String,
                    variances: Span[Variance],
                    params: Span[Id],
                    parents: Span[Id]
                ) extends Entry:
                    require(variances.size == params.size)
                end ClassEntry

                enum Variance(val show: String) extends Serializable derives CanEqual:
                    case Invariant     extends Variance("")
                    case Covariant     extends Variance("+")
                    case Contravariant extends Variance("-")
                end Variance

                final case class LambdaEntry(
                    params: Span[String],
                    lowerBounds: Span[Id],
                    upperBounds: Span[Id],
                    body: Id
                ) extends Entry

                final case class OpaqueEntry(
                    name: String,
                    lowerBound: Id,
                    upperBound: Id,
                    variances: Span[Variance],
                    params: Span[Id]
                ) extends Entry

            end Entry
        end Type

        private val threadSlots =
            if Platform.isJS then 1
            else Runtime.getRuntime().availableProcessors() * 8

        @SafePublish final private case class Comparison @noinline() (a: Tag[Any], b: Tag[Any], mode: Mode, result: Boolean):
            def matches(a: Tag[Any], b: Tag[Any], mode: Mode): Boolean = (a eq this.a) && (b eq this.b) && mode == this.mode

        /** Recent `checkTypes` results for one thread slot, keyed by the compared tags' identity and the mode.
          *
          * Hashes choose the set; they never authorize reuse. Each entry holds one immutable comparison, so a racing
          * replacement can cost a reader its hit but cannot hand it one pair's result under another pair's identity.
          *
          * Threads whose hashes pick the same cache share it, and the entries are plain, so an entry is safe to publish only
          * because its fields are final and the JVM orders a final field's construction before any read of it. Scala Native
          * orders only fields marked `@safePublish`, so `Comparison` carries that annotation: without it, an arm64 reader
          * could follow the new reference into memory still holding the object that occupied it before, and fail its type
          * test or read another comparison's result. Scala Native emits the annotation's release fence at the
          * constructor's return and drops it when the optimizer inlines the constructor, so the constructor is
          * `@noinline`.
          */
        final private[kyo] class ComparisonCache(sets: Int):
            // Two entries per set: a set keeps its two most recently used comparisons. With one, two hot comparisons whose hashes
            // share it evict each other on every check, and the hashes come from the encoded tags, so any change to an encoding can
            // create such a pair. Measured on Native: one pair took kyo-stm's miss rate from 3.8% to 46%, each miss decoding and
            // walking both types.
            private val entries = Array.fill[Maybe[Comparison]](sets * 2)(Absent)

            def set(a: Tag[Any], b: Tag[Any], mode: Mode): Int =
                var hash = (TagHash.of(a).toLong << 32) | (TagHash.of(b) & 0xffffffffL)
                hash += mode.factor
                hash ^= (hash >>> 30)
                hash *= 0xbf58476d1ce4e5b9L
                hash ^= (hash >>> 27)
                hash &= Long.MaxValue
                (hash & (sets - 1)).toInt
            end set

            def get(set: Int, a: Tag[Any], b: Tag[Any], mode: Mode): Maybe[Boolean] =
                val first = set * 2
                entries(first) match
                    case Present(cached) if cached.matches(a, b, mode) => Present(cached.result)
                    case head                                          =>
                        entries(first + 1) match
                            case Present(cached) if cached.matches(a, b, mode) =>
                                entries(first + 1) = head
                                entries(first) = Present(cached)
                                Present(cached.result)
                            case _ => Absent
                end match
            end get

            def put(set: Int, a: Tag[Any], b: Tag[Any], mode: Mode, result: Boolean): Unit =
                val first = set * 2
                entries(first + 1) = entries(first)
                entries(first) = Present(Comparison(a, b, mode, result))
            end put
        end ComparisonCache

        private[kyo] val comparisonCaches: Array[ComparisonCache] = Array.fill(threadSlots)(ComparisonCache(64))

        private[kyo] def cacheSlot(thread: Thread): Int = thread.hashCode & (threadSlots - 1)

        private def dynamicHashCode(tag: String, map: Map[Entry.Id, Any]): Int =
            val builder = new java.lang.StringBuilder(tag)
            map.toSeq.sortBy(_._1).foreach { (key, value) =>
                val valueHash =
                    value match
                        case value: String  => XXHash.hash32(value)
                        case value: Dynamic => value.hashCode
                        case value          => value.hashCode
                builder.append('\u0000').append(key).append('\u0001').append(valueHash)
            }
            XXHash.hash32(builder.toString)
        end dynamicHashCode

        /** A tag built at run time: the encoding the compiler wrote, with a slot for each type only a given tag knows, and the tag that
          * fills each slot. Its type is read once, with every part filled in and each union and intersection as the compiler writes one
          * (see [[normalized]]), so a comparison or a rendering never steps from one part into another.
          */
        final case class Dynamic(tag: String, map: Map[Entry.Id, Any]):
            lazy val tpe          = Type(normalized(flatten(this)))
            override val hashCode = dynamicHashCode(tag, map)

        /** The tag the compiler builds at run time. One whose union or intersection, its parts filled in, is a class or opaque type without
          * parameters is that type's encoding as the compiler writes it: `=:=` stops at the text of such a type, so it has no other.
          */
        def composed(tag: String, map: Map[Entry.Id, Any]): String | Dynamic =
            val built = Dynamic(tag, map)
            // a root the compiler could not know is a slot, absent from its own entries
            decode(tag).staticDB.get("0") match
                case Some(_: UnionEntry | _: IntersectionEntry) =>
                    val entries = built.tpe.staticDB
                    if concreteFlag(entries("0")) == "*" then encode(entries) else built
                case _ => built
            end match
        end composed

        enum Mode(val factor: Int) derives CanEqual:
            case Equality extends Mode(31)
            case Subtype  extends Mode(37)

        def checkTypes[A, B](a: Tag[A], b: Tag[B], mode: Mode): Boolean =
            val cache = comparisonCaches(cacheSlot(Thread.currentThread()))
            val set   = cache.set(a.erased, b.erased, mode)
            cache.get(set, a.erased, b.erased, mode) match
                case Present(cached) => cached
                case Absent          =>
                    val aTpe   = a.tpe
                    val bTpe   = b.tpe
                    val result =
                        mode match
                            case Mode.Equality => isSameType(aTpe, bTpe, aTpe.entryId, bTpe.entryId)
                            case Mode.Subtype  => isSubType(aTpe, bTpe, aTpe.entryId, bTpe.entryId)
                    cache.put(set, a.erased, b.erased, mode, result)
                    result
            end match
        end checkTypes

        private def isSubType(aOwner: Type[?], bOwner: Type[?], aId: Entry.Id, bId: Entry.Id): Boolean =
            val aEntry = aOwner.staticDB(aId)
            val bEntry = bOwner.staticDB(bId)
            aEntry match
                case NothingEntry => true
                case AnyEntry     =>
                    bEntry match
                        case AnyEntry              => true
                        case BoundsEntry(_, upper) => isSubType(aOwner, bOwner, aId, upper)
                        case _                     => false
                case NullEntry => !bEntry.isInstanceOf[LiteralEntry]

                // Outside an argument position a wildcard is read as its upper bound.
                case BoundsEntry(_, aUpper) => isSubType(aOwner, bOwner, aUpper, bId)

                case IntersectionEntry(aSet) =>
                    bEntry match
                        case IntersectionEntry(bSet) =>
                            bSet.forall { bElemId =>
                                aSet.exists { aElemId =>
                                    isSubType(aOwner, bOwner, aElemId, bElemId)
                                }
                            }
                        case _ =>
                            aSet.exists { aElemId =>
                                isSubType(aOwner, bOwner, aElemId, bId)
                            }

                case UnionEntry(aSet) =>
                    aSet.forall { aElemId =>
                        isSubType(aOwner, bOwner, aElemId, bId)
                    }

                case LiteralEntry(aWidened, value) =>
                    bEntry match
                        case LiteralEntry(bWidened, bValue) => isSubType(aOwner, bOwner, aWidened, bWidened) & value.equals(bValue)
                        case _                              => isSubType(aOwner, bOwner, aWidened, bId)

                case LambdaEntry(aParams, aLower, aUpper, aBody) =>
                    bEntry match
                        case AnyEntry                                    => true
                        case LambdaEntry(bParams, bLower, bUpper, bBody) =>
                            aParams.size == bParams.size &&
                            Span.forallZip(aLower, bLower) { (aLowerId, bLowerId) =>
                                isSubType(bOwner, aOwner, bLowerId, aLowerId)
                            } &&
                            Span.forallZip(aUpper, bUpper) { (aUpperId, bUpperId) =>
                                isSubType(aOwner, bOwner, aUpperId, bUpperId)
                            } &&
                            isSubType(aOwner, bOwner, aBody, bBody)
                        case _ => false

                case OpaqueEntry(aName, _, aUpper, aVariances, aParams) =>
                    bEntry match
                        case OpaqueEntry(bName, bLower, _, bVariances, bParams) if aName == bName =>
                            aParams.size == bParams.size &&
                            Span.forallZip(aVariances, aParams, bParams) { (variance, aParamId, bParamId) =>
                                conforms(variance, aOwner, bOwner, aParamId, bParamId)
                            }
                        case _ =>
                            isSubType(aOwner, bOwner, aUpper, bId)

                case aClass: ClassEntry =>
                    bEntry match
                        case NothingEntry => false
                        case AnyEntry     => true
                        case NullEntry    => false

                        case IntersectionEntry(bSet) =>
                            bSet.forall { bElemId =>
                                isSubType(aOwner, bOwner, aId, bElemId)
                            }

                        case UnionEntry(bSet) =>
                            bSet.exists { bElemId =>
                                isSubType(aOwner, bOwner, aId, bElemId)
                            }

                        case _: LiteralEntry => false

                        case _: LambdaEntry => false

                        case BoundsEntry(lower, upper) =>
                            isSubType(bOwner, aOwner, lower, aId) && isSubType(aOwner, bOwner, aId, upper)

                        case OpaqueEntry(_, lower, upper, variances, params) =>
                            isSubType(aOwner, bOwner, aId, lower) && {
                                Span.forallZip(variances, params) { (variance, paramId) =>
                                    isSameType(aOwner, bOwner, aId, paramId)
                                }
                            }

                        case bClass: ClassEntry =>
                            if isSameString(aClass.className, bClass.className) then
                                aClass.params.size == bClass.params.size &&
                                Span.forallZip(aClass.variances, aClass.params, bClass.params) { (variance, aParam, bParam) =>
                                    conforms(variance, aOwner, bOwner, aParam, bParam)
                                }
                            else
                                aClass.parents.exists { parentId =>
                                    isSubType(aOwner, bOwner, parentId, bId)
                                }
            end match
        end isSubType

        /** Whether the argument `aId` fills a parameter of `variance` as `bId` does, with a wildcard on either side read as the set of
          * types within its bounds: there is a type within `b`'s bounds that `a`'s argument conforms to under the variance, for every
          * type within `a`'s bounds. The lower bound matters only where the parameter is invariant or contravariant, the upper only
          * where it is invariant or covariant.
          */
        private def conforms(variance: Variance, aOwner: Type[?], bOwner: Type[?], aId: Entry.Id, bId: Entry.Id): Boolean =
            (aOwner.staticDB(aId), bOwner.staticDB(bId)) match
                case (BoundsEntry(aLower, aUpper), BoundsEntry(bLower, bUpper)) =>
                    variance match
                        case Variance.Invariant =>
                            isSubType(bOwner, aOwner, bLower, aLower) && isSubType(aOwner, bOwner, aUpper, bUpper)
                        case Variance.Covariant     => isSubType(aOwner, bOwner, aUpper, bUpper)
                        case Variance.Contravariant => isSubType(bOwner, aOwner, bLower, aLower)
                case (BoundsEntry(aLower, aUpper), _) =>
                    variance match
                        case Variance.Invariant     => false
                        case Variance.Covariant     => isSubType(aOwner, bOwner, aUpper, bId)
                        case Variance.Contravariant => isSubType(bOwner, aOwner, bId, aLower)
                case (_, BoundsEntry(bLower, bUpper)) =>
                    variance match
                        case Variance.Invariant =>
                            isSubType(bOwner, aOwner, bLower, aId) && isSubType(aOwner, bOwner, aId, bUpper)
                        case Variance.Covariant     => isSubType(aOwner, bOwner, aId, bUpper)
                        case Variance.Contravariant => isSubType(bOwner, aOwner, bLower, aId)
                case _ =>
                    variance match
                        case Variance.Invariant =>
                            isSubType(aOwner, bOwner, aId, bId) && isSubType(bOwner, aOwner, bId, aId)
                        case Variance.Covariant     => isSubType(aOwner, bOwner, aId, bId)
                        case Variance.Contravariant => isSubType(bOwner, aOwner, bId, aId)
        end conforms

        private def isSameString(a: String, b: String): Boolean = (a eq b) || (a.hashCode() == b.hashCode() && a.equals(b))

        private def isSameType(aOwner: Type[?], bOwner: Type[?], aId: Entry.Id, bId: Entry.Id): Boolean =
            val aEntry = aOwner.staticDB(aId)
            val bEntry = bOwner.staticDB(bId)
            aEntry match
                case NothingEntry => bEntry eq NothingEntry
                case AnyEntry     => bEntry eq AnyEntry
                case NullEntry    => bEntry eq NullEntry

                case BoundsEntry(aLower, aUpper) =>
                    bEntry match
                        case BoundsEntry(bLower, bUpper) =>
                            isSameType(aOwner, bOwner, aLower, bLower) && isSameType(aOwner, bOwner, aUpper, bUpper)
                        case _ => false

                // Both sides are normalized, no member covering another, so the members are the same types in some order. The
                // compiler orders them by their text where it derives the type, which for a slot is its parameter's name.
                case IntersectionEntry(aSet) =>
                    bEntry match
                        case IntersectionEntry(bSet) =>
                            aSet.size == bSet.size && aSet.forall(a => bSet.exists(isSameType(aOwner, bOwner, a, _)))
                        case _ => false

                case UnionEntry(aSet) =>
                    bEntry match
                        case UnionEntry(bSet) =>
                            aSet.size == bSet.size && aSet.forall(a => bSet.exists(isSameType(aOwner, bOwner, a, _)))
                        case _ => false

                case LiteralEntry(aId, value) =>
                    bEntry match
                        case LiteralEntry(bId, `value`) => isSameType(aOwner, bOwner, aId, bId)
                        case _                          => false

                case LambdaEntry(aParams, aLower, aUpper, aBody) =>
                    bEntry match
                        case LambdaEntry(bParams, bLower, bUpper, bBody) =>
                            aParams.size == bParams.size &&
                            Span.forallZip(aLower, bLower)(isSameType(aOwner, bOwner, _, _)) &&
                            Span.forallZip(aUpper, bUpper)(isSameType(aOwner, bOwner, _, _))
                        case _ => false

                case OpaqueEntry(name, aLower, aUpper, aVariances, aParams) =>
                    bEntry match
                        case OpaqueEntry(`name`, bLower, bUpper, bVariances, bParams) =>
                            aParams.size == bParams.size &&
                            isSameType(aOwner, bOwner, aLower, bLower) &&
                            isSameType(aOwner, bOwner, aUpper, bUpper) &&
                            Span.forallZip(aParams, bParams)(isSameType(aOwner, bOwner, _, _))
                        case _ =>
                            false

                case ClassEntry(name, aVariances, aParams, aParents) =>
                    bEntry match
                        case ClassEntry(`name`, bVariances, bParams, bParents) if aVariances.is(bVariances) =>
                            aParams.size == bParams.size && Span.forallZip(aParams, bParams)(isSameType(aOwner, bOwner, _, _))
                        case _ => false
            end match
        end isSameType

        def encode[A](staticDB: Map[Type.Entry.Id, Type.Entry]): String =
            def encodeEntry(entry: Entry): String =
                entry match
                    case AnyEntry     => "A"
                    case NothingEntry => "N"
                    case NullEntry    => "U"

                    case LiteralEntry(widened, value) => s"L:$widened:$value"
                    case BoundsEntry(lower, upper)    => s"B:$lower:$upper"
                    case IntersectionEntry(set)       => s"I:${set.mkString(":")}"
                    case UnionEntry(set)              => s"U:${set.mkString(":")}"

                    case LambdaEntry(params, lower, upper, body) =>
                        s"M:$body:${params.size}:${params.mkString(":")}:${lower.mkString(":")}:${upper.mkString(":")}"

                    case OpaqueEntry(name, lower, upper, variances, params) =>
                        s"O:$name:$lower:$upper:${params.size}:${variances.map(_.ordinal).mkString(":")}:${params.mkString(":")}"

                    case ClassEntry(className, variances, params, parents) =>
                        val sanitized = className.replaceAll(":", "_colon_")
                        s"C:$sanitized:${params.size}:${variances.map(_.ordinal).mkString(":")}:" +
                            s"${params.mkString(":")}:${parents.mkString(":")}"
            end encodeEntry

            concreteFlag(staticDB("0")) +
                staticDB.map { (id, entry) =>
                    s"$id:${encodeEntry(entry)}"
                }.mkString("\n")
        end encode

        /** The tag as one encoding, the same for every tag of its type, which a record keeps and [[parse]] reads back. A class or opaque
          * type without parameters keeps the compiler's text, the one `=:=` stops at. Any other type is written with the entries that
          * stand for one type merged, each union's and intersection's members in the order their renderings sort in, and the ids in the
          * order of that walk, so a type the compiler derived and the same type built at run time are written alike.
          */
        def encode(tag: Tag[Any]): String =
            tag match
                case tag: String if tag.charAt(0) == '*' => tag
                case tag                                 => encode(canonical(tag.tpe.staticDB))

        // The entries of a tag built at run time with each slot filled: a part's root takes the slot's id and its other entries go under
        // that id, so the ids of two parts never meet.
        private def flatten(dynamic: Dynamic): Map[Entry.Id, Entry] =
            // the compiler fills each slot with the given tag it found, as a Tag[Any]
            dynamic.map.asInstanceOf[Map[Entry.Id, Tag[Any]]].foldLeft(decode(dynamic.tag).staticDB) { case (entries, (slot, part)) =>
                def under(id: Entry.Id): Entry.Id = if id == "0" then slot else s"$slot.$id"
                entries ++ part.tpe.staticDB.map((id, entry) => under(id) -> renamed(entry, under))
            }

        /** `entries` with each union and intersection as the compiler writes one, in the ids the compiler would give them. A member that is
          * itself a union, or in an intersection an intersection, is spliced in, and a member another one covers is dropped: a narrower
          * member of a union, a wider one of an intersection, and the second of two that stand for one type. One left standing is the
          * whole: every reference to the union, the root's included, goes to that member, so a member that names itself through a parent,
          * as `String` does through `Comparable[String]`, still does.
          */
        private def normalized(entries: Map[Entry.Id, Entry]): Map[Entry.Id, Entry] =
            val tpe                                                           = Type(entries)
            def spliced(set: Span[Entry.Id], union: Boolean): Chunk[Entry.Id] =
                Chunk.from(set.toArray).flatMap(member =>
                    entries(member) match
                        case UnionEntry(inner) if union         => spliced(inner, union)
                        case IntersectionEntry(inner) if !union => spliced(inner, union)
                        case _                                  => Chunk(member)
                )
            def kept(members: Chunk[Entry.Id], union: Boolean): Chunk[Entry.Id] =
                def covers(wide: Entry.Id, narrow: Entry.Id) =
                    if union then isSubType(tpe, tpe, narrow, wide) else isSubType(tpe, tpe, wide, narrow)
                members.zipWithIndex.filterNot((member, i) =>
                    members.zipWithIndex.exists((other, j) => j != i && covers(other, member) && (!covers(member, other) || j < i))
                ).map(_._1)
            end kept
            val reduced = entries.collect {
                case (id, UnionEntry(set))        => id -> (kept(spliced(set, union = true), union = true), true)
                case (id, IntersectionEntry(set)) => id -> (kept(spliced(set, union = false), union = false), false)
            }
            @tailrec def resolved(id: Entry.Id): Entry.Id =
                reduced.get(id) match
                    case Some((members, _)) if members.size == 1 => resolved(members(0))
                    case _                                       => id
            val rewritten = entries.map { (id, entry) =>
                reduced.get(id) match
                    case Some((members, union)) if members.size != 1 =>
                        val set = Span.from(members.map(resolved))
                        id -> (if union then UnionEntry(set) else IntersectionEntry(set))
                    case _ => id -> renamed(entry, resolved)
            }
            written(rewritten, resolved("0"))
        end normalized

        // `entries` written so that every encoding of one type is the same: entries that stand for one type merged, by the coarsest
        // grouping by kind that what each one names respects, so a type naming itself through a parent merges too; the members of each
        // union and intersection in the order their renderings sort in; and the ids in the order of that walk.
        private def canonical(entries: Map[Entry.Id, Entry]): Map[Entry.Id, Entry] =
            val order                                                          = walk(entries, "0")
            @tailrec def refine(group: Map[Entry.Id, Int]): Map[Entry.Id, Int] =
                val keys  = order.map(id => id -> (group(id), names(entries(id)).map(group)))
                val index = keys.map(_._2).distinct.zipWithIndex.toMap
                if index.size == group.values.toSet.size then group else refine(keys.map((id, key) => id -> index(key)).toMap)
            end refine
            val kinds = order.map(id => kind(entries(id))).distinct.zipWithIndex.toMap
            val group = refine(order.map(id => id -> kinds(kind(entries(id)))).toMap)
            // the first entry of each group in walk order stands for it
            val first  = order.reverse.map(id => group(id) -> id).toMap
            val tpe    = Type(entries)
            val merged = order.filter(id => first(group(id)) == id).map { id =>
                id ->
                    (renamed(entries(id), other => first(group(other))) match
                        case UnionEntry(set)        => UnionEntry(Span.from(set.toArray.sortBy(tpe.render)))
                        case IntersectionEntry(set) => IntersectionEntry(Span.from(set.toArray.sortBy(tpe.render)))
                        case other                  => other)
            }.toMap
            written(merged, first(group("0")))
        end canonical

        // `entries` from `root`, which takes `0`, renumbered in the order the compiler visits a type: each entry once, before the ones it
        // names, in the order it names them, and its lines as the compiler's map lays them out. A type the compiler derived is written
        // back as the text it wrote.
        private def written(entries: Map[Entry.Id, Entry], root: Entry.Id): Map[Entry.Id, Entry] =
            val order  = walk(entries, root)
            val number = order.zipWithIndex.map((id, index) => id -> index.toString).toMap
            HashMap.from(order.map(id => number(id) -> renamed(entries(id), number)))
        end written

        private def walk(entries: Map[Entry.Id, Entry], root: Entry.Id): Chunk[Entry.Id] =
            @tailrec def loop(pending: List[Entry.Id], seen: Set[Entry.Id], order: Chunk[Entry.Id]): Chunk[Entry.Id] =
                pending match
                    case Nil                             => order
                    case id :: rest if seen.contains(id) => loop(rest, seen, order)
                    case id :: rest                      => loop(names(entries(id)) ++ rest, seen + id, order.append(id))
            loop(List(root), Set.empty, Chunk.empty)
        end walk

        // The ids an entry names, in the order the compiler visits them when it derives the entry.
        private def names(entry: Entry): List[Entry.Id] =
            entry match
                case AnyEntry | NothingEntry | NullEntry     => Nil
                case LiteralEntry(widened, _)                => List(widened)
                case BoundsEntry(lower, upper)               => List(lower, upper)
                case IntersectionEntry(set)                  => set.toArray.toList
                case UnionEntry(set)                         => set.toArray.toList
                case LambdaEntry(_, lower, upper, body)      => lower.toArray.toList ++ upper.toArray.toList :+ body
                case OpaqueEntry(_, lower, upper, _, params) => lower :: upper :: params.toArray.toList
                case ClassEntry(_, _, params, parents)       => params.toArray.toList ++ parents.toArray.toList

        // An entry without the ids it names: two entries of one kind that name the same types stand for one type.
        private def kind(entry: Entry): String =
            entry match
                case AnyEntry                                => "A"
                case NothingEntry                            => "N"
                case NullEntry                               => "U"
                case LiteralEntry(_, value)                  => s"L:$value"
                case BoundsEntry(_, _)                       => "B"
                case IntersectionEntry(set)                  => s"I:${set.size}"
                case UnionEntry(set)                         => s"U:${set.size}"
                case LambdaEntry(params, _, _, _)            => s"M:${params.mkString(":")}"
                case OpaqueEntry(name, _, _, variances, _)   => s"O:$name:${variances.map(_.ordinal).mkString(":")}"
                case ClassEntry(name, variances, _, parents) => s"C:$name:${variances.map(_.ordinal).mkString(":")}:${parents.size}"

        private def renamed(entry: Entry, f: Entry.Id => Entry.Id): Entry =
            entry match
                case AnyEntry | NothingEntry | NullEntry                => entry
                case LiteralEntry(widened, value)                       => LiteralEntry(f(widened), value)
                case BoundsEntry(lower, upper)                          => BoundsEntry(f(lower), f(upper))
                case IntersectionEntry(set)                             => IntersectionEntry(set.map(f))
                case UnionEntry(set)                                    => UnionEntry(set.map(f))
                case LambdaEntry(params, lower, upper, body)            => LambdaEntry(params, lower.map(f), upper.map(f), f(body))
                case OpaqueEntry(name, lower, upper, variances, params) => OpaqueEntry(name, f(lower), f(upper), variances, params.map(f))
                case ClassEntry(name, variances, params, parents)       => ClassEntry(name, variances, params.map(f), parents.map(f))

        // A concrete type, a class or opaque type without parameters, is flagged so that an equality check against it stops at its text.
        private def concreteFlag(root: Entry): String =
            root match
                case ClassEntry(_, _, params, _) if params.isEmpty     => "*"
                case OpaqueEntry(_, _, _, _, params) if params.isEmpty => "*"
                case _                                                 => "."

        /** The tag `encoded` holds, when it is a whole encoding: the flag its root is written with, then entries as [[encode]] writes
          * them, the root `0` among them, and every id an entry names one of them. A text read back as a tag goes through it, so one that
          * is not an encoding fails where it is read; [[decode]] trusts its text, which the compiler wrote.
          */
        def parse[A](encoded: String): Maybe[Tag[A]] =
            val entries =
                if encoded.isEmpty then Absent
                else
                    encoded.drop(1).linesIterator.foldLeft(Maybe(Map.empty[Entry.Id, Entry])) { (read, line) =>
                        read.flatMap { entries =>
                            line.split(":", -1).toList match
                                case id :: fields if id.nonEmpty && !entries.contains(id) => readEntry(fields).map(entries.updated(id, _))
                                case _                                                    => Absent
                        }
                    }
            entries.filter { entries =>
                entries.get("0").exists(root => concreteFlag(root) == encoded.take(1)) &&
                entries.values.forall(names(_).forall(entries.contains))
            }.map(_ => encoded)
        end parse

        /** Cache for decoded type structures. This cache ensures that each unique encoded type string is only deserialized once,
          * significantly improving performance for repeated operations on the same types. Unlike the subtype cache, this cache is fully
          * thread-safe using ConcurrentHashMap and never evicts entries.
          *
          * Since the set of unique types in a program is determined at compile time and is typically much smaller than the set of possible
          * subtype relationships, this cache can safely grow without bounds. Each unique Tag is only decoded once during the lifetime of
          * the application, regardless of how many times it's used.
          */
        private val decodeCache = new ConcurrentHashMap[String, Type[?]]()

        private val decodeFunction: java.util.function.Function[String, Type[?]] =
            (encoded: String) =>
                val lines    = encoded.drop(1).linesIterator // discard concreteFlag
                val staticDb =
                    HashMap.empty[Entry.Id, Entry] ++
                        lines.map { encoded =>
                            val fields = encoded.split(":", -1).toList
                            fields.head -> decodeEntry(fields.tail)
                        }
                Type(staticDb)

        def decode[A](encoded: String): Type[?] =
            decodeCache.computeIfAbsent(encoded, decodeFunction)

        def decodeEntry(fields: List[String]): Entry =
            readEntry(fields).getOrElse(bug("Invalid tag payload! " + fields.mkString(":")))

        // An entry's fields as `encode` writes them. A parameterless class, lambda or opaque type writes its empty variances and
        // parameters as one empty field each.
        private def readEntry(fields: List[String]): Maybe[Entry] =
            fields match
                case "A" :: Nil                     => Present(AnyEntry)
                case "N" :: Nil                     => Present(NothingEntry)
                case "U" :: Nil                     => Present(NullEntry)
                case "L" :: widened :: value :: Nil => Present(LiteralEntry(widened, value))
                case "B" :: lower :: upper :: Nil   => Present(BoundsEntry(lower, upper))
                case "I" :: set                     => Present(IntersectionEntry(Span.from(set)))
                case "U" :: set                     => Present(UnionEntry(Span.from(set)))

                case "M" :: body :: size :: rest =>
                    count(size, rest, 3).map(n =>
                        LambdaEntry(
                            body = body,
                            params = Span.from(rest.take(n)),
                            lowerBounds = Span.from(rest.slice(n, 2 * n)),
                            upperBounds = Span.from(rest.slice(2 * n, 3 * n))
                        )
                    )

                case "O" :: name :: lower :: upper :: size :: rest =>
                    count(size, rest, 2).flatMap(n =>
                        variances(rest.take(n)).map(variances =>
                            OpaqueEntry(
                                name = name,
                                lowerBound = lower,
                                upperBound = upper,
                                variances = variances,
                                params = Span.from(rest.slice(n, 2 * n))
                            )
                        )
                    )

                case "C" :: name :: size :: rest =>
                    count(size, rest, 2).flatMap(n =>
                        variances(rest.take(n)).map(variances =>
                            ClassEntry(
                                className = name,
                                variances = variances,
                                params = Span.from(rest.slice(n, 2 * n)),
                                parents = Span.from(rest.drop(if n == 0 then 2 else 2 * n).filter(_.nonEmpty))
                            )
                        )
                    )

                case _ => Absent
            end match
        end readEntry

        // The `n` an entry's `size` field holds, when `rest` has the `sections` runs of `n` fields it promises.
        private def count(size: String, rest: List[String], sections: Int): Maybe[Int] =
            Maybe.fromOption(size.toIntOption).filter(n => n >= 0 && n.toLong * sections <= rest.size)

        private def variances(fields: List[String]): Maybe[Span[Variance]] =
            fields.foldLeft(Maybe(Chunk.empty[Variance])) { (read, field) =>
                read.flatMap(kept =>
                    Maybe.fromOption(field.toIntOption).filter(ordinal => ordinal >= 0 && ordinal < Variance.values.length)
                        .map(ordinal => kept.append(Variance.fromOrdinal(ordinal)))
                )
            }.map(Span.from(_))
    end internal

end Tag
