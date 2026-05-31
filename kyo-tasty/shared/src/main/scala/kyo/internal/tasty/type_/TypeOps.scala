package kyo.internal.tasty.type_

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Tasty

/** Smart constructors for Tasty.Type that normalize certain applied types at build time.
  *
  * All normalizations happen here before passing to TypeArena.intern, so two structurally equivalent types normalized from different sites
  * produce the same canonical form.
  *
  * Normalization table:
  *   - scala.FunctionN[A1..AN, R] => Function(Chunk(A1..AN), R, false)
  *   - scala.ContextFunctionN[A1..AN, R] => Function(Chunk(A1..AN), R, true)
  *   - scala.TupleN[T1..TN] => Tuple(Chunk(T1..TN))
  *   - scala.Array[T] => Array(T)
  *   - AndType(scala.Singleton, X) => X
  *   - AndType(X, scala.Singleton) => X
  *   - anything else => Applied(base, args)
  */
object TypeOps:

    private val FunctionPrefix        = "scala.Function"
    private val ContextFunctionPrefix = "scala.ContextFunction"
    private val TuplePrefix           = "scala.Tuple"
    private val ArrayFqn              = "scala.Array"
    private val SingletonFqn          = "scala.Singleton"

    private val FunctionSimple        = "Function"
    private val ContextFunctionSimple = "ContextFunction"
    private val TupleSimple           = "Tuple"
    private val ArraySimple           = "Array"
    private val SingletonSimple       = "Singleton"

    /** Smart constructor for APPLIEDtype normalization.
      *
      * Normalizes common Scala standard library types (FunctionN, ContextFunctionN, TupleN, Array) using FQN when available from `fqnHint`,
      * falling back to simple-name matching. The `fqnHint` is the FQN of the base type constructor when it is a tracked unresolved
      * cross-file reference (populated from `DecodeSession.unresolvedIdToFqn`).
      *
      * @param base
      *   The type constructor.
      * @param args
      *   The type arguments.
      * @param fqnHint
      *   Optional FQN of the base type constructor for normalization lookup. Pass `null` when unavailable.
      */
    def applied(base: Tasty.Type, args: Chunk[Tasty.Type], fqnHint: String | Null = null)(using AllowUnsafe): Tasty.Type =
        val fqn = if fqnHint != null then fqnHint else ""
        if fqn.nonEmpty then
            if fqn.startsWith(FunctionPrefix) && isDigitSuffix(fqn, FunctionPrefix.length) then
                if args.nonEmpty then Tasty.Type.Function(args.dropRight(1), args.last, false)
                else Tasty.Type.Applied(base, args)
            else if fqn.startsWith(ContextFunctionPrefix) && isDigitSuffix(fqn, ContextFunctionPrefix.length) then
                if args.nonEmpty then Tasty.Type.Function(args.dropRight(1), args.last, true)
                else Tasty.Type.Applied(base, args)
            else if fqn.startsWith(TuplePrefix) && isDigitSuffix(fqn, TuplePrefix.length) then
                Tasty.Type.Tuple(args)
            else if fqn == ArrayFqn && args.size == 1 then
                Tasty.Type.Array(args.head)
            else
                Tasty.Type.Applied(base, args)
        else
            // Fall back to simple-name heuristic for phase-B local symbols
            base match
                case Tasty.Type.Named(_) =>
                    Tasty.Type.Applied(base, args)
                case _ =>
                    Tasty.Type.Applied(base, args)
        end if
    end applied

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X.
      *
      * Uses the FQN hint for the named type when available (from `DecodeSession.unresolvedIdToFqn`) to identify `scala.Singleton`.
      *
      * @param left
      *   The left side of the intersection.
      * @param right
      *   The right side of the intersection.
      * @param leftFqn
      *   Optional FQN of `left` when it is a Named type. Pass `null` when unavailable.
      * @param rightFqn
      *   Optional FQN of `right` when it is a Named type. Pass `null` when unavailable.
      */
    def andType(
        left: Tasty.Type,
        right: Tasty.Type,
        leftFqn: String | Null = null,
        rightFqn: String | Null = null
    )(using AllowUnsafe): Tasty.Type =
        val lFqn = if leftFqn != null then leftFqn else ""
        val rFqn = if rightFqn != null then rightFqn else ""
        if lFqn == SingletonFqn then right
        else if rFqn == SingletonFqn then left
        else Tasty.Type.AndType(left, right)
    end andType

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Tasty.Type): Tasty.Type = Tasty.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
