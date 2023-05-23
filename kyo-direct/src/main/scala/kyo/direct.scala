package kyo

import scala.annotation.targetName
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
    compiletime.error("`await` must be used within a `defer` block")

  private def impl[T: Type](f: Expr[T])(using Quotes): Expr[Any] = {
    import quotes.reflect._
    import quotes.reflect.report._

    var effects = List.empty[Type[_]]

    Trees.traverse(f.asTerm) {
      case x =>
        if (x.show.startsWith("kyo.direct.await"))
          x match {
            case '{ kyo.direct.await[t, s]($v) } =>
              info("match " + x.show)
            case _ =>
              info("no match " + Printer.TreeCode.show(x.asTerm))
          }
    }

    Trees.traverse(f.asTerm) {
      case '{ kyo.direct.await[t, s]($v) } =>
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
            case '{ kyo.direct.await[t, s2]($v) } =>
              '{
                cps.await[[T] =>> T > s, t, [T] =>> T > s](${ v.asExprOf[t > s] })
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
