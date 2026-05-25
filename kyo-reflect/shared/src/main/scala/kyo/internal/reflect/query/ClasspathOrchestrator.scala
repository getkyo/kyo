package kyo.internal.reflect.query

import kyo.*
import kyo.Maybe.Absent
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.tasty.AstUnpickler
import kyo.internal.reflect.tasty.CommentsUnpickler
import kyo.internal.reflect.tasty.FileAttributes
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.SectionIndex
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TastyHeader
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Phase A/B/C classpath orchestration.
  *
  * Phase A: header sweep -- reads file bytes, decoding header + name table for each .tasty file; per-file `Scope.run` inner block releases
  * handles promptly. Phase B: parallel body decode -- each fiber decodes one file's full AST section, producing symbols with lazy bodies;
  * each fiber owns its own `TypeArena`; per-file inner `Scope.run` (here the handle is already bytes in memory so no file handle to close,
  * but the pattern is maintained for consistency). Phase C: single-threaded merge -- resolves cross-file type references (`UnresolvedRef`),
  * merges TypeArenas, CAS-transitions `Classpath` from `Building` to `Ready`.
  *
  * Per `feedback_kyo_scope_fiber_shared`: every `Scope.acquireRelease` inside an `Async.foreach` worker MUST be wrapped in an inner
  * `Scope.run` to prevent file-handle accumulation in the outer scope.
  *
  * Strict vs soft-fail: in strict mode any single file error fails the entire load. In soft-fail mode (default), bad files accumulate
  * errors; valid files continue decoding.
  */
object ClasspathOrchestrator:

    /** Minimum concurrency for Phase A and B. Bounded by available processors. */
    private def defaultConcurrency: Int = Runtime.getRuntime.availableProcessors().max(1)

    /** Result of decoding one TASTy file.
      *
      * On success: `Present(pairs)` where pairs are (fqn, symbol). On decode error in soft-fail mode: `Absent` (error already accumulated
      * in the classpath's Building state error list).
      *
      * `parentsBySymbol` and `childrenByOwner` are pre-indexed maps from Pass1Result used by `mergeResults` to assign `_parents`,
      * `_typeParams`, and `_declarations` on each symbol after Phase C placeholder resolution completes.
      */
    final private case class FileResult(
        fqns: Seq[(String, Reflect.Symbol)],
        arena: TypeArena,
        errors: Seq[ReflectError],
        placeholders: Chunk[UnresolvedRef],
        parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]],
        childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]],
        typeBySymbol: Map[Reflect.Symbol, Reflect.Type],
        commentsBySymbol: Map[Reflect.Symbol, String]
    )

    /** Open a new classpath from a set of root paths.
      *
      * Roots may be directories containing `.tasty` files or individual `.tasty` files. A `Scope.ensure` finalizer registered on the
      * enclosing `Scope` closes the classpath when the outer `Scope.run` exits.
      */
    def open(
        roots: Seq[String],
        strict: Boolean,
        source: FileSource,
        concurrency: Int
    )(using Frame): Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        Classpath.allocate.flatMap: cp =>
            Scope.ensure(Sync.defer(Classpath.close(cp))).andThen:
                openInto(roots, strict, source, concurrency, cp).andThen:
                    cp

    /** Open into a pre-allocated Classpath. Also used by `openCached` to reuse snapshot data. */
    private[kyo] def openInto(
        roots: Seq[String],
        strict: Boolean,
        source: FileSource,
        concurrency: Int,
        cp: Classpath
    )(using Frame): Unit < (Sync & Async & Abort[ReflectError]) =
        // Validate roots exist first (both strict and soft-fail report FileNotFound immediately)
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex then Abort.fail(ReflectError.FileNotFound(root))
                else Kyo.unit
        .andThen:
            collectTastyFiles(roots, source).flatMap: tastyFiles =>
                runPhaseAB(tastyFiles, strict, source, concurrency, cp)

    /** Phase A: read all .tasty bytes concurrently; Phase B: decode each file concurrently. Returns merged FileResult list. */
    private def runPhaseAB(
        tastyFiles: Chunk[String],
        strict: Boolean,
        source: FileSource,
        concurrency: Int,
        cp: Classpath
    )(using Frame): Unit < (Sync & Async & Abort[ReflectError]) =
        val interner = new Interner(128)
        // Phase A/B merged: read + decode each file in one parallel step with an inner Scope.run
        Async.foreach(tastyFiles, concurrency): file =>
            Scope.run:
                readAndDecodeTastyFile(file, interner, source, cp, strict)
        .flatMap: fileResults =>
            // Phase C: single-threaded merge
            mergeResults(fileResults, cp)
    end runPhaseAB

    /** Read bytes and decode a single TASTy file. Returns FileResult. */
    private def readAndDecodeTastyFile(
        file: String,
        interner: Interner,
        source: FileSource,
        cp: Classpath,
        strict: Boolean
    )(using Frame): FileResult < (Sync & Abort[ReflectError]) =
        Abort.run[ReflectError](
            source.read(file).flatMap: bytes =>
                decodeTastyBytes(file, bytes, interner, cp)
        ).map:
            case Result.Success(fr) => fr
            case Result.Failure(err: ReflectError) =>
                if strict then
                    Abort.fail(err)
                else
                    FileResult(Seq.empty, TypeArena.canonical(), Seq(err), Chunk.empty, Map.empty, Map.empty, Map.empty, Map.empty)
            case Result.Panic(t) =>
                val err = ReflectError.CorruptedFile(file, 0L, t.getMessage)
                if strict then
                    Abort.fail(err)
                else
                    FileResult(Seq.empty, TypeArena.canonical(), Seq(err), Chunk.empty, Map.empty, Map.empty, Map.empty, Map.empty)
                end if

    /** Decode TASTy bytes into a FileResult (fqn-symbol pairs + arena). */
    private def decodeTastyBytes(
        file: String,
        bytes: Array[Byte],
        interner: Interner,
        cp: Classpath
    )(using Frame): FileResult < (Sync & Abort[ReflectError]) =
        val view  = ByteView(bytes)
        val home  = new ClasspathRef
        val arena = new TypeArena
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            pass1Result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    Abort.fail(ReflectError.MalformedSection("ASTs", s"$file: ASTs section not found"))
            commentsBySymbol <- sections.get(TastyFormat.CommentsSection) match
                case Present((offset, length)) =>
                    val commentsView = view.subView(offset, offset + length)
                    CommentsUnpickler.read(commentsView, pass1Result.addrMap)
                case Absent =>
                    Sync.defer(Map.empty[Reflect.Symbol, String])
        yield
            val pairs = pass1Result.symbols.toSeq.flatMap: sym =>
                val fqn = nameToString(sym.fullName)
                if fqn.nonEmpty then Seq((fqn, sym)) else Seq.empty
            FileResult(
                pairs,
                arena,
                Seq.empty,
                pass1Result.placeholders,
                pass1Result.parentsBySymbol,
                pass1Result.childrenByOwner,
                pass1Result.typeBySymbol,
                commentsBySymbol
            )
        end for
    end decodeTastyBytes

    /** Collect all .tasty files from the root paths. */
    private def collectTastyFiles(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
        Kyo.foreach(Chunk.from(roots)): root =>
            source.list(root, ".tasty").flatMap: listed =>
                if listed.isEmpty then
                    // Root may itself be a .tasty file
                    source.exists(root).map: ex =>
                        if ex && root.endsWith(".tasty") then Chunk(root)
                        else listed
                else
                    listed
        .map(_.flatten)

    /** Phase C: merge all per-file results into the Classpath. */
    private def mergeResults(
        fileResults: Chunk[FileResult],
        cp: Classpath
    )(using Frame): Unit < Sync =
        Sync.defer:
            val canonical    = TypeArena.canonical()
            val fqnIndex     = mutable.HashMap.empty[String, Reflect.Symbol]
            val packageIndex = mutable.HashMap.empty[String, Reflect.Symbol]
            val allSyms      = mutable.ArrayBuffer.empty[Reflect.Symbol]
            val topLevelCls  = mutable.ArrayBuffer.empty[Reflect.Symbol]
            val packages     = mutable.ArrayBuffer.empty[Reflect.Symbol]
            val accErrors    = mutable.ArrayBuffer.empty[ReflectError]

            for fr <- fileResults do
                for (fqn, sym) <- fr.fqns do
                    // Disambiguation strategy for same-dotted-FQN symbols that appear in the same TASTy file:
                    // - Object-kind symbols are stored under a "$"-suffixed key (unless already ending in "$").
                    //   This prevents companion objects from overwriting same-name class symbols.
                    // - Non-Class/Trait/Object kinds (e.g., Val module accessor) do not overwrite an
                    //   existing Class/Trait entry at the same key. They are stored only if the key is free
                    //   or currently holds a non-structural symbol.
                    val indexKey = if sym.kind == Reflect.SymbolKind.Object && !fqn.endsWith("$") then fqn + "$" else fqn
                    val existing = fqnIndex.get(indexKey)
                    val shouldStore = existing match
                        case None       => true
                        case Some(prev) =>
                            // Prefer Class/Trait/Object over other kinds. Don't let Val/Method/etc. overwrite structural symbols.
                            val prevIsStructural = prev.kind == Reflect.SymbolKind.Class ||
                                prev.kind == Reflect.SymbolKind.Trait || prev.kind == Reflect.SymbolKind.Object
                            val newIsStructural = sym.kind == Reflect.SymbolKind.Class ||
                                sym.kind == Reflect.SymbolKind.Trait || sym.kind == Reflect.SymbolKind.Object
                            // Allow overwrite only if new symbol is structural (or prev is non-structural).
                            newIsStructural || !prevIsStructural
                    if shouldStore then fqnIndex(indexKey) = sym
                    allSyms += sym
                    sym.kind match
                        case Reflect.SymbolKind.Package =>
                            packages += sym
                            packageIndex(fqn) = sym
                        case Reflect.SymbolKind.Class | Reflect.SymbolKind.Trait | Reflect.SymbolKind.Object =>
                            topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end for
                accErrors ++= fr.errors
                canonical.merge(fr.arena)
            end for

            // Phase C: resolve all UnresolvedRef placeholders accumulated during Phase B decode.
            // All arenas merged and fqnIndex fully populated above, so lookups are complete.
            // Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below).
            val allPlaceholders = fileResults.flatMap(_.placeholders)
            import AllowUnsafe.embrace.danger
            for placeholder <- allPlaceholders do
                fqnIndex.get(placeholder.fqn) match
                    case Some(sym) =>
                        placeholder.replaceSlot.set(Reflect.Type.Named(sym))
                    case None =>
                        placeholder.replaceSlot.set(Reflect.Type.Named(makeUnresolvedSym(placeholder.fqn)))
            end for

            // After G13 placeholder resolution: assign _parents, _typeParams, _declarations on TASTy symbols.
            // All parent type slots are now resolved; cross-file proxy types have their SingleAssign slots set.
            // Each symbol appears in exactly one FileResult so no double-set can occur.
            for fr <- fileResults do
                for (sym, parents) <- fr.parentsBySymbol do
                    sym._parents.set(parents)
                end for
            end for
            for fr <- fileResults do
                for (sym, children) <- fr.childrenByOwner do
                    val typeParams   = children.filter(_.kind == Reflect.SymbolKind.TypeParam)
                    val declarations = children.filter(_.kind != Reflect.SymbolKind.TypeParam)
                    sym._typeParams.set(typeParams)
                    sym._declarations.set(declarations)
                end for
            end for

            // Set _parents, _typeParams, _declarations to empty for all symbols not covered by the above loops.
            // This covers non-class-like symbols (methods, fields, type params, parameters, packages) which have
            // no entry in parentsBySymbol or childrenByOwner, and any class symbol whose template had no parents or no children.
            for sym <- allSyms do
                if !sym._parents.isSet then sym._parents.set(Chunk.empty)
                if !sym._typeParams.isSet then sym._typeParams.set(Chunk.empty)
                if !sym._declarations.isSet then sym._declarations.set(Chunk.empty)
            end for

            // Phase 5 (G20): assign _declaredType AFTER Phase C placeholder resolution.
            // TASTy path: assign from typeBySymbol (VALDEF, PARAM, TYPEPARAM, type-level TYPEDEF, DEFDEF return types).
            for fr <- fileResults do
                for (sym, t) <- fr.typeBySymbol do
                    if !sym._declaredType.isSet then sym._declaredType.set(t)
                end for
            end for
            // Class-like TYPEDEF symbols (Class/Trait/Object) get Type.Named(sym) as their declaredType.
            for sym <- allSyms do
                if !sym._declaredType.isSet && (sym.kind == Reflect.SymbolKind.Class ||
                        sym.kind == Reflect.SymbolKind.Trait ||
                        sym.kind == Reflect.SymbolKind.Object)
                then
                    sym._declaredType.set(Reflect.Type.Named(sym))
            end for
            // Package and root symbols: leave _declaredType unset.
            // The public accessor has a kind == Package guard that returns NotImplemented.

            // Phase 6 (G3): assign _scaladoc from commentsBySymbol.
            // Symbols with a scaladoc entry get Present(text); all others get Absent.
            for fr <- fileResults do
                for (sym, text) <- fr.commentsBySymbol do
                    if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe(text))
                end for
            end for
            for sym <- allSyms do
                if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe.Absent)
            end for

            // Add errors accumulated during Building state (e.g., from root validation)
            // Unsafe: stateRef.unsafe.get() read of Building state, single-threaded Phase C merge
            cp.stateRef.unsafe.get() match
                case b: Classpath.State.Building => accErrors ++= b.errors
                case _                           => ()

            Classpath.transitionToReady(
                cp,
                Chunk.from(allSyms.toSeq),
                Chunk.from(topLevelCls.toSeq),
                Chunk.from(packages.toSeq),
                fqnIndex.toMap,
                packageIndex.toMap,
                canonical,
                Chunk.from(accErrors.toSeq)
            )

    /** Convert a Name (opaque Interner.Entry) to a String. */
    private def nameToString(n: Reflect.Name): String =
        import Reflect.Name.asString
        n.asString

    /** Create a synthetic unresolved symbol for a FQN not found in fqnIndex.
      *
      * Mirrors TypeUnpickler.makeUnresolvedSym; duplicated here to avoid promoting a private method across package boundaries.
      */
    private def makeUnresolvedSym(fqn: String): Reflect.Symbol =
        InternalSymbol.makeSymbol(
            Reflect.SymbolKind.Unresolved,
            Reflect.Flags.empty,
            Reflect.Name(fqn),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0),
            Absent
        )

end ClasspathOrchestrator
