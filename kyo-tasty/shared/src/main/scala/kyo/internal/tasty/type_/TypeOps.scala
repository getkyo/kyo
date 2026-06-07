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
  *   - scala.FunctionN[A1..AN, R] => Function(Chunk(A1..AN), R)
  *   - scala.ContextFunctionN[A1..AN, R] => ContextFunction(Chunk(A1..AN), R)
  *   - scala.TupleN[T1..TN] => Tuple(Chunk(T1..TN))
  *   - scala.Array[T] => Array(T)
  *   - scala.<repeated>[T] (APPLIEDtype base) => Repeated(T) (fallback)
  *   - AndType(scala.Singleton, X) => X
  *   - AndType(X, scala.Singleton) => X
  *   - anything else => Applied(base, args)
  *
  * Varargs detection: the primary mechanism is in TreeUnpickler.decodeTptAsType at the
  * ANNOTATEDtpt case. Scala 3 TASTy encodes `xs: A*` as ANNOTATEDtpt(@scala.annotation.internal.Repeated,
  * underlying_elem_type). When the annotation is @Repeated the tpe_Tree is wrapped in Type.Repeated.
  */
object TypeOps:

    private val FunctionPrefix        = "scala.Function"
    private val ContextFunctionPrefix = "scala.ContextFunction"
    private val TuplePrefix           = "scala.Tuple"
    private val ArrayFqn              = "scala.Array"
    private val SingletonFqn          = "scala.Singleton"
    // scala.& and scala.| are the internal FQNs for intersection and union type
    // constructors in Scala 3 TASTy. APPLIEDtype(scala.&, [A, B]) collapses to AndType(A, B) and
    // APPLIEDtype(scala.|, [A, B]) collapses to OrType(A, B). With args.size != 2 the Applied form is
    // preserved unchanged.
    val AndFqn = "scala.&"
    val OrFqn  = "scala.|"
    // varargs parameters in Scala 3 TASTy are encoded as ANNOTATEDtpt wrapping the element
    // type with annotation @scala.annotation.internal.Repeated. TreeUnpickler.decodeTptAsType detects
    // this annotation by checking the FQN against RepeatedAnnotationFqn and returns Type.Repeated(elem).
    // The APPLIEDtype route via RepeatedFqn and RepeatedSimple is kept for cases where <repeated> appears
    // as an APPLIEDtype base directly (different Scala version encodings or cross-file scenarios).
    val RepeatedFqn           = "scala.<repeated>"
    val RepeatedAnnotationFqn = "scala.annotation.internal.Repeated"

    private val FunctionSimple        = "Function"
    private val ContextFunctionSimple = "ContextFunction"
    private val TupleSimple           = "Tuple"
    private val ArraySimple           = "Array"
    private val SingletonSimple       = "Singleton"
    private val RepeatedSimple        = "<repeated>"

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
                if args.nonEmpty then Tasty.Type.Function(args.dropRight(1), args.last)
                else Tasty.Type.Applied(base, args)
            else if fqn.startsWith(ContextFunctionPrefix) && isDigitSuffix(fqn, ContextFunctionPrefix.length) then
                // ContextFunctionN decodes to ContextFunction, not Function.
                // This keeps context-function and plain-function types structurally distinct so callers
                // can pattern-match on the dedicated case.
                if args.nonEmpty then Tasty.Type.ContextFunction(args.dropRight(1), args.last)
                else Tasty.Type.Applied(base, args)
            else if fqn.startsWith(TuplePrefix) && isDigitSuffix(fqn, TuplePrefix.length) then
                Tasty.Type.Tuple(args)
            else if fqn == ArrayFqn && args.size == 1 then
                Tasty.Type.Array(args.head)
            else if fqn == RepeatedFqn && args.size == 1 then
                // scala.<repeated>[T] is the internal type of varargs parameters.
                // Collapse APPLIEDtype(Named(negId: scala.<repeated>), [T]) to Type.Repeated(T).
                Tasty.Type.Repeated(args.head)
            else if fqn == AndFqn && args.size == 2 then
                // scala.&[A, B] is the APPLIEDtype form of intersection types.
                // Collapse to AndType(A, B) for structural parity with the ANDtype tag path.
                Tasty.Type.AndType(args(0), args(1))
            else if fqn == OrFqn && args.size == 2 then
                // scala.|[A, B] is the APPLIEDtype form of union types.
                // Collapse to OrType(A, B) for structural parity with the ORtype tag path.
                Tasty.Type.OrType(args(0), args(1))
            else
                Tasty.Type.Applied(base, args)
        else
            // Fall back to simple-name matching for TypeRef-based constructors.
            // In Scala 3's TASTy, types like ContextFunction1[A, B] in parent-type position
            // are encoded as Applied(TypeRef(qualifier, "ContextFunction1"), args) where the
            // TypeRef's name is the simple class name. The fqnHint mechanism only fires for
            // Named(trackedNegId) bases; TypeRef bases are encountered in TEMPLATE parents
            // (decoded via parentTypes path) where no unresolvedIdToFqn entry exists.
            // Recognise the TypeRef simple-name pattern so ContextFunctionN parent
            // types also decode to ContextFunction rather than remaining as raw Applied.
            base match
                case Tasty.Type.TypeRef(_, nm) =>
                    import kyo.Tasty.Name.asString
                    val simpleName = nm.asString
                    if simpleName.startsWith(FunctionSimple) && isDigitSuffix(simpleName, FunctionSimple.length) then
                        if args.nonEmpty then Tasty.Type.Function(args.dropRight(1), args.last)
                        else Tasty.Type.Applied(base, args)
                    else if simpleName.startsWith(ContextFunctionSimple) && isDigitSuffix(simpleName, ContextFunctionSimple.length) then
                        if args.nonEmpty then Tasty.Type.ContextFunction(args.dropRight(1), args.last)
                        else Tasty.Type.Applied(base, args)
                    else if simpleName.startsWith(TupleSimple) && isDigitSuffix(simpleName, TupleSimple.length) then
                        Tasty.Type.Tuple(args)
                    else if simpleName == ArraySimple && args.size == 1 then
                        Tasty.Type.Array(args.head)
                    else if simpleName == RepeatedSimple && args.size == 1 then
                        // Same-file TypeRef(pkg, "<repeated>") path for varargs types.
                        // Collapse APPLIEDtype(TypeRef(scalaPkg, "<repeated>"), [T]) to Type.Repeated(T).
                        Tasty.Type.Repeated(args.head)
                    else
                        Tasty.Type.Applied(base, args)
                    end if
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
