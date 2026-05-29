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

    /** Smart constructor for APPLIEDtype normalization. */
    def applied(base: Tasty.Type, args: Chunk[Tasty.Type]): Tasty.Type =
        // Unsafe: Symbol.fullName and Name.asString require AllowUnsafe; embraced here in type-normalization context (§839 case 3).
        import AllowUnsafe.embrace.danger
        base match
            case Tasty.Type.Named(sym) =>
                val fqn = sym.fullName.asString
                if fqn.startsWith(FunctionPrefix) && isDigitSuffix(fqn, FunctionPrefix.length) then
                    Tasty.Type.Function(args.dropRight(1), args.last, false)
                else if fqn.startsWith(ContextFunctionPrefix) && isDigitSuffix(fqn, ContextFunctionPrefix.length) then
                    Tasty.Type.Function(args.dropRight(1), args.last, true)
                else if fqn.startsWith(TuplePrefix) && isDigitSuffix(fqn, TuplePrefix.length) then
                    Tasty.Type.Tuple(args)
                else if fqn == ArrayFqn && args.length == 1 then
                    Tasty.Type.Array(args.head)
                else
                    Tasty.Type.Applied(base, args)
                end if
            case _ =>
                Tasty.Type.Applied(base, args)
        end match
    end applied

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X. */
    def andType(left: Tasty.Type, right: Tasty.Type): Tasty.Type =
        // Unsafe: Symbol.fullName and Name.asString require AllowUnsafe; embraced here in type-normalization context (§839 case 3).
        import AllowUnsafe.embrace.danger
        (left, right) match
            case (Tasty.Type.Named(sym), _) if sym.fullName.asString == SingletonFqn => right
            case (_, Tasty.Type.Named(sym)) if sym.fullName.asString == SingletonFqn => left
            case _                                                                   => Tasty.Type.AndType(left, right)
        end match
    end andType

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Tasty.Type): Tasty.Type = Tasty.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
