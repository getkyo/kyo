package kyo

import kyo.Ansi.*
import scala.annotation.tailrec
import scala.quoted.*

private[kyo] object Validate:
    def apply(expr: Expr[Any])(using Quotes): Unit =
        import quotes.reflect.*

        val s                   = expr.show
        val forceDebug: Boolean = false && s.contains("collect") && s.contains("xs")

        if forceDebug then
            println("-" * 10)
            println(s)
            println(expr.asTerm)
        end if

        def fail(tree: Tree, msg: String): Unit =
            report.error(msg, tree.pos)

        def pure(tree: Tree): Boolean =
            !Trees.exists(tree) {
                case Apply(TypeApply(Ident("now"), _), _) => true
            }
        end pure

        def validAsyncShift(select: Select): Boolean =
            val Select(qualifier, methodName) = select
            inline def validType =
                qualifier.tpe <:< TypeRepr.of[Iterable[?]] |
                    qualifier.tpe <:< TypeRepr.of[Option[?]] |
                    qualifier.tpe <:< TypeRepr.of[scala.util.Try[?]]

            inline def validName =
                Set(
                    "collectFirst",
                    "collect",
                    "dropWhile",
                    "map",
                    "flatMap",
                    "filter",
                    "filterNot",
                    "flatten",
                    "groupBy",
                    "groupMap",
                    "fold",
                    "groupMapReduce",
                    "count",
                    "foldRight",
                    "foldLeft",
                    "forall",
                    "foreach",
                    "exists",
                    "corresponds",
                    "find",
                    "maxByOption",
                    "scanLeft",
                    "scanRight",
                    "span",
                    "takeWhile",
                    "partition",
                    "partitionMap",
                    "tapEach",
                    "withFilter",
                    "orElse",
                    "getOrElse",
                    "recover",
                    "recoverWith"
                ).contains(methodName)

            validType && validName
        end validAsyncShift

        extension (inline e: Expr[Any] | Tree)
            transparent inline def safeShow: String =
                inline e match
                    case expr: Expr[Any] => expr.asTerm.safeShow
                    case tree: Tree =>
                        if tree.symbol.exists && (tree.symbol.maybeOwner ne Symbol.noSymbol) then
                            tree.show
                        else
                            s"<unnamed:${tree.getClass.getSimpleName.stripSuffix("$")}>"
        end extension

        def asyncShiftDive(qualifiers: List[Tree])(using Trees.Step): Unit =
            qualifiers match
                case List(Block(List(DefDef(_, _, _, Some(body))), _)) =>
                    body match
                        case Match(_, cases) =>
                            cases.foreach:
                                case CaseDef(_, _, body) => Trees.Step.goto(body)
                        case _ => Trees.Step.goto(body)

                case _ => qualifiers.foreach(qual => Trees.Step.goto(qual))

        Trees.traverseGoto(expr.asTerm) {
            case Apply(Apply(TypeApply(select: Select, _), argGroup0), argGroup1) if validAsyncShift(select) =>
                Trees.Step.goto(select.qualifier)
                asyncShiftDive(argGroup0)
                asyncShiftDive(argGroup1)

            case Apply(select: Select, argGroup0) if validAsyncShift(select) =>
                Trees.Step.goto(select.qualifier)
                asyncShiftDive(argGroup0)

            case Apply(TypeApply(select: Select, _), argGroup0) if validAsyncShift(select) =>
                Trees.Step.goto(select.qualifier)
                asyncShiftDive(argGroup0)

            case Apply(TypeApply(Ident("now" | "later"), _), List(qual)) =>
                @tailrec
                def dive(qual: Tree): Unit =
                    qual match
                        case Block(quals, last) =>
                            quals.foreach(Trees.Step.goto)
                            dive(last)
                        case _ =>

                dive(qual)

                Trees.traverse(qual) {
                    case tree @ Apply(TypeApply(Ident("now" | "later"), _), _) =>
                        fail(
                            tree,
                            s"""${".now".cyan} and ${".later".cyan} can only be used directly inside a ${"`defer`".yellow} block.
                            |
                            |Common mistake: You may have forgotten to wrap an effectful computation in ${"`defer`".yellow}:
                            |${highlight("""
                            |// Missing defer when handling effects:
                            |val result = Emit.run {      // NOT OK - missing defer
                            |    Emit.value(1).now
                            |    Emit.value(2).now
                            |}
                            |
                            |// Correctly wrapped in defer:
                            |val result = Emit.run {
                            |    defer {                  // OK - effects wrapped in defer
                            |        Emit.value(1).now
                            |        Emit.value(2).now
                            |    }
                            |}""")}
                            |
                            |If you're seeing this inside a ${"`defer`".yellow} block, you may have nested ${".now".cyan}/${".later".cyan} calls:
                            |${highlight("""
                            |// Instead of nested .now:
                            |defer {
                            |    (counter.get.now + 1).now     // NOT OK - nested .now
                            |}
                            |
                            |// Store intermediate results:
                            |defer {
                            |    val value = counter.get.now    // OK - get value first
                            |    val incr = value + 1           // OK - pure operation
                            |    IO(incr).now                   // OK - single .now
                            |}""".stripMargin)}""".stripMargin
                        )
                }

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

            case tree @ ValDef(_, _, _) if tree.symbol.flags.is(Flags.Mutable) =>
                fail(
                    tree,
                    s"""${"`var`".yellow} declarations are not allowed inside a ${"`defer`".yellow} block.
                       |
                       |Mutable state can lead to unexpected behavior with effects. Instead, use proper state management tools:
                       |
                       |• Most common: Atomic* classes (kyo-core)
                       |• With transactional behavior: TRef and derivatives (kyo-stm)
                       |• Advanced pure state management: Var (kyo-prelude)
                       """.stripMargin
                )

            case tree @ ValDef(_, _, _) if tree.symbol.flags.is(Flags.Lazy) =>
                fail(
                    tree,
                    s"""${"`lazy val`".yellow} and ${"`object`".yellow} declarations are not allowed inside a ${"`defer`".yellow} block.
                    |
                    |These interfere with effect sequencing. Define them outside the defer block:
                    |${highlight("""
                    |// Instead of lazy declarations in defer:
                    |defer {
                    |  lazy val x = IO(1).now  // NOT OK - lazy val
                    |  object A               // NOT OK - object
                    |  x + 1
                    |}
                    |
                    |// Define outside defer:
                    |lazy val x = IO(1)       // OK - outside
                    |object A                 // OK - outside
                    |
                    |// Use inside defer:
                    |defer {
                    |  val result = x.now     // OK - proper sequencing
                    |  A.method.now
                    |}""".stripMargin)}
                    |
                    |For expensive computations needing caching, consider ${"`Async.memoize`".cyan}:
                    |${highlight("""
                    |defer {
                    |  val memoized = Async.memoize(expensiveComputation).now
                    |  memoized().now  // First computes, then caches
                    |}""".stripMargin)}""".stripMargin.stripMargin
                )

            case tree @ DefDef(_, _, _, Some(body)) if !pure(body) =>
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

            case tree @ Try(_, _, _) =>
                fail(
                    tree,
                    s"""${"`try`".yellow}/${"`catch`".yellow} blocks are not supported inside ${"`defer`".yellow} blocks.
                       |
                       |Use error handling effects instead. You can handle each effect in a separate defer block:
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
                       |// Handle the effect defer block:
                       |defer {
                       |  Abort.run(computation).now match {
                       |    case Result.Success(v) => v
                       |    case Result.Failure(e) => handleError(e)
                       |    case Result.Panic(e) => handleUnexpected(e)
                       |  }
                       |}""".stripMargin)}
                       |
                       |Separating effect definition from effect handling allows for better composition
                       |and clearer error handling boundaries.""".stripMargin
                )

            case tree @ ClassDef(_, _, _, _, _) =>
                fail(
                    tree,
                    s"""${"`class`".yellow} and ${"`trait`".yellow} declarations are not allowed inside ${"`defer`".yellow} blocks.
                        |
                        |Define them outside defer blocks:
                        |${highlight("""
                        |// Instead of declarations in defer:
                        |defer {
                        |  class MyClass(x: Int)    // NOT OK
                        |  trait MyTrait            // NOT OK
                        |  new MyClass(10)
                        |}
                        |
                        |// Define outside:
                        |class MyClass(x: Int)      // OK - outside defer
                        |trait MyTrait              // OK - outside defer
                        |
                        |defer {
                        |  new MyClass(10)          // OK - usage in defer
                        |}""".stripMargin)}""".stripMargin
                )

            case tree @ Apply(Ident("throw"), _) =>
                fail(
                    tree,
                    s"""${"`throw`".yellow} expressions are not allowed inside a ${"`defer`".yellow} block.
                    |
                    |Exception throwing can break effect sequencing. Use error handling effects instead:
                    |${highlight("""
                    |// Instead of throw:
                    |defer {
                    |  if condition then
                    |    throw new Exception("error")  // NOT OK - throws exception
                    |  IO(1).now
                    |}
                    |
                    |// Use Abort effect:
                    |defer {
                    |  if condition then
                    |    Abort.fail("error").now       // OK - proper error handling
                    |  else IO(1).now
                    |}""".stripMargin)}""".stripMargin
                )

            case tree @ Select(_, "synchronized") =>
                fail(
                    tree,
                    s"""${"`synchronized`".yellow} blocks are not allowed inside a ${"`defer`".yellow} block.
                       |
                       |Synchronization can lead to deadlocks with effects. Instead, use proper concurrency primitives:
                       |
                       |• Most common: Atomic* classes for thread-safe values (kyo-core)
                       |• With mutual exclusion: Meter for controlled access (kyo-core)
                       |• Advanced transactional state: TRef and STM (kyo-stm)
                       """.stripMargin
                )

            case tree @ Select(_, _) if tree.symbol.flags.is(Flags.Mutable) =>
                fail(
                    tree,
                    s"""Mutable field access is not allowed inside a ${"`defer`".yellow} block.
                    |
                    |Mutable state can lead to race conditions. Use proper state management instead:
                    |
                    |• Most common: Atomic* classes (kyo-core)
                    |• With transactional behavior: TRef and derivatives (kyo-stm)
                    |• Advanced pure state management: Var (kyo-prelude)""".stripMargin
                )
        }
    end apply
end Validate
