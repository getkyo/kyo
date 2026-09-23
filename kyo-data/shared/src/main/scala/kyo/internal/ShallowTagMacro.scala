package kyo.internal

import kyo.ShallowTag
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.quoted.*

/** Derives [[kyo.ShallowTag]] by computing Scala's erasure of a type and emitting it as a `classOf` constant.
  *
  * The quotes API exposes no erasure, so this restates the rules of the compiler's `TypeErasure` that decide the class of `new Array[A]`:
  * `erasedLub` for unions and `erasedGlb` for intersections. The result must match what a compiler-synthesized ClassTag holds, because
  * arrays built from either kind of evidence meet in the same Span and a mismatch fails the `checkcast` at a use site.
  */
@publicInBinary
private[kyo] object ShallowTagMacro:

    /** Stores into an array held at Span's own shape, `Array[? <: A]`.
      *
      * That shape erases to `Object` when values of `A` may need either a primitive or a reference array (`Any`, `AnyVal`, `Int | String`),
      * because a Span of such an `A` may be backed by either, as a `Span[Int]` widened to `Span[Any]` is. A typed store would cast the
      * array to `Array[A]` (`Object[]`) and fail on the `int[]`, so those types store through `ScalaRunTime.array_update`. Every other `A`
      * keeps the typed store.
      */
    def store[A: Type](array: Expr[Array[? <: A]], idx: Expr[Int], value: Expr[A])(using Quotes): Expr[Unit] =
        import quotes.reflect.*
        if arrayUpperBound(TypeRepr.of[A]).isDefined then '{ $array.asInstanceOf[Array[A]]($idx) = $value }
        else '{ scala.runtime.ScalaRunTime.array_update($array, $idx, $value) }
    end store

    // The JVM array kind that can hold every value of `tpe`: a primitive class, Object, or none when values need both kinds
    // (TypeErasure.isGenericArrayElement).
    private def arrayUpperBound(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.Symbol] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        tpe.dealias match
            case tpe: TypeRef if tpe.typeSymbol.flags.is(Flags.Opaque) => arrayUpperBound(tpe.translucentSuperType)
            case tpe: TypeRef if tpe.typeSymbol.isClassDef             =>
                val sym = tpe.typeSymbol
                if topSymbols.contains(sym) && sym != defn.ObjectClass || sym == Symbol.requiredClass("scala.Singleton") then None
                else if isPrimitive(sym) then Some(sym)
                else Some(defn.ObjectClass)
            case AppliedType(tycon: TypeRef, args) if tycon.typeSymbol.flags.is(Flags.Opaque) =>
                arrayUpperBound(tycon.translucentSuperType.appliedTo(args))
            case AppliedType(tycon, _) => arrayUpperBound(tycon)
            case tpe: ConstantType     => arrayUpperBound(tpe.widen)
            case tpe: TermRef          => arrayUpperBound(tpe.widen)
            case AndType(a, b)         =>
                val (ra, rb) = (arrayUpperBound(a), arrayUpperBound(b))
                if ra == rb then ra else ra.orElse(rb)
            case OrType(a, b) =>
                val (ra, rb) = (arrayUpperBound(a), arrayUpperBound(b))
                if ra == rb then ra else None
            case _ => None
        end match
    end arrayUpperBound

    private def topSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        import quotes.reflect.*
        Set(defn.AnyClass, defn.AnyValClass, defn.MatchableClass, defn.ObjectClass)

    private def isPrimitive(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        sym == defn.IntClass || sym == defn.LongClass || sym == defn.DoubleClass || sym == defn.FloatClass ||
        sym == defn.ByteClass || sym == defn.ShortClass || sym == defn.CharClass || sym == defn.BooleanClass ||
        sym == defn.UnitClass
    end isPrimitive

    def derive[A: Type](using Quotes): Expr[ShallowTag[A]] =
        import quotes.reflect.*

        enum Erased:
            case Cls(sym: Symbol)
            case Arr(elem: Erased)

        given CanEqual[Symbol, Symbol] = CanEqual.derived
        given CanEqual[Erased, Erased] = CanEqual.derived

        val objectSym  = defn.ObjectClass
        val anySyms    = topSymbols
        val pairSym    = Symbol.requiredClass("scala.*:")
        val tupleSyms  = (1 to 22).map(n => Symbol.requiredClass(s"scala.Tuple$n"))
        val productSym = Symbol.requiredClass("scala.Product")
        val pureName   = "scala.caps.Pure"

        def fail(detail: String): Nothing =
            report.errorAndAbort(
                s"""This method requires a ShallowTag, but ${TypeRepr.of[A].show} has no runtime class: $detail
                   |For a generic type, take the evidence from the caller: def method[A](using ShallowTag[A]) = ???""".stripMargin
            )

        def isBottom(tpe: TypeRepr): Boolean =
            val sym = tpe.dealias.typeSymbol
            sym == defn.NothingClass || sym == defn.NullClass

        def cls(sym: Symbol): Erased =
            if anySyms.contains(sym) then Erased.Cls(objectSym) else Erased.Cls(sym)

        def erase(tpe: TypeRepr): Erased =
            tpe.dealias match
                case tpe: ConstantType            => erase(tpe.widen)
                case tpe: TermRef                 => erase(tpe.widen)
                case tpe: ThisType                => erase(tpe.widen)
                case AnnotatedType(underlying, _) => erase(underlying)
                case Refinement(parent, _, _)     => erase(parent)
                case AndType(a, b)                => glb(erase(a), erase(b))
                case OrType(a, b)                 =>
                    if a.dealias.typeSymbol == defn.NothingClass then erase(b)
                    else if b.dealias.typeSymbol == defn.NothingClass then erase(a)
                    else if a.dealias.typeSymbol == defn.NullClass && b.derivesFrom(objectSym) then erase(b)
                    else if b.dealias.typeSymbol == defn.NullClass && a.derivesFrom(objectSym) then erase(a)
                    else lub(erase(a), erase(b))
                case tpe @ AppliedType(tycon, args) =>
                    val sym = tycon.typeSymbol
                    if sym.flags.is(Flags.Opaque) then
                        tycon match
                            case tycon: TypeRef => erase(tycon.translucentSuperType.appliedTo(args))
                            case _              => fail(s"${tycon.show} is not a class")
                    else if sym == defn.ArrayClass then eraseElem(args.head)
                    else if sym == pairSym then tuple(tpe)
                    else if sym.isClassDef then cls(sym)
                    else fail(s"${tycon.show} is not a class")
                    end if
                case tpe: TypeRef =>
                    val sym = tpe.typeSymbol
                    if sym == defn.NothingClass || sym == defn.NullClass then fail(s"${sym.name} has no instances to hold")
                    else if sym.flags.is(Flags.Opaque) then erase(tpe.translucentSuperType)
                    else if sym.isClassDef then cls(sym)
                    else fail(s"${tpe.show} is an abstract type")
                    end if
                case other => fail(s"${other.show} is not a class type")
            end match
        end erase

        // A wildcard element such as the `? <: Int` in `Span[Int] = Array[? <: Int]` erases to its upper bound when every value of the
        // bound fits one JVM array kind, and the whole array erases to Object otherwise (TypeErasure.isGenericArrayElement).
        def eraseElem(tpe: TypeRepr): Erased =
            tpe match
                case TypeBounds(_, hi) =>
                    if arrayUpperBound(hi).isEmpty then Erased.Cls(objectSym) else eraseElem(hi)
                case _ =>
                    if isBottom(tpe) then fail(s"an array of ${tpe.show} has no runtime class")
                    else Erased.Arr(erase(tpe))

        // TypeErasure.erasePair: a statically sized `*:` chain erases to the matching TupleN (TupleXXL past 22), anything else to Product.
        def tuple(tpe: TypeRepr): Erased =
            @tailrec def arity(t: TypeRepr, n: Int): Int =
                t.dealias match
                    case AppliedType(tycon, List(_, tail)) if tycon.typeSymbol == pairSym => arity(tail, n + 1)
                    case t if t =:= TypeRepr.of[EmptyTuple]                               => n
                    case _                                                                => -1
            val n = arity(tpe, 0)
            if n < 0 then Erased.Cls(productSym)
            else if n <= 22 then Erased.Cls(tupleSyms(n - 1))
            else Erased.Cls(Symbol.requiredClass("scala.runtime.TupleXXL"))
        end tuple

        def isPrimitiveElem(erased: Erased): Boolean =
            erased match
                case Erased.Cls(sym) => isPrimitive(sym)
                case Erased.Arr(_)   => false

        def derivesFrom(a: Symbol, b: Symbol): Boolean = a.typeRef.derivesFrom(b)

        // TypeErasure.erasedLub: the last minimal common base class in tp1's linearization, which prefers classes over traits.
        def lub(a: Erased, b: Erased): Erased =
            (a, b) match
                case (Erased.Arr(ea), Erased.Arr(eb)) =>
                    if isPrimitiveElem(ea) || isPrimitiveElem(eb) then
                        if ea == eb then a else Erased.Cls(objectSym)
                    else Erased.Arr(lub(ea, eb))
                case (Erased.Arr(_), _) | (_, Erased.Arr(_)) => Erased.Cls(objectSym)
                case (Erased.Cls(sa), Erased.Cls(sb))        =>
                    val common = sa.typeRef.baseClasses.filter(c => c.fullName != pureName && derivesFrom(sb, c))
                    // The compiler's `takeUntil` keeps the leading non-traits plus the first trait, whatever its comment suggests.
                    val candidates = common.span(!_.flags.is(Flags.Trait)) match
                        case (classes, firstTrait :: _) => classes :+ firstTrait
                        case (classes, Nil)             => classes
                    val minimums = candidates.filter(c => c != pairSym && candidates.forall(x => !derivesFrom(x, c) || x == c))
                    minimums.lastOption.fold(Erased.Cls(objectSym))(cls)

        // TypeErasure.compareErasedGlb: arrays, then primitives, then real classes, then subclasses, then full name.
        def glb(a: Erased, b: Erased): Erased =
            def compare(a: Erased, b: Erased): Int =
                (a, b) match
                    case (Erased.Arr(ea), Erased.Arr(eb)) => compare(ea, eb)
                    case (Erased.Arr(_), _)               => -1
                    case (_, Erased.Arr(_))               => 1
                    case (Erased.Cls(sa), Erased.Cls(sb)) =>
                        def byClass =
                            if derivesFrom(sa, sb) then -1
                            else if derivesFrom(sb, sa) then 1
                            else sa.fullName.compareTo(sb.fullName)
                        val (primA, primB) = (isPrimitive(sa), isPrimitive(sb))
                        val (realA, realB) = (!sa.flags.is(Flags.Trait), !sb.flags.is(Flags.Trait))
                        if primA != primB then (if primA then -1 else 1)
                        else if primA then byClass
                        else if realA != realB then (if realA then -1 else 1)
                        else byClass
                        end if
            if compare(a, b) <= 0 then a else b
        end glb

        def typeOf(erased: Erased): TypeRepr =
            erased match
                case Erased.Cls(sym) if sym == defn.UnitClass => TypeRepr.of[scala.runtime.BoxedUnit]
                case Erased.Cls(sym)                          => sym.typeRef
                case Erased.Arr(elem)                         => TypeRepr.of[Array].appliedTo(typeOf(elem))

        val tpe = TypeRepr.of[A]
        if isBottom(tpe) then fail(s"${tpe.show} has no instances to hold")
        val classOf = Literal(ClassOfConstant(typeOf(erase(tpe)))).asExprOf[Class[?]]
        '{ $classOf.asInstanceOf[ShallowTag[A]] }
    end derive

end ShallowTagMacro
