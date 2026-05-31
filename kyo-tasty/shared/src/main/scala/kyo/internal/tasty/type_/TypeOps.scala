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

    // These FQN constants are preserved for Phase 09 reference but unused in Phase 02.
    // plan: phase-02 inline; Phase 09 restores FQN-based matching.
    private val FunctionPrefix        = "scala.Function"
    private val ContextFunctionPrefix = "scala.ContextFunction"
    private val TuplePrefix           = "scala.Tuple"
    private val ArrayFqn              = "scala.Array"
    private val SingletonFqn          = "scala.Singleton"

    // plan: phase-02 inline; uses sym.name.asString (simple name) instead of sym.fullName.asString (FQN).
    // This is a conservative approximation: e.g., "Function1" matches even if not in scala package.
    // Phase 09 restores FQN-based matching once Symbol.fullName is available as a resolution method.
    private val FunctionSimple        = "Function"
    private val ContextFunctionSimple = "ContextFunction"
    private val TupleSimple           = "Tuple"
    private val ArraySimple           = "Array"
    private val SingletonSimple       = "Singleton"

    /** Smart constructor for APPLIEDtype normalization. */
    def applied(base: Tasty.Type, args: Chunk[Tasty.Type])(using AllowUnsafe): Tasty.Type =
        base match
            case Tasty.Type.Named(sym) =>
                import Tasty.Name.asString
                val n = sym.name.asString
                if n.startsWith(FunctionSimple) && isDigitSuffix(n, FunctionSimple.length) then
                    Tasty.Type.Function(args.dropRight(1), args.last, false)
                else if n.startsWith(ContextFunctionSimple) && isDigitSuffix(n, ContextFunctionSimple.length) then
                    Tasty.Type.Function(args.dropRight(1), args.last, true)
                else if n.startsWith(TupleSimple) && isDigitSuffix(n, TupleSimple.length) then
                    Tasty.Type.Tuple(args)
                else if n == ArraySimple && args.length == 1 then
                    Tasty.Type.Array(args.head)
                else
                    Tasty.Type.Applied(base, args)
                end if
            case _ =>
                Tasty.Type.Applied(base, args)
        end match
    end applied

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X. */
    def andType(left: Tasty.Type, right: Tasty.Type)(using AllowUnsafe): Tasty.Type =
        (left, right) match
            case (Tasty.Type.Named(sym), _) if { import Tasty.Name.asString; sym.name.asString == SingletonSimple } => right
            case (_, Tasty.Type.Named(sym)) if { import Tasty.Name.asString; sym.name.asString == SingletonSimple } => left
            case _ => Tasty.Type.AndType(left, right)
        end match
    end andType

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Tasty.Type): Tasty.Type = Tasty.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
