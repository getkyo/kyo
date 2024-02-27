package kyo

import cps.CpsMonad
import cps.CpsMonadContext
import cps.async
import cps.await
import directInternal.*
import scala.quoted.*

private inline given kyoCpsMonad[S]: KyoCpsMonad[S] = KyoCpsMonad[S]

transparent inline def defer[T](inline f: T) = ${ impl[T]('f) }

inline def await[T, S](v: T < S): T =
    compiletime.error("`await` must be used within a `defer` block")

private def impl[T: Type](f: Expr[T])(using Quotes): Expr[Any] =
    import quotes.reflect.*

    Validate(f)

    var effects = List.empty[Type[_]]

    Trees.traverse(f.asTerm) {
        case Apply(TypeApply(Ident("await"), List(t, s)), List(v)) =>
            effects ::= s.tpe.asType
    }

    val s =
        effects
            .distinct
            .flatMap {
                case '[s] =>
                    TypeRepr.of[s] match
                        case AndType(a, b) =>
                            List(a.asType, b.asType)
                        case _ =>
                            List(Type.of[s])
            }.sortBy {
                case '[t] => TypeTree.of[t].show
            } match
            case Nil => Type.of[Any]
            case l =>
                l.reduce {
                    case ('[t1], '[t2]) =>
                        Type.of[t1 & t2]
                }

    s match
        case '[s] =>
            val body =
                Trees.transform(f.asTerm) {
                    case Apply(TypeApply(Ident("await"), List(t, s2)), List(v)) =>
                        t.tpe.asType match
                            case '[t] =>
                                '{
                                    cps.await[[T] =>> T < s, t, [T] =>> T < s](${
                                        v.asExprOf[t < s]
                                    })
                                }.asTerm
                }

            '{
                given KyoCpsMonad[s] = KyoCpsMonad[s]
                async[[U] =>> U < s] {
                    ${ body.asExprOf[T] }
                }: T < s
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

        override inline def pure[T](t: T): T < S = t

        override inline def map[A, B](fa: A < S)(f: A => B): B < S = fa.map(f(_))

        override inline def flatMap[A, B](fa: A < S)(f: A => B < S): B < S = fa.flatMap(f)
    end KyoCpsMonad
end directInternal
