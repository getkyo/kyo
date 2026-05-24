package kyo.internal.reflect.type_

import kyo.Chunk
import kyo.Reflect

/** Smart constructors for Reflect.Type that normalize certain applied types at build time.
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
    def applied(base: Reflect.Type, args: Chunk[Reflect.Type]): Reflect.Type =
        base match
            case Reflect.Type.Named(sym) =>
                val fqn = sym.fullName.asString
                if fqn.startsWith(FunctionPrefix) && isDigitSuffix(fqn, FunctionPrefix.length) then
                    Reflect.Type.Function(args.dropRight(1), args.last, false)
                else if fqn.startsWith(ContextFunctionPrefix) && isDigitSuffix(fqn, ContextFunctionPrefix.length) then
                    Reflect.Type.Function(args.dropRight(1), args.last, true)
                else if fqn.startsWith(TuplePrefix) && isDigitSuffix(fqn, TuplePrefix.length) then
                    Reflect.Type.Tuple(args)
                else if fqn == ArrayFqn && args.length == 1 then
                    Reflect.Type.Array(args.head)
                else
                    Reflect.Type.Applied(base, args)
                end if
            case _ =>
                Reflect.Type.Applied(base, args)

    /** Smart constructor for ANDtype normalization: collapse AndType(Singleton, X) or AndType(X, Singleton) to X. */
    def andType(left: Reflect.Type, right: Reflect.Type): Reflect.Type =
        (left, right) match
            case (Reflect.Type.Named(sym), _) if sym.fullName.asString == SingletonFqn => right
            case (_, Reflect.Type.Named(sym)) if sym.fullName.asString == SingletonFqn => left
            case _                                                                     => Reflect.Type.AndType(left, right)

    /** Direct Array constructor for Java array types from the classfile reader. */
    def mkArray(elem: Reflect.Type): Reflect.Type = Reflect.Type.Array(elem)

    private def isDigitSuffix(s: String, prefixLen: Int): Boolean =
        prefixLen < s.length && s.substring(prefixLen).forall(_.isDigit)

end TypeOps
