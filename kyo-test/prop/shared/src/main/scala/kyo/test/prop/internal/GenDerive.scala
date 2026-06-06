package kyo.test.prop.internal

import kyo.test.prop.Gen
import scala.quoted.*

/** Macro-based derivation of Gen[A] via scala.deriving.Mirror.
  *
  * Supports:
  *   - Case classes (Mirror.ProductOf[A]): derives Gen for each field by summoning Gen[FieldType], then assembles instances via
  *     Mirror.fromProduct.
  *   - Sealed traits (Mirror.SumOf[A]): picks a subtype uniformly at random and delegates to that subtype's Gen.
  *
  * Recursion control: the size parameter is decremented by 1 at each recursive level. At size == 0, the first (index 0) subtype is always
  * chosen for Sum mirrors to produce a base case and avoid infinite recursion on recursive ADTs.
  */
object GenDerive:

    def deriveImpl[A: Type](using Quotes): Expr[Gen[A]] =
        import quotes.reflect.*

        val aType = TypeRepr.of[A]

        // Look for a given Mirror for A
        Expr.summon[scala.deriving.Mirror.Of[A]] match
            case Some(mirror) =>
                mirror match
                    case '{ $m: scala.deriving.Mirror.ProductOf[A] { type MirroredElemTypes = elems } } =>
                        deriveProduct[A, elems](m)
                    case '{ $m: scala.deriving.Mirror.SumOf[A] { type MirroredElemTypes = elems } } =>
                        deriveSum[A, elems](m)
                    case _ =>
                        report.errorAndAbort(
                            s"Gen.derive: cannot derive Gen for ${Type.show[A]}: unsupported Mirror shape"
                        )
            case None =>
                report.errorAndAbort(
                    s"Gen.derive: no Mirror found for ${Type.show[A]}. Only case classes and sealed traits are supported."
                )
        end match
    end deriveImpl

    // ── Product derivation ───────────────────────────────────────────────

    private def deriveProduct[A: Type, Elems: Type](mirror: Expr[scala.deriving.Mirror.ProductOf[A]])(using Quotes): Expr[Gen[A]] =
        import quotes.reflect.*

        val elemTypes = collectTypes[Elems]

        // macro-time AST collection: List is the quotes idiom
        // For each field type, summon or derive a Gen
        val fieldGens: List[Expr[Gen[?]]] = elemTypes.map { case '[t] =>
            Expr.summon[Gen[t]] match
                case Some(g) => g
                case None    =>
                    // Attempt recursive derivation
                    deriveImpl[t]
        }

        val fieldGensExpr: Expr[Array[Gen[?]]] =
            // Unsafe: type-erased macro-time coercion; Varargs requires List[Expr[T]] but we hold List[Expr[Gen[?]]]; safe by macro construction
            '{ Array(${ Varargs(fieldGens.asInstanceOf[List[Expr[Gen[?]]]]) }*) }

        val numFields = elemTypes.size

        '{
            new Gen[A]:
                private val _fieldGens: Array[Gen[?]] = $fieldGensExpr

                def sample(seed: kyo.test.prop.internal.Seed, size: Int): kyo.test.prop.internal.Tree[A] =
                    val innerSize = math.max(0, size - 1)
                    // Sample each field's tree from an independent split of the seed.
                    val fieldTrees = new Array[kyo.test.prop.internal.Tree[Any]](${ Expr(numFields) })
                    var current    = seed
                    var i          = 0
                    while i < ${ Expr(numFields) } do
                        val (fieldSeed, next) = kyo.test.prop.internal.Seed.split(current)
                        // Unsafe: type-erased Array[Gen[?]] storage; index i holds Gen[ElemType_i]; safe by macro construction
                        fieldTrees(i) = _fieldGens(i).asInstanceOf[Gen[Any]].sample(fieldSeed, innerSize)
                        current = next
                        i += 1
                    end while
                    // Zip the field trees into a product tree: the node value assembles the product from field values;
                    // shrinks replace one field at a time with one of its own shrink subtrees.
                    kyo.test.prop.internal.GenDeriveRuntime.productTree(
                        fieldTrees,
                        elems => $mirror.fromProduct(Tuple.fromArray(elems))
                    )
                end sample
        }
    end deriveProduct

    // ── Sum derivation ───────────────────────────────────────────────────

    private def deriveSum[A: Type, Elems: Type](mirror: Expr[scala.deriving.Mirror.SumOf[A]])(using Quotes): Expr[Gen[A]] =
        import quotes.reflect.*

        val subtypeTypes = collectTypes[Elems]
        val numSubtypes  = subtypeTypes.size

        // macro-time AST collection: List is the quotes idiom
        // For each subtype, summon or derive a Gen
        val subtypeGens: List[Expr[Gen[? <: A]]] = subtypeTypes.map { case '[t] =>
            Expr.summon[Gen[t]] match
                // Unsafe: t <: A guaranteed by Mirror.SumOf; widening to Gen[? <: A] is safe by macro construction
                case Some(g) => '{ $g.asInstanceOf[Gen[? <: A]] }
                case None    => '{ ${ deriveImpl[t] }.asInstanceOf[Gen[? <: A]] }
        }

        val subtypeGensExpr: Expr[Array[Gen[? <: A]]] =
            '{ Array(${ Varargs(subtypeGens) }*) }

        '{
            new Gen[A]:
                private val _subtypeGens: Array[Gen[? <: A]] = $subtypeGensExpr

                def sample(seed: kyo.test.prop.internal.Seed, size: Int): kyo.test.prop.internal.Tree[A] =
                    val innerSize               = math.max(0, size - 1)
                    val (chosenSeed, crossSeed) = kyo.test.prop.internal.Seed.split(seed)
                    val (raw, s1)               = kyo.test.prop.internal.Seed.next(chosenSeed)
                    // At size == 0 use index 0 (base case) to prevent infinite recursion on recursive ADTs.
                    val idx =
                        if size == 0 then 0
                        else (((raw >>> 1) % ${ Expr(numSubtypes) }.toLong).toInt)
                    val chosen: kyo.test.prop.internal.Tree[A] = _subtypeGens(idx).sample(s1, innerSize)
                    // Build lazy earlier-subtype thunks (j < idx), each from an independent crossSeed split.
                    // When idx == 0 the loop body never executes and earlier is empty, so sumTree returns chosen unchanged.
                    val earlier      = new Array[() => kyo.test.prop.internal.Tree[A]](idx)
                    var crossCurrent = crossSeed
                    var j            = 0
                    while j < idx do
                        val (jSeed, nextCross) = kyo.test.prop.internal.Seed.split(crossCurrent)
                        val jIdx               = j // capture for the closure
                        // Unsafe: t <: A guaranteed by Mirror.SumOf; widening to Tree[A] is safe by macro construction
                        earlier(jIdx) = () => _subtypeGens(jIdx).sample(jSeed, innerSize).asInstanceOf[kyo.test.prop.internal.Tree[A]]
                        crossCurrent = nextCross
                        j += 1
                    end while
                    kyo.test.prop.internal.GenDeriveRuntime.sumTree(chosen, earlier)
                end sample
        }
    end deriveSum

    // ── Helpers ──────────────────────────────────────────────────────────

    // macro-time AST collection: List is the quotes idiom
    /** Collect the individual types from a type-level tuple (HList of types). */
    private def collectTypes[T: Type](using Quotes): List[Type[?]] =
        import quotes.reflect.*
        Type.of[T] match
            case '[scala.EmptyTuple] => Nil
            case '[h *: t]           => Type.of[h] :: collectTypes[t]
            case _ =>
                report.errorAndAbort(s"GenDerive.collectTypes: unexpected type shape ${Type.show[T]}")
        end match
    end collectTypes

end GenDerive
