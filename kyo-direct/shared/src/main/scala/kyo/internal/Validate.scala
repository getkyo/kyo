package kyo.internal

import kyo.Ansi.*
import kyo.internal.Trees
import scala.annotation.tailrec
import scala.collection.IterableOps
import scala.quoted.*

private val validMethodNamesForAsyncShift = Set(
    "map",
    "flatMap",
    "flatten",
    "collect",
    "collectFirst",
    "find",
    "filter",
    "filterNot",
    "withFilter",
    "dropWhile",
    "takeWhile",
    "partition",
    "partitionMap",
    "span",
    "fold",
    "foldLeft",
    "foldRight",
    "groupBy",
    "groupMap",
    "groupMapReduce",
    "exists",
    "forall",
    "count",
    "maxByOption",
    "corresponds",
    "foreach",
    "tapEach",
    "orElse",
    "getOrElse",
    "recover",
    "recoverWith",
    "scanLeft",
    "scanRight"
)

private[kyo] object Validate:
    def apply(expr: Expr[Any])(using quotes: Quotes): Unit =
        import quotes.reflect.*

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
                    qualifier.tpe <:< TypeRepr.of[IterableOps[?, ?, ?]] |
                    qualifier.tpe <:< TypeRepr.of[Option[?]] |
                    qualifier.tpe <:< TypeRepr.of[scala.util.Try[?]] |
                    qualifier.tpe <:< TypeRepr.of[Either[?, ?]] |
                    qualifier.tpe <:< TypeRepr.of[Either.LeftProjection[?, ?]]

            inline def validName: Boolean = validMethodNamesForAsyncShift.contains(methodName)

            validType && validName
        end validAsyncShift

        def asyncShiftDive(qualifiers: List[Tree])(using Trees.Step): Unit =
            qualifiers match
                case Block(List(DefDef(_, _, _, Some(body))), _) :: xs =>
                    body match
                        case Match(_, cases) =>
                            cases.foreach:
                                case CaseDef(_, _, body) => Trees.Step.goto(body)
                        case _ => Trees.Step.goto(body)
                    end match
                    asyncShiftDive(xs)

                case _ => qualifiers.foreach(qual => Trees.Step.goto(qual))

        Trees.traverseGoto(expr.asTerm) {
            case Apply(
                    Apply(TypeApply(Apply(TypeApply(Select(Ident("Maybe" | "Result"), _), _), List(qualifier)), _), argGroup0),
                    argGroup1
                ) =>
                Trees.Step.goto(qualifier)
                asyncShiftDive(argGroup0)
                asyncShiftDive(argGroup1)

            case Apply(TypeApply(Apply(TypeApply(Select(Ident("Maybe" | "Result"), _), _), List(qualifier)), _), argGroup0) =>
                Trees.Step.goto(qualifier)
                asyncShiftDive(argGroup0)

            case Apply(Apply(TypeApply(Select(Ident("Maybe" | "Result"), _), _), List(qualifier)), argGroup0) =>
                Trees.Step.goto(qualifier)
                asyncShiftDive(argGroup0)

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
                            s"""${".now".cyan} and ${".later".cyan} can only be used directly inside a ${"`direct`".yellow} block.
                            |
                            |Common mistake: You may have forgotten to wrap an effectful computation in ${"`direct`".yellow}:
                            |${highlight("""
                            |// Missing direct when handling effects:
                            |val result = Emit.run {      // NOT OK - missing direct
                            |    Emit.value(1).now
                            |    Emit.value(2).now
                            |}
                            |
                            |// Correctly wrapped in direct:
                            |val result = Emit.run {
                            |    direct {                  // OK - effects wrapped in direct
                            |        Emit.value(1).now
                            |        Emit.value(2).now
                            |    }
                            |}""")}
                            |
                            |If you're seeing this inside a ${"`direct`".yellow} block, you may have nested ${".now".cyan}/${".later".cyan} calls:
                            |${highlight("""
                            |// Instead of nested .now:
                            |direct {
                            |    (counter.get.now + 1).now     // NOT OK - nested .now
                            |}
                            |
                            |// Store intermediate results:
                            |direct {
                            |    val value = counter.get.now    // OK - get value first
                            |    val incr = value + 1           // OK - pure operation
                            |    Sync(incr).now                   // OK - single .now
                            |}""".stripMargin)}""".stripMargin
                        )
                }

            case tree: Term if tree.tpe.typeSymbol.name == "<" =>
                fail(
                    tree,
                    s"""Effectful computations must explicitly use either ${".now".cyan} or ${".later".cyan} in a ${"direct".yellow} block.
                       |
                       |You have two options:
                       |
                       |${bold("1. Use .now when you need the effect's result immediately:")}
                       |${highlight("""
                       |direct {
                       |  val x: Int = Sync(1).now      // Get result here
                       |  val y: Int = x + Sync(2).now  // Use result in next computation
                       |  y * 2                       // Use final result
                       |}""".stripMargin)}
                       |
                       |${bold("2. Use .later (advanced) when you want to preserve the effect:")}
                       |${highlight("""
                       |direct {
                       |  val x: Int < Sync = Sync(1).later    // Keep effect for later
                       |  val y: Int < Sync = Sync(2).later    // Keep another effect
                       |  x.now + y.now                    // Sequence effects
                       |}""".stripMargin)}
                       |""".stripMargin
                )

            case tree @ ValDef(_, _, _) if tree.symbol.flags.is(Flags.Mutable) =>
                fail(
                    tree,
                    s"""${"`var`".yellow} declarations are not allowed inside a ${"`direct`".yellow} block.
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
                    s"""${"`lazy val`".yellow} and ${"`object`".yellow} declarations are not allowed inside a ${"`direct`".yellow} block.
                    |
                    |These interfere with effect sequencing. Define them outside the direct block:
                    |${highlight("""
                    |// Instead of lazy declarations in direct:
                    |direct {
                    |  lazy val x = Sync(1).now  // NOT OK - lazy val
                    |  object A               // NOT OK - object
                    |  x + 1
                    |}
                    |
                    |// Define outside direct:
                    |lazy val x = Sync(1)       // OK - outside
                    |object A                 // OK - outside
                    |
                    |// Use inside direct:
                    |direct {
                    |  val result = x.now     // OK - proper sequencing
                    |  A.method.now
                    |}""".stripMargin)}
                    |
                    |For expensive computations needing caching, consider ${"`Async.memoize`".cyan}:
                    |${highlight("""
                    |direct {
                    |  val memoized = Async.memoize(expensiveComputation).now
                    |  memoized().now  // First computes, then caches
                    |}""".stripMargin)}""".stripMargin.stripMargin
                )

            case tree @ DefDef(_, _, _, Some(body)) if !pure(body) =>
                fail(
                    tree,
                    s"""Method definitions containing ${".now".cyan} are not supported inside ${"`direct`".yellow} blocks.
                       |
                       |Define methods outside direct blocks:
                       |${highlight("""
                       |// Instead of method in direct:
                       |direct {
                       |  def process(x: Int) = Sync(x).now  // NOT OK
                       |  process(10)
                       |}
                       |
                       |// Define outside:
                       |def process(x: Int): Int < Sync = direct {
                       |  Sync(x).now
                       |}
                       |
                       |direct {
                       |  process(10).now  // OK
                       |}""".stripMargin)}""".stripMargin
                )

            case tree @ Try(_, _, _) =>
                fail(
                    tree,
                    s"""${"`try`".yellow}/${"`catch`".yellow} blocks are not supported inside ${"`direct`".yellow} blocks.
                       |
                       |Use error handling effects instead. You can handle each effect in a separate direct block:
                       |${highlight("""
                       |// Instead of try/catch:
                       |direct {
                       |  try {
                       |    Sync(1).now    // NOT OK
                       |  } catch {
                       |    case e => handleError(e)
                       |  }
                       |}
                       |
                       |// Define the effectful computation:
                       |def computation = direct {
                       |  Sync(1).now
                       |}
                       |
                       |// Handle the effect direct block:
                       |direct {
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
                    s"""${"`class`".yellow} and ${"`trait`".yellow} declarations are not allowed inside ${"`direct`".yellow} blocks.
                        |
                        |Define them outside direct blocks:
                        |${highlight("""
                        |// Instead of declarations in direct:
                        |direct {
                        |  class MyClass(x: Int)    // NOT OK
                        |  trait MyTrait            // NOT OK
                        |  new MyClass(10)
                        |}
                        |
                        |// Define outside:
                        |class MyClass(x: Int)      // OK - outside direct
                        |trait MyTrait              // OK - outside direct
                        |
                        |direct {
                        |  new MyClass(10)          // OK - usage in direct
                        |}""".stripMargin)}""".stripMargin
                )

            case tree @ Apply(Ident("throw"), _) =>
                fail(
                    tree,
                    s"""${"`throw`".yellow} expressions are not allowed inside a ${"`direct`".yellow} block.
                    |
                    |Exception throwing can break effect sequencing. Use error handling effects instead:
                    |${highlight("""
                    |// Instead of throw:
                    |direct {
                    |  if condition then
                    |    throw new Exception("error")  // NOT OK - throws exception
                    |  Sync(1).now
                    |}
                    |
                    |// Use Abort effect:
                    |direct {
                    |  if condition then
                    |    Abort.fail("error").now       // OK - proper error handling
                    |  else Sync(1).now
                    |}""".stripMargin)}""".stripMargin
                )

            case tree @ Select(_, "synchronized") =>
                fail(
                    tree,
                    s"""${"`synchronized`".yellow} blocks are not allowed inside a ${"`direct`".yellow} block.
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
                    s"""Mutable field access is not allowed inside a ${"`direct`".yellow} block.
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
