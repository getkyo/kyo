package kyo.ffi.codegen

import kyo.ffi.codegen.model.CallbackKind
import kyo.ffi.codegen.model.ConfigSpec
import kyo.ffi.codegen.model.EnumSpec
import kyo.ffi.codegen.model.MethodSpec
import kyo.ffi.codegen.model.ParamSpec
import kyo.ffi.codegen.model.ReturnShape
import kyo.ffi.codegen.model.StructField
import kyo.ffi.codegen.model.StructSpec
import kyo.ffi.codegen.model.TraitSpec
import kyo.ffi.codegen.model as fm
import scala.quoted.Quotes
import scala.tasty.inspector.*

/** Tasty inspector that visits every `ClassDef`, filters those extending `kyo.ffi.Ffi`, and builds [[kyo.ffi.codegen.model.TraitSpec]]s by
  * walking method signatures.
  *
  * The `quotes.reflect` scope defines its own `TypeRef`; we refer to the model type via the `fm` alias to avoid shadowing.
  */
final private[codegen] class FfiInspector(collector: TastyExtractor) extends Inspector:

    def inspect(using quotes: Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
        val ctx = new Context[quotes.type](using quotes)
        for tasty <- tastys do
            ctx.visit(tasty.path, tasty.ast)
    end inspect

    /** Extraction context bound to a single `Quotes` instance. Holds reused symbol handles and the `StructSpec`s accumulated while walking
      * a trait.
      */
    final private class Context[Q <: Quotes & Singleton](using val quotes: Q):
        import quotes.reflect.*
        // Inside this scope, quotes.reflect.TypeRef shadows the model TypeRef.
        // Use `fm.TypeRef` everywhere we want the model type.

        private val ffiSym: Symbol      = Symbol.requiredClass("kyo.ffi.Ffi")
        private val guardSym: Symbol    = Symbol.requiredClass("kyo.ffi.Ffi.Guard")
        private val bufferSym: Symbol   = Symbol.requiredClass("kyo.ffi.Buffer")
        private val configSym: Symbol   = Symbol.requiredClass("kyo.ffi.Ffi.Config")
        private val blockingSym: Symbol = Symbol.requiredClass("kyo.ffi.Ffi.blocking")
        private val byValueSym: Symbol  = Symbol.requiredClass("kyo.ffi.Ffi.byValue")

        // StructSpecs collected during a single trait walk, keyed by FQCN.
        private var structAccum: scala.collection.mutable.LinkedHashMap[String, StructSpec] =
            scala.collection.mutable.LinkedHashMap.empty
        // EnumSpecs collected during a single trait walk, keyed by FQCN.
        private var enumAccum: scala.collection.mutable.LinkedHashMap[String, EnumSpec] =
            scala.collection.mutable.LinkedHashMap.empty
        // Symbols currently being extracted, used to detect struct self-recursion.
        private var structInFlight: scala.collection.mutable.Set[String] =
            scala.collection.mutable.Set.empty
        // Packed-struct FQCNs or simple names from companion config.
        private var packedNames: Set[String] = Set.empty

        def visit(path: String, tree: Tree): Unit = tree match
            case pc: PackageClause =>
                pc.stats.foreach(visit(path, _))
            case cd: ClassDef if cd.symbol.flags.is(Flags.Trait) && extendsFfi(cd.symbol) =>
                extractTraitSpec(path, cd) match
                    case Right(spec) => collector.addResult(spec)
                    case Left(err)   => collector.addError(err)
                ()
            case cd: ClassDef =>
                cd.body.foreach(visit(path, _))
            case _ =>
                ()

        private def extendsFfi(sym: Symbol): Boolean =
            !sameSymbol(sym, ffiSym) && sym.typeRef.baseClasses.exists(sameSymbol(_, ffiSym))

        // Symbol equality is not CanEqual, compare via reference hash and `==` fallback via AnyRef.
        private def sameSymbol(a: Symbol, b: Symbol): Boolean =
            (a: AnyRef) eq (b: AnyRef)

        private def extractTraitSpec(path: String, cd: ClassDef): Either[ExtractorError, TraitSpec] =
            structAccum = scala.collection.mutable.LinkedHashMap.empty
            enumAccum = scala.collection.mutable.LinkedHashMap.empty
            structInFlight = scala.collection.mutable.Set.empty
            val traitSym   = cd.symbol
            val fqcn       = traitSym.fullName
            val simpleName = traitSym.name
            val packageName =
                val owner = traitSym.maybeOwner
                if !sameSymbol(owner, Symbol.noSymbol) && owner.isPackageDef then owner.fullName
                else fqcn.stripSuffix("." + simpleName)
            end packageName

            val companion = findCompanion(traitSym)
            val configOpt = companion.flatMap(extractConfig(path, _, simpleName).toOption)
            packedNames = configOpt.map(_.packedStructs).getOrElse(Set.empty)
            val library = configOpt.flatMap(c => Option(c.library).filter(_.nonEmpty)).getOrElse(defaultLibrary(simpleName))

            val methodEithers = abstractMethodDefs(cd).map(extractMethodSpec(path, _, configOpt, traitSym))
            val errs          = methodEithers.collect { case Left(e) => e }
            if errs.nonEmpty then
                errs.foreach(collector.addError)
                Left(errs.head)
            else
                val methods       = methodEithers.collect { case Right(m) => m }
                val structs       = structAccum.values.toList
                val enumSpecs     = enumAccum.values.toList
                val headers       = configOpt.map(_.headers).getOrElse(Seq.empty)
                val nativeBundled = configOpt.exists(_.nativeBundled)
                Right(TraitSpec(
                    fqcn = fqcn,
                    simpleName = simpleName,
                    packageName = packageName,
                    library = library,
                    methods = methods,
                    structs = structs,
                    companion = configOpt,
                    headers = headers,
                    enums = enumSpecs,
                    nativeBundled = nativeBundled
                ))
            end if
        end extractTraitSpec

        private def findCompanion(traitSym: Symbol): Option[Symbol] =
            val c = traitSym.companionModule
            if sameSymbol(c, Symbol.noSymbol) then None else Some(c)

        private def extractConfig(path: String, companion: Symbol, traitSimple: String): Either[ExtractorError, ConfigSpec] =
            val extendsConfig = companion.moduleClass.typeRef.baseClasses.exists(sameSymbol(_, configSym))
            if !extendsConfig then
                Left(ExtractorError(path, 0, s"companion of $traitSimple does not extend Ffi.Config"))
            else
                val companionClass = companion.moduleClass
                val parentArgs     = extractParentArgs(companionClass)

                for
                    library        <- readConfigField(parentArgs, "library", extractStringLit, "", path)
                    symbolPrefix   <- readConfigField(parentArgs, "symbolPrefix", extractStringLit, "", path)
                    symbols        <- readConfigField(parentArgs, "symbols", extractStringMap, Map.empty[String, String], path)
                    packed         <- readConfigField(parentArgs, "packedStructs", extractStringSet, Set.empty[String], path)
                    scratchSize    <- readConfigOptField(parentArgs, "scratchSize", extractIntSome, path)
                    checkedBorrows <- readConfigField(parentArgs, "checkedBorrows", extractBooleanLit, false, path)
                    headers        <- readConfigField(parentArgs, "headers", extractStringSeq, Seq.empty[String], path)
                    nativeBundled  <- readConfigField(parentArgs, "nativeBundled", extractBooleanLit, false, path)
                yield ConfigSpec(
                    library = library,
                    symbolPrefix = symbolPrefix,
                    symbols = symbols,
                    packedStructs = packed,
                    scratchSize = scratchSize,
                    checkedBorrows = checkedBorrows,
                    headers = headers,
                    nativeBundled = nativeBundled
                )
                end for
            end if
        end extractConfig

        /** Extract parent constructor arguments from a companion object's ClassDef.
          *
          * TASTy represents `object Foo extends Config(library = "bar")` with parents like:
          * {{{
          * Apply(Select(New(TypeIdent("Config")), "<init>"), List(NamedArg("library", Literal("bar"))))
          * }}}
          *
          * May also be nested: `Apply(Apply(Select(New(...), "<init>"), positionalArgs), namedArgs)`. This method collects all arguments
          * from potentially nested Apply nodes and maps them by name (named args) or by position (positional args).
          */
        private def extractParentArgs(companionClass: Symbol): Map[String, Tree] =
            val configParamNames =
                Seq("library", "symbolPrefix", "symbols", "packedStructs", "scratchSize", "checkedBorrows", "headers", "nativeBundled")
            val classDef = companionClass.tree.asInstanceOf[ClassDef]

            // Collect all args from (potentially nested) Apply nodes.
            def collectApplyArgs(tree: Tree): List[Tree] = tree match
                case Apply(inner @ Apply(_, _), args) => collectApplyArgs(inner) ++ args
                case Apply(_, args)                   => args
                case _                                => Nil

            // Strip transparent TASTy wrappers (Inlined, Typed, trivial Block) that the
            // compiler may insert depending on compilation mode (test fixture vs sbt vs
            // incremental). Leaves the first structurally significant node (Apply, Block
            // with lifted locals, TypeApply, etc.) intact.
            def unwrap(tree: Tree): Tree = tree match
                case Inlined(_, Nil, inner)      => unwrap(inner)
                case Inlined(_, bindings, inner) =>
                    // Inlined with non-empty bindings: the bindings may contain lifted
                    // locals, so re-wrap as a Block so the downstream match sees them.
                    unwrap(Block(bindings, inner))
                case Typed(inner, _)   => unwrap(inner)
                case Block(Nil, inner) => unwrap(inner)
                case _                 => tree

            // When args are non-trivial, Scala 3 TASTy lifts them into a Block:
            //   Block(List(ValDef(headers$1, ..., Seq.apply(...))), Apply(..., Ident(headers$1)))
            // Build a lookup for these lifted locals so we can resolve Ident references back to the original RHS.
            val (parentApply, liftedLocals) = classDef.parents.map(unwrap).collectFirst {
                case apply: Apply               => (apply, Map.empty[String, Tree])
                case TypeApply(apply: Apply, _) => (apply, Map.empty[String, Tree])
                case Block(stats, apply: Apply) =>
                    val locals = stats.collect {
                        case vd: ValDef => vd.name -> vd.rhs.getOrElse(vd)
                    }.toMap
                    (apply, locals)
                case Block(stats, TypeApply(apply: Apply, _)) =>
                    val locals = stats.collect {
                        case vd: ValDef => vd.name -> vd.rhs.getOrElse(vd)
                    }.toMap
                    (apply, locals)
            }.unzip match
                case (Some(app), Some(locals)) => (Some(app), locals)
                case _                         => (None, Map.empty[String, Tree])

            // Resolve an argument tree: if it's an Ident referencing a lifted local, return the local's RHS.
            def resolveArg(tree: Tree): Tree = tree match
                case Ident(name) => liftedLocals.getOrElse(name, tree)
                case _           => tree

            parentApply match
                case Some(applyTree) =>
                    val allArgs = collectApplyArgs(applyTree)
                    allArgs.zipWithIndex.flatMap {
                        case (NamedArg(name, value), _) =>
                            val resolved = resolveArg(value)
                            // Skip compiler-inserted default arg references.
                            if isDefaultArgRef(resolved) then None
                            else Some(name -> resolved)
                        case (value, idx) if idx < configParamNames.length =>
                            val resolved = resolveArg(value)
                            // Skip compiler-inserted default arg references.
                            if isDefaultArgRef(resolved) then None
                            else Some(configParamNames(idx) -> resolved)
                        case _ => None
                    }.toMap
                case _ => Map.empty
            end match
        end extractParentArgs

        /** Detect compiler-generated default argument references like `Config.$lessinit$greater$default$2`. These are `Select` nodes
          * referencing `$lessinit$greater$default$N` members, they represent "use the constructor default", not user-supplied values.
          */
        private def isDefaultArgRef(tree: Tree): Boolean = tree match
            case Select(_, name) => name.contains("$default$")
            case _               => false

        /** Read a config field from parent constructor args. If the field is present but the extraction function cannot parse it as a
          * compile-time literal, fail the build with a clear error naming the field. If the field is absent, use the default.
          */
        private def readConfigField[T](
            parentArgs: Map[String, Tree],
            fieldName: String,
            extract: Tree => Option[T],
            default: T,
            path: String
        ): Either[ExtractorError, T] =
            parentArgs.get(fieldName) match
                case None => Right(default)
                case Some(tree) =>
                    extract(tree) match
                        case Some(value) => Right(value)
                        case None =>
                            Left(ExtractorError(
                                path,
                                0,
                                s"Ffi.Config.$fieldName must be a compile-time literal. " +
                                    s"Dynamic expressions cannot be read at build time by the code generator."
                            ))
                    end match
        end readConfigField

        /** Read a `Maybe[Int]` config field (for scratchSize). If present, the tree must be a `Present(n)` / `Maybe(n)` literal or `Absent`.
          * Returns `Right(None)` when absent or `Absent`; `Right(Some(n))` when a literal `Present(n)` is found; `Left(error)` when a
          * non-literal is detected.
          */
        private def readConfigOptField(
            parentArgs: Map[String, Tree],
            fieldName: String,
            extract: Tree => Option[Int],
            path: String
        ): Either[ExtractorError, Option[Int]] =
            parentArgs.get(fieldName) match
                case None       => Right(None)
                case Some(tree) =>
                    // Check if tree is the `Absent` literal first.
                    if isAbsentLiteral(tree) then Right(None)
                    else
                        extract(tree) match
                            case Some(value) => Right(Some(value))
                            case None =>
                                Left(ExtractorError(
                                    path,
                                    0,
                                    s"Ffi.Config.$fieldName must be a compile-time literal. " +
                                        s"Dynamic expressions cannot be read at build time by the code generator."
                                ))
        end readConfigOptField

        /** Check if a tree represents the `Maybe.Absent` literal. */
        private def isAbsentLiteral(t: Tree): Boolean = t match
            case Inlined(_, _, inner) => isAbsentLiteral(inner)
            case Typed(inner, _)      => isAbsentLiteral(inner)
            case Block(Nil, inner)    => isAbsentLiteral(inner)
            case Ident("Absent")      => true
            case Select(_, "Absent")  => true
            case _                    => false

        private def extractStringSeq(t: Tree): Option[Seq[String]] = t match
            case Inlined(_, _, inner) => extractStringSeq(inner)
            case Typed(inner, _)      => extractStringSeq(inner)
            case Block(Nil, inner)    => extractStringSeq(inner)
            case Repeated(elems, _) =>
                val lits = elems.flatMap(extractStringLit)
                if lits.length == elems.length then Some(lits.toSeq) else None
            case Apply(_, args) =>
                val unwrapped = args.flatMap {
                    case Typed(Repeated(elems, _), _) => elems
                    case Repeated(elems, _)           => elems
                    case other                        => List(other)
                }
                val lits = unwrapped.flatMap(extractStringLit)
                if lits.length == unwrapped.length then Some(lits.toSeq) else None
            case _ => None

        private def extractBooleanLit(t: Tree): Option[Boolean] = t match
            case Literal(BooleanConstant(b)) => Some(b)
            case Inlined(_, _, inner)        => extractBooleanLit(inner)
            case Typed(inner, _)             => extractBooleanLit(inner)
            case Block(Nil, inner)           => extractBooleanLit(inner)
            case _                           => None

        private def extractIntSome(t: Tree): Option[Int] = t match
            case Inlined(_, _, inner)  => extractIntSome(inner)
            case Typed(inner, _)       => extractIntSome(inner)
            case Block(Nil, inner)     => extractIntSome(inner)
            case Apply(_, List(inner)) => extractIntLit(inner)
            case _                     => None

        private def extractIntLit(t: Tree): Option[Int] = t match
            case Literal(IntConstant(i)) => Some(i)
            case Inlined(_, _, inner)    => extractIntLit(inner)
            case Typed(inner, _)         => extractIntLit(inner)
            case Block(Nil, inner)       => extractIntLit(inner)
            // Handle `Some(1024 * 128)`, constant-fold trivial multiplications of two Int literals.
            case Apply(Select(Literal(IntConstant(a)), op), List(Literal(IntConstant(b)))) if op == "*" =>
                Some(a * b)
            case Apply(Select(Literal(IntConstant(a)), op), List(Literal(IntConstant(b)))) if op == "+" =>
                Some(a + b)
            case Apply(Select(Literal(IntConstant(a)), op), List(Literal(IntConstant(b)))) if op == "-" =>
                Some(a - b)
            case _ => None

        private def extractStringLit(t: Tree): Option[String] = t match
            case Literal(StringConstant(s)) => Some(s)
            case Inlined(_, _, inner)       => extractStringLit(inner)
            case Typed(inner, _)            => extractStringLit(inner)
            case Block(Nil, inner)          => extractStringLit(inner)
            case _                          => None

        private def extractStringMap(t: Tree): Option[Map[String, String]] = t match
            case Inlined(_, _, inner) => extractStringMap(inner)
            case Typed(inner, _)      => extractStringMap(inner)
            case Block(Nil, inner)    => extractStringMap(inner)
            case Apply(_, args)       =>
                // Unwrap varargs: `Map.apply[K,V](pairs*)` may arrive as
                // `Apply(_, List(Typed(Repeated(elems), _*)))` in sbt-compiled TASTy.
                val unwrapped = args.flatMap {
                    case Typed(Repeated(elems, _), _) => elems
                    case Repeated(elems, _)           => elems
                    case other                        => List(other)
                }
                val pairs = unwrapped.flatMap(extractStringPair)
                if pairs.length == unwrapped.length then Some(pairs.toMap) else None
            case _ => None

        /** Extract a `(String, String)` map entry from either of the two source forms Scala 3 desugars distinctly:
          *
          *   - tuple literal `("a", "b")` -> `Tuple2.apply("a", "b")` -> `Apply(_, List("a", "b"))`, both string literals are the two
          *     positional args of one `Apply`.
          *   - arrow `"a" -> "b"` -> `ArrowAssoc("a").->("b")` ->
          *     `Apply(Select(Apply(_, List("a")), "->"), List("b"))`, the key is the single arg of the `ArrowAssoc(...)` qualifier of the
          *     `->` select and the value is the single arg of the outer apply.
          *
          * `TypeApply` wrappers (the inferred type args on `apply` / `ArrowAssoc` / `->`) are stripped before matching.
          */
        private def extractStringPair(t: Tree): Option[(String, String)] = t match
            case Inlined(_, _, inner) => extractStringPair(inner)
            case Typed(inner, _)      => extractStringPair(inner)
            case TypeApply(inner, _)  => extractStringPair(inner)
            // Arrow form `k -> v`: the `->` select's qualifier is the `ArrowAssoc(k)` application.
            case Apply(fn, List(v)) if arrowKey(fn).isDefined =>
                for ks <- arrowKey(fn); vs <- extractStringLit(v) yield ks -> vs
            // Tuple form `(k, v)`.
            case Apply(_, List(k, v)) =>
                for ks <- extractStringLit(k); vs <- extractStringLit(v) yield ks -> vs
            case _ => None

        /** If `fn` is the function side of an arrow application (`ArrowAssoc(k).->`), return the key string literal `k`. The select carrying
          * the `->` member may be wrapped in a `TypeApply` (inferred value-type arg); its qualifier is the `ArrowAssoc(k)` application
          * whose single argument is the key.
          */
        private def arrowKey(fn: Tree): Option[String] = fn match
            case TypeApply(inner, _)     => arrowKey(inner)
            case Select(qualifier, "->") => arrowAssocArg(qualifier)
            case _                       => None

        /** Extract the key literal from an `ArrowAssoc(k)` (or `Predef.ArrowAssoc(k)`) application, stripping the inferred type-arg
          * `TypeApply` wrapper. The single argument is the map key.
          */
        private def arrowAssocArg(t: Tree): Option[String] = t match
            case Inlined(_, _, inner)                                 => arrowAssocArg(inner)
            case Typed(inner, _)                                      => arrowAssocArg(inner)
            case Apply(TypeApply(fn, _), List(k)) if isArrowAssoc(fn) => extractStringLit(k)
            case Apply(fn, List(k)) if isArrowAssoc(fn)               => extractStringLit(k)
            case _                                                    => None

        /** Recognise the `ArrowAssoc` factory, whether referenced bare (`Ident("ArrowAssoc")`) or qualified
          * (`Select(_, "ArrowAssoc")`, e.g. `Predef.ArrowAssoc`).
          */
        private def isArrowAssoc(fn: Tree): Boolean = fn match
            case Ident("ArrowAssoc")     => true
            case Select(_, "ArrowAssoc") => true
            case _                       => false

        private def extractStringSet(t: Tree): Option[Set[String]] = t match
            case Inlined(_, _, inner) => extractStringSet(inner)
            case Typed(inner, _)      => extractStringSet(inner)
            case Block(Nil, inner)    => extractStringSet(inner)
            case Apply(_, args)       =>
                // Unwrap varargs: `Set.apply[String]("a", "b")` may arrive as
                // `Apply(_, List(Typed(Repeated(elems), _*)))` in sbt-compiled TASTy.
                val unwrapped = args.flatMap {
                    case Typed(Repeated(elems, _), _) => elems
                    case Repeated(elems, _)           => elems
                    case other                        => List(other)
                }
                val lits = unwrapped.flatMap(extractStringLit)
                if lits.length == unwrapped.length then Some(lits.toSet) else None
            case _ => None

        private def abstractMethodDefs(cd: ClassDef): List[DefDef] =
            cd.body.collect {
                case dd: DefDef if dd.rhs.isEmpty && !dd.symbol.flags.is(Flags.Synthetic) && !dd.symbol.flags.is(Flags.Private) => dd
            }

        private def extractMethodSpec(
            path: String,
            dd: DefDef,
            configOpt: Option[ConfigSpec],
            traitSym: Symbol
        ): Either[ExtractorError, MethodSpec] =
            val scalaName = dd.name
            val line      = lineOf(dd.pos, 0)

            // The FFI binding layer is the unsafe tier: every binding method takes a trailing `(using AllowUnsafe)`
            // clause (every native call is a side effect). That clause is a contextual (`using`) term-param clause,            // it never marshals to C, so it is excluded from `allParamVds`. `hasAllowUnsafe` records whether the
            // method declared it, used to enforce the `@Ffi.blocking` contract below. A `using` clause carries the
            // `Given` flag (not the legacy `implicit`/`Implicit` flag), so we treat a clause as contextual when it is
            // either given or implicit, and match the `kyo.AllowUnsafe` element type so an ordinary explicit param is
            // never mistaken for it.
            val (usingClauses, valueClauses) = dd.termParamss.partition(c => c.isGiven || c.isImplicit)
            val hasAllowUnsafe               = usingClauses.exists(_.params.exists(vd => isAllowUnsafeType(vd.tpt.tpe)))

            val allParamVds = valueClauses.flatMap(_.params)
            // Detect a trailing Scala varargs parameter. Scala 3 TASTy preserves the original tree
            // shape `<repeated>[A]` on the `tpt`; the resolved `tpt.tpe` dealiases to `Seq[A]`, so we
            // inspect the TREE rather than the TYPE to distinguish `A*` from an explicit `Seq[A]`
            // parameter. When present, require element type `Any` and exclude the varargs param from
            // the extracted `params` list, emitters synthesize the trailing `args: Any*` in the
            // generated method signature.
            // Scala 3 TASTy encodes a varargs parameter `A*` as `Seq[A] @scala.annotation.internal.Repeated`
            // , an `AnnotatedType` wrapping `Seq[A]` with the internal `Repeated` annotation. Match the
            // annotation by its fully-qualified symbol name so the check is independent of classloader
            // lookups; the source-form `tpt.show` rendering (`A*`) is a defensive fallback.
            def isRepeatedTpt(vd: ValDef): Boolean =
                vd.tpt.tpe match
                    case AnnotatedType(_, ann) =>
                        ann.tpe.typeSymbol.fullName == "scala.annotation.internal.Repeated"
                    case _ =>
                        vd.tpt.show.endsWith("*")
                end match
            end isRepeatedTpt

            val (fixedVds, varargsTail) =
                allParamVds.lastOption match
                    case Some(last) if isRepeatedTpt(last) =>
                        (allParamVds.init, Some(last))
                    case _ =>
                        (allParamVds, None)
            end val

            // When a varargs parameter exists, validate the element type is `Any`, typed varargs (e.g. `Int*`)
            // are not supported in F8b (heterogeneous C varargs require runtime-class dispatch, which the
            // emitters implement against `Any`). Emit a clear error directing users to `Any*`.
            val varargsErr: Option[ExtractorError] = varargsTail match
                case Some(vd) =>
                    // Unwrap the `@Repeated` annotation layer; the underlying is `Seq[A]`.
                    val underlying = vd.tpt.tpe match
                        case AnnotatedType(u, _) => u
                        case t                   => t
                    val elemTpeOpt = underlying match
                        case AppliedType(_, List(a)) => Some(a.dealias)
                        case _                       => None
                    elemTpeOpt match
                        case Some(t) if t =:= TypeRepr.of[Any] => None
                        case Some(t) =>
                            Some(ExtractorError(
                                path,
                                lineOf(vd.pos, line),
                                s"method '$scalaName' has a typed varargs parameter '${vd.name}: ${t.show}*'. " +
                                    "kyo-ffi variadic support requires `Any*`, heterogeneous C varargs are threaded through " +
                                    "runtime-class dispatch (Int/Long/Double/String/Buffer). Change the parameter to " +
                                    s"`${vd.name}: Any*`."
                            ))
                        case None =>
                            Some(ExtractorError(
                                path,
                                lineOf(vd.pos, line),
                                s"method '$scalaName' has an unrecognized varargs parameter shape, expected `Any*`."
                            ))
                    end match
                case None => None

            val paramEithers: List[Either[ExtractorError, ParamSpec]] = fixedVds.map { vd =>
                extractType(vd.tpt.tpe, path, lineOf(vd.pos, line)).map(t => ParamSpec(vd.name, t))
            }
            val paramErrs = paramEithers.collect { case Left(e) => e } ++ varargsErr.toList
            if paramErrs.nonEmpty then
                paramErrs.tail.foreach(collector.addError)
                Left(paramErrs.head)
            else
                val params   = paramEithers.collect { case Right(p) => p }
                val blocking = dd.symbol.hasAnnotation(blockingSym)
                val byValue  = dd.symbol.hasAnnotation(byValueSym)
                // A `@Ffi.blocking` method declares its return as `Fiber.Unsafe[WithError[A], Any]` (or
                // `Fiber.Unsafe[A, Any]`): the binding layer is the unsafe tier, so the blocking downcall is surfaced as
                // an already-completed (JVM/Native) or callback-resolved (JS) `Fiber.Unsafe`, not a bare value or a
                // `< Async` computation. Strip the `Fiber.Unsafe[_, _]` wrapper first (→ `WithError[A]`), then strip
                // `WithError` (→ `A`) so the marshalling shape is the bare inner type. A non-blocking method declares a
                // plain value (optionally `WithError[A]`) with no `Fiber.Unsafe` wrapper.
                val rawRetType = dd.returnTpt.tpe
                val isFiber    = isFiberUnsafeType(rawRetType)
                // Enforce the `@Ffi.blocking` contract: the method MUST declare `(using AllowUnsafe)` and a
                // `Fiber.Unsafe[…, Any]` return. Each native call is a side effect tracked by the caller's
                // `AllowUnsafe`, and the blocking downcall is surfaced as a fiber. A clear error names the offence.
                val blockingErr: Option[ExtractorError] =
                    if !blocking then None
                    else if !isFiber then
                        Some(ExtractorError(
                            path,
                            line,
                            s"`@Ffi.blocking` method '$scalaName' must declare a `Fiber.Unsafe[…, Any]` return type " +
                                "(the blocking downcall is surfaced as a fiber), got " +
                                s"`${rawRetType.show}`. Wrap the inner return in `Fiber.Unsafe[…, Any]`."
                        ))
                    else if !hasAllowUnsafe then
                        Some(ExtractorError(
                            path,
                            line,
                            s"`@Ffi.blocking` method '$scalaName' must declare a trailing `(using AllowUnsafe)` clause " +
                                "(every native call is a side effect tracked by the caller's `AllowUnsafe`)."
                        ))
                    else None
                blockingErr match
                    case Some(err) => Left(err)
                    case None =>
                        val fiberInner   = if isFiber then unwrapFiberUnsafeType(rawRetType) else rawRetType
                        val isWithError  = isWithErrorType(fiberInner)
                        val innerRetType = if isWithError then unwrapWithErrorType(fiberInner) else fiberInner
                        extractReturnShape(innerRetType, path, line, dd.symbol, scalaName, params, byValue).map { shape =>
                            val hasGuard = params.exists(p => p.tpe == fm.TypeRef.GuardT)
                            val hasFn    = params.exists(p => p.tpe.isInstanceOf[fm.TypeRef.FnPtrT])
                            val callback =
                                if !hasFn then CallbackKind.None
                                else if hasGuard then CallbackKind.Retained
                                else CallbackKind.Transient
                            val hasArrayP = params.exists(p => p.tpe.isInstanceOf[fm.TypeRef.ArrayT])
                            val cSymbol   = resolveCSymbol(scalaName, configOpt)
                            MethodSpec(
                                scalaName = scalaName,
                                cSymbol = cSymbol,
                                params = params,
                                returnShape = shape,
                                blocking = blocking,
                                hasArrayParam = hasArrayP,
                                callbackKind = callback,
                                hasVarargs = varargsTail.isDefined,
                                withError = isWithError
                            )
                        }
                end match
            end if
        end extractMethodSpec

        /** Default max-bytes cap for a borrowed String return, reads `-Dkyo.ffi.stringFieldMaxBytes=`, falls back to 64 KiB (the same
          * default as `Scratch.stringFieldMaxBytes`). Captured at generator invocation so tests can override at JVM-launch time.
          */
        private val borrowedStringMaxBytes: Int =
            sys.props.get("kyo.ffi.stringFieldMaxBytes")
                .flatMap(s => scala.util.Try(s.toInt).toOption)
                .getOrElse(64 * 1024)

        private def resolveCSymbol(scalaName: String, configOpt: Option[ConfigSpec]): String =
            configOpt match
                case Some(c) =>
                    c.symbols.get(scalaName) match
                        case Some(explicit) => explicit
                        case None           => c.symbolPrefix + snakeCase(scalaName)
                case None =>
                    snakeCase(scalaName)

        private def extractReturnShape(
            tpe: TypeRepr,
            path: String,
            line: Int,
            methodSym: Symbol,
            methodName: String,
            params: List[ParamSpec],
            byValue: Boolean
        ): Either[ExtractorError, ReturnShape] =
            if tpe =:= TypeRepr.of[Unit] then
                if byValue then
                    Left(ExtractorError(
                        path,
                        line,
                        s"`@Ffi.byValue` method '$methodName' returns `Unit`; a by-value return requires a case-class struct return type."
                    ))
                else Right(ReturnShape.Void)
            // `@Ffi.byValue` selects the by-value struct-return ABI: the whole returned case class is the C struct
            // returned by value (vs the default case-class return, which is a multi-value C out-param decomposition).
            // The annotated return type must therefore be a case-class struct; anything else is a misuse.
            else if byValue then extractByValueReturn(tpe, path, line, methodName)
            else
                // Check for Borrowed[A] BEFORE dealiasing, Borrowed is opaque (erases to A under dealias).
                if isBorrowedType(tpe) then
                    extractBorrowedReturn(tpe, path, line, methodName, params)
                // Check for Maybe[Handle[A]] BEFORE dealiasing, both Maybe and Handle are opaque types.
                else if isMaybeHandleType(tpe) then
                    extractMaybeHandleReturn(tpe, path, line)
                else
                    extractType(tpe, path, line).flatMap { tref =>
                        tref match
                            case fm.TypeRef.UnitT => Right(ReturnShape.Void)
                            case fm.TypeRef.StringT =>
                                Left(ExtractorError(
                                    path,
                                    line,
                                    s"method '$methodName' returns `String` as a top-level value, wrap in `Borrowed[String]` " +
                                        "to declare that the C side retains ownership of the returned memory. " +
                                        "See `Ffi.Borrowed` scaladoc for details."
                                ))
                            case fm.TypeRef.BufferT(elem) =>
                                Left(ExtractorError(
                                    path,
                                    line,
                                    s"method '$methodName' returns `Buffer[${elem}]` as a top-level value, wrap in " +
                                        "`Borrowed[Buffer[...]]` to declare that the C side retains ownership of the returned memory. " +
                                        "See `Ffi.Borrowed` scaladoc for details."
                                ))
                            case fm.TypeRef.UnionT(_) =>
                                Left(ExtractorError(
                                    path,
                                    line,
                                    s"method '$methodName' returns a union type by value. " +
                                        "Union returns are not supported, use the concrete variant type directly " +
                                        "when the active variant is known."
                                ))
                            case fm.TypeRef.StructT(fqcnName) =>
                                structAccum.get(fqcnName) match
                                    case None =>
                                        Left(ExtractorError(path, line, s"struct '$fqcnName' was not captured during extraction"))
                                    case Some(spec) =>
                                        if spec.fields.size == 1 then
                                            Left(ExtractorError(
                                                path,
                                                line,
                                                s"case-class return `${spec.simpleName}(${spec.fields.head.name}: ...)` has a single field. " +
                                                    "For a single primitive return, declare the method's return type as the primitive directly. " +
                                                    "case-class returns are reserved for multi-value (C out-param) returns."
                                            ))
                                        else if spec.fields.size >= 2 then Right(ReturnShape.MultiValue(spec))
                                        else
                                            Left(ExtractorError(
                                                path,
                                                line,
                                                s"case class '${spec.simpleName}' has no fields, cannot return a 0-field struct"
                                            ))
                                        end if
                                end match
                            case fm.TypeRef.HandleT(fqcnName) =>
                                Right(ReturnShape.HandleReturn(fqcnName))
                            case fm.TypeRef.EnumT(fqcnName) =>
                                Right(ReturnShape.EnumReturn(fqcnName))
                            case prim if fm.TypeRef.isPrimitive(prim) =>
                                Right(ReturnShape.Primitive(prim))
                            case other =>
                                Left(ExtractorError(
                                    path,
                                    line,
                                    s"return type $other is not supported (only primitives, Unit, case classes, " +
                                        "Handle[A], Maybe[Handle[A]], enum types, and Borrowed[String] / Borrowed[Buffer[A]] allowed)"
                                ))
                        end match
                    }
                end if
            end if
        end extractReturnShape

        /** Resolve the return shape for a `@Ffi.byValue` method: the C function returns its struct by value, so the whole
          * returned case class is the struct (not a multi-value out-param decomposition). The return type must be a
          * case-class struct with at least one field; unlike a multi-value return, a single field is allowed. Any
          * non-struct return (primitive, Handle, Borrowed, union, ...) is a misuse of the annotation and is rejected with
          * a clear message.
          */
        private def extractByValueReturn(
            tpe: TypeRepr,
            path: String,
            line: Int,
            methodName: String
        ): Either[ExtractorError, ReturnShape] =
            extractType(tpe, path, line).flatMap {
                case fm.TypeRef.StructT(fqcnName) =>
                    structAccum.get(fqcnName) match
                        case None =>
                            Left(ExtractorError(path, line, s"struct '$fqcnName' was not captured during extraction"))
                        case Some(spec) if spec.fields.isEmpty =>
                            Left(ExtractorError(
                                path,
                                line,
                                s"`@Ffi.byValue` method '$methodName' returns case class '${spec.simpleName}' which has no fields; " +
                                    "a by-value struct return must have at least one field."
                            ))
                        case Some(spec) =>
                            Right(ReturnShape.Struct(spec))
                case other =>
                    Left(ExtractorError(
                        path,
                        line,
                        s"`@Ffi.byValue` method '$methodName' must return a case-class struct (by value), got `${tpe.show}`. " +
                            "Remove `@Ffi.byValue` for non-struct returns, or for a multi-value (C out-param) return use a " +
                            "case class without the annotation."
                    ))
            }
        end extractByValueReturn

        /** Check if a type is `Ffi.Borrowed[A]`, an applied type whose constructor is the opaque type `kyo.ffi.Ffi.Borrowed`. Must check
          * the NON-dealiased type since Borrowed is opaque (dealiases to A). Uses fully-qualified symbol name matching since opaque types
          * cannot be resolved via `Symbol.requiredClass`.
          */
        private def isBorrowedType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, _) =>
                    val n = tc.typeSymbol.fullName
                    n == "kyo.ffi.Ffi.Borrowed" || n == "kyo.ffi.Ffi$.Borrowed"
                case _ =>
                    false

        /** Check if a type is `Ffi.WithError[A]`, a final class wrapper for errno-aware returns. Must check the NON-dealiased type to
          * catch it before the inner type is resolved. Uses fully-qualified symbol name matching.
          */
        private def isWithErrorType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, _) =>
                    val n = tc.typeSymbol.fullName
                    n == "kyo.ffi.Ffi.WithError" || n == "kyo.ffi.Ffi$.WithError"
                case _ =>
                    false

        /** Extract the inner type `A` from `WithError[A]`. Assumes [[isWithErrorType]] was already checked. */
        private def unwrapWithErrorType(tpe: TypeRepr): TypeRepr =
            tpe match
                case AppliedType(_, List(inner)) => inner
                case _                           => tpe

        /** Check if a type is `kyo.Fiber.Unsafe[A, S]`, the opaque fiber-handle type a `@Ffi.blocking` method declares as its return.
          * Match the NON-dealiased applied type by fully-qualified constructor name (the opaque type erases under dealias), the same
          * FQCN-matching style used for the opaque `Handle` / `Borrowed` / `WithError` / `Maybe` wrappers above. `Fiber` is a top-level
          * object, so the compiler wraps it in a synthetic `Fiber$package` and TASTy surfaces the constructor symbol as
          * `kyo.Fiber$package$.Fiber$.Unsafe` (module segments carry a trailing `$`). We canonicalise by stripping the synthetic
          * `$package` segment and every trailing `$` and compare against the clean `kyo.Fiber.Unsafe`, so the check is robust to which
          * `$`-mangled form surfaces.
          */
        private def isFiberUnsafeType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, List(_, _)) =>
                    canonicalFqcn(tc.typeSymbol.fullName) == "kyo.Fiber.Unsafe"
                case _ =>
                    false

        /** Strip the synthetic top-level-wrapper segment (`Fiber$package`, inserted by the compiler around top-level definitions) and the
          * trailing `$` that marks module (object) segments, yielding the clean dotted name. `kyo.Fiber$package$.Fiber$.Unsafe` →
          * `kyo.Fiber.Unsafe`.
          */
        private def canonicalFqcn(fullName: String): String =
            fullName.split('.').iterator
                .map(_.stripSuffix("$"))
                .filterNot(_.endsWith("$package"))
                .mkString(".")

        /** Extract the value side `A` from `Fiber.Unsafe[A, S]`. Assumes [[isFiberUnsafeType]] was already checked. */
        private def unwrapFiberUnsafeType(tpe: TypeRepr): TypeRepr =
            tpe match
                case AppliedType(_, List(value, _)) => value
                case _                              => tpe

        /** Check if a type is `kyo.AllowUnsafe`, the contextual evidence every binding method's trailing `(using AllowUnsafe)` clause
          * carries. Matched against the dealiased symbol's FQCN so the `(using AllowUnsafe)` clause is distinguished from an ordinary
          * explicit parameter when validating the `@Ffi.blocking` contract.
          */
        private def isAllowUnsafeType(tpe: TypeRepr): Boolean =
            tpe.dealias.typeSymbol.fullName == "kyo.AllowUnsafe"

        /** Check if a type is `Maybe[Handle[A]]`, an applied Maybe whose inner type argument is an applied Handle. Must check the
          * NON-dealiased type since both Maybe and Handle are opaque types that erase under dealias.
          *
          * In TASTy, the top-level opaque type `kyo.Maybe` is encoded as `kyo.Maybe$package.Maybe` (or the `$` variant). We match all known
          * representations.
          */
        private def isMaybeHandleType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, List(inner)) =>
                    val n = tc.typeSymbol.fullName
                    isMaybeSymbol(n) && isHandleType(inner)
                case _ =>
                    false

        private def isMaybeSymbol(fullName: String): Boolean =
            fullName == "kyo.Maybe" || fullName == "kyo.Maybe$" ||
                fullName == "kyo.Maybe$package.Maybe" || fullName == "kyo.Maybe$package$.Maybe"

        /** Extract `Maybe[Handle[A]]` return type into `HandleReturn(fqcn, nullable = true)`. */
        private def extractMaybeHandleReturn(
            tpe: TypeRepr,
            path: String,
            line: Int
        ): Either[ExtractorError, ReturnShape] =
            tpe match
                case AppliedType(_, List(handleTpe)) =>
                    handleTpe match
                        case AppliedType(_, List(arg)) =>
                            val argFqcn = arg.dealias.typeSymbol.fullName
                            Right(ReturnShape.HandleReturn(argFqcn, nullable = true))
                        case _ =>
                            Left(ExtractorError(
                                path,
                                line,
                                s"Maybe[Handle[A]] requires Handle to have a type argument, got: ${handleTpe.show}"
                            ))
                case _ =>
                    Left(ExtractorError(path, line, s"Maybe[Handle[A]] requires exactly one type argument, got: ${tpe.show}"))

        /** Extract the inner type from `Borrowed[A]` and produce the appropriate BorrowedString / BorrowedBuffer return shape. For
          * `Borrowed[Buffer[A]]`, infers the size parameter from the method's Int/Long parameters.
          */
        private def extractBorrowedReturn(
            tpe: TypeRepr,
            path: String,
            line: Int,
            methodName: String,
            params: List[ParamSpec]
        ): Either[ExtractorError, ReturnShape] =
            tpe match
                case AppliedType(_, List(innerTpe)) =>
                    // Check the inner type (before dealiasing for Handle etc.)
                    val innerDealiased = innerTpe.dealias
                    if innerDealiased =:= TypeRepr.of[String] then
                        Right(ReturnShape.BorrowedString(borrowedStringMaxBytes))
                    else
                        val innerSym = innerDealiased.typeSymbol
                        if sameSymbol(innerSym, bufferSym) then
                            // Borrowed[Buffer[A]], extract elem type and infer size param
                            typeArg(innerDealiased, 0) match
                                case Some(elemTpe) =>
                                    extractType(elemTpe, path, line).flatMap { elemRef =>
                                        if !fm.TypeRef.isPrimitive(elemRef) && !isBufferOk(elemRef) then
                                            Left(ExtractorError(path, line, s"Buffer element type $elemRef is not supported"))
                                        else
                                            inferSizeParam(params, path, line, methodName).map { sizeParam =>
                                                ReturnShape.BorrowedBuffer(elemRef, sizeParam)
                                            }
                                    }
                                case None =>
                                    Left(ExtractorError(path, line, "Buffer requires a type argument"))
                            end match
                        else
                            // Borrowed applied to something other than String or Buffer, reject
                            Left(ExtractorError(
                                path,
                                line,
                                s"method '$methodName' returns `Borrowed[${innerDealiased.show}]` which is not supported. " +
                                    "`Borrowed[A]` is only valid for `String` or `Buffer[A]` inner types."
                            ))
                        end if
                    end if
                case _ =>
                    Left(ExtractorError(path, line, s"Borrowed requires exactly one type argument, got: ${tpe.show}"))
        end extractBorrowedReturn

        /** Infer the size parameter for a `Borrowed[Buffer[A]]` return by scanning the method's parameters for Int/Long types. When exactly
          * one is found, use it. When zero or multiple are found, fail with a clear error.
          */
        private def inferSizeParam(
            params: List[ParamSpec],
            path: String,
            line: Int,
            methodName: String
        ): Either[ExtractorError, String] =
            val intLongParams = params.filter { p =>
                p.tpe == fm.TypeRef.IntT || p.tpe == fm.TypeRef.LongT
            }
            intLongParams match
                case List(single) => Right(single.name)
                case Nil =>
                    Left(ExtractorError(
                        path,
                        line,
                        s"method '$methodName' returns `Borrowed[Buffer[...]]` but has no `Int` or `Long` parameters " +
                            "to infer the element count from. Add an `Int` or `Long` parameter for the buffer size."
                    ))
                case multiple =>
                    Left(ExtractorError(
                        path,
                        line,
                        s"method '$methodName' returns `Borrowed[Buffer[...]]` but has ${multiple.size} `Int`/`Long` " +
                            s"parameters (${multiple.map(_.name).mkString(", ")}), making the size ambiguous. " +
                            "Exactly one `Int` or `Long` parameter is required for automatic size inference."
                    ))
            end match
        end inferSizeParam

        private def extractType(tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, fm.TypeRef] =
            // Check Handle[A] BEFORE dealiasing, Handle is opaque (erases to AnyRef under dealias).
            if isHandleType(tpe) then return extractHandleType(tpe, path, line)
            // Check for OrType (Scala 3 union type A | B) BEFORE dealiasing, OrType is structural.
            if isOrType(tpe) then return extractUnionType(tpe, path, line)
            val dealiased = tpe.dealias
            dealiased match
                case t if t =:= TypeRepr.of[Boolean] => Right(fm.TypeRef.BooleanT)
                case t if t =:= TypeRepr.of[Byte]    => Right(fm.TypeRef.ByteT)
                case t if t =:= TypeRepr.of[Short]   => Right(fm.TypeRef.ShortT)
                case t if t =:= TypeRepr.of[Int]     => Right(fm.TypeRef.IntT)
                case t if t =:= TypeRepr.of[Long]    => Right(fm.TypeRef.LongT)
                case t if t =:= TypeRepr.of[Float]   => Right(fm.TypeRef.FloatT)
                case t if t =:= TypeRepr.of[Double]  => Right(fm.TypeRef.DoubleT)
                case t if t =:= TypeRepr.of[Unit]    => Right(fm.TypeRef.UnitT)
                case t if t =:= TypeRepr.of[String]  => Right(fm.TypeRef.StringT)
                case _                               =>
                    // Also check dealiased for OrType (in case the non-dealiased form didn't match).
                    if isOrType(dealiased) then return extractUnionType(dealiased, path, line)
                    val sym = dealiased.typeSymbol
                    if sameSymbol(sym, guardSym) then Right(fm.TypeRef.GuardT)
                    else if sameSymbol(sym, bufferSym) then
                        typeArg(dealiased, 0) match
                            case Some(arg) =>
                                extractType(arg, path, line).flatMap { inner =>
                                    if fm.TypeRef.isPrimitive(inner) || isBufferOk(inner) then Right(fm.TypeRef.BufferT(inner))
                                    else Left(ExtractorError(path, line, s"Buffer element type $inner is not supported"))
                                }
                            case None =>
                                Left(ExtractorError(path, line, "Buffer requires a type argument"))
                        end match
                    else if isArrayType(dealiased) then
                        typeArg(dealiased, 0) match
                            case Some(arg) =>
                                extractType(arg, path, line).flatMap { inner =>
                                    if fm.TypeRef.isPrimitive(inner) then Right(fm.TypeRef.ArrayT(inner))
                                    else
                                        Left(ExtractorError(
                                            path,
                                            line,
                                            s"Array element type $inner is not supported (only primitive element types allowed)"
                                        ))
                                }
                            case None => Left(ExtractorError(path, line, "Array requires a type argument"))
                        end match
                    else if isFunctionType(dealiased) then
                        extractFunctionType(dealiased, path, line)
                    else if isScala3Enum(sym) then
                        extractEnum(sym, dealiased, path, line)
                    else if isCaseClass(sym) then
                        extractStruct(sym, dealiased, path, line).map(spec => fm.TypeRef.StructT(spec.fqcn))
                    else
                        Left(ExtractorError(
                            path,
                            line,
                            s"Unsupported FFI type `${dealiased.show}`. " +
                                "Supported types: Boolean, Byte, Short, Int, Long, Float, Double, Unit, String, " +
                                "Array[A] (for primitive A), Buffer[A], Handle[A], case classes whose fields are all supported, " +
                                "function types as callbacks, Ffi.Guard (for retained-callback methods), union types (A | B)."
                        ))
                    end if
            end match
        end extractType

        /** Check if a type is an `OrType` (Scala 3 union type `A | B`). */
        private def isOrType(tpe: TypeRepr): Boolean =
            tpe match
                case OrType(_, _) => true
                case _            => false

        /** Flatten a potentially nested OrType tree into a flat list of variant types.
          *
          * `A | B | C` is represented in TASTy as `OrType(OrType(A, B), C)`. This recursively flattens to `List(A, B, C)`.
          */
        private def flattenOrType(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case OrType(left, right) => flattenOrType(left) ++ flattenOrType(right)
                case other               => List(other)

        /** Extract a Scala 3 union type (OrType) into `UnionT(variants)`. Validates that each variant is a primitive or struct (case
          * class). Rejects String, Buffer, Handle, function types, Guard, and Array.
          */
        private def extractUnionType(tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, fm.TypeRef] =
            val variantTypes = flattenOrType(tpe)
            if variantTypes.size < 2 then
                Left(ExtractorError(path, line, s"Union type must have at least two variants, got: ${tpe.show}"))
            else
                val variantEithers = variantTypes.map { vt =>
                    extractType(vt, path, line).flatMap { ref =>
                        if isValidUnionVariant(ref) then Right(ref)
                        else
                            Left(ExtractorError(
                                path,
                                line,
                                s"Union variant type `${vt.show}` is not permitted in a union. " +
                                    "Union variants must be primitive types (Boolean, Byte, Short, Int, Long, Float, Double) " +
                                    "or case classes (structs). `String`, `Buffer[A]`, `Array[A]`, `Handle[A]`, function types, " +
                                    "and `Ffi.Guard` are rejected."
                            ))
                    }
                }
                variantEithers.collectFirst { case Left(e) => e } match
                    case Some(e) => Left(e)
                    case None =>
                        val variants = variantEithers.collect { case Right(v) => v }
                        Right(fm.TypeRef.UnionT(variants))
                end match
            end if
        end extractUnionType

        /** Return `true` when a TypeRef is allowed as a variant in a union type. Primitives and structs are accepted; String, Buffer,
          * Handle, Array, function types, and Guard are rejected.
          */
        private def isValidUnionVariant(t: fm.TypeRef): Boolean = t match
            case _ if fm.TypeRef.isPrimitive(t) => true
            case _: fm.TypeRef.StructT          => true
            case _                              => false

        /** Check if a type is `Ffi.Handle[A]`, an applied type whose constructor is the opaque type `kyo.ffi.Ffi.Handle`. Must check the
          * NON-dealiased type since Handle is opaque (dealiases to AnyRef). Uses fully-qualified symbol name matching since opaque types
          * cannot be resolved via `Symbol.requiredClass`.
          */
        private def isHandleType(tpe: TypeRepr): Boolean =
            // The type show representation for Handle[X] is "kyo.ffi.Ffi.Handle[...]"
            // We need to check the raw type before dealiasing since Handle is opaque.
            // First try AppliedType match, then fall back to string-based matching.
            tpe match
                case AppliedType(tc, _) =>
                    val n = tc.typeSymbol.fullName
                    n == "kyo.ffi.Ffi.Handle" || n == "kyo.ffi.Ffi$.Handle"
                case _ =>
                    false

        /** Extract the type argument from `Ffi.Handle[A]` and return `HandleT(A.fqcn)`. */
        private def extractHandleType(tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, fm.TypeRef] =
            tpe match
                case AppliedType(_, List(arg)) =>
                    val argFqcn = arg.dealias.typeSymbol.fullName
                    Right(fm.TypeRef.HandleT(argFqcn))
                case _ =>
                    Left(ExtractorError(path, line, s"Handle requires exactly one type argument, got: ${tpe.show}"))

        /** Check if a symbol represents a Scala 3 enum. */
        private def isScala3Enum(sym: Symbol): Boolean =
            sym.flags.is(Flags.Enum) && !sym.flags.is(Flags.Case)

        /** Extract a Scala 3 enum used in an FFI binding. Validates structurally that the enum has a `value: Int` field and a companion
          * `fromInt(Int): EnumType` method. If the type is an enum but doesn't match the pattern, the build fails with a clear error
          * explaining what's needed. Accumulates the [[EnumSpec]] and returns `EnumT(fqcn)`.
          */
        private def extractEnum(sym: Symbol, tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, fm.TypeRef] =
            val fqcn       = sym.fullName
            val simpleName = sym.name
            // Check for the `value` field of type Int.
            val valueMember = sym.fieldMember("value")
            val hasValueField =
                if sameSymbol(valueMember, Symbol.noSymbol) then false
                else tpe.memberType(valueMember) =:= TypeRepr.of[Int]
            if !hasValueField then
                Left(ExtractorError(
                    path,
                    line,
                    s"enum '${simpleName}' is used in an FFI binding but does not have a `val value: Int` field. " +
                        "FFI enums must have a `val value: Int` parameter on every case to marshal as a C int enum."
                ))
            else
                // Check for companion's `fromInt(Int): EnumType` method.
                val companion = sym.companionModule
                val hasFromInt =
                    if sameSymbol(companion, Symbol.noSymbol) then false
                    else
                        val fromIntSym = companion.moduleClass.declarations.find(s =>
                            s.name == "fromInt" && s.isDefDef
                        )
                        fromIntSym.isDefined
                if !hasFromInt then
                    Left(ExtractorError(
                        path,
                        line,
                        s"enum '${simpleName}' is used in an FFI binding but its companion is missing a " +
                            s"`def fromInt(v: Int): $simpleName` method. FFI enums require this method to " +
                            "reconstruct the enum case from a raw C int."
                    ))
                else
                    val _ = enumAccum.getOrElseUpdate(fqcn, EnumSpec(fqcn, simpleName))
                    Right(fm.TypeRef.EnumT(fqcn))
                end if
            end if
        end extractEnum

        // Buffer[A] permits primitive A and struct elements (pointer-valued, see §4.2.2).
        private def isBufferOk(t: fm.TypeRef): Boolean = t match
            case _: fm.TypeRef.StructT => true
            case _                     => false

        private def typeArg(tpe: TypeRepr, idx: Int): Option[TypeRepr] = tpe match
            case AppliedType(_, args) if idx < args.length => Some(args(idx))
            case _                                         => None

        private def isArrayType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, _) => sameSymbol(tc.typeSymbol, defn.ArrayClass)
                case _                  => false

        private def isCaseClass(sym: Symbol): Boolean =
            sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module)

        private def isFunctionType(tpe: TypeRepr): Boolean =
            tpe match
                case AppliedType(tc, _) =>
                    val n = tc.typeSymbol.fullName
                    n.startsWith("scala.Function") && n.drop("scala.Function".length).forall(_.isDigit)
                case _ => false

        private def extractFunctionType(tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, fm.TypeRef] =
            tpe match
                case AppliedType(_, args) if args.nonEmpty =>
                    val (paramTpes, retTpe) = (args.init, args.last)
                    val paramEithers        = paramTpes.map(extractType(_, path, line))
                    paramEithers.collectFirst { case Left(e) => e } match
                        case Some(e) => Left(e)
                        case None =>
                            extractType(retTpe, path, line).map { ret =>
                                fm.TypeRef.FnPtrT(paramEithers.collect { case Right(p) => p }, ret)
                            }
                    end match
                case _ => Left(ExtractorError(path, line, "function type is missing type arguments"))

        private def extractStruct(sym: Symbol, tpe: TypeRepr, path: String, line: Int): Either[ExtractorError, StructSpec] =
            val fqcn = sym.fullName
            structAccum.get(fqcn) match
                case Some(existing) => Right(existing)
                case None =>
                    if structInFlight(fqcn) then
                        Left(ExtractorError(
                            path,
                            line,
                            s"struct '$fqcn' recursively contains itself, C requires indirection (use Buffer[...] for pointer fields)"
                        ))
                    else
                        structInFlight += fqcn
                        val simpleName = sym.name
                        val caseFields = sym.caseFields
                        val fieldEithers = caseFields.map { fsym =>
                            val fline = fsym.pos.map(_.startLine + 1).getOrElse(line)
                            val ftype = tpe.memberType(fsym)
                            extractType(ftype, path, fline).map(t => StructField(fsym.name, t))
                        }
                        structInFlight -= fqcn
                        fieldEithers.collectFirst { case Left(e) => e } match
                            case Some(e) => Left(e)
                            case None =>
                                val fields = fieldEithers.collect { case Right(f) => f }
                                val packed = packedNames.contains(simpleName) || packedNames.contains(fqcn)
                                val spec = StructSpec(
                                    fqcn = fqcn,
                                    simpleName = simpleName,
                                    fields = fields,
                                    packed = packed
                                )
                                structAccum.update(fqcn, spec)
                                Right(spec)
                        end match
                    end if
            end match
        end extractStruct

        // Quotes positions cannot always be materialised. `startLine` throws if the position is synthetic
        // ("NoPosition"); we recover the provided fallback line instead.
        private def lineOf(pos: Position, fallback: Int): Int =
            try pos.startLine + 1
            catch case _: Throwable => fallback

        private def defaultLibrary(simpleName: String): String = snakeCase(simpleName)

        private def snakeCase(name: String): String =
            val sb = new StringBuilder
            var i  = 0
            while i < name.length do
                val c = name.charAt(i)
                if c.isUpper then
                    if i > 0 then sb.append('_')
                    sb.append(c.toLower)
                else sb.append(c)
                end if
                i += 1
            end while
            sb.toString
        end snakeCase
    end Context
end FfiInspector
