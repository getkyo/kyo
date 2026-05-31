package kyo.internal.tasty.query

import kyo.*
import kyo.Maybe.Absent
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ModuleInfoReader
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.AttributeUnpickler
import kyo.internal.tasty.reader.CommentsUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.PositionsUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena
import kyo.stats.Attributes
import scala.collection.mutable

/** Phase A/B/C classpath orchestration via streaming Channel pipeline.
  *
  * Phase A: root walker -- enqueues (entryPath, kind) pairs to `entryCh`. Phase B: parallel decoders -- consume `entryCh`; each decodes one
  * file and enqueues a `DecodeResult` to `resultCh`. Phase C: single-threaded merger -- consumes `resultCh` and accumulates a `MergeState`;
  * after merger drains, `finalizeMerge` resolves placeholders and transitions the Classpath to Ready.
  *
  * Per `feedback_kyo_scope_fiber_shared`: every `Scope.acquireRelease` inside an `Async.foreach` worker MUST be wrapped in an inner
  * `Scope.run` to prevent file-handle accumulation in the outer scope.
  *
  * Strict vs soft-fail: in strict mode any single file error fails the entire load. In soft-fail mode (default), bad files accumulate
  * errors; valid files continue decoding.
  *
  * Channel close semantics: `closeAwaitEmpty` (not `close`) is used on both channels so that buffered items are drained to consumers before
  * the channel transitions to fully-closed. Using `close` would return buffered items in its result rather than delivering them.
  * `Scope.ensure` registrations guarantee channels close on any exit (success, Abort, interrupt) so the merger never blocks forever.
  */
object ClasspathOrchestrator:

    /** Whether to emit the one-line timing summary to stderr. Set -Dkyo.reflect.timing=true to enable. */
    private val timingEnabled: Boolean = java.lang.System.getProperty("kyo.reflect.timing") == "true"

    /** Minimum concurrency for Phase A and B. Bounded by available processors. */
    private def defaultConcurrency: Int = Runtime.getRuntime.availableProcessors().max(1)

    /** Result of decoding one TASTy file.
      *
      * On success: `Present(pairs)` where pairs are (fqn, symbol). On decode error in soft-fail mode: `Absent` (error already accumulated
      * in the classpath's Building state error list).
      *
      * `parentsBySymbol` and `childrenByOwner` are pre-indexed maps from Pass1Result used by `finalizeMerge` to assign `_parents`,
      * `_typeParams`, and `_declarations` on each symbol after Phase C placeholder resolution completes.
      */
    /** Fields `parentsBySymbol`, `childrenByOwner`, and `typeBySymbol` are `mutable.HashMap` instances. They are safe because `FileResult`
      * is written by a single decoder fiber and consumed exclusively by the single-threaded merger fiber after the channel put/take
      * provides a happens-before edge.
      *
      * plan: phase-02 bridge fields ownerBySymbol, bodyDataByAddr, sectionBytes, sectionOffset, names -- fed from AstUnpickler Pass1Result
      * to ClasspathOrchestrator Pass C; removed in Phase 07 when the pipeline fully uses SymbolDescriptor.
      */
    final private case class FileResult(
        fqns: Chunk[(String, Tasty.Symbol)],
        arena: TypeArena,
        errors: Seq[TastyError],
        placeholders: Chunk[UnresolvedRef],
        parentsBySymbol: mutable.HashMap[Tasty.Symbol, Chunk[Tasty.Type]],
        childrenByOwner: mutable.HashMap[Tasty.Symbol, Chunk[Tasty.Symbol]],
        typeBySymbol: mutable.HashMap[Tasty.Symbol, Tasty.Type],
        commentsBySymbol: Map[Tasty.Symbol, String],
        positionsBySymbol: Map[Tasty.Symbol, Tasty.Position],
        ownerBySymbol: mutable.HashMap[Tasty.Symbol, Tasty.Symbol],
        bodyDataByAddr: mutable.HashMap[Tasty.Symbol, (Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        fileNames: Array[Tasty.Name]
    )

    /** Tagged union for results flowing through the result channel.
      *
      * `FileResultCase` carries a decoded TASTy file result. `ModuleInfoCase` carries a decoded module-info.class descriptor.
      */
    sealed private trait DecodeResult
    final private case class FileResultCase(fr: FileResult)                           extends DecodeResult
    final private case class ModuleInfoCase(name: String, md: Tasty.ModuleDescriptor) extends DecodeResult

    /** Mutable accumulator for the single-threaded merger stage (Phase C).
      *
      * Collects decoded file results and module descriptors as they arrive from the result channel. `finalizeMerge` reads from this state
      * once the merger has drained the result channel.
      */
    final private class MergeState:
        val fqnIndex: mutable.HashMap[String, Tasty.Symbol]              = mutable.HashMap.empty
        val packageIndex: mutable.HashMap[String, Tasty.Symbol]          = mutable.HashMap.empty
        val allSyms: mutable.ArrayBuffer[Tasty.Symbol]                   = mutable.ArrayBuffer.empty
        val topLevelCls: mutable.ArrayBuffer[Tasty.Symbol]               = mutable.ArrayBuffer.empty
        val packages: mutable.ArrayBuffer[Tasty.Symbol]                  = mutable.ArrayBuffer.empty
        val accErrors: mutable.ArrayBuffer[TastyError]                   = mutable.ArrayBuffer.empty
        val fileResults: mutable.ArrayBuffer[FileResult]                 = mutable.ArrayBuffer.empty
        val moduleIndex: mutable.HashMap[String, Tasty.ModuleDescriptor] = mutable.HashMap.empty
    end MergeState

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
    )(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        Classpath.allocate.flatMap: cp =>
            Scope.ensure(Sync.Unsafe.defer {
                // Unsafe: atomic state write; called from Scope finalizer.
                Classpath.close(cp)
            }).andThen:
                openInto(roots, strict, source, concurrency, cp).andThen:
                    cp

    /** Open into a pre-allocated Classpath. Also used by `openCached` to reuse snapshot data. */
    private[kyo] def openInto(
        roots: Seq[String],
        strict: Boolean,
        source: FileSource,
        concurrency: Int,
        cp: Classpath
    )(using Frame): Unit < (Sync & Async & Scope & Abort[TastyError]) =
        // Validate roots exist first (both strict and soft-fail report FileNotFound immediately)
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex then Abort.fail(TastyError.FileNotFound(root))
                else Kyo.unit
        .andThen:
            // Install a read-batch context so JvmFileSource can share mmap readers across the scan+decode pipeline.
            // The default FileSource.withReadBatch is a no-op; JvmFileSource overrides it.
            source.withReadBatch:
                runPhaseAB(roots, strict, source, concurrency, cp)

    /** Phase A+B+C pipeline via Channels.
      *
      * Three concurrent stages run inside `Async.foreach`: - Producer: walks each root via `source.list`, puts (entryPath, kind) to
      * `entryCh`, then closes `entryCh` with `closeAwaitEmpty` so decoders drain. - Decoders: consume `entryCh` via `streamUntilClosed`;
      * each decoded result is put to `resultCh`; after all entries consumed, close `resultCh`. - Merger: consumes `resultCh` via
      * `streamUntilClosed`; accumulates into `MergeState`; exits when `resultCh` closes.
      *
      * `Scope.ensure` registrations on both channels guarantee they close on any exit (success, Abort, interrupt). Without this, a
      * strict-mode Abort in a decoder would leave `resultCh` open and the merger would block forever.
      */
    private def runPhaseAB(
        roots: Seq[String],
        strict: Boolean,
        source: FileSource,
        concurrency: Int,
        cp: Classpath
    )(using Frame): Unit < (Sync & Async & Abort[TastyError]) =
        val decodeConcurrency = concurrency.max(1)
        val rootCount         = roots.size.max(1)
        val entryCap          = decodeConcurrency * 4
        val resultCap         = decodeConcurrency * 2
        val numShards         = 128
        val mergeState        = new MergeState()
        // Heuristic: 128 entries per shard accommodates classpaths up to ~12K entries (75% load).
        // This avoids the per-cold-load pre-walk cost that exceeds the resize savings on small classpaths.
        val sizeHint = 128

        // Timing instrumentation: snapshot nanoTime at each stage boundary.
        // AtomicLong.Unsafe used so decoder fibers can record t_decodeEnd from any thread.
        // flow-allow: §839 case 3; pipeline-launch boundary; timing slots allocated once per open() call.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val t_start       = AtomicLong.Unsafe.init(java.lang.System.nanoTime())
        val t_listEnd     = AtomicLong.Unsafe.init(0L)
        val t_decodeEnd   = AtomicLong.Unsafe.init(0L)
        val t_mergeEnd    = AtomicLong.Unsafe.init(0L)

        Sync.Unsafe.defer(Interner.init(numShards = numShards, initialShardCapacity = sizeHint)).flatMap { interner =>
            Scope.run:
                Channel.initUnscoped[(String, String)](entryCap, Access.MultiProducerMultiConsumer).flatMap: entryCh =>
                    Channel.initUnscoped[DecodeResult](resultCap, Access.MultiProducerMultiConsumer).flatMap: resultCh =>
                        // Scope.ensure registrations guarantee channels close on ANY exit (success, abort, interrupt).
                        // Uses close (not closeAwaitEmpty) so that on abort the signal is immediate: interrupted
                        // consumers are no longer draining, and closeAwaitEmpty would block forever waiting for them.
                        // streamUntilClosed handles the Closed signal correctly on any exit path.
                        Scope.ensure(entryCh.close.unit).andThen:
                            Scope.ensure(resultCh.close.unit).andThen:
                                val producerStage = Async.foreach(Chunk.from(roots), rootCount): root =>
                                    TastyStat.scope.traceSpan(
                                        "walkRoot",
                                        Attributes.empty.add("root", root)
                                    )(walkRoot(root, entryCh, source))

                                val decoderStage = Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency): _ =>
                                    TastyStat.scope.traceSpan("decoder") {
                                        entryCh.streamUntilClosed().foreach: (entryPath, kind) =>
                                            decodeOneEntry(entryPath, kind, interner, source, cp, strict).flatMap: result =>
                                                // If resultCh closed early (strict-mode abort), silently discard
                                                Abort.run[Closed](resultCh.put(result)).unit
                                    }

                                val mergerStage: Unit < (Async & Abort[TastyError]) =
                                    TastyStat.scope.traceSpan("merger") {
                                        resultCh.streamUntilClosed().foreach: result =>
                                            Sync.defer(mergeOneInto(mergeState, result))
                                    }

                                // Producer closes entryCh after all puts complete (closeAwaitEmpty so decoders drain buffer).
                                val producerWithClose: Unit < (Abort[TastyError] & Async) =
                                    producerStage
                                        .andThen(Sync.Unsafe.defer(t_listEnd.set(java.lang.System.nanoTime())))
                                        .andThen(entryCh.closeAwaitEmpty.unit)
                                // Decoders close resultCh after all puts complete.
                                val decoderWithClose: Unit < (Abort[TastyError] & Async) =
                                    decoderStage
                                        .andThen(Sync.Unsafe.defer(t_decodeEnd.set(java.lang.System.nanoTime())))
                                        .andThen(resultCh.closeAwaitEmpty.unit)
                                // Merger records its end time after draining resultCh.
                                val mergerWithTiming: Unit < (Async & Abort[TastyError]) =
                                    mergerStage.andThen(Sync.Unsafe.defer(t_mergeEnd.set(java.lang.System.nanoTime())))

                                // Async.foreach with concurrency=3 and 3 items runs all 3 stages concurrently.
                                // Unlike Async.gather, Async.foreach propagates the first Abort failure and
                                // interrupts the other fibers (including a stuck merger) via IOPromise.interrupts.
                                val stages: Chunk[Unit < (Abort[TastyError] & Async)] =
                                    Chunk(producerWithClose, decoderWithClose, mergerWithTiming)
                                Async.foreach(stages, 3): stage =>
                                    stage
                                .andThen(finalizeMerge(mergeState, source, strict, cp))
                                    .andThen:
                                        if timingEnabled then
                                            Sync.Unsafe.defer:
                                                val t_end       = java.lang.System.nanoTime()
                                                val t0          = t_start.get()
                                                val tList       = t_listEnd.get()
                                                val tDec        = t_decodeEnd.get()
                                                val tMrg        = t_mergeEnd.get()
                                                val listMs      = if tList > 0 then (tList - t0) / 1_000_000L else -1L
                                                val decodeMs    = if tDec > 0 then (tDec - t0) / 1_000_000L else -1L
                                                val mergeMs     = if tMrg > 0 then (tMrg - t0) / 1_000_000L else -1L
                                                val totalMs     = (t_end - t0) / 1_000_000L
                                                val finalizeMs  = if tMrg > 0 then (t_end - tMrg) / 1_000_000L else -1L
                                                val jars        = TastyPerfStats.jarOpens.get()
                                                val entries     = TastyPerfStats.entryReads.get()
                                                val bytesRaw    = TastyPerfStats.bytesRead.get()
                                                val bytesMB     = bytesRaw / (1024L * 1024L)
                                                val constructMs = TastyPerfStats.jarConstructNs.get() / 1_000_000L
                                                val readMs      = TastyPerfStats.jarReadNs.get() / 1_000_000L
                                                val headerMs    = TastyPerfStats.tastyHeaderNs.get() / 1_000_000L
                                                val namesMs     = TastyPerfStats.nameUnpicklerNs.get() / 1_000_000L
                                                val sectionMs   = TastyPerfStats.sectionIndexNs.get() / 1_000_000L
                                                val attrMs      = TastyPerfStats.attributeUnpicklerNs.get() / 1_000_000L
                                                val astMs       = TastyPerfStats.astPass1Ns.get() / 1_000_000L
                                                val posMs       = TastyPerfStats.positionsUnpicklerNs.get() / 1_000_000L
                                                val commentsMs  = TastyPerfStats.commentsUnpicklerNs.get() / 1_000_000L
                                                java.lang.System.err.println(
                                                    s"[kyo-tasty] cold-load: list=${listMs}ms decode=${decodeMs}ms merge=${mergeMs}ms finalize=${finalizeMs}ms total=${totalMs}ms | jars=$jars (construct=${constructMs}ms read=${readMs}ms) entries=$entries bytes=${bytesMB}MB"
                                                )
                                                java.lang.System.err.println(
                                                    s"[kyo-tasty]   decode-breakdown: header=${headerMs}ms names=${namesMs}ms section=${sectionMs}ms attr=${attrMs}ms ast=${astMs}ms pos=${posMs}ms comments=${commentsMs}ms"
                                                )
                                        else
                                            Kyo.unit
        }
    end runPhaseAB

    /** Walk a single root, putting (entryPath, kind) pairs into entryCh.
      *
      * If `root` is itself a `.tasty` or `module-info.class` file (i.e., `source.list` returns empty), emit it directly. If `entryCh` is
      * already closed (strict-mode abort scenario), the put fails with `Closed`; we discard the error and stop walking this root.
      */
    private def walkRoot(
        root: String,
        entryCh: Channel[(String, String)],
        source: FileSource
    )(using Frame): Unit < (Sync & Async & Abort[TastyError]) =
        source.list(root, Chunk(".tasty", "module-info.class")).flatMap: listed =>
            val entries: Chunk[String] =
                if listed.isEmpty then
                    if root.endsWith(".tasty") || root.endsWith("module-info.class") then Chunk(root)
                    else Chunk.empty
                else listed
            Kyo.foreach(entries): entry =>
                val kind =
                    if entry.endsWith("module-info.class") then "module-info.class"
                    else ".tasty"
                // Discard Closed: if entryCh closed early, stop putting
                Abort.run[Closed](entryCh.put((entry, kind))).unit
            .unit

    /** Decode one entry by kind and return a DecodeResult.
      *
      * For `.tasty`: reads bytes, decodes TASTy, returns a FileResultCase. For `module-info.class`: reads bytes, decodes module descriptor,
      * returns a ModuleInfoCase.
      *
      * In strict mode, decode errors propagate as Abort[TastyError]. In soft-fail mode they produce empty/error FileResult.
      */
    private def decodeOneEntry(
        entryPath: String,
        kind: String,
        interner: Interner,
        source: FileSource,
        cp: Classpath,
        strict: Boolean
    )(using Frame): DecodeResult < (Sync & Async & Abort[TastyError]) =
        if kind == "module-info.class" then
            Abort.run[TastyError](
                source.read(entryPath).flatMap: bytes =>
                    Sync.Unsafe.defer:
                        TastyPerfStats.entryReads.inc()
                        TastyPerfStats.bytesRead.add(bytes.length.toLong)
                    .andThen(ModuleInfoReader.read(bytes))
            ).flatMap:
                case Result.Success(desc) =>
                    ModuleInfoCase(desc.name, desc)
                case Result.Failure(err: TastyError) =>
                    if strict then Abort.fail(err)
                    else
                        // Soft-fail: produce an empty FileResult with the error recorded
                        FileResultCase(emptyFileResultWithError(entryPath, err))
                case Result.Panic(t) =>
                    val err = TastyError.CorruptedFile(entryPath, 0L, t.getMessage)
                    if strict then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
        else
            Scope.run:
                readAndDecodeTastyFile(entryPath, interner, source, cp, strict).map(FileResultCase.apply)

    /** Merge one DecodeResult into the MergeState. Single-threaded (only the merger fiber calls it). */
    private def mergeOneInto(state: MergeState, result: DecodeResult): Unit =
        result match
            case FileResultCase(fr) =>
                // plan: phase-02 bridge; add ALL symbols (including members) to allSyms so that
                // finalizeMerge can look them up by index when building typeParamIds/declarationIds.
                // First add all symbols from ownerBySymbol (covers all non-root AST symbols).
                val seenSyms = new java.util.HashSet[Tasty.Symbol]()
                for sym <- fr.ownerBySymbol.keys do
                    if seenSyms.add(sym) then state.allSyms += sym
                end for
                for sym <- fr.ownerBySymbol.values do
                    if seenSyms.add(sym) then state.allSyms += sym
                end for

                for (fqn, sym) <- fr.fqns do
                    val indexKey = if sym.kind == Tasty.SymbolKind.Object && !fqn.endsWith("$") then fqn + "$" else fqn
                    val existing = state.fqnIndex.get(indexKey)
                    val shouldStore = existing match
                        case None => true
                        case Some(prev) =>
                            val prevIsStructural = prev.kind == Tasty.SymbolKind.Class ||
                                prev.kind == Tasty.SymbolKind.Trait || prev.kind == Tasty.SymbolKind.Object
                            val newIsStructural = sym.kind == Tasty.SymbolKind.Class ||
                                sym.kind == Tasty.SymbolKind.Trait || sym.kind == Tasty.SymbolKind.Object
                            newIsStructural || !prevIsStructural
                    if shouldStore then state.fqnIndex(indexKey) = sym
                    if seenSyms.add(sym) then state.allSyms += sym
                    sym.kind match
                        case Tasty.SymbolKind.Package =>
                            state.packages += sym
                            state.packageIndex(fqn) = sym
                        case Tasty.SymbolKind.Class | Tasty.SymbolKind.Trait | Tasty.SymbolKind.Object =>
                            state.topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end for
                state.accErrors ++= fr.errors
                state.fileResults += fr
            case ModuleInfoCase(name, md) =>
                state.moduleIndex(name) = md

    /** Phase C: placeholder resolution + SymbolDescriptor construction + Classpath transition.
      *
      * Runs once after the merger has drained the result channel. Resolves cross-file type references, builds SymbolDescriptors from the
      * accumulated per-file data, calls materializeSymbols to produce the final immutable Symbol case-class instances, and transitions the
      * Classpath from Building to Ready.
      *
      * plan: phase-02 impl; the SymbolDescriptor accumulation in this method is the Pass C replacement for the old slot-fill loops. Phase
      * 07 removes the partial-Symbol pipeline entirely and wires AstUnpickler to produce SymbolDescriptors directly.
      */
    private def finalizeMerge(
        state: MergeState,
        source: FileSource,
        strict: Boolean,
        cp: Classpath
    )(using Frame): Unit < Sync =
        val canonical    = TypeArena.canonical()
        val fileResults  = state.fileResults.toSeq
        val fqnIndex     = state.fqnIndex
        val packageIndex = state.packageIndex
        val allPartial   = state.allSyms // partial Symbols from Pass 1-2
        val topLevelCls  = state.topLevelCls
        val packages     = state.packages
        val accErrors    = state.accErrors
        val moduleIndex  = state.moduleIndex.toMap

        TastyStat.scope.traceSpan("finalize.placeholderResolve") {
            Sync.Unsafe.defer:
                for fr <- fileResults do
                    canonical.merge(fr.arena)
                end for

                // Phase C: resolve all UnresolvedRef placeholders accumulated during Phase B decode.
                // All arenas merged and fqnIndex fully populated above, so lookups are complete.
                val allPlaceholders = Chunk.from(fileResults).flatMap(_.placeholders)
                for placeholder <- allPlaceholders do
                    fqnIndex.get(placeholder.fqn) match
                        case Some(sym) =>
                            placeholder.replaceSlot.set(Tasty.Type.Named(sym))
                        case None =>
                            placeholder.replaceSlot.set(Tasty.Type.Named(makeUnresolvedSym(placeholder.fqn)))
                end for
        }.andThen:
            TastyStat.scope.traceSpan("finalize.materializeSymbols") {
                Sync.Unsafe.defer:
                    // Build a map from partial Symbol -> its final SymbolId (index in allPartial).
                    // This allows Pass C to convert ownerBySymbol references to integer SymbolId values.
                    val symbolIdMap = new java.util.HashMap[Tasty.Symbol, Int](allPartial.length * 2)
                    var i           = 0
                    for sym <- allPartial do
                        symbolIdMap.put(sym, i)
                        i += 1
                    end for

                    // Build SymbolDescriptors from the accumulated per-file data.
                    val count = allPartial.length
                    val descs = new Array[SymbolDescriptor](count)
                    i = 0
                    for partialSym <- allPartial do
                        val id = i
                        // Compute ownerId: look up the partial owner's index in symbolIdMap.
                        // The root symbol owns itself (self-referential sentinel = id == ownerId).
                        // If the owner is not in symbolIdMap (e.g. null for root), use self as sentinel.
                        descs(i) = new SymbolDescriptor(
                            id = id,
                            kind = partialSym.kind,
                            flags = partialSym.flags,
                            name = partialSym.name,
                            ownerId = id, // default: self-referential (root sentinel); overridden below
                            declaredType = Maybe.Absent,
                            scaladoc = Maybe.Absent,
                            sourcePosition = Maybe.Absent,
                            javaMetadata = Maybe.Absent,
                            parentTypes = Chunk.empty,
                            typeParamIds = Chunk.empty,
                            declarationIds = Chunk.empty,
                            permittedSubclassIds = Maybe.Absent,
                            body = Maybe.Absent
                        )
                        i += 1
                    end for

                    // Fill in ownerId from ownerBySymbol maps in each FileResult.
                    for fr <- fileResults do
                        for (sym, owner) <- fr.ownerBySymbol do
                            val symIdx   = symbolIdMap.get(sym)
                            val ownerIdx = symbolIdMap.getOrDefault(owner, symIdx) // self-ref if owner not found
                            if symIdx >= 0 && symIdx < count then
                                descs(symIdx).ownerId = ownerIdx
                        end for
                    end for

                    // Fill in parentTypes from parentsBySymbol.
                    for fr <- fileResults do
                        for (sym, parents) <- fr.parentsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count then
                                descs(idx).parentTypes = parents
                        end for
                    end for

                    // Fill in typeParamIds and declarationIds from childrenByOwner.
                    for fr <- fileResults do
                        for (sym, children) <- fr.childrenByOwner do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count then
                                val typeParams   = children.filter(_.kind == Tasty.SymbolKind.TypeParam)
                                val declarations = children.filter(_.kind != Tasty.SymbolKind.TypeParam)
                                descs(idx).typeParamIds = typeParams.map(c => symbolIdMap.getOrDefault(c, -1)).filter(_ >= 0)
                                descs(idx).declarationIds = declarations.map(c => symbolIdMap.getOrDefault(c, -1)).filter(_ >= 0)
                            end if
                        end for
                    end for

                    // Fill in declaredType from typeBySymbol (after placeholder resolution).
                    for fr <- fileResults do
                        for (sym, t) <- fr.typeBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).declaredType.isEmpty then
                                descs(idx).declaredType = Maybe(t)
                        end for
                    end for

                    // Default declaredType for Class/Trait/Object (Type.Named(self)).
                    i = 0
                    for sym <- allPartial do
                        if descs(i).declaredType.isEmpty then
                            val k = sym.kind
                            if k == Tasty.SymbolKind.Class || k == Tasty.SymbolKind.Trait || k == Tasty.SymbolKind.Object then
                                descs(i).declaredType = Maybe(Tasty.Type.Named(sym))
                        end if
                        i += 1
                    end for

                    // Fill in scaladoc from commentsBySymbol.
                    for fr <- fileResults do
                        for (sym, text) <- fr.commentsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).scaladoc.isEmpty then
                                descs(idx).scaladoc = Maybe(text)
                        end for
                    end for

                    // Fill in sourcePosition from positionsBySymbol.
                    for fr <- fileResults do
                        for (sym, pos) <- fr.positionsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).sourcePosition.isEmpty then
                                descs(idx).sourcePosition = Maybe(pos)
                        end for
                    end for

                    // Fill in body data (SymbolBody) from bodyDataByAddr and file-level section data.
                    for fr <- fileResults do
                        for (sym, (bodyStart, bodyEnd)) <- fr.bodyDataByAddr do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && bodyStart > 0 && bodyEnd > bodyStart then
                                // Build addrMap: convert IntMap[partial Symbol] to IntMap[SymbolId]
                                // using symbolIdMap. This is done lazily per symbol.
                                // For Phase 02, build a per-file IntMap[SymbolId] once and share it.
                                // (built below for the full file addrMap)
                                descs(idx).body = Maybe(Tasty.SymbolBody(
                                    bodyStart = bodyStart,
                                    bodyEnd = bodyEnd,
                                    sectionBytes = fr.sectionBytes,
                                    names = fr.fileNames,
                                    sectionOffset = fr.sectionOffset,
                                    addrMap = scala.collection.immutable.IntMap.empty // populated in next pass
                                ))
                            end if
                        end for
                    end for

                    // Materialize final immutable Symbols from SymbolDescriptors.
                    val finalSymbols = materializeSymbols(descs, count)

                    // Update fqnIndex, packageIndex, topLevelCls, packages to point to final Symbols.
                    // The keys in state.fqnIndex are FQN strings pointing to partial Symbols;
                    // replace each partial Symbol value with its corresponding final Symbol.
                    val newFqnIndex = state.fqnIndex.map { case (fqn, partial) =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then (fqn, finalSymbols(idx)) else (fqn, partial)
                    }
                    val newPackageIndex = state.packageIndex.map { case (pkg, partial) =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then (pkg, finalSymbols(idx)) else (pkg, partial)
                    }
                    val newTopLevelCls = topLevelCls.map { partial =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then finalSymbols(idx) else partial
                    }
                    val newPackages = packages.map { partial =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then finalSymbols(idx) else partial
                    }

                    // Add errors accumulated during Building state.
                    cp.stateRef.unsafe.get() match
                        case b: Classpath.State.Building => accErrors ++= b.errors
                        case _                           => ()

                    Classpath.transitionToReady(
                        cp,
                        Chunk.from(finalSymbols),
                        Chunk.from(newTopLevelCls),
                        Chunk.from(newPackages),
                        newFqnIndex,
                        newPackageIndex,
                        canonical,
                        Chunk.from(accErrors),
                        moduleIndex
                    )
            }
    end finalizeMerge

    /** Construct final immutable Symbols from SymbolDescriptors.
      *
      * Each SymbolDescriptor's int fields (ownerId, typeParamIds, declarationIds) become SymbolId values. This is the single-shot
      * materialization step of Pass C.
      */
    private def materializeSymbols(
        descriptors: Array[SymbolDescriptor],
        count: Int
    )(using AllowUnsafe): Array[Tasty.Symbol] =
        val out = new Array[Tasty.Symbol](count)
        var i   = 0
        while i < count do
            val d = descriptors(i)
            out(i) = Tasty.Symbol.fromDescriptor(
                id = SymbolId(d.id),
                kind = d.kind,
                flags = d.flags,
                name = d.name,
                ownerId = SymbolId(d.ownerId),
                declaredType = d.declaredType,
                scaladoc = d.scaladoc,
                sourcePosition = d.sourcePosition,
                javaMetadata = d.javaMetadata,
                parentTypes = d.parentTypes,
                typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                declarationIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_))),
                permittedSubclassIds = d.permittedSubclassIds.map(_.map(SymbolId(_))),
                body = d.body
            )
            i += 1
        end while
        out
    end materializeSymbols

    /** Read bytes and decode a single TASTy file. Returns FileResult. */
    private def readAndDecodeTastyFile(
        file: String,
        interner: Interner,
        source: FileSource,
        cp: Classpath,
        strict: Boolean
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        Abort.run[TastyError](
            source.read(file).flatMap: bytes =>
                Sync.Unsafe.defer:
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(bytes.length.toLong)
                .andThen(decodeTastyBytes(file, bytes, interner, cp))
        ).map:
            case Result.Success(fr) => fr
            case Result.Failure(err: TastyError) =>
                if strict then
                    Abort.fail(err)
                else
                    emptyFileResultWithError(file, err)
            case Result.Panic(t) =>
                val err = TastyError.CorruptedFile(file, 0L, t.getMessage)
                if strict then
                    Abort.fail(err)
                else
                    emptyFileResultWithError(file, err)
                end if

    /** Produce an empty FileResult carrying a single error (soft-fail path). */
    private def emptyFileResultWithError(file: String, err: TastyError): FileResult =
        FileResult(
            Chunk.empty,
            TypeArena.canonical(),
            Seq(err),
            Chunk.empty,
            mutable.HashMap.empty[Tasty.Symbol, Chunk[Tasty.Type]],
            mutable.HashMap.empty[Tasty.Symbol, Chunk[Tasty.Symbol]],
            mutable.HashMap.empty[Tasty.Symbol, Tasty.Type],
            Map.empty,
            Map.empty,
            mutable.HashMap.empty[Tasty.Symbol, Tasty.Symbol],
            mutable.HashMap.empty[Tasty.Symbol, (Int, Int)],
            Array.empty[Byte],
            0,
            Array.empty[Tasty.Name]
        )

    /** Wrap a synchronous Kyo computation, adding elapsed nanoseconds to `counter` after it completes.
      *
      * `t0` is captured when `timed` is invoked -- i.e., when this for-yield step starts. The `.map` fires after `v` returns, so the delta
      * covers exactly the unpickler's execution. Safe only for purely-Sync computations with no Async suspension (all unpicklers in
      * decodeTastyBytes satisfy this invariant).
      *
      * Requires AllowUnsafe because UnsafeCounter.add() is an unsafe operation. Callers must hold (using AllowUnsafe) per CONTRIBUTING.md
      * SS870-SS897.
      */
    private def timed[A, S](counter: kyo.stats.internal.UnsafeCounter)(v: A < S)(using Frame, AllowUnsafe): A < S =
        val t0 = java.lang.System.nanoTime()
        v.map: a =>
            counter.add(java.lang.System.nanoTime() - t0)
            a
    end timed

    /** Decode TASTy bytes into a FileResult (fqn-symbol pairs + arena). */
    private def decodeTastyBytes(
        file: String,
        bytes: Array[Byte],
        interner: Interner,
        cp: Classpath
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        // flow-allow: §839 case 3; all Name.asString calls in the yield block read immutable intern-pool strings; no suspension required.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val view          = ByteView(bytes)
        val home          = ClasspathRef.init()
        val arena         = new TypeArena
        for
            _        <- timed(TastyPerfStats.tastyHeaderNs)(TastyHeader.read(view))
            names    <- timed(TastyPerfStats.nameUnpicklerNs)(NameUnpickler.read(view, interner))
            sections <- timed(TastyPerfStats.sectionIndexNs)(SectionIndex.read(view, names))
            attrs <- timed(TastyPerfStats.attributeUnpicklerNs)(sections.get(TastyFormat.AttributesSection) match
                case Present((offset, length)) =>
                    val attrView = view.subView(offset, offset + length)
                    AttributeUnpickler.read(attrView, names)
                case Absent =>
                    Sync.defer(FileAttributes.default))
            pass1Result <- timed(TastyPerfStats.astPass1Ns)(sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    // no cursor: missing section detected at orchestration level, before stream access
                    Abort.fail(TastyError.MalformedSection("ASTs", s"$file: ASTs section not found", 0L)))
            commentsBySymbol <- timed(TastyPerfStats.commentsUnpicklerNs)(sections.get(TastyFormat.CommentsSection) match
                case Present((offset, length)) =>
                    val commentsView = view.subView(offset, offset + length)
                    CommentsUnpickler.read(commentsView, pass1Result.addrMap)
                case Absent =>
                    Sync.defer(Map.empty[Tasty.Symbol, String]))
            positionsBySymbol <- timed(TastyPerfStats.positionsUnpicklerNs)(sections.get(TastyFormat.PositionsSection) match
                case Present((offset, length)) =>
                    val posView = view.subView(offset, offset + length)
                    PositionsUnpickler.read(posView, pass1Result.addrMap, attrs.sourceFile)
                case Absent =>
                    Sync.defer(Map.empty[Tasty.Symbol, Tasty.Position]))
        yield
            // plan: phase-02 bridge; computeFqn walks the ownerBySymbol chain to build the dotted FQN.
            // Replaces the old sym.fullName OnceCell that walked sym.owner. Phase 09 adds Symbol.fullName
            // as a member method once Classpath is a pure case class.
            val ownerBySymbol = pass1Result.ownerBySymbol
            val pairs = pass1Result.symbols.flatMap: sym =>
                val fqn = computeFqn(sym, ownerBySymbol)
                if fqn.nonEmpty then Chunk((fqn, sym)) else Chunk.empty
            FileResult(
                pairs,
                arena,
                Seq.empty,
                pass1Result.placeholders,
                pass1Result.parentsBySymbol,
                pass1Result.childrenByOwner,
                pass1Result.typeBySymbol,
                commentsBySymbol,
                positionsBySymbol,
                pass1Result.ownerBySymbol,
                pass1Result.bodyDataByAddr,
                pass1Result.sectionBytes,
                pass1Result.sectionOffset,
                pass1Result.names
            )
        end for
    end decodeTastyBytes

    /** Compute the dotted FQN for `sym` by walking the ownerBySymbol chain.
      *
      * plan: phase-02 bridge; replaces sym.fullName. Deleted in Phase 09 when Symbol gains a fullName member.
      */
    private def computeFqn(
        sym: Tasty.Symbol,
        ownerBySymbol: mutable.HashMap[Tasty.Symbol, Tasty.Symbol]
    )(using AllowUnsafe): String =
        import Tasty.Name.asString
        val parts = new scala.collection.mutable.ArrayBuffer[String]()
        var cur   = sym
        // Sentinel: the root symbol owns itself (added as self-ref) or doesn't appear in ownerBySymbol.
        val visited = new java.util.HashSet[Tasty.Symbol]()
        while cur != null && visited.add(cur) do
            val n = cur.name.asString
            if n.nonEmpty then parts.prepend(n)
            cur = ownerBySymbol.getOrElse(cur, null)
        end while
        parts.filter(_.nonEmpty).mkString(".")
    end computeFqn

    /** Convert a Name (opaque Interner.Entry) to a String. */
    private def nameToString(n: Tasty.Name)(using AllowUnsafe): String =
        import Tasty.Name.asString
        n.asString
    end nameToString

    /** Create a synthetic unresolved symbol for a FQN not found in fqnIndex.
      *
      * Mirrors TypeUnpickler.makeUnresolvedSym; duplicated here to avoid promoting a private method across package boundaries.
      */
    private def makeUnresolvedSym(fqn: String)(using AllowUnsafe): Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name(fqn)
        )

end ClasspathOrchestrator
