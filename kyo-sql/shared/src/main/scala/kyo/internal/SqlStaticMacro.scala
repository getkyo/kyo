package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Absent
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Sql
import kyo.Sql.BoundValue
import kyo.SqlSchema
import kyo.db.Idiom
import kyo.internal.macros.BindFromExpr
import scala.quoted.*
import scala.util.control.NonFatal

/** Renders a statement at compile time, once per SQL flavor on the compile classpath.
  *
  * The flavors come from the classpath rather than from this file: every `META-INF/services/kyo.db.Backend` resource the compiler can see
  * names a [[kyo.db.Backend]], each one is instantiated on the macro classloader and its [[kyo.db.Idiom]] taken, and each renders the lifted
  * AST during expansion. The
  * resulting SQL strings are spliced as string constants, so no statement text is assembled at run time and no renderer runs there, and
  * core names no backend: adding a third engine adds a services entry, not a case here.
  *
  * What a call site does still build per execution is the [[kyo.Sql.Rendered]] that carries those constants: the per-dialect map and one
  * [[kyo.Sql.BoundValue]] per bind, constructed where [[SqlRunMacro]] splices the render. A macro expansion replaces an expression rather
  * than introducing a member, so there is no call-site val to hold it in and nothing to hoist it into.
  *
  * No server version is knowable while compiling, so every render targets its dialect's [[kyo.db.Idiom.capabilityFloor]]. That is what
  * makes a statically-rendered statement safe to send to any supported server of that flavor, and also why a construct the deployed server
  * has but the floor does not is unavailable on this path.
  */
private[kyo] object SqlStaticMacro:

    private val servicesPath = "META-INF/services/kyo.db.Backend"

    // Backends resolved on the compile classpath but not instantiable on this macro's classloader are excluded from
    // the compile-time dialect set (see loadDialects) rather than failing the compile. The exclusion is surfaced as a
    // compile `info` (a `warning` would trip -Werror and fail the build), deduped through this set so it is
    // reported once per backend rather than at every `.run` site. The set is macro-side (JVM) mutable state whose only
    // effect is diagnostic dedup; thread-safe because the compiler may expand macros in parallel.
    private val excludedFromStaticFold = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

    /** Renders `q` for every classpath dialect, or fails the compile naming what blocked the fold. */
    /** Whether `.runStatic` reports its folded SQL at the call site. Enabled by default; disable with
      * `-Dkyo.sql.static.log=false` on the compiler's JVM.
      */
    private lazy val staticLogEnabled: Boolean =
        java.lang.System.getProperty("kyo.sql.static.log", "true").equalsIgnoreCase("true")

    def impl(q: Expr[Sql.Executable[?]])(using Quotes): Expr[Sql.Rendered] =
        import quotes.reflect.*
        liftAst(q) match
            // `.get` is total here because `opportunistic = false` makes every `Absent`-producing branch inside
            // `renderLifted` abort instead: no dialects, a dialect that cannot render, and a bind divergence.
            case Present(ast) => renderLifted(ast, q, opportunistic = false).get
            case Absent       =>
                // A nested macro that already refused this statement (an INSERT cell with no Column, a fragment
                // bind with no codec) leaves its error tree inside `q`, and that residue is WHY the lift answered
                // `Absent`. The refusal is positioned and actionable on its own; adding "cannot be folded" on top
                // would send the reader hunting for a lift defect in a statement that is simply not bindable. So
                // expansion stops without a second diagnostic, which is the documented use of StopMacroExpansion.
                if containsErrorTree(q.asTerm) then throw new scala.quoted.runtime.StopMacroExpansion
                report.errorAndAbort(
                    ".runStatic: query cannot be folded at compile time. " +
                        "Ensure the query and its DSL fragments are constructed at the call site (a query stored in a `val` reaches " +
                        "this macro as a variable reference, without the construction behind it). " +
                        "Use .run for opportunistic static folding with runtime fallback, or .runDynamic to skip static folding entirely.",
                    q.asTerm.pos
                )
        end match
    end impl

    /** True when `root` contains a subtree the compiler has already marked erroneous, meaning an error for this
      * statement is already reported at a more precise position.
      *
      * The quotes API exposes no direct erroneous-type test, so the probe is indirect: rendering the type of an
      * error residue surfaces the reflect layer's unrepresentable-type marker instead of a source form. The check
      * fails safe in both directions it can miss: a false negative only means the generic fold diagnostic is
      * reported alongside the precise one, and it can never suppress the compile error itself, because the caller
      * still aborts expansion either way.
      */
    private def containsErrorTree(using Quotes)(root: quotes.reflect.Term): Boolean =
        import quotes.reflect.*
        var found = false
        val walker = new TreeAccumulator[Unit]:
            def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
                if !found then
                    tree match
                        case t: Term =>
                            val shown =
                                try t.tpe.dealias.show
                                catch case NonFatal(_) => ""
                            if shown.contains("does not have a corresponding reflect extractor") then found = true
                        case _ => ()
                    end match
                    if !found then foldOverTree(u, tree)(owner)
        walker.foldTree((), root)(Symbol.spliceOwner)
        found
    end containsErrorTree

    /** Same as [[impl]] but answers `Absent` instead of failing the compile, for `.run`'s opportunistic path.
      *
      * `Absent` covers all three conditions under which `.run` renders at run time instead: the AST does not lift, the compile classpath
      * carries no dialect at all, and a dialect that is present cannot express the statement. The first two are properties of the build and
      * the third of the statement, and none is an error here because `.runDynamic` renders the same statement against the live client.
      */
    def tryImpl(q: Expr[Sql.Executable[?]])(using Quotes): Maybe[Expr[Sql.Rendered]] =
        liftAst(q).flatMap(ast => renderLifted(ast, q, opportunistic = true))

    /** Single FromExpr-driven lift of the full Executable AST. Answers `Absent` when the AST cannot be lifted (dynamic schema, non-inline
      * record fragment, etc.). Imports the column-projection / Record givens that the derivation needs.
      */
    private def liftAst(q: Expr[Sql.Executable[?]])(using Quotes): Maybe[Sql.Executable[?]] =
        import kyo.internal.macros.BindFromExpr.given
        import kyo.internal.macros.ColumnFromExpr.given
        import kyo.internal.macros.RecordFromExpr.given
        given scala.quoted.FromExpr[Sql.Executable[?]] = kyo.FromExpr.derived
        // `Expr.value` is the Scala macro API's own reading and answers in Option; this is the boundary where
        // that becomes the module's own absence type.
        Maybe.fromOption(q.value)
    end liftAst

    /** Renders a pre-lifted AST for every classpath dialect and lifts the result back to an `Expr[Sql.Rendered]`.
      *
      * `posExpr` anchors every diagnostic at the call site. `opportunistic` selects `.run`'s behaviour over `.runStatic`'s: a classpath with
      * no dialect, or a statement a dialect cannot express, answers `Absent` for the caller to render at run time instead of failing the
      * compile.
      */
    private def renderLifted(
        ast: Sql.Executable[?],
        posExpr: Expr[?],
        opportunistic: Boolean
    )(using Quotes): Maybe[Expr[Sql.Rendered]] =
        import quotes.reflect.*

        val dialects = loadDialects(posExpr)
        if dialects.isEmpty then
            if opportunistic then Absent
            else
                report.errorAndAbort(
                    ".runStatic: no kyo-sql backend on the compile classpath. " +
                        "Add the kyo-sql backend artifact for the engine you are targeting.",
                    posExpr.asTerm.pos
                )
        else
            val rendered =
                dialects.foldLeft(Maybe(List.empty[(Idiom, Idiom.Rendering)])) { (acc, dialect) =>
                    acc.flatMap(done => renderOne(ast, dialect, posExpr, opportunistic).map(r => done :+ (dialect -> r)))
                }
            // `match` rather than `Maybe.map`: a quote that is the tail of a lambda passed to a cross-file `inline`
            // parameter crashes dotty in `pickleQuotes`.
            rendered match
                case Absent => Absent
                case Present(rs) =>
                    if !bindsAgree(rs, posExpr, opportunistic) then Absent
                    else
                        if !opportunistic && staticLogEnabled then
                            // The receipt: .runStatic demanded a compile-time render, so the text it produced is
                            // reported at the call site, one line per dialect the classpath claims. Info rather
                            // than warning, which -Werror would fail the build on.
                            report.info(
                                rs.map((dialect, r) => s"static SQL [${dialect.id.value}]: ${r.sql}").mkString("\n"),
                                posExpr.asTerm.pos
                            )
                        end if
                        val entries = rs.map { (dialect, r) =>
                            val id    = Expr(dialect.id.value)
                            val sql   = Expr(r.sql)
                            val major = Expr(r.version.major)
                            val minor = Expr(r.version.minor)
                            val patch = Expr(r.version.patch)
                            '{ (Idiom.Id($id), Sql.Rendered.Dialected($sql, Idiom.ServerVersion($major, $minor, $patch))) }
                        }
                        // Every dialect agreed on the binds, checked just above, so any one of them supplies the chunk.
                        val params = liftParams(rs.head._2)
                        Present('{ Sql.Rendered(Map(${ Varargs(entries) }*), $params) })
            end match
        end if
    end renderLifted

    /** Renders `ast` in one dialect during expansion, or reports that the dialect cannot express it.
      *
      * `Idiom.renderRaw` is a pure function, safe to call while compiling. It raises for a construct the flavor cannot express, which is a
      * fallback condition for `.run` and a compile error for `.runStatic` naming both the dialect and the construct.
      */
    private def renderOne(
        ast: Sql.Executable[?],
        dialect: Idiom,
        posExpr: Expr[?],
        opportunistic: Boolean
    )(using Quotes): Maybe[Idiom.Rendering] =
        import quotes.reflect.*
        try Present(dialect.renderRaw(ast, Absent, Frame.internal))
        catch
            case e: kyo.SqlException =>
                if opportunistic then Absent
                else
                    report.errorAndAbort(
                        s".runStatic: ${dialect.id.value} cannot render this statement: ${e.getMessage}. " +
                            "Use .run for opportunistic static folding with runtime fallback, or .runDynamic to render " +
                            "against the connected server instead.",
                        posExpr.asTerm.pos
                    )
        end try
    end renderOne

    /** The dialects of every backend named by a `META-INF/services/kyo.db.Backend` resource that this macro can both resolve on the compile
      * classpath AND instantiate on its own classloader. Backends it can resolve but not instantiate are EXCLUDED from the compile-time set
      * rather than failing the compile (see below); genuinely missing or broken backends still fail loudly.
      *
      * Three outcomes per entry, checking three different things:
      *   - Symbol-table lookup asks the compiler whether the named class is on the compile classpath. A services entry naming a class the
      *     build does not carry (or that carries no constructor) fails HERE, loudly, rather than at run time.
      *   - Reflection then instantiates the backend the render needs, which is why a backend ships a public zero-argument constructor.
      *   - A `ClassNotFoundException` at the reflective step, for a symbol that DID resolve above, is neither "missing" nor "broken": the
      *     class is in the compilation but its JVM bytecode is not reachable by this macro's JVM classloader. That is the normal state for a
      *     backend compiled to a non-JVM target (JS / Native / Wasm), a test backend defined in the compilation now running, or a JVM class
      *     an incremental recompile has invalidated. Such a backend cannot contribute a compile-time dialect, so it is EXCLUDED and left to
      *     runtime backend discovery, which loads it on its own platform. The real published backends, whose JVM jars ARE on the macro
      *     classpath, load here normally, so the static dialect set stays correct on every platform. Excluding rather than aborting is what
      *     lets `.run` (and the whole SQL test tree) compile on JS / Native / Wasm and across incremental rebuilds. Every OTHER reflective
      *     failure (a `LinkageError`, a non-`ClassNotFound` `ReflectiveOperationException`, a `ClassCastException` for an entry that names a
      *     non-`Backend`) still means a broken artifact and fails the compile.
      *
      * The symbol lookup takes two shapes for an unresolvable name because `Symbol.requiredClass` either raises or hands back a stub standing
      * in for the missing class; `NoSymbol` is neither. Both are normalized to `noSymbol` below, the stub through its declarations, which are
      * empty, so it has no constructor for the reflective step to call either.
      */
    private def loadDialects(posExpr: Expr[?])(using Quotes): List[Idiom] =
        import quotes.reflect.*
        backendNames().flatMap { fqn =>
            val cls =
                try
                    val sym = Symbol.requiredClass(fqn)
                    if sym.primaryConstructor.exists then sym else Symbol.noSymbol
                catch case ex: Throwable if NonFatal(ex) => Symbol.noSymbol
            if !cls.exists then
                report.errorAndAbort(
                    s"$servicesPath names '$fqn', which this compilation cannot resolve to a class it can construct. " +
                        "The backend artifact declaring it is either missing from the compile classpath or was published with a " +
                        "different class name.",
                    posExpr.asTerm.pos
                )
            end if
            try
                val instance = Class.forName(fqn, true, this.getClass.getClassLoader).getDeclaredConstructor().newInstance()
                // Erasure boundary: reflection hands back an untyped instance, and the services file's contract is that
                // every entry names a kyo.db.Backend, whose dialect this render uses. An entry that names something else
                // fails in the catch below.
                Some(instance.asInstanceOf[kyo.db.Backend].dialect)
            catch
                case _: ClassNotFoundException =>
                    // Resolved as a symbol above but not instantiable on this macro's classloader: a backend compiled
                    // for a non-JVM target, a backend defined in the compilation now running, or a JVM class an
                    // incremental recompile invalidated. It contributes no compile-time dialect and is left to runtime
                    // discovery (see the scaladoc). Surfaced once per backend (info, not warning, which -Werror would
                    // fail the build on) so a downstream author whose own backend stops folding statically sees why.
                    if excludedFromStaticFold.add(fqn) then
                        report.info(
                            s"static SQL: backend '$fqn' is on the compile classpath but not instantiable by the " +
                                "compile-time renderer here (a backend compiled for JS/Native/Wasm, defined in the current " +
                                "compilation, or invalidated by an incremental rebuild). Its dialect is excluded from " +
                                "compile-time static folding; queries targeting it render at runtime instead. Published JVM " +
                                "backends are unaffected.",
                            posExpr.asTerm.pos
                        )
                    end if
                    None
                case e: LinkageError =>
                    report.errorAndAbort(dialectLoadFailure(fqn, e), posExpr.asTerm.pos)
                case e: ReflectiveOperationException =>
                    report.errorAndAbort(dialectLoadFailure(fqn, e), posExpr.asTerm.pos)
                case e: ClassCastException =>
                    report.errorAndAbort(dialectLoadFailure(fqn, e), posExpr.asTerm.pos)
            end try
        }
    end loadDialects

    private def dialectLoadFailure(fqn: String, cause: Throwable): String =
        s"$servicesPath names '$fqn', which could not be instantiated as a kyo.db.Backend: $cause. " +
            "A backend needs a public zero-argument constructor and must extend kyo.db.Backend; its dialect is what this render uses."

    /** The backend class names declared across every services resource, in classpath order and without repeats.
      *
      * Follows the `ServiceLoader` file convention: one class name per line, `#` starts a comment, blank lines are ignored.
      */
    private def backendNames(): List[String] =
        val resources = this.getClass.getClassLoader.getResources(servicesPath)
        val names     = List.newBuilder[String]
        while resources.hasMoreElements do
            val stream = resources.nextElement().openStream()
            try
                val text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                text.linesIterator.foreach { line =>
                    val name = line.takeWhile(_ != '#').trim
                    if name.nonEmpty then names += name
                }
            finally stream.close()
            end try
        end while
        names.result().distinct
    end backendNames

    /** True when every dialect turned the AST into the same bind list, position by position.
      *
      * The binds are the one part of a render that must not vary by flavor: the emitted call site carries a single params chunk and hands it
      * to whichever dialect the client turns out to speak. A count mismatch means one renderer bound a value another did not; a schema
      * mismatch means the two bound different things at the same position. Either way the chunk is wrong for at least one flavor.
      *
      * `opportunistic` decides what that costs the caller, and it is the same split the rest of this file uses. Under `.runStatic` a
      * divergence is a compile error naming both dialects, because the caller asked for a guarantee this render cannot give. Under `.run` it
      * answers `false` and the statement renders against the live server instead, which is always correct and never worse than the fold.
      * Aborting there would turn a renderer disagreement the caller has no control over into a failed build.
      */
    private def bindsAgree(
        rendered: List[(Idiom, Idiom.Rendering)],
        posExpr: Expr[?],
        opportunistic: Boolean
    )(using Quotes): Boolean =
        import quotes.reflect.*
        val (headDialect, head) = rendered.head

        def disagree(message: String): Boolean =
            if opportunistic then false
            else report.errorAndAbort(s"static SQL render: $message", posExpr.asTerm.pos)

        rendered.tail.forall { (dialect, r) =>
            if r.params.size != head.params.size then
                disagree(
                    s"dialect divergence, ${headDialect.id.value} produced ${head.params.size} binds, " +
                        s"${dialect.id.value} produced ${r.params.size}"
                )
            else
                r.params.zip(head.params).zipWithIndex.forall { case ((mine, theirs), position) =>
                    // Reference identity, not the tag: two distinct schemas can share a tag deliberately (`SqlSchema[Chunk[Int]]` and
                    // `SqlSchema[Chunk[String]]` both carry `Chunk`'s erased class), so a tag comparison would call them equal and let one
                    // flavor's schema bind the other flavor's value.
                    if kyo.internal.SqlColumns.eqRef(mine.schema, theirs.schema) then true
                    else
                        disagree(
                            s"dialect divergence at bind ${position + 1}, " +
                                s"${headDialect.id.value} bound ${theirs.typeName} and " +
                                s"${dialect.id.value} bound ${mine.typeName}"
                        )
                }
        }
    end bindsAgree

    /** Rebuilds the rendered params as an `Expr[Chunk[BoundValue[?]]]` out of the call site's own bind expressions.
      *
      * Every bind in a lifted AST arrives as a `BindFromExpr.BindHole` holding the `Expr` the user wrote, so nothing here reconstructs a
      * value: it splices the held expression and the held column-codec reference back into the emitted code, at the same call site where they
      * were in scope. That is what lets a query with a runtime bind fold statically at all, with no value re-lifting involved.
      */
    private def liftParams(rendered: Idiom.Rendering)(using Quotes): Expr[Chunk[BoundValue[?]]] =
        import quotes.reflect.*
        val exprs: List[Expr[BoundValue[?]]] = rendered.params.toList.map { bv =>
            bv.value match
                case hole: BindFromExpr.BindHole =>
                    // Derive the bound type from the SCHEMA's type parameter, never from the value's own type:
                    // a value such as `Maybe.Absent` has a runtime-narrow static type that widens to its own
                    // singleton, which mismatches the field's `SqlSchema[Maybe[Int]]` (SqlSchema is invariant)
                    // and fails the cast. The bind's schema always carries the field's declared type, and the
                    // value conforms to it, so the schema is the authoritative source for `t`.
                    hole.schemaExpr.asTerm.tpe.widen.asType match
                        case '[SqlSchema.Column[t]] =>
                            val valueExpr  = hole.valueExpr.asExprOf[t]
                            val schemaExpr = hole.schemaExpr.asExprOf[SqlSchema.Column[t]]
                            val typeName   = Expr(Type.show[t])
                            '{ BoundValue[t]($valueExpr, $schemaExpr, $typeName) }.asExprOf[BoundValue[?]]
                        case _ =>
                            report.errorAndAbort(
                                s"static SQL render: bind codec is not an SqlSchema.Column[_] (${hole.schemaExpr.asTerm.tpe.show}). " +
                                    "This is a kyo-sql bug: every bind codec should lift as an SqlSchema.Column."
                            )
                case other =>
                    // Unreachable while every bind carrier lifts through BindFromExpr. Loud rather than silent, because the
                    // alternative is emitting a constant that the call site never wrote.
                    report.errorAndAbort(
                        s"static SQL render: bind at this position has no call-site expression behind it ($other). " +
                            "This is a kyo-sql bug: every bind should lift as a BindFromExpr.BindHole."
                    )
            end match
        }
        '{ Chunk.from(${ Expr.ofList(exprs) }) }
    end liftParams

end SqlStaticMacro
