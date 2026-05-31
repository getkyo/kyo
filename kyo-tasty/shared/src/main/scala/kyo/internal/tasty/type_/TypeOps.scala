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

    /** Smart constructor for APPLIEDtype normalization.
      *
      * plan: phase-05 inline; Named(symbolId) no longer carries a name directly; name resolution requires a Classpath (Phase 09 concern).
      * For Phase 05, all Applied types pass through unchanged. Phase 09 restores FQN-based normalization once cp.symbol(id).name is
      * available.
      */
    def applied(base: Tasty.Type, args: Chunk[Tasty.Type])(using AllowUnsafe): Tasty.Type =
        Tasty.Type.Applied(base, args)
    end applied

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X.
      *
      * plan: phase-05 inline; Singleton name check deferred to Phase 09 (same reason as applied).
      */
    def andType(left: Tasty.Type, right: Tasty.Type)(using AllowUnsafe): Tasty.Type =
        Tasty.Type.AndType(left, right)
    end andType

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Tasty.Type): Tasty.Type = Tasty.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
