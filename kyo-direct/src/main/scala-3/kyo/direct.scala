package kyo

import kyo._
import language.experimental.macros
import scala.quoted.*

object direct {

  transparent inline def defer[T](inline body: T): Any = ${ transform('body) }

  private def transform(x: Expr[Any])(using Quotes): Expr[Any] = {
    import quotes.reflect.*
    x.asTerm match {
      case Inlined(_, Nil, tree) =>
        Translate(tree, Symbol.spliceOwner).asExpr
    }
  }

  def await[T, S](m: T > S): T = throw new Exception("Unlift must be used within a `await` body.")

  object internal {

    inline def branch[T, S1, S2, S3](
        inline cond: Boolean > S1,
        inline ifTrue: => T > S2,
        inline ifFalse: => T > S3
    ): T > (S1 with S2 with S3) =
      cond.map {
        case true  => ifTrue
        case false => ifFalse
      }

    inline def cont(v: Any > Nothing, f: Any => Any > Nothing): Any > Nothing =
      v.map(f)

    inline def loop[T, S1, S2](
        inline cond: => Boolean > S1,
        inline v: => T > S1
    ): Unit > (S1 with S2) =
      cond.map {
        case true  => v.map(_ => loop(cond, v.unit))
        case false => ()
      }
  }

}
