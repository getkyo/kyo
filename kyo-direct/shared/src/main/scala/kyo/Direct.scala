package kyo

import cps.CpsMonad
import cps.CpsMonadContext
import cps.async
import cps.runtime.SeqAsyncShift
import directInternal.KyoCpsMonad
import kyo.Ansi.*
import kyo.Kyo.toIndexed
import kyo.kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.annotation.tailrec
import scala.collection.SeqOps
import scala.collection.immutable.LinearSeq
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
  * The `defer` block is desugared into regular monadic composition using Kyo's monadic bind (`map`).
  *
  * @tparam A
  *   The type of the value returned by the deferred block
  * @param f
  *   The block of code to be deferred
  * @return
  *   A value of type `A < S`, where `S` represents the combined effects of all operations
  */
transparent inline def defer[A](inline f: A) = ${ impl[A]('f) }

extension [A, S](inline self: A < S)

    /** Sequences an effect immediately, making its result available for use.
      *
      * Must be used within a `defer` block. This operation tells the direct syntax to execute the effect at this point in the computation,
      * allowing you to use its result in subsequent operations.
      *
      * @return
      *   The unwrapped value of type `A` from the effect
      * @throws RuntimeException
      *   if used outside a `defer` block
      */
    inline def now: A = ${ nowImpl('self) }

    /** Preserves an effect without immediate sequencing (advanced API).
      *
      * Must be used within a `defer` block. This advanced operation preserves the effect in its wrapped form without sequencing it,
      * providing more control over effect composition. Use this when building reusable effect combinations or when explicit control over
      * effect sequencing is needed.
      *
      * @return
      *   The preserved effect of type `A < S`
      * @throws RuntimeException
      *   if used outside a `defer` block
      */
    inline def later: A < S = ${ laterImpl('self) }
end extension

private def nowImpl[A: Type, S: Type](self: Expr[A < S])(using Quotes): Expr[A] =
    import quotes.reflect.*
    report.errorAndAbort(
        s"""${".now".cyan} must be used within a ${"`defer`".yellow} block.
           |
           |${".now".cyan} tells the system to sequence this effect at this point in the computation. Use it when you need 
           |the effect's result for your next computation:
           |
           |${highlight("""
           |defer {
           |  val x = IO(1).now     // Get result here
           |  val y = IO(2).now     // Then get this result  
           |  x + y                 // Use both results
           |}""".stripMargin)}
           |""".stripMargin,
        self.asTerm.pos
    )
end nowImpl

private def laterImpl[A: Type, S: Type](self: Expr[A < S])(using Quotes): Expr[A < S] =
    import quotes.reflect.*
    report.errorAndAbort(
        s"""${".later".cyan} must be used within a ${"`defer`".yellow} block.
           |
           |${".later".cyan} is an advanced operation that preserves an effect without sequencing it. This gives you more 
           |control but requires understanding effect composition. Use it when building reusable effect combinations 
           |or when you explicitly don't want to sequence an effect at this point.
           |
           |${highlight("""
           |// Example: Preserve effects for composition
           |def combination = defer {
           |  val effect1 = IO(1).later   // Effect preserved
           |  val effect2 = IO(2).later   // Effect preserved
           |  (effect1, effect2)          // Return tuple of effects
           |}
           |
           |defer {
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

                import directInternal.given
                given KyoCpsMonad[s] = KyoCpsMonad[s]

                async {
                    ${ transformedBody.asExprOf[A] }
                }.asInstanceOf[A < s]
            }
    end match
end impl

object directInternal:
    given Frame = Frame.internal
    class KyoCpsMonad[S]
        extends CpsMonadContext[[A] =>> A < S]
        with CpsMonad[[A] =>> A < S]
        with Serializable:

        type Context = KyoCpsMonad[S]

        override def monad: CpsMonad[[A] =>> A < S] = this

        override def apply[A](op: Context => A < S): A < S = op(this)

        override def pure[A](t: A): A < S = t

        override def map[A, B](fa: A < S)(f: A => B): B < S = flatMap(fa)(f)

        override def flatMap[A, B](fa: A < S)(f: A => B < S): B < S = fa.flatMap(f)
    end KyoCpsMonad

    class KyoSeqAsyncShift[A, C[X] >: Chunk[X] <: Seq[X] & SeqOps[X, C, C[X]], CA <: C[A]] extends SeqAsyncShift[A, C, CA]:

        override def shiftedFold[F[_], Acc, B, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: Acc, action: A => F[B], acc: (Acc, A, B) => Acc, epilog: Acc => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] =>
                    Kyo.foldLeft(c)(prolog)((state, a) =>
                        action(a).map(b => acc(state, a, b))
                    ).map(s => epilog(s))
                case _ => super.shiftedFold(c, monad)(prolog, action, acc, epilog)
        end shiftedFold

        override def shiftedStateFold[F[_], S, R](c: CA, monad: CpsMonad[F])(prolog: S, acc: (S, A) => F[S], epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foldLeft(c)(prolog)((s, a) => acc(s, a)).map(s => epilog(s))
                case _                 => super.shiftedStateFold(c, monad)(prolog, acc, epilog)

        override def shiftedWhile[F[_], S, R](
            c: CA,
            monad: CpsMonad[F]
        )(prolog: S, condition: A => F[Boolean], acc: (S, Boolean, A) => S, epilog: S => R): F[R] =
            monad match
                case _: KyoCpsMonad[?] => directInternal.shiftedWhile(c)(prolog, condition, acc, epilog)
                case _                 => super.shiftedWhile(c, monad)(prolog, condition, acc, epilog)

        override def dropWhile[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.dropWhile(c)(a => p(a))
                case _                 => super.dropWhile(c, monad)(p)

        override def filter[F[_]](c: CA, monad: CpsMonad[F])(p: A => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.filter(c)(a => p(a))
                case _                 => super.filter(c, monad)(p)

        override def takeWhile[F[_]](c: CA, monad: CpsMonad[F])(p: (A) => F[Boolean]): F[C[A]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.takeWhile(c)(a => p(a))
                case _                 => super.takeWhile(c, monad)(p)

        override def map[F[_], B](c: CA, monad: CpsMonad[F])(f: A => F[B]): F[C[B]] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foreach(c)(a => f(a))
                case _                 => super.map(c, monad)(f)

        override def foreach[F[_], U](c: CA, monad: CpsMonad[F])(f: A => F[U]): F[Unit] =
            monad match
                case _: KyoCpsMonad[?] => Kyo.foreachDiscard(c)(a => f(a))
                case _                 => super.foreach(c, monad)(f)
    end KyoSeqAsyncShift

    class SeqToChunkAsyncShift[A] extends KyoSeqAsyncShift[A, Seq, Seq[A]]
    class ChunkAsyncShift[A]      extends KyoSeqAsyncShift[A, Chunk, Chunk[A]]

    transparent inline given shiftedSeqToChunk[A]: SeqToChunkAsyncShift[A] = new SeqToChunkAsyncShift[A]
    transparent inline given shiftedChunk[A]: ChunkAsyncShift[A]           = new ChunkAsyncShift[A]

    private[kyo] def shiftedWhile[A, S, B, C](source: IterableOnce[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        source.knownSize match
            case 0 => epilog(prolog)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] => Loop(linearSeq, prolog): (seq, b) =>
                            if seq.isEmpty then Loop.done(epilog(b))
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(seq.tail, acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(prolog): (idx, b) =>
                            if idx == size then Loop.done(epilog(b))
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue(acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

        end match
    end shiftedWhile

end directInternal
