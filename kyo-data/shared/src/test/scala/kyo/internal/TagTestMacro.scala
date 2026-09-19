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

    /** Every `Tag` operation below that disagreed with the compiler when run by the macro host.
      *
      * The calls execute during expansion, on the JVM running the compiler, which is the only place a JS or Wasm
      * artifact's class files can be observed running on a JVM. Nothing at runtime reaches that, so the result is
      * lifted into the expansion for a suite to assert on.
      */
    inline def portabilityFailures(): List[String] = ${ portabilityFailuresImpl }

    private def portabilityFailuresImpl(using q: Quotes): Expr[List[String]] =
        import q.reflect.*

        val failures = List.newBuilder[String]

        def check(name: String, actual: Boolean, expected: Boolean): Unit =
            if actual != expected then
                failures += s"Tag macro-host $name: expected $expected, obtained $actual"

        def compare[A: Type, B: Type](left: Tag[A], right: Tag[B]): Unit =
            val a     = TypeRepr.of[A]
            val b     = TypeRepr.of[B]
            val label = s"${a.show}, ${b.show}"
            check(s"equality ($label)", left =:= right, a =:= b)
            check(s"inequality ($label)", left =!= right, !(a =:= b))
            check(s"subtype ($label)", left <:< right, a <:< b)
            check(s"supertype ($label)", left >:> right, b <:< a)
        end compare

        def list[A: Tag]: Tag[List[A]]                   = Tag.dynamic[List[A]]
        def nested[A: Tag, B: Tag]: Tag[Map[A, List[B]]] = Tag.dynamic[Map[A, List[B]]]

        // These are ordinary API calls executed by the macro host, outside the returned quote.
        compare(Tag[Int], Tag[String])
        compare(Tag[Int], Tag[Int])
        compare(Tag[true], Tag[false])
        compare(Tag[1], Tag[Int])
        compare(Tag[Int], Tag[1])
        compare(list[Int], Tag[List[Int]])
        compare(list[Int], list[Int])
        compare(list[Int], list[String])
        compare(list[Int], Tag[Seq[Int]])
        compare(list[Int], Tag[Seq[String]])
        compare(nested[String, Int], Tag[Map[String, List[Int]]])
        compare(nested[String, Int], nested[Int, String])
        compare(Tag[Int | String], Tag[String | Int])
        check("Int public hash", Tag[Int].hash == -1492440803, true)
        check("String public hash", Tag[String].hash == -59591402, true)
        check("Int show", Tag[Int].show == "scala.Int", true)
        check("String show", Tag[String].show == "java.lang.String", true)
        check("dynamic show", list[Int].show == Tag[List[Int]].show, true)
        check("dynamic hash repeat", nested[String, Int].hash == nested[String, Int].hash, true)
        check("dynamic hash argument identity", nested[String, Int].hash != nested[Int, String].hash, true)
        Expr(failures.result())
    end portabilityFailuresImpl

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
                        failure("=!=", kresult, ! ${ Expr(compilerEquals) })($k2, $k1)
                    )
            )

            ${ register }(subtypeTest.name, subtypeTest.body(), $pendingExpr)
            ${ register }(supertypeTest.name, supertypeTest.body(), $pendingExpr)
            ${ register }(equalityTest.name, equalityTest.body(), $pendingExpr)
            ${ register }(inequalityTest.name, inequalityTest.body(), $pendingExpr)
        }
    end testImpl
end TagTestMacro
