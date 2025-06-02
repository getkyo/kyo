package kyo.grpc.compiler.internal

import org.scalatest.freespec.AnyFreeSpec
import org.typelevel.paiges.Doc
import scalapb.compiler.FunctionalPrinter

class ScalaFunctionalPrinterOpsTest extends AnyFreeSpec {

    "ScalaFunctionalPrinterOps" - {
        "addDoc" - {
            "should add multiline docs correctly" in {
                val fp     = new FunctionalPrinter()
                val doc    = Doc.text("a") + Doc.hardLine + Doc.text("b")
                val actual = fp.addDoc(doc).result()
                val expected =
                    """a
                      |b""".stripMargin
                assert(actual == expected)
            }
        }
        "addMethod" - {
            "should add an abstract method without mods" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").result()
                val expected =
                    """def foo""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with a mod" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addMods(_.Override).result()
                val expected =
                    """override def foo""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple mods" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addMods(_.Private, _.Override).result()
                val expected =
                    """private override def foo""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with a type parameter" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addTypeParameters("A").result()
                val expected =
                    """def foo[A]""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple type parameters" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addTypeParameters("A", "B").result()
                val expected =
                    """def foo[A, B]""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple long type parameters" in {
                val fp     = new FunctionalPrinter()
                val as     = "A" * (WIDTH / 2)
                val bs     = "B" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addTypeParameters(as, bs).result()
                val expected =
                    s"""def foo[$as, $bs]""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with a parameter" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addParameterList("a" :- "A").result()
                val expected =
                    """def foo(a: A)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameters on one line" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addParameterList("a" :- "A", "b" :- "B").result()
                val expected =
                    """def foo(a: A, b: B)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameters on multiple lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A", bs :- "B").result()
                val expected =
                    s"""def foo(
                       |  $as: A,
                       |  $bs: B
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameter lists on one line" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addParameterList("a" :- "A", "b" :- "B").addParameterList("c" :- "C").result()
                val expected =
                    """def foo(a: A, b: B)(c: C)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameter lists on mixed lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A").addParameterList(bs :- "B", cs :- "C").result()
                val expected =
                    s"""def foo(
                       |  $as: A
                       |)(
                       |  $bs: B,
                       |  $cs: C
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameter lists on mixed lines 2" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A", bs :- "B").addParameterList(cs :- "C").result()
                val expected =
                    s"""def foo(
                       |  $as: A,
                       |  $bs: B
                       |)(
                       |  $cs: C
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple parameter lists on multiple lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val ds     = "d" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A", bs :- "B").addParameterList(cs :- "C", ds :- "D").result()
                val expected =
                    s"""def foo(
                       |  $as: A,
                       |  $bs: B
                       |)(
                       |  $cs: C,
                       |  $ds: D
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with an implicit parameter" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addImplicitParameters("a" :- "A").result()
                val expected =
                    """def foo(implicit a: A)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple implicit parameters on one line" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addImplicitParameters("a" :- "A", "b" :- "B").result()
                val expected =
                    """def foo(implicit a: A, b: B)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with multiple implicit parameters on multiple lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addImplicitParameters(as :- "A", bs :- "B").result()
                val expected =
                    s"""def foo(implicit
                       |  $as: A,
                       |  $bs: B
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with normal parameters and implicit parameters on one line" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addParameterList("a" :- "A", "b" :- "B").addImplicitParameters("c" :- "C").result()
                val expected =
                    """def foo(a: A, b: B)(implicit c: C)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with normal parameters and implicit parameters on mixed lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A").addImplicitParameters(bs :- "B", cs :- "C").result()
                val expected =
                    s"""def foo(
                       |  $as: A
                       |)(implicit
                       |  $bs: B,
                       |  $cs: C
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with normal parameters and implicit parameters on mixed lines 2" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A", bs :- "B").addImplicitParameters(cs :- "C").result()
                val expected =
                    s"""def foo(
                       |  $as: A,
                       |  $bs: B
                       |)(implicit
                       |  $cs: C
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with normal parameters and implicit parameters on multiple lines" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH / 2)
                val bs     = "b" * (WIDTH / 2)
                val cs     = "c" * (WIDTH / 2)
                val ds     = "d" * (WIDTH / 2)
                val actual = fp.addMethod("foo").addParameterList(as :- "A", bs :- "B").addImplicitParameters(cs :- "C", ds :- "D").result()
                val expected =
                    s"""def foo(
                       |  $as: A,
                       |  $bs: B
                       |)(implicit
                       |  $cs: C,
                       |  $ds: D
                       |)""".stripMargin
                assert(actual == expected)
            }
            "should add an abstract method with a return type" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addReturnType("Foo").result()
                val expected =
                    """def foo: Foo""".stripMargin
                assert(actual == expected)
            }
            "should add a method with a simple body" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addMethod("foo").addBody(_.add("Foo")).result()
                val expected =
                    """def foo = Foo""".stripMargin
                assert(actual == expected)
            }
            "should add a method with a long body" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH + 1)
                val actual = fp.addMethod("foo").addBody(_.add(as)).result()
                val expected =
                    s"""def foo =
                       |  $as""".stripMargin
                assert(actual == expected)
            }
            "should add a method with a multiline signature an a long body" in {
                val fp = new FunctionalPrinter()

                val as     = "a" * (WIDTH + 1)
                val bs     = "b" * (WIDTH + 1)
                val actual = fp.addMethod("foo").addParameterList(as :- "A").addBody(_.add(bs)).result()
                val expected =
                    s"""def foo(
                       |  $as: A
                       |) =
                       |  $bs""".stripMargin
                assert(actual == expected)
            }
            "should add a method with a multiline body" in {
                val fp = new FunctionalPrinter()
                val body =
                    """a
                      |b""".stripMargin
                val actual = fp.addMethod("foo").addBody(_.add(body)).result()
                val expected =
                    """def foo = {
                      |  a
                      |  b
                      |}""".stripMargin
                assert(actual == expected)
            }
        }
        "addObject" - {
            "should add an object without mods" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").result()
                val expected =
                    """object Foo""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a mod" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").addMods(_.Case).result()
                val expected =
                    """case object Foo""".stripMargin
                assert(actual == expected)
            }
            "should add an object with multiple mods" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").addMods(_.Private, _.Case).result()
                val expected =
                    """private case object Foo""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a parent" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").addParents("A").result()
                val expected =
                    """object Foo extends A""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a long parent" in {
                val fp     = new FunctionalPrinter()
                val as     = "A" * WIDTH
                val actual = fp.addObject("Foo").addParents(as).result()
                val expected =
                    s"""object Foo
                       |    extends $as""".stripMargin
                assert(actual == expected)
            }
            "should add an object with multiple parents" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").addParents("A", "B").result()
                val expected =
                    """object Foo extends A with B""".stripMargin
                assert(actual == expected)
            }
            "should add an object with multiple long parents" in {
                val fp     = new FunctionalPrinter()
                val as     = "A" * (WIDTH / 2)
                val bs     = "B" * (WIDTH / 2)
                val actual = fp.addObject("Foo").addParents(as, bs).result()
                val expected =
                    s"""object Foo
                       |    extends $as
                       |    with $bs""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a simple body" in {
                val fp     = new FunctionalPrinter()
                val actual = fp.addObject("Foo").addBody(_.add("Foo")).result()
                val expected =
                    """object Foo {
                      |  Foo
                      |}""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a long body" in {
                val fp     = new FunctionalPrinter()
                val as     = "a" * (WIDTH + 1)
                val actual = fp.addObject("Foo").addBody(_.add(as)).result()
                val expected =
                    s"""object Foo {
                       |  $as
                       |}""".stripMargin
                assert(actual == expected)
            }
            "should add an object with a multiline body" in {
                val fp = new FunctionalPrinter()
                val body =
                    """a
                      |b""".stripMargin
                val actual = fp.addObject("Foo").addBody(_.add(body)).result()
                val expected =
                    """object Foo {
                       |  a
                       |  b
                       |}""".stripMargin
                assert(actual == expected)
            }
        }
    }
}
