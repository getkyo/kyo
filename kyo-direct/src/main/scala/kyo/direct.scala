package kyo

import cps.CpsMonad
import cps.CpsMonadContext
import cps.async
import cps.await
import directInternal.*
import scala.annotation.tailrec
import scala.quoted.*

transparent inline def defer[T](inline f: T) = ${ impl[T]('f) }

inline def await[T, S](v: T < S): T =
    compiletime.error("`await` must be used within a `defer` block")

private def impl[T: Type](body: Expr[T])(using Quotes): Expr[Any] =
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
                                    cps.await[[T] =>> T < s2, t, [T] =>> T < s2](${
                                        v.asExprOf[t < s2]
                                    })
                                }.asTerm
                }

            '{
                given KyoCpsMonad[s] = KyoCpsMonad[s]
                async {
                    ${ transformedBody.asExprOf[T] }
                }.asInstanceOf[T < s]
            }
    end match
end impl

object directInternal:
    class KyoCpsMonad[S]
        extends CpsMonadContext[[T] =>> T < S]
        with CpsMonad[[T] =>> T < S]:

        type Context = KyoCpsMonad[S]

        override def monad: CpsMonad[[T] =>> T < S] = this

        override def apply[T](op: Context => T < S): T < S = op(this)

        override def pure[T](t: T): T < S = t

        override def map[A, B](fa: A < S)(f: A => B): B < S = flatMap(fa)(f)

        override def flatMap[A, B](fa: A < S)(f: A => B < S): B < S = fa.flatMap(f)
    end KyoCpsMonad
end directInternal
