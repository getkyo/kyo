package kyo.internal.reflect.reads

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Reflect
import scala.quoted.*

/** Tree-walker that collects the FieldSet bits touched by a compiled Reflect.Reads.read body.
  *
  * Extracted from ReflectMacro for testability. The analysis is conservative: it recognizes Select(qualifier, methodName) nodes where
  * qualifier has type <:< Reflect.Symbol and maps the method name to the corresponding FieldSet bit.
  *
  * Hygiene rule 1: a fast-path Trees.exists pre-check avoids TreeTraverser allocation when the body contains no Symbol-typed selections.
  *
  * Hygiene rule 2: Match nodes are handled specially -- the .pattern subtree is skipped to avoid false positives from Bind/Unapply/Wildcard
  * nodes that carry effect-tagged types resembling accessor calls.
  *
  * Hygiene rule 3: Block(Nil, ...) lambda wrapper peeling before entering the main traversal.
  *
  * Design note (dual-purpose path): ReflectMacro builds touchedFields at field-analysis time via field-name dispatch (directFieldTouched)
  * and summoned-instance propagation (r.touchedFields). This is functionally equivalent to calling analyze on the generated body for the
  * cases covered by direct-field dispatch and SummonField propagation. The analyze method is preserved as the testable extracted path:
  * tests can call it directly on any function body to verify hygiene rule 2 (Match.pattern skipped) and rule 1 (cheap pre-check) without
  * going through the full derivation pipeline.
  *
  * Hand-written Reads instances may call `TouchedFields.declare(fields)` inside their `read` body as a compile-time hint. The derivation
  * macro recognizes this call and uses the declared FieldSet for transitive touchedFields propagation instead of defaulting to
  * FieldSet.All.
  */
object TouchedFields:

    /** Compile-time hint for hand-written `Reads` instances.
      *
      * Call this inside a `read` body to declare which FieldSet bits the implementation accesses. The call expands to `()` at runtime (no
      * overhead). The derivation macro recognizes `TouchedFields.declare(fs)` in a summoned Reads' `read` body and uses `fs` for transitive
      * touchedFields propagation instead of defaulting to `FieldSet.All`.
      *
      * Example:
      * {{{
      * given Reflect.Reads[MyType] = new Reflect.Reads[MyType]:
      *     val symbolKinds   = Set(Reflect.SymbolKind.values*)
      *     val needsBodies   = false
      *     val touchedFields = Reflect.FieldSet.Name | Reflect.FieldSet.Flags
      *     def read(sym: Reflect.Symbol)(using Frame): MyType < (Sync & Async & Abort[ReflectError]) =
      *         TouchedFields.declare(Reflect.FieldSet.Name | Reflect.FieldSet.Flags)
      *         Kyo.lift(MyType(sym.name, sym.flags))
      * }}}
      */
    def declare(fields: Reflect.FieldSet): Unit = ()

    /** Analyze a compiled read body Term and return the union of all FieldSet bits it touches.
      *
      * This is the testable extracted path. ReflectMacro builds touchedFields via field-name dispatch at derivation time; analyze can be
      * called directly on any function body (including hand-written ones) to verify hygiene rule behavior.
      *
      * If the body contains a `TouchedFields.declare(fs)` call, returns `fs` immediately (the declared value takes precedence).
      *
      * @param readBody
      *   the body Term of the read method override
      * @return
      *   FieldSet representing all Symbol accessors called in readBody
      */
    def analyze(using Quotes)(readBody: quotes.reflect.Term): Reflect.FieldSet =
        import quotes.reflect.*

        // Hygiene rule 3: peel Block(Nil, ...) lambda wrappers
        val unwrapped = peel(readBody)

        // Check for TouchedFields.declare(fs) call -- if present, use declared value immediately.
        extractDeclaredFieldSet(unwrapped) match
            case Present(fs) => return fs
            case Absent      =>

        // Hygiene rule 1: cheap pre-check -- skip allocation if no Symbol-typed selects
        val hasSymbolSelect = existsSymbolSelect(unwrapped)
        if !hasSymbolSelect then return Reflect.FieldSet.Empty

        var result = Reflect.FieldSet.Empty

        traverseGoto(unwrapped) {
            // Hygiene rule 2: Match -- skip .pattern, visit scrutinee/guard/rhs only
            case Match(scrutinee, cases) =>
                goto(scrutinee)
                cases.foreach { c =>
                    c.guard.foreach(goto)
                    goto(c.rhs)
                }

            // Symbol accessor collection
            case Select(qualifier, methodName) if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] =>
                result = result | fieldSetForAccessor(methodName)
        }
        result
    end analyze

    /** Try to extract a `TouchedFields.declare(fs)` call from a read body Term.
      *
      * Returns `Present(fs)` if the body starts with (or contains as a Block statement) a call to `TouchedFields.declare`, so the macro can
      * use the declared value for transitive touchedFields propagation. Returns `Absent` if no declare call is found.
      */
    def extractDeclaredFieldSet(using Quotes)(body: quotes.reflect.Term): Maybe[Reflect.FieldSet] =
        import quotes.reflect.*
        val declareSym = TypeRepr.of[TouchedFields.type].typeSymbol.methodMember("declare").headOption
        declareSym match
            case None => Absent
            case Some(declSym) =>
                var found: Maybe[Reflect.FieldSet] = Absent
                (new TreeTraverser:
                    override def traverseTree(t: Tree)(owner: Symbol): Unit =
                        if found.isEmpty then
                            t match
                                // TouchedFields.declare(fs) -- direct call
                                case Apply(Select(_, "declare"), List(fsArg)) if found.isEmpty =>
                                    fsArg.tpe.widenTermRefByName.asType match
                                        case '[Reflect.FieldSet] =>
                                            fsArg match
                                                case Inlined(_, _, inner) =>
                                                    tryEvalFieldSet(inner) match
                                                        case Present(fs) => found = Present(fs)
                                                        case Absent      => super.traverseTree(t)(owner)
                                                case _ =>
                                                    tryEvalFieldSet(fsArg) match
                                                        case Present(fs) => found = Present(fs)
                                                        case Absent      => super.traverseTree(t)(owner)
                                        case _ => super.traverseTree(t)(owner)
                                case _ => super.traverseTree(t)(owner)
                ).traverseTree(body)(Symbol.spliceOwner)
                found
        end match
    end extractDeclaredFieldSet

    /** Attempt to statically evaluate a FieldSet expression at macro time.
      *
      * Handles the common patterns: FieldSet constant (via `new FieldSet(bitsLiteral)`), binary `|` of two FieldSets, and named companion
      * vals like `Reflect.FieldSet.Name` / `Reflect.FieldSet.Flags` (which appear as `Select` nodes in the AST since they are regular vals,
      * not inline vals). Returns Absent if the expression cannot be evaluated statically.
      */
    def tryEvalFieldSet(using Quotes)(expr: quotes.reflect.Term): Maybe[Reflect.FieldSet] =
        import quotes.reflect.*
        expr match
            // new Reflect.FieldSet(bitsLong) -- direct constructor
            case Apply(Select(New(_), "<init>"), List(Literal(LongConstant(bits)))) =>
                Present(new Reflect.FieldSet(bits))
            // Literal Long constant (unlikely but handle it)
            case Literal(LongConstant(bits)) =>
                Present(new Reflect.FieldSet(bits))
            // fs1 | fs2 -- union of two FieldSets
            case Apply(Select(lhs, "|"), List(rhs)) =>
                for
                    l <- tryEvalFieldSet(lhs)
                    r <- tryEvalFieldSet(rhs)
                yield l | r
            // Inlined(_, _, inner) -- inline expansion wrapper
            case Inlined(_, _, inner) =>
                tryEvalFieldSet(inner)
            // Block(Nil, inner) -- single-expression block
            case Block(Nil, inner) =>
                tryEvalFieldSet(inner)
            // Named companion val: Reflect.FieldSet.Name, .Flags, etc.
            // These appear as Select nodes since FieldSet companion vals are not inline.
            // Walk the RHS of the val's ValDef to resolve the bits.
            case select: Select if select.tpe <:< TypeRepr.of[Reflect.FieldSet] =>
                val sym = select.symbol
                if sym.isNoSymbol then Absent
                else
                    try
                        sym.tree match
                            case ValDef(_, _, Some(rhs)) => tryEvalFieldSet(rhs)
                            case _                       => Absent
                    catch case _: Throwable => Absent
                end if
            case _ => Absent
        end match
    end tryEvalFieldSet

    /** Inline macro entry point for testing: analyze the body of a `Reflect.Symbol => Any` function at compile time.
      *
      * Captures the lambda body as a quoted Term and delegates to analyze. Used by ReadsDerivationTest to verify hygiene rules without
      * going through full derivation. Example usage:
      * {{{
      * val bits = TouchedFields.analyzeInline((sym: Reflect.Symbol) => sym.name)
      * // bits.bits == Reflect.FieldSet.Name.bits
      * }}}
      */
    inline def analyzeInline(inline f: Reflect.Symbol => Any): Reflect.FieldSet =
        ${ analyzeInlineImpl('f) }

    private def analyzeInlineImpl(using Quotes)(f: Expr[Reflect.Symbol => Any]): Expr[Reflect.FieldSet] =
        import quotes.reflect.*
        val body    = f.asTerm
        val fs      = analyze(body)
        val bitsVal = Expr(fs.bits)
        '{ new Reflect.FieldSet($bitsVal) }
    end analyzeInlineImpl

    /** Map a Symbol method name to the corresponding FieldSet bit. */
    private[reads] def fieldSetForAccessor(methodName: String): Reflect.FieldSet =
        methodName match
            case "name" | "fullName" => Reflect.FieldSet.Name
            case "binaryName"        => Reflect.FieldSet.BinaryName
            case "flags" | "isInline" | "isContextual" | "isOpaque" |
                "isPackageObject" | "isModule" | "isJava" => Reflect.FieldSet.Flags
            case "kind"         => Reflect.FieldSet.Kind
            case "owner"        => Reflect.FieldSet.Owner
            case "declaredType" => Reflect.FieldSet.DeclaredType
            case "parents"      => Reflect.FieldSet.Parents
            case "typeParams"   => Reflect.FieldSet.TypeParams
            case "declarations" => Reflect.FieldSet.Members
            case "companion"    => Reflect.FieldSet.Companion
            case "javaSpecific" => Reflect.FieldSet.JavaSpecific
            case _              => Reflect.FieldSet.Empty

    // ── Tree traversal helpers ──────────────────────────────────────────────

    /** Peel Block(Nil, expr) wrappers. */
    @annotation.tailrec
    private def peel(using Quotes)(t: quotes.reflect.Term): quotes.reflect.Term =
        import quotes.reflect.*
        t match
            case Block(Nil, expr) => peel(expr)
            case _                => t
    end peel

    /** Check cheaply whether any Select has a Reflect.Symbol-typed qualifier. */
    private def existsSymbolSelect(using Quotes)(tree: quotes.reflect.Tree): Boolean =
        import quotes.reflect.*
        var found = false
        (new TreeTraverser:
            override def traverseTree(t: Tree)(owner: Symbol): Unit =
                if !found then
                    t match
                        case Select(qualifier, _) if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] =>
                            found = true
                        case _ =>
                            super.traverseTree(t)(owner)
        ).traverseTree(tree)(Symbol.spliceOwner)
        found
    end existsSymbolSelect

    /** Opaque accumulator for trees to visit next in a traverseGoto step.
      *
      * Using a `scala.collection.mutable.ListBuffer` as the underlying type avoids any path-dependent cast: the `Tree` type is fixed to the
      * outer `Quotes` instance.
      */
    private class GotoAccum[Q <: Quotes](val q: Q):
        private val buf                  = new scala.collection.mutable.ListBuffer[q.reflect.Tree]
        def add(t: q.reflect.Tree): Unit = buf += t
        def flush(): List[q.reflect.Tree] =
            val r = buf.toList; buf.clear(); r
    end GotoAccum

    /** Enqueues a tree for visiting inside a `traverseGoto` step. */
    private def goto(using acc: GotoAccum[? <: Quotes])(tree: acc.q.reflect.Tree): Unit = acc.add(tree)

    /** Traverse `tree` with a step-based partial function.
      *
      * If the partial function matches a node, the default TreeTraverser descent is suppressed. Instead, only trees explicitly passed to
      * `goto(...)` inside the partial function body are visited next. This is the same contract as kyo-direct's Trees.traverseGoto.
      */
    private def traverseGoto(using
        q: Quotes
    )(
        tree: quotes.reflect.Tree
    )(pf: (acc: GotoAccum[q.type]) ?=> PartialFunction[quotes.reflect.Tree, Unit]): Unit =
        import quotes.reflect.*
        (new TreeTraverser:
            override def traverseTree(t: Tree)(owner: Symbol): Unit =
                given acc: GotoAccum[q.type] = new GotoAccum(q)
                pf.lift(t) match
                    case Some(_) =>
                        // pf matched: only visit explicitly goto'd trees
                        acc.flush().foreach(child => traverseTree(child)(owner))
                    case None =>
                        // pf did not match: default descent
                        super.traverseTree(t)(owner)
                end match
            end traverseTree
        ).traverseTree(tree)(Symbol.spliceOwner)
    end traverseGoto

end TouchedFields
