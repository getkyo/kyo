package kyo

import cps.CpsAwaitable
import cps.CpsMonadContext
import cps.CpsMonadInstanceContext
import cps.CpsMonadNoAdoptContext
import cps.async
import cps.await

import scala.annotation.targetName
import scala.quoted._

object direct {

  private inline given kyoCpsMonad[S]: KyoCpsMonad[S] = KyoCpsMonad[S]

  transparent inline def defer[T](inline f: T) = ${ impl[T]('f) }

  case class Awaitable[T, S](v: T > S)

  given [T, S]: Conversion[T > S, Awaitable[T, S]] with
    /*inline(3)*/
    def apply(v: T > S) = Awaitable(v)

  inline def await[T, S](v: Awaitable[T, S]): T =
    compiletime.error("`await` must be used within a `defer` block")

  private def impl[T: Type](f: Expr[T])(using Quotes): Expr[Any] = {
    import quotes.reflect._
    import quotes.reflect.report._

    var effects = List.empty[Type[_]]

    Trees.traverse(f.asTerm) {
      case '{ await[t, s]($v) } =>
        effects ::= Type.of[s]
      case expr if (expr.isExprOf[>[Any, Any]]) =>
        error("Kyo computations must used within an `await` block: " + expr.show, expr)
    }

    val s =
      effects
        .distinct
        .flatMap {
          case '[s] =>
            TypeRepr.of[s] match {
              case AndType(a, b) =>
                List(a.asType, b.asType)
              case _ =>
                List(Type.of[s])
            }
        }.sortBy {
          case '[t] => TypeTree.of[t].show
        } match {
        case Nil => Type.of[Any]
        case l =>
          l.reduce {
            case ('[t1], '[t2]) =>
              Type.of[t1 & t2]
          }
      }

    s match {
      case '[s] =>
        val body =
          Trees.transform(f.asTerm) {
            case '{ await[t, s2]($v) } =>
              '{
                cps.await[[T] =>> T > s, t, [T] =>> T > s]($v.asInstanceOf[Awaitable[t, s]].v)
              }.asTerm
          }

        '{
          given KyoCpsMonad[s] = KyoCpsMonad[s]
          async[[U] =>> U > s] {
            ${ body.asExprOf[T] }
          }: T > s
        }
    }
  }

  private[kyo] class KyoCpsMonad[S]
      extends CpsMonadInstanceContext[[T] =>> T > S]
      with CpsAwaitable[[T] =>> T > S]
      with CpsMonadNoAdoptContext[[T] =>> T > S] {

    override inline def pure[T](t: T): T > S = t

    override inline def map[A, B](fa: A > S)(f: A => B): B > S = fa.map(f(_))

    override inline def flatMap[A, B](fa: A > S)(f: A => B > S): B > S = fa.flatMap(f)

    override inline def adoptAwait[A](fa: A > S): A > S = fa
  }
}
