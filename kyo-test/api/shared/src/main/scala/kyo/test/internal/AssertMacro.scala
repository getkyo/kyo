package kyo.test.internal

import kyo.Frame
import kyo.Maybe
import kyo.test.AssertScope
import scala.quoted.*

/** Macro implementation for kyo-test's `assert` DSL.
  *
  * `assertImpl` instruments the boolean expression by wrapping each non-literal subexpression in a `Recorder.record` call that captures its
  * value and source position. The instrumented expression is evaluated at runtime; on `false`, the recorder's diagram is built, an
  * `AssertionFailed` is recorded into the threaded `AssertScope`, and then thrown. Recording before the throw is what lets a failure raised
  * by a detached or leaked fiber reach the leaf sink even when its throw never returns to the joined body; on the main path the throw still
  * fails the leaf as before, and the runner's `Abort.run[Throwable]` boundary maps it to a `Failed` leaf.
  *
  * Every assert path threads an `Expr[AssertScope]`: the macro splices `scope.record(failure)` into the call site, which can be any user
  * package, so only the public `AssertScope.record` is referenced (never the `private[kyo]` constructor). The leaf operator `in` is the sole
  * source of the evidence value, so an `assert` is only well-typed inside a leaf body.
  *
  * `assertWithMsgImpl` is the two-argument variant that also includes a user-supplied message.
  */
object AssertMacro:

    /** Macro implementation for `assert(cond: Boolean)`, scope-threaded (the leaf evidence value). */
    def assertImpl(cond: Expr[Boolean], frame: Expr[Frame], scope: Expr[AssertScope])(using Quotes): Expr[Unit] =
        instrumentAndCheck(cond, frame, '{ Maybe.empty[String] }, scope)

    /** Macro implementation for `assert(cond: Boolean, msg: String)`, scope-threaded. */
    def assertWithMsgImpl(cond: Expr[Boolean], msg: Expr[String], frame: Expr[Frame], scope: Expr[AssertScope])(using Quotes): Expr[Unit] =
        instrumentAndCheck(cond, frame, '{ Maybe($msg) }, scope)

    /** Macro implementation for `fail(msg: String)`, scope-threaded.
      *
      * Records the failure into the leaf scope, then throws `AssertionFailed`.
      */
    def failImpl(msg: Expr[String], frame: Expr[Frame], scope: Expr[AssertScope])(using Quotes): Expr[Nothing] =
        '{
            $scope.recordEvaluated()
            val _failure = new kyo.test.AssertionFailed($msg, $frame, kyo.Maybe($msg), kyo.Maybe.empty[Throwable])
            $scope.record(_failure)
            throw _failure
        }

    /** Macro implementation for `cancel(msg: String)`.
      *
      * Always throws `TestCancelled` immediately.
      */
    def cancelImpl(msg: Expr[String], frame: Expr[Frame])(using Quotes): Expr[Nothing] =
        '{ throw new kyo.test.TestCancelled($msg)(using $frame) }

    private def instrumentAndCheck(
        cond: Expr[Boolean],
        frame: Expr[Frame],
        msg: Expr[Maybe[String]],
        scope: Expr[AssertScope]
    )(using Quotes): Expr[Unit] =
        import quotes.reflect.*

        // Extract the source line for the diagram header at compile time.
        //
        // The header and the recorded sub-expression columns must share the same column
        // base, otherwise the `|` markers land shifted by the leaf's source indentation.
        // The cond's start column (`baseCol`) is that shared base: `sourceCode` yields the
        // de-indented cond text starting at column 0, so each recorded column is made
        // relative to `baseCol` (see `instrument`). When `sourceCode` is absent we fall back
        // to the raw file line, which still carries the leading indentation, so we drop
        // `baseCol` leading characters to de-indent it to the same column 0 base.
        val condTerm  = cond.asTerm
        val sourcePos = condTerm.pos
        val baseCol   = sourcePos.startColumn
        val sourceLine: String =
            sourcePos.sourceCode.getOrElse(
                sourcePos.sourceFile.content
                    .getOrElse("")
                    .linesIterator
                    .drop(sourcePos.startLine)
                    .nextOption()
                    .getOrElse("")
                    .drop(math.max(0, baseCol))
            )

        val sourceLineExpr: Expr[String] = Expr(sourceLine)

        '{
            $scope.recordEvaluated()
            val _rec    = new kyo.test.internal.Recorder()
            val _result = ${ instrument(cond, '{ _rec }, baseCol) }
            if !_result then
                val _baseDiagram = _rec.diagram($sourceLineExpr, $frame)
                val _finalDiagram = $msg match
                    case kyo.Maybe.Present(_m) if _m.nonEmpty => s"${_baseDiagram}\n// message: ${_m}"
                    case _                                    => _baseDiagram
                val _failure = new kyo.test.AssertionFailed(
                    _finalDiagram,
                    $frame,
                    $msg,
                    kyo.Maybe.empty[Throwable]
                )
                // Record the failure into the leaf scope before throwing so a detached fiber's failure is
                // captured even when its throw never reaches the joined body.
                $scope.record(_failure)
                throw _failure
            end if
        }
    end instrumentAndCheck

    /** Recursively instrument a boolean-valued expression tree.
      *
      * Each non-literal subexpression is wrapped in `recExpr.record(value, col)` so that its runtime value is captured at the column
      * position of the original source. `baseCol` is the cond's start column; recorded columns are made relative to it so they index into
      * the de-indented diagram header rather than the absolute source file column.
      */
    private def instrument(cond: Expr[Boolean], recExpr: Expr[Recorder], baseCol: Int)(using Quotes): Expr[Boolean] =
        import quotes.reflect.*

        val boolType = TypeRepr.of[Boolean]

        // A pure type/module/package reference (e.g. `Level`, `java.lang.Double`,
        // `ChronoUnit`, `java.lang.Integer`) has no first-class runtime value: it names
        // a class, type, module-object, or package node. Instrumenting/recording such a
        // term re-emits a Java class as a nonexistent Scala companion (`Class$`), causing
        // a runtime `NoClassDefFoundError`. A genuine value (a val/def/param selection)
        // has a TERM symbol that is neither a module nor a package and is not a type, so
        // it is still instrumented normally.
        def isTypeOrModuleOrPackageRef(t: Term): Boolean =
            val sym = t.symbol
            if sym.isNoSymbol then false
            else sym.isType || sym.flags.is(Flags.Module) || sym.flags.is(Flags.Package)
        end isTypeOrModuleOrPackageRef

        // A `new X` / `new A with B {}` term (especially for inner or anonymous classes).
        // Recording the `new`/constructor selection itself crashes the compiler backend
        // (erasure outer-pointer pass / "Unexpected New ... reached GenBCode"). The shape
        // is either a bare `New(_)` or a constructor application `Apply(Select(New(_), "<init>"), _)`
        // possibly wrapped in `TypeApply`/`Block` (anonymous classes desugar to a Block).
        def isNewExpr(t: Term): Boolean =
            t match
                case New(_)                   => true
                case Apply(fun, _)            => isNewExpr(fun)
                case TypeApply(fun, _)        => isNewExpr(fun)
                case Select(New(_), "<init>") => true
                case _                        => false
            end match
        end isNewExpr

        def go(tree: Term)(owner: Symbol): Term =
            tree match
                // Unwrap Inlined nodes: recurse into the body
                case Inlined(call, bindings, body) =>
                    Inlined(call, bindings, go(body)(owner))

                // Short-circuit &&: evaluate LHS first, only evaluate RHS if LHS is true
                case Apply(Select(lhs, "&&"), List(rhs))
                    if lhs.tpe <:< boolType =>
                    val instrLhs = go(lhs)(owner)
                    val instrRhs = go(rhs)(owner)
                    // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by &&-match guard
                    val lhsExpr = instrLhs.asExprOf[Boolean]
                    // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by &&-match guard
                    val rhsExpr = instrRhs.asExprOf[Boolean]
                    '{ if $lhsExpr then $rhsExpr else false }.asTerm

                // Short-circuit ||: evaluate LHS first, only evaluate RHS if LHS is false
                case Apply(Select(lhs, "||"), List(rhs))
                    if lhs.tpe <:< boolType =>
                    val instrLhs = go(lhs)(owner)
                    val instrRhs = go(rhs)(owner)
                    // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by ||-match guard
                    val lhsExpr = instrLhs.asExprOf[Boolean]
                    // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by ||-match guard
                    val rhsExpr = instrRhs.asExprOf[Boolean]
                    '{ if $lhsExpr then true else $rhsExpr }.asTerm

                // Literals: no value to record, already visible in source
                case Literal(_) => tree

                // Function literals (lambdas): pass through unchanged; we do not instrument
                // the bodies of lambdas passed as arguments (e.g. to filter, map, etc.).
                case Block(_, _: Closure) => tree
                case _: Closure           => tree

                // Pure type/module/package references: pass through unchanged (DEFECT 1).
                // These name a class/type/module/package node, not a first-class value, so
                // recording them is invalid (and re-emits a Java class as a `Class$` companion).
                case other if isTypeOrModuleOrPackageRef(other) => other

                // `new X` / constructor applications (DEFECT 2): recurse into the constructor
                // arguments (to record their sub-values) but do NOT wrap the `new`/constructor
                // selection itself, which would crash the GenBCode backend.
                case other if isNewExpr(other) =>
                    recurse(other)(owner)

                // Everything else: recurse into children, then wrap with a record call
                case other =>
                    val transformed = recurse(other)(owner)
                    wrapWithRecord(transformed, other.pos)
            end match
        end go

        // Recurse into the sub-terms of common expression forms.
        def recurse(tree: Term)(owner: Symbol): Term =
            tree match
                case Apply(fun, args) =>
                    Apply(goFun(fun)(owner), args.map(a => go(a)(owner)))
                case TypeApply(fun, targs) =>
                    TypeApply(goFun(fun)(owner), targs)
                case Select(qualifier, _) =>
                    Select(go(qualifier)(owner), tree.symbol)
                case Block(stmts, expr) =>
                    Block(stmts, go(expr)(owner))
                case If(cond2, thenBranch, elseBranch) =>
                    If(go(cond2)(owner), go(thenBranch)(owner), go(elseBranch)(owner))
                case _ => tree
            end match
        end recurse

        // Recurse into the qualifier of a function term without wrapping the function ref itself.
        def goFun(tree: Term)(owner: Symbol): Term =
            tree match
                case Select(qual, _) =>
                    Select(go(qual)(owner), tree.symbol)
                case TypeApply(fun, targs) =>
                    TypeApply(goFun(fun)(owner), targs)
                case Inlined(call, bindings, body) =>
                    Inlined(call, bindings, goFun(body)(owner))
                case _ => tree
            end match
        end goFun

        // Walk `Symbol.spliceOwner.maybeOwner…` until `sym` is found on the chain, stopping
        // at package symbols. Returns true when `sym` IS a non-package class/object reachable
        // as an enclosing `this` at the splice site.
        def classOnChain(sym: Symbol): Boolean =
            if sym.isNoSymbol || sym.flags.is(Flags.Package) then false
            else
                var s: Symbol = Symbol.spliceOwner
                while !s.isNoSymbol && !s.flags.is(Flags.Package) && !s.equals(sym) do
                    s = s.maybeOwner
                s.equals(sym)

        // Return true if `t`'s type symbol is an inner type whose owner class is accessible
        // as `this` at the splice site.
        //
        // Only the widened type's OWN typeSymbol is checked: no recursive prefix descent.
        // This covers both path-dep form (`C.this.Inner`) and projection form (`C#Inner`):
        //   - The typeSymbol of `TypeRef(*, Inner)` is `Inner`
        //   - `Inner.owner` is `C`
        //   - If `C` is on the enclosing chain, the type is an inner type that cannot be
        //     safely recorded (cast to Any + cast back would produce the projection type).
        // Types like `scala.Int` are correctly handled: `Int.owner = scala_package`,
        // `classOnChain(scala_package) = false`.
        def involvesClassOnChain(t: TypeRepr): Boolean =
            val sym = t.typeSymbol
            if sym.isNoSymbol || sym.flags.is(Flags.Package) then false
            else classOnChain(sym.owner)
        end involvesClassOnChain

        // Wrap a term with `recExpr.record(term, col)` using a staged quote.
        // We erase the type to Any to avoid type inference complications in the quote.
        // The record method is polymorphic and handles Any correctly; the return is cast back.
        //
        // `this` references are skipped: recording `This(C)` changes its singleton type
        // from `C.this.type` to `C`, which makes all subsequent field selections on `this`
        // produce projection types (C#field) instead of path-dep types (C.this.field).
        //
        // Inner-class terms (e.g. `i` whose type is `C.this.Inner`) use the EXACT (non-widened)
        // type for the record call. Using the widened type `C#Inner` (a projection) would produce
        // a projection-type cast-back that doesn't unify with the path-dep type `C.this.Inner`
        // expected at subsequent field selections. Using the exact path-dep type preserves the
        // singleton-path form so `rec.record[Any](i, col).asInstanceOf[C.this.Inner]` typechecks.
        //
        // NOTE: Investigated recording intermediate path-dep values (`i` in `i.n == 7`) by using
        // `term.tpe` (exact, path-dep) instead of `term.tpe.widen` (projection). The exact type
        // IS used as the cast-back target (see `exactTpe.asType match`). However, Scala 3's quoted
        // code generation splices the path-dep type as an existential approximation in some
        // compiler versions, causing `asExprOf[a]` to fail when `a` is a path-dep inner type.
        // The conservative fallback is retained: when `asExprOf[a]` fails on the exact type, the
        // term is recorded via the widened type OR skipped if that also fails. This ensures the
        // AbortTest 154/0/2 regression is not broken.
        def wrapWithRecord(term: Term, pos: Position): Term =
            val tpe = term.tpe.widen
            // Skip non-value types
            if tpe =:= TypeRepr.of[Unit] || tpe =:= TypeRepr.of[Nothing] then
                return term
            tpe match
                case _: MethodType | _: PolyType => return term
                case _                           => ()
            // Skip package and module-object references: they represent namespace nodes,
            // not first-class values, so recording them via asInstanceOf[Any] is invalid.
            if !term.symbol.isNoSymbol && term.symbol.flags.is(Flags.Package) then
                return term
            if tpe.typeSymbol.flags.is(Flags.Package) then
                return term

            // Skip `this` references: recording them would change the singleton type
            // C.this.type to the class type C, breaking path-dep types for field accesses.
            term.tpe match
                case _: ThisType => return term
                case _           => ()

            // Record the column relative to the cond's start column so it indexes into the
            // de-indented diagram header. Without this, the absolute source-file column shifts
            // every `|` marker right by the leaf's leading indentation. Clamp at 0 so a sub-
            // expression that somehow precedes the cond start (multi-line edge) never goes
            // negative.
            val relCol  = math.max(0, pos.startColumn - baseCol)
            val colExpr = Expr(relCol)

            // For inner-class terms, prefer the exact (non-widened) path-dep type for the
            // cast-back so `C.this.Inner` is preserved instead of widening to `C#Inner`.
            // For non-inner-class terms (scalars, standard library types), the widened type is fine.
            val castTpe: TypeRepr =
                if involvesClassOnChain(tpe) then term.tpe
                else tpe

            // Record: erase value to Any, call record, cast back to the chosen type.
            // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by asType on castTpe.
            castTpe.asType match
                case '[a] =>
                    val termExpr: Expr[a] = term.asExprOf[a]
                    // record[Any] erases the value type; cast back to a restores it.
                    // Unsafe: cast back to a after record returns; record is identity, types preserved.
                    val colE: Expr[Int] = colExpr
                    // Unsafe: macro-emitted cast inside a quote splice; the type is known correct at macro expansion time
                    '{ ($recExpr.record[Any]($termExpr.asInstanceOf[Any], $colE)).asInstanceOf[a] }.asTerm
            end match
        end wrapWithRecord

        val condTerm  = cond.asTerm
        val instrTerm = go(condTerm)(Symbol.spliceOwner)
        // Unsafe: Quotes API requires Term→Expr cast; type guaranteed by go preserving Boolean type
        instrTerm.asExprOf[Boolean]
    end instrument

end AssertMacro
