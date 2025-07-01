package kyo

import cps.async
import internal.*
import kyo.Ansi.*
import scala.annotation.tailrec
import scala.quoted.*

/** Defers the execution of a block of code, allowing the use of `.now` and `.later` for effect handling.
  *
  * This macro transforms the given block of code to work with effectful computations. Effects can be:
  *   - Sequenced immediately using `.now` when you need their results
  *   - Preserved using `.later` (advanced API) for more controlled effect composition
  *
  * The `.later` operation is an advanced feature that gives more control over effect sequencing, but requires a deeper understanding of
  * effect composition.
  *
  * The `direct` block is desugared into regular monadic composition using Kyo's monadic bind (`map`).
  *
  * @tparam A
  *   The type of the value returned by the deferred block
  * @param f
  *   The block of code to be deferred
  * @return
  *   A value of type `A < S`, where `S` represents the combined effects of all operations
  */
transparent inline def direct[A](inline f: A) = ${ impl[A]('f) }

extension [A, S](inline self: A < S)

    /** Sequences an effect immediately, making its result available for use.
      *
      * Must be used within a `direct` block. This operation tells the direct syntax to execute the effect at this point in the computation,
      * allowing you to use its result in subsequent operations.
      *
      * @return
      *   The unwrapped value of type `A` from the effect
      * @throws RuntimeException
      *   if used outside a `direct` block
      */
    inline def now: A = ${ nowImpl('self) }

    /** Preserves an effect without immediate sequencing (advanced API).
      *
      * Must be used within a `direct` block. This advanced operation preserves the effect in its wrapped form without sequencing it,
      * providing more control over effect composition. Use this when building reusable effect combinations or when explicit control over
      * effect sequencing is needed.
      *
      * @return
      *   The preserved effect of type `A < S`
      * @throws RuntimeException
      *   if used outside a `direct` block
      */
    inline def later: A < S = ${ laterImpl('self) }
end extension

private def nowImpl[A: Type, S: Type](self: Expr[A < S])(using Quotes): Expr[A] =
    import quotes.reflect.*
    report.errorAndAbort(
        s"""${".now".cyan} must be used within a ${"`direct`".yellow} block.
           |
           |${".now".cyan} tells the system to sequence this effect at this point in the computation. Use it when you need 
           |the effect's result for your next computation:
           |
           |${highlight("""
           |direct {
           |  val x = Sync(1).now     // Get result here
           |  val y = Sync(2).now     // Then get this result
           |  x + y                 // Use both results
           |}""".stripMargin)}
           |""".stripMargin,
        self.asTerm.pos
    )
end nowImpl

private def laterImpl[A: Type, S: Type](self: Expr[A < S])(using Quotes): Expr[A < S] =
    import quotes.reflect.*
    report.errorAndAbort(
        s"""${".later".cyan} must be used within a ${"`direct`".yellow} block.
           |
           |${".later".cyan} is an advanced operation that preserves an effect without sequencing it. This gives you more 
           |control but requires understanding effect composition. Use it when building reusable effect combinations 
           |or when you explicitly don't want to sequence an effect at this point.
           |
           |${highlight("""
           |// Example: Preserve effects for composition
           |def combination = direct {
           |  val effect1 = Sync(1).later   // Effect preserved
           |  val effect2 = Sync(2).later   // Effect preserved
           |  (effect1, effect2)          // Return tuple of effects
           |}
           |
           |direct {
           |  val (e1, e2) = combination.now  // Get both effects
           |  e1.now + e2.now                 // Sequence them here
           |}""".stripMargin)}
           |""".stripMargin,
        self.asTerm.pos
    )
end laterImpl

private def impl[A: Type](body: Expr[A])(using quotes: Quotes): Expr[Any] =
    import quotes.reflect.*

    Validate(body)

    var effects = List.empty[TypeRepr]

    Trees.traverse(body.asTerm) {
        case Apply(TypeApply(Ident("now"), List(_, effect)), List(qual)) =>
            effects ::= effect.tpe
    }

    def flatten(l: List[TypeRepr]): List[TypeRepr] =
        @tailrec def loop(l: List[TypeRepr], acc: List[TypeRepr]): List[TypeRepr] =
            l match
                case Nil =>
                    acc.distinct.sortBy(_.show)
                case AndType(a, b) :: Nil =>
                    loop(a :: b :: Nil, acc)
                case head :: tail =>
                    loop(tail, head :: acc)
        loop(l, Nil)
    end flatten

    val pending: TypeRepr =
        flatten(effects) match
            case Nil =>
                TypeRepr.of[Any]
            case effects =>
                effects.reduce((a, b) => AndType(a, b))
    end pending

    var genSym = 0
    def freshName(name: String): String =
        genSym = genSym + 1
        s"${name}_N$genSym"

    val preparedBody = Trees.transform(body.asTerm) {
        case Apply(
                Apply(
                    TypeApply(
                        Apply(
                            ta: TypeApply,
                            List(a @ Apply(TypeApply(Ident("now"), List(t, s)), List(_)))
                        ),
                        types0
                    ),
                    args0
                ),
                args1
            ) if t.tpe.typeSymbol.flags.is(Flags.Opaque) =>

            val newVal: Symbol = Symbol.newVal(
                Symbol.spliceOwner,
                freshName("opaqueAlias"),
                t.tpe,
                Flags.Private,
                Symbol.noSymbol
            )
            Block(
                List(ValDef(newVal, Some(a))),
                Apply(
                    Apply(TypeApply(Apply(ta, List(Ident(newVal.termRef))), types0), args0),
                    args1
                )
            )
    }

    pending.asType match
        case '[s] =>
            val transformedBody =
                Trees.transform(preparedBody) {
                    case Apply(TypeApply(Ident("now"), List(t, s2)), List(qual)) =>
                        (t.tpe.asType, s2.tpe.asType) match
                            case ('[t], '[s2]) => '{
                                    given KyoCpsMonad[s2] = KyoCpsMonad[s2]
                                    cps.await[[A] =>> A < s2, t, [A] =>> A < s2](${
                                        qual.asExprOf[t < s2]
                                    })
                                }.asTerm
                            case _ => bug("unreachable")
                    case Apply(TypeApply(Ident("later"), List(t, s2)), List(qual)) =>
                        qual
                }

            '{

                given KyoCpsMonad[s] = KyoCpsMonad[s]
                import kyo.internal.asyncShift.given

                async {
                    ${ transformedBody.asExprOf[A] }
                }.asInstanceOf[A < s]
            }
    end match
end impl
