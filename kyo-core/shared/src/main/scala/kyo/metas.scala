package kyo

import scala.compiletime
import scala.quoted.*

class metas:

    trait MetaEffect[M[_]]:
        inline def suspend[T](inline v: M[T]): T
        inline def handle[T](inline v: T): M[T]

    sealed trait MetaHandler[M[_]]

    object MetaHandler:
        // query[Person].map(_.name)
        trait Interpreter[M[_]]:
            inline def apply[T](using Quotes)(expr: Expr[T]): Expr[M[T]]

        trait ControlFlow[M[_]]:

            inline def notSupported = compiletime.error("Construct not supported")

            // used for the transformation
            inline def pure[T](inline v: T): M[T]

            // encodes the "semicolon" (typically called flatMap)
            inline def map[T, U](inline f: T => M[U]): M[U]

            // try/catch
            inline def rescue[T](inline v: M[T], inline pf: PartialFunction[Throwable, M[T]]): M[T] = 
                notSupported

            // lazy val
            inline def memo[T](inline v: M[T]): M[T] =
                notSupported

            // finally
            inline def ensure[T](inline v: M[T]): M[T] =
                notSupported
        end ControlFlow
    end MetaHandler
end metas
