package kyo

import kyo.Ansi.*
import scala.quoted.*
import scala.util.control.NonFatal

private[kyo] object Validate:
    def apply(expr: Expr[Any])(using Quotes): Unit =
        import quotes.reflect.*

        val tree = expr.asTerm

        def fail(tree: Tree, msg: String): Unit =
            report.error(msg, tree.pos)

        def pure(tree: Tree): Boolean =
            !Trees.exists(tree) {
                case Apply(TypeApply(Ident("now"), _), _) => true
                case _                                    => false
            }

        Trees.traverse(tree) {
            case Apply(TypeApply(Ident("now"), _), _)   =>
            case Apply(TypeApply(Ident("later"), _), _) =>
            case tree: Term if tree.tpe.typeSymbol.name == "<" =>
                fail(
                    tree,
                    s"""Effectful computations must explicitly use either ${".now".cyan} or ${".later".cyan} in a ${"defer".yellow} block.
                       |
                       |You have two options:
                       |
                       |${bold("1. Use .now when you need the effect's result immediately:")}
                       |${highlight("""
                       |defer {
                       |  val x: Int = IO(1).now      // Get result here
                       |  val y: Int = x + IO(2).now  // Use result in next computation
                       |  y * 2                       // Use final result
                       |}""".stripMargin)}
                       |
                       |${bold("2. Use .later (advanced) when you want to preserve the effect:")}
                       |${highlight("""
                       |defer {
                       |  val x: Int < IO = IO(1).later    // Keep effect for later
                       |  val y: Int < IO = IO(2).later    // Keep another effect
                       |  x.now + y.now                    // Sequence effects
                       |}""".stripMargin)}
                       |""".stripMargin
                )

            case tree @ Apply(TypeApply(Ident("defer"), _), _) =>
                fail(
                    tree,
                    s"""Nested ${"`defer`".yellow} blocks are not allowed.
                       |
                       |Instead of nesting defer blocks:
                       |${highlight("""
                       |defer {
                       |  defer {        // NOT OK - nested defer
                       |    IO(1).now
                       |  }
                       |}""".stripMargin)}
                       |
                       |Define separate operations:
                       |${highlight("""
                       |def innerOperation = defer {
                       |  IO(1).now
                       |}
                       |
                       |defer {
                       |  innerOperation.now  // OK - composing effects
                       |}""".stripMargin)}""".stripMargin
                )

            case tree @ ValDef(_, _, _) if tree.show.startsWith("var ") =>
                fail(
                    tree,
                    s"""${"`var`".yellow} declarations are not allowed inside a ${"`defer`".yellow} block.
                       |
                       |Mutable state can lead to unexpected behavior with effects. Instead, use proper state management tools:
                       |
                       |• Var (kyo-prelude)
                       |• Atomic* classes (kyo-core)
                       |• TRef and derivatives (kyo-stm)""".stripMargin
                )

            case Return(_, _) =>
                fail(
                    tree,
                    s"""${"`return`".yellow} statements are not allowed inside a ${"`defer`".yellow} block.
                       |
                       |Early returns can break effect sequencing. Instead:
                       |${highlight("""
                       |// Instead of return:
                       |defer {
                       |  if condition then
                       |    return value    // NOT OK - early return
                       |  IO(1).now
                       |}
                       |
                       |// Use if expressions:
                       |defer {
                       |  if condition then value
                       |  else IO(1).now    // OK - explicit flow
                       |}""".stripMargin)}""".stripMargin
                )

            case tree @ ValDef(_, _, _) if tree.show.startsWith("lazy val ") =>
                fail(
                    tree,
                    s"""${"`lazy val`".yellow} declarations are not allowed inside a ${"`defer`".yellow} block.
                       |
                       |Lazy evaluation can interfere with effect sequencing. Instead:
                       |${highlight("""
                       |// Instead of lazy val:
                       |defer {
                       |  lazy val x = IO(1).now  // NOT OK - lazy
                       |  x + 1
                       |}
                       |
                       |// Use regular val:
                       |defer {
                       |  val x = IO(1).now       // OK - eager
                       |  x + 1
                       |}""".stripMargin)}""".stripMargin
                )

            case Lambda(_, body) if !pure(body) =>
                fail(
                    tree,
                    s"""Lambda functions containing ${".now".cyan} are not supported.
                       |
                       |Effects in lambdas can lead to unexpected ordering:
                       |${highlight("""
                       |defer {
                       |  val f = (x: Int) => IO(x).now  // NOT OK - effects in lambda
                       |  f(10)
                       |}""".stripMargin)}""".stripMargin
                )

            case DefDef(_, _, _, Some(body)) if !pure(body) =>
                fail(
                    tree,
                    s"""Method definitions containing ${".now".cyan} are not supported inside ${"`defer`".yellow} blocks.
                       |
                       |Define methods outside defer blocks:
                       |${highlight("""
                       |// Instead of method in defer:
                       |defer {
                       |  def process(x: Int) = IO(x).now  // NOT OK
                       |  process(10)
                       |}
                       |
                       |// Define outside:
                       |def process(x: Int): Int < IO = defer {
                       |  IO(x).now
                       |}
                       |
                       |defer {
                       |  process(10).now  // OK
                       |}""".stripMargin)}""".stripMargin
                )

            case Try(_, _, _) =>
                fail(
                    tree,
                    s"""${"`try`".yellow}/`catch`".yellow} blocks are not supported inside ${"`defer`".yellow} blocks.
                       |
                       |Use error handling effects instead. Handle each effect in a separate defer block:
                       |${highlight("""
                       |// Instead of try/catch:
                       |defer {
                       |  try {
                       |    IO(1).now    // NOT OK
                       |  } catch {
                       |    case e => handleError(e)
                       |  }
                       |}
                       |
                       |// Define the effectful computation:
                       |def computation = defer {
                       |  IO(1).now
                       |}
                       |
                       |// Handle the effect in a separate defer block:
                       |defer {
                       |  Abort.run(computation).now match {
                       |    case Result.Success(v) => v
                       |    case Result.Fail(e) => handleError(e)
                       |    case Result.Panic(e) => handleUnexpected(e)
                       |  }
                       |}""".stripMargin)}
                       |
                       |Separating effect definition from effect handling allows for better composition
                       |and clearer error handling boundaries.""".stripMargin
                )

            case ClassDef(_, _, _, _, _) =>
                fail(
                    tree,
                    s"""${"`class`".yellow} declarations are not supported inside ${"`defer`".yellow} blocks.
                       |
                       |Define classes outside defer blocks:
                       |${highlight("""
                       |// Instead of class in defer:
                       |defer {
                       |  class MyClass(x: Int)  // NOT OK
                       |  new MyClass(10)
                       |}
                       |
                       |// Define outside:
                       |class MyClass(x: Int)
                       |
                       |defer {
                       |  new MyClass(10)  // OK
                       |}""".stripMargin)}""".stripMargin
                )
        }
    end apply
end Validate
