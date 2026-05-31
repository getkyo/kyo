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

    // These FQN constants are preserved for Phase 10 FQN-based normalization.
    // plan: phase-10; TypeOps.applied has no Classpath parameter; FQN-based matching deferred to Phase 10.
    private val FunctionPrefix        = "scala.Function"
    private val ContextFunctionPrefix = "scala.ContextFunction"
    private val TuplePrefix           = "scala.Tuple"
    private val ArrayFqn              = "scala.Array"
    private val SingletonFqn          = "scala.Singleton"

    // plan: phase-10; uses sym.name.asString (simple name) until TypeOps.applied receives a Classpath parameter.
    // This is a conservative approximation: e.g., "Function1" matches even if not in scala package.
    private val FunctionSimple        = "Function"
    private val ContextFunctionSimple = "ContextFunction"
    private val TupleSimple           = "Tuple"
    private val ArraySimple           = "Array"
    private val SingletonSimple       = "Singleton"

    /** Smart constructor for APPLIEDtype normalization.
      *
      * plan: phase-10; Named(symbolId) carries no name directly; FQN-based normalization requires a Classpath which this method does not
      * yet receive. Phase 10 adds the Classpath parameter and restores full normalization.
      */
    def applied(base: Tasty.Type, args: Chunk[Tasty.Type])(using AllowUnsafe): Tasty.Type =
        Tasty.Type.Applied(base, args)
    end applied

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X.
      *
      * plan: phase-10; Singleton name check deferred to Phase 10 (same reason as applied).
      */
    def andType(left: Tasty.Type, right: Tasty.Type)(using AllowUnsafe): Tasty.Type =
        Tasty.Type.AndType(left, right)
    end andType

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Tasty.Type): Tasty.Type = Tasty.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
