package kyo

import core._
import scala.quoted._
import java.awt.Taskbar.State
import cps.await
import cps.async
import cps.CpsMonadContext
import cps.CpsAwaitable
import cps.CpsMonadInstanceContext
import cps.CpsMonadNoAdoptContext

object direct {

  class CpsMonad[S] 
    extends CpsMonadInstanceContext[[T] =>> T > S] 
    with CpsAwaitable[[T] =>> T > S] 
    with CpsMonadNoAdoptContext[[T] =>> T > S] {

    override inline def pure[T](t: T): T > S = t

    override inline def map[A, B](fa: A > S)(f: A => B): B > S = fa(f(_))

    override inline def flatMap[A, B](fa: A > S)(f: A => B > S): B > S = fa(f)

    override def adoptAwait[A](fa: A > S): A > S = fa
  }

  given [S]: CpsMonad[S] = CpsMonad[S]

  transparent inline def select[T](inline f: T) = ${ macroImpl[T]('f) }

  inline def from[T, S](v: T > S): T = compiletime.error("must be used within a `select` block")

  def macroImpl[T: Type](f: Expr[T])(using Quotes): Expr[Any] =
    import quotes.reflect._
    import quotes.reflect.report._

    var effects = List.empty[Type[_]]

    Trees.traverse(f.asTerm) {
      case '{ from[t, s]($v) } =>
        effects ::= Type.of[s]
    }

    effects.reduce { (a, b) =>
      (a, b) match {
        case ('[a], '[b]) =>
          Type.of[a | b]
      }
    } match {
      case '[s] =>
        val body =
          Trees.transform(f.asTerm) {
            case '{ from[t, s2]($v) } =>
              '{
                await[[T] =>> T > s, t, [T] =>> T > s](${ v.asExprOf[t > s] })
              }.asTerm
          }
        
        ' {
          given CpsMonad[s] = CpsMonad[s]
          async[[T] =>> T > s] {
            ${ body.asExprOf[T] }
          }: T > s
        }
    }
}
