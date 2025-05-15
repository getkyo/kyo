package kyo.internal

import izumi.reflect.Tag as ITag
import kyo.Ansi.yellow
import kyo.Frame
import kyo.Tag
import org.scalatest.Assertions.assert
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type

abstract class RegisterFunction:
    def apply(name: String, test: => Assertion, pending: Boolean): Unit

object TagTestMacro:
    case class Test(name: String, body: () => Assertion)

    inline def test[T1, T2](using k1: Tag[T1], i1: ITag[T1], k2: Tag[T2], i2: ITag[T2], register: RegisterFunction, frame: Frame): Unit =
        test[T1, T2]()

    inline def test[T1, T2](
        inline pending: Boolean = false,
        inline skipIzumiWarning: Boolean = false
    )(using k1: Tag[T1], i1: ITag[T1], k2: Tag[T2], i2: ITag[T2], register: RegisterFunction, frame: Frame): Unit =
        ${ testImpl[T1, T2]('k1, 'i1, 'k2, 'i2, 'register, '{ pending }, '{ skipIzumiWarning }, '{ frame }) }

    private def testImpl[T1: Type, T2: Type](
        k1: Expr[Tag[T1]],
        i1: Expr[ITag[T1]],
        k2: Expr[Tag[T2]],
        i2: Expr[ITag[T2]],
        register: Expr[RegisterFunction],
        pendingExpr: Expr[Boolean],
        skipExpr: Expr[Boolean],
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
            def failure[A, B](op: String, kyo: Boolean, compiler: Boolean, izumi: Boolean)(a: Tag[A], b: Tag[B]): String =
                s"${show(a)} $op ${show(b)} returned Kyo: $kyo, Compiler: $compiler, Izumi: $izumi\n"

            def izumiMismatch(op: String, compiler: Boolean, izumi: Boolean)(using frame: Frame): String =
                s"""
                |              ------------------------------ WARNING ${frame.position.show} ------------------------------
                |Izumi's behavior for $op differs from compiler: Izumi: $izumi, Compiler: $compiler (types: ${$k1.show} $op ${$k2.show})
                |
                |If this is not a known limitation, report an issue: https://github.com/zio/izumi-reflect/issues/
                |""".stripMargin.yellow

            val subtypeTest = Test(
                s"${show($k1)} <:< ${show($k2)}",
                () =>
                    val kresult = $k1 <:< $k2
                    val iresult = $i1 <:< $i2
                    if ! $skipExpr && ${ Expr(compilerSubtype) } != iresult then
                        println(izumiMismatch("<:<", ${ Expr(compilerSubtype) }, iresult))
                    assert(
                        kresult == ${ Expr(compilerSubtype) },
                        failure("<:<", kresult, ${ Expr(compilerSubtype) }, iresult)($k1, $k2)
                    )
            )

            val supertypeTest = Test(
                s"${show($k1)} >:> ${show($k2)}",
                () =>
                    val kresult = $k1 >:> $k2
                    val iresult = $i2 <:< $i1
                    if ! $skipExpr && ${ Expr(compilerSupertype) } != iresult then
                        println(izumiMismatch(">:>", ${ Expr(compilerSupertype) }, iresult))
                    assert(
                        kresult == ${ Expr(compilerSupertype) },
                        failure(">:>", kresult, ${ Expr(compilerSupertype) }, iresult)($k1, $k2)
                    )
            )

            val equalityTest = Test(
                s"${show($k1)} =:= ${show($k2)}",
                () =>
                    val kresult = $k1 =:= $k2
                    val iresult = $i1 =:= $i2
                    if ! $skipExpr && ${ Expr(compilerEquals) } != iresult then
                        println(izumiMismatch("=:=", ${ Expr(compilerEquals) }, iresult))
                    assert(
                        kresult == ${ Expr(compilerEquals) },
                        failure("=:=", kresult, ${ Expr(compilerEquals) }, iresult)($k2, $k1)
                    )
            )

            val inequalityTest = Test(
                s"${show($k1)} =!= ${show($k2)}",
                () =>
                    val kresult = $k1 =!= $k2
                    val iresult = !($i1 =:= $i2)
                    if ! $skipExpr && !${ Expr(compilerEquals) } != iresult then
                        println(izumiMismatch("=!=", !${ Expr(compilerEquals) }, iresult))
                    assert(
                        kresult != ${ Expr(compilerEquals) },
                        failure("=!=", kresult, !${ Expr(compilerEquals) }, iresult)($k2, $k1)
                    )
            )

            ${ register }(subtypeTest.name, subtypeTest.body(), $pendingExpr)
            ${ register }(supertypeTest.name, supertypeTest.body(), $pendingExpr)
            ${ register }(equalityTest.name, equalityTest.body(), $pendingExpr)
            ${ register }(inequalityTest.name, inequalityTest.body(), $pendingExpr)
        }
    end testImpl
end TagTestMacro
