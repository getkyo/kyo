package kyo.internal

import kyo.Frame
import kyo.Tag
import scala.concurrent.Future
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type

abstract class RegisterFunction:
    def apply(name: String, test: => Unit, pending: Boolean): Unit

object TagTestMacro:
    case class Test(name: String, body: () => Unit)

    inline def test[T1, T2](using k1: Tag[T1], k2: Tag[T2], register: RegisterFunction, frame: Frame): Unit =
        test[T1, T2]()

    inline def test[T1, T2](
        inline pending: Boolean = false
    )(using k1: Tag[T1], k2: Tag[T2], register: RegisterFunction, frame: Frame): Unit =
        ${ testImpl[T1, T2]('k1, 'k2, 'register, '{ pending }, '{ frame }) }

    private def testImpl[T1: Type, T2: Type](
        k1: Expr[Tag[T1]],
        k2: Expr[Tag[T2]],
        register: Expr[RegisterFunction],
        pendingExpr: Expr[Boolean],
        frame: Expr[Frame]
    )(using q: Quotes): Expr[Unit] =
        import q.reflect.*
        val t1                = TypeRepr.of[T1]
        val t2                = TypeRepr.of[T2]
        val compilerSubtype   = t1 <:< t2
        val compilerSupertype = t2 <:< t1
        val compilerEquals    = t1 =:= t2

        '{
            def show[A](a: Tag[A]) = a.show.replace("kyo.TagTest.", "").replace("_$", "")
            def failure[A, B](op: String, kyo: Boolean, compiler: Boolean)(a: Tag[A], b: Tag[B]): String =
                s"${show(a)} $op ${show(b)} returned Kyo: $kyo, Compiler: $compiler\n"

            val subtypeTest = Test(
                s"${show($k1)} <:< ${show($k2)}",
                () =>
                    val kresult = $k1 <:< $k2
                    assert(
                        kresult == ${ Expr(compilerSubtype) },
                        failure("<:<", kresult, ${ Expr(compilerSubtype) })($k1, $k2)
                    )
            )

            val supertypeTest = Test(
                s"${show($k1)} >:> ${show($k2)}",
                () =>
                    val kresult = $k1 >:> $k2
                    assert(
                        kresult == ${ Expr(compilerSupertype) },
                        failure(">:>", kresult, ${ Expr(compilerSupertype) })($k1, $k2)
                    )
            )

            val equalityTest = Test(
                s"${show($k1)} =:= ${show($k2)}",
                () =>
                    val kresult = $k1 =:= $k2
                    assert(
                        kresult == ${ Expr(compilerEquals) },
                        failure("=:=", kresult, ${ Expr(compilerEquals) })($k2, $k1)
                    )
            )

            val inequalityTest = Test(
                s"${show($k1)} =!= ${show($k2)}",
                () =>
                    val kresult = $k1 =!= $k2
                    assert(
                        kresult != ${ Expr(compilerEquals) },
                        failure("=!=", kresult, !${ Expr(compilerEquals) })($k2, $k1)
                    )
            )

            ${ register }(subtypeTest.name, subtypeTest.body(), $pendingExpr)
            ${ register }(supertypeTest.name, supertypeTest.body(), $pendingExpr)
            ${ register }(equalityTest.name, equalityTest.body(), $pendingExpr)
            ${ register }(inequalityTest.name, inequalityTest.body(), $pendingExpr)
        }
    end testImpl
end TagTestMacro
