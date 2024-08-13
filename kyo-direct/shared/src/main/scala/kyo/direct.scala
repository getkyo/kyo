package kyo

import cps.CpsMonad
import cps.CpsMonadContext
import cps.async
import cps.await
import directInternal.*
import scala.annotation.tailrec
import scala.quoted.*

transparent inline def defer[A](inline f: A) = ${ impl[A]('f) }

inline def await[A, S](v: A < S): A =
    compiletime.error("`await` must be used within a `defer` block")

private def impl[A: Type](body: Expr[A])(using Quotes): Expr[Any] =
    import quotes.reflect.*

    Validate(body)

    var effects = List.empty[TypeRepr]

    Trees.traverse(body.asTerm) {
        case Apply(TypeApply(Ident("await"), List(t, s)), List(v)) =>
            effects ::= s.tpe
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

    val pending =
        flatten(effects) match
            case Nil =>
                TypeRepr.of[Any]
            case effects =>
                effects.reduce((a, b) => AndType(a, b))
    end pending

    pending.asType match
        case '[s] =>
            val transformedBody =
                Trees.transform(body.asTerm) {
                    case Apply(TypeApply(Ident("await"), List(t, s2)), List(v)) =>
                        (t.tpe.asType, s2.tpe.asType) match
                            case ('[t], '[s2]) =>
                                '{
                                    given KyoCpsMonad[s2] = KyoCpsMonad[s2]
                                    cps.await[[A] =>> A < s2, t, [A] =>> A < s2](${
                                        v.asExprOf[t < s2]
                                    })
                                }.asTerm
                }

            '{
                given KyoCpsMonad[s] = KyoCpsMonad[s]
                async {
                    ${ transformedBody.asExprOf[A] }
                }.asInstanceOf[A < s]
            }
    end match
end impl

object directInternal:
    class KyoCpsMonad[S]
        extends CpsMonadContext[[A] =>> A < S]
        with CpsMonad[[A] =>> A < S]:

        type Context = KyoCpsMonad[S]

        override def monad: CpsMonad[[A] =>> A < S] = this

        override def apply[A](op: Context => A < S): A < S = op(this)

        override def pure[A](t: A): A < S = t

        override def map[A, B](fa: A < S)(f: A => B): B < S = flatMap(fa)(f)

        override def flatMap[A, B](fa: A < S)(f: A => B < S): B < S = fa.flatMap(f)
    end KyoCpsMonad
end directInternal
