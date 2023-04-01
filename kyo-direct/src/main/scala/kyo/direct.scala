package kyo

import core._
import scala.quoted._
import cps.await
import cps.async
import cps.CpsMonadContext
import cps.CpsAwaitable
import cps.CpsMonadInstanceContext
import cps.CpsMonadNoAdoptContext

object direct {

  private inline given kyoCpsMonad[S]: KyoCpsMonad[S] = KyoCpsMonad[S]

  transparent inline def defer[T](inline f: T) = ${ impl[T]('f) }
  inline def await[T, S](v: T > S): T =
    compiletime.error("`run` must be used within a `defer` block")

  private def impl[T: Type](f: Expr[T])(using Quotes): Expr[Any] =
    import quotes.reflect._
    import quotes.reflect.report._

    var effects = List.empty[Type[_]]

    Trees.traverse(f.asTerm) {
      case expr if (expr.isExprOf[>[Any, Any]]) =>
        error("Kyo computations must used within a `run` block", expr)
      case '{ await[t, s]($v) } =>
        effects ::= Type.of[s]
    }

    val s =
      effects
        .distinct
        .flatMap {
          case '[s] =>
            TypeRepr.of[s] match {
              case OrType(a, b) =>
                List(a.asType, b.asType)
              case _ =>
                List(Type.of[s])
            }
        }.sortBy {
          case '[t] => TypeTree.of[t].show
        } match {
        case Nil => Type.of[Nothing]
        case l =>
          l.reduce {
            case ('[t1], '[t2]) =>
              Type.of[t1 | t2]
          }
      }

    s match {
      case '[s] =>
        val body =
          Trees.transform(f.asTerm) {
            case '{ await[t, s2]($v) } =>
              '{
                cps.await[[T] =>> T > s, t, [T] =>> T > s](${ v.asExprOf[t > s] })
              }.asTerm
          }

        '{
          given KyoCpsMonad[s] = kyoCpsMonad[s]
          async[[U] =>> U > s] {
            ${ body.asExprOf[T] }
          }: T > s
        }
    }

  private[kyo] class KyoCpsMonad[S]
      extends CpsMonadInstanceContext[[T] =>> T > S]
      with CpsAwaitable[[T] =>> T > S]
      with CpsMonadNoAdoptContext[[T] =>> T > S] {

    override inline def pure[T](t: T): T > S = t

    override inline def map[A, B](fa: A > S)(f: A => B): B > S = fa(f(_))

    override inline def flatMap[A, B](fa: A > S)(f: A => B > S): B > S = fa(f)

    override inline def adoptAwait[A](fa: A > S): A > S = fa
  }
}
