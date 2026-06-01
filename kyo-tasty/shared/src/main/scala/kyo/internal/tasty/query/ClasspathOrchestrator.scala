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
import kyo.internal.tasty.symbol.TypedSymbolFactory
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
      * to ClasspathOrchestrator Pass C; removed in Phase 12 when the pipeline fully uses SymbolDescriptor.
      */
    final private case class FileResult(
        fqns: Chunk[(String, Tasty.Symbol)],
        arena: TypeArena,
        errors: Seq[TastyError],
        placeholders: Chunk[Nothing], // placeholder field; always empty after Phase 07
        parentsBySymbol: mutable.HashMap[Tasty.Symbol, Chunk[Tasty.Type]],
        childrenByOwner: mutable.HashMap[Tasty.Symbol, Chunk[Tasty.Symbol]],
        typeBySymbol: mutable.HashMap[Tasty.Symbol, Tasty.Type],
        commentsBySymbol: Map[Tasty.Symbol, String],
        positionsBySymbol: Map[Tasty.Symbol, Tasty.Position],
        ownerBySymbol: mutable.HashMap[Tasty.Symbol, Tasty.Symbol],
        bodyDataByAddr: mutable.HashMap[Tasty.Symbol, (Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        fileNames: Array[Tasty.Name],
        /** Per-file TASTy address -> partial symbol map, used in Phase C to remap Phase-B temporary SymbolIds (PHASE_B_ADDR_OFFSET + addr)
          * to final SymbolIds.
          */
        addrMap: scala.collection.immutable.IntMap[Tasty.Symbol],
        /** Cross-file unresolved FQN tracking: maps unique negative SymbolIds to FQNs for Phase C parent type resolution. */
        unresolvedIdToFqn: mutable.HashMap[Int, String],
        /** F-G-001 fix: per-symbol annotation lists decoded from ANNOTATION modifier blocks. Populated in Phase B by AstUnpickler;
          * consumed by finalizeMerge to write descs(idx).annotations.
          */
        annotationsBySymbol: mutable.HashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]]
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

    /** Init a new classpath from a set of root paths.
      *
      * Roots may be directories containing `.tasty` files or individual `.tasty` files.
      */
    def init(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        source: FileSource,
        concurrency: Int
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        // Validate roots exist first (both FailFast and SoftFail report FileNotFound immediately)
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex then Abort.fail(TastyError.FileNotFound(root))
                else Kyo.unit
        .andThen:
            // Install a read-batch context so JvmFileSource can share mmap readers across the scan+decode pipeline.
            // The default FileSource.withReadBatch is a no-op; JvmFileSource overrides it.
            source.withReadBatch:
                runPhaseAB(roots, mode, source, concurrency)

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
        mode: Tasty.ErrorMode,
        source: FileSource,
        concurrency: Int
    )(using Frame): Tasty.Classpath < (Sync & Async & Abort[TastyError]) =
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
        // §839 case 3; pipeline-launch boundary; timing slots allocated once per open() call.
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
                                            decodeOneEntry(entryPath, kind, interner, source, mode).flatMap: result =>
                                                // If resultCh closed early (FailFast abort), silently discard
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
                                .andThen(finalizeMerge(mergeState, source, mode)).flatMap: result =>
                                    (if timingEnabled then
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
                                    ).andThen(result)
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
        mode: Tasty.ErrorMode
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
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else
                        // Soft-fail: produce an empty FileResult with the error recorded
                        FileResultCase(emptyFileResultWithError(entryPath, err))
                case Result.Panic(t) =>
                    val err = TastyError.CorruptedFile(entryPath, 0L, t.getMessage)
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
        else
            Scope.run:
                readAndDecodeTastyFile(entryPath, interner, source, mode).map(FileResultCase.apply)

    /** Merge one DecodeResult into the MergeState. Single-threaded (only the merger fiber calls it). */
    private def mergeOneInto(state: MergeState, result: DecodeResult): Unit =
        result match
            case FileResultCase(fr) =>
                // Add ALL symbols (including members) to allSyms so that finalizeMerge can look them
                // up by index when building typeParamIds/declarationIds.
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
                                prev.kind == Tasty.SymbolKind.Trait || prev.kind == Tasty.SymbolKind.Object ||
                                prev.kind == Tasty.SymbolKind.EnumCase
                            val newIsStructural = sym.kind == Tasty.SymbolKind.Class ||
                                sym.kind == Tasty.SymbolKind.Trait || sym.kind == Tasty.SymbolKind.Object ||
                                sym.kind == Tasty.SymbolKind.EnumCase
                            newIsStructural || !prevIsStructural
                    if shouldStore then state.fqnIndex(indexKey) = sym
                    // F-E-001 / Q-003 / HARD RULE 8 dual-index (source FQN):
                    // Pattern: `<pkg>.<owner>$package$.<TypeName>` -> source FQN is `<pkg>.<TypeName>`.
                    // Only applies when the opaque type is a DIRECT child of a `$package$` owner:
                    // the segment after `$package$.` must contain no dot (to avoid indexing nested members).
                    // Algorithm (per plan Q-003 regex): find `$package$.`, extract <pkg> as everything
                    // before the last dot before `$package$`, and <TypeName> as the segment after `$package$.`.
                    if sym.kind == Tasty.SymbolKind.OpaqueType then
                        val pkgSuffix    = "$package$."
                        val pkgSuffixIdx = fqn.indexOf(pkgSuffix)
                        if pkgSuffixIdx >= 0 then
                            val afterPkg = fqn.substring(pkgSuffixIdx + pkgSuffix.length)
                            // Only register source FQN for direct opaque type children (no further dots).
                            if !afterPkg.contains('.') then
                                val beforePkg      = fqn.substring(0, pkgSuffixIdx) // e.g. "kyo.Maybe"
                                val lastDot        = beforePkg.lastIndexOf('.')
                                val pkgPrefix      = if lastDot >= 0 then beforePkg.substring(0, lastDot + 1) else ""
                                val sourceFqn      = pkgPrefix + afterPkg           // e.g. "kyo." + "Maybe" = "kyo.Maybe"
                                val existingSource = state.fqnIndex.get(sourceFqn)
                                val storeSource = existingSource match
                                    case None       => true
                                    case Some(prev) => prev.kind != Tasty.SymbolKind.OpaqueType
                                if storeSource then state.fqnIndex(sourceFqn) = sym
                            end if
                        end if
                    end if
                    // F-I-006 / HARD RULE 8 dual-index Rule A: Object companion source FQN.
                    // When the binary key ends in `$` (all Object symbols; line 337 appended it),
                    // also register the source FQN (key without the trailing `$`).
                    // e.g. `scala.Predef$` -> also index `scala.Predef`.
                    // Guard: additive only -- write source key only when NO entry exists yet.
                    // A class or trait already registered at the source key takes precedence.
                    // A Val forwarder (non-structural) is overwritten by the Object so that
                    // the user-facing source FQN resolves to the canonical companion Object.
                    if sym.kind == Tasty.SymbolKind.Object && indexKey.endsWith("$") then
                        val sourceFqn = indexKey.stripSuffix("$")
                        if sourceFqn.nonEmpty then
                            val existingSource = state.fqnIndex.get(sourceFqn)
                            val storeSource = existingSource match
                                case None       => true
                                case Some(prev) =>
                                    // Object wins over non-structural entries (Val, Method, etc.)
                                    // but NOT over Class, Trait, EnumCase, or another Object.
                                    prev.kind != Tasty.SymbolKind.Class &&
                                    prev.kind != Tasty.SymbolKind.Trait &&
                                    prev.kind != Tasty.SymbolKind.Object &&
                                    prev.kind != Tasty.SymbolKind.EnumCase
                            if storeSource then state.fqnIndex(sourceFqn) = sym
                        end if
                    end if
                    // F-I-006 / HARD RULE 8 dual-index Rule B: object-nested member source FQN.
                    // When the binary key contains `$.` (a member nested inside an object, e.g.
                    // `kyo.Tasty$.Symbol`), also register the source FQN with `$.` replaced by `.`
                    // (e.g. `kyo.Tasty.Symbol`).
                    // Rule B is applied AFTER Rule A; an Object key ending in `$` (e.g. `scala.Predef$`)
                    // does NOT contain `$.` so there is no overlap between the two rules.
                    // Guard: strictly additive -- write source key ONLY when it is absent.
                    // If a natural `fr.fqns` iteration has already registered a direct source-FQN
                    // entry, that entry is more authoritative than any Rule-B-derived key and must
                    // not be overwritten. Rule B fills gaps; it does not override direct registrations.
                    if indexKey.contains("$.") then
                        val sourceFqn = indexKey.replace("$.", ".")
                        if state.fqnIndex.get(sourceFqn).isEmpty then
                            state.fqnIndex(sourceFqn) = sym
                        end if
                    end if
                    if seenSyms.add(sym) then state.allSyms += sym
                    sym.kind match
                        case Tasty.SymbolKind.Package =>
                            state.packages += sym
                            state.packageIndex(fqn) = sym
                        case Tasty.SymbolKind.Class | Tasty.SymbolKind.Trait | Tasty.SymbolKind.Object |
                            Tasty.SymbolKind.EnumCase =>
                            state.topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end for
                state.accErrors ++= fr.errors
                state.fileResults += fr
            case ModuleInfoCase(name, md) =>
                state.moduleIndex(name) = md

    /** Phase C: SymbolDescriptor construction + single-shot Symbol materialization + index building.
      *
      * Runs once after the merger has drained the result channel. Builds SymbolDescriptors from the accumulated per-file data, calls
      * materializeSymbols to produce the final immutable Symbol case-class instances, builds all index maps, and returns the public
      * Tasty.Classpath case class.
      */
    private def finalizeMerge(
        state: MergeState,
        source: FileSource,
        mode: Tasty.ErrorMode
    )(using Frame): Tasty.Classpath < Sync =
        val canonical   = TypeArena.canonical()
        val fileResults = state.fileResults.toSeq
        val allPartial  = state.allSyms
        val topLevelCls = state.topLevelCls
        val packages    = state.packages
        val accErrors   = state.accErrors
        val moduleIndex = state.moduleIndex.toMap

        TastyStat.scope.traceSpan("finalize.mergeArenas") {
            Sync.Unsafe.defer:
                for fr <- fileResults do canonical.merge(fr.arena)
                end for
        }.andThen:
            TastyStat.scope.traceSpan("finalize.materializeSymbols") {
                Sync.Unsafe.defer:
                    // Build a map from partial Symbol -> its final SymbolId (index in allPartial).
                    val symbolIdMap = new java.util.HashMap[Tasty.Symbol, Int](allPartial.length * 2)
                    var i           = 0
                    for sym <- allPartial do
                        symbolIdMap.put(sym, i)
                        i += 1
                    end for

                    val count = allPartial.length
                    val descs = new Array[SymbolDescriptor](count)
                    i = 0
                    for partialSym <- allPartial do
                        val id = i
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

                    for fr <- fileResults do
                        for (sym, owner) <- fr.ownerBySymbol do
                            val symIdx   = symbolIdMap.get(sym)
                            val ownerIdx = symbolIdMap.getOrDefault(owner, symIdx)
                            if symIdx >= 0 && symIdx < count then
                                descs(symIdx).ownerId = ownerIdx
                        end for
                    end for

                    // Build per-file remapping structures for Phase-B temporary SymbolId resolution.
                    //
                    // Two kinds of temporary IDs from Phase B:
                    // 1. PHASE_B_ADDR_OFFSET + addr: local same-file symbol references (TYPEREFdirect/TERMREFdirect/TYPEREFsymbol).
                    //    Remapped using the file's addrMap -> symbolIdMap.
                    // 2. Unique negative IDs (< -1): cross-file FQN references (TYPEREFpkg/TERMREFpkg/TYPEREFin).
                    //    Remapped by looking up the FQN in fqnIndex (the merged global FQN map).
                    val phaseBOffset = kyo.internal.tasty.reader.TypeUnpickler.PHASE_B_ADDR_OFFSET

                    // Build the global fqnIndex BEFORE parent remap (it's built after materializing symbols, need to compute it).
                    // We use state.fqnIndex (the merged fqnIndex from MergeState) as the lookup source since fqnIndex contains partial syms.
                    // After symbolIdMap is built, fqnIndex partial syms can be resolved to final IDs.

                    final case class FileRemap(
                        addrToFinal: java.util.HashMap[Int, Int],
                        negIdToFinal: java.util.HashMap[Int, Int]
                    )

                    val fileRemaps = fileResults.map: fr =>
                        // Map 1: addr -> finalId (for PHASE_B_ADDR_OFFSET refs)
                        val addrToFinal = new java.util.HashMap[Int, Int](fr.addrMap.size * 2)
                        fr.addrMap.foreach { case (addr, partialSym) =>
                            val finalIdx = symbolIdMap.getOrDefault(partialSym, -1)
                            if finalIdx >= 0 then discard(addrToFinal.put(addr, finalIdx))
                        }
                        // Map 2: negId -> finalId (for cross-file FQN refs)
                        val negIdToFinal = new java.util.HashMap[Int, Int](fr.unresolvedIdToFqn.size * 2)
                        fr.unresolvedIdToFqn.foreach { case (negId, fqn) =>
                            // Look up FQN in the partial fqnIndex and then get the final SymbolId
                            state.fqnIndex.get(fqn) match
                                case Some(partialSym) =>
                                    val finalIdx = symbolIdMap.getOrDefault(partialSym, -1)
                                    if finalIdx >= 0 then discard(negIdToFinal.put(negId, finalIdx))
                                case None =>
                                    // FQN not found: leave as unresolved
                                    ()
                        }
                        FileRemap(addrToFinal, negIdToFinal)
                    .toArray

                    def remapType(t: Tasty.Type, fr: FileRemap): Tasty.Type =
                        t match
                            case Tasty.Type.Named(sid) =>
                                val v = sid.value
                                if v >= phaseBOffset then
                                    // Local same-file reference
                                    val addr     = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(addr, -1)
                                    if finalIdx >= 0 then Tasty.Type.Named(SymbolId(finalIdx))
                                    else t
                                else if v < -1 then
                                    // Cross-file FQN reference (unique negative ID)
                                    val finalIdx = fr.negIdToFinal.getOrDefault(v, -1)
                                    if finalIdx >= 0 then Tasty.Type.Named(SymbolId(finalIdx))
                                    else t
                                else t
                                end if
                            case Tasty.Type.Applied(base, args) =>
                                val newBase = remapType(base, fr)
                                val newArgs = args.map(remapType(_, fr))
                                Tasty.Type.Applied(newBase, newArgs)
                            // F-A-001 fix: recurse into TypeLambda so that cross-file Named refs
                            // inside the body (result type, param type) get resolved by Phase C.
                            case Tasty.Type.TypeLambda(paramIds, body) =>
                                Tasty.Type.TypeLambda(paramIds, remapType(body, fr))
                            case Tasty.Type.Function(params, result, isCtx) =>
                                Tasty.Type.Function(params.map(remapType(_, fr)), remapType(result, fr), isCtx)
                            case Tasty.Type.ByName(underlying) =>
                                Tasty.Type.ByName(remapType(underlying, fr))
                            case Tasty.Type.Repeated(elem) =>
                                Tasty.Type.Repeated(remapType(elem, fr))
                            case Tasty.Type.Array(elem) =>
                                Tasty.Type.Array(remapType(elem, fr))
                            case Tasty.Type.AndType(left, right) =>
                                Tasty.Type.AndType(remapType(left, fr), remapType(right, fr))
                            case Tasty.Type.OrType(left, right) =>
                                Tasty.Type.OrType(remapType(left, fr), remapType(right, fr))
                            case Tasty.Type.Refinement(parent, name, info) =>
                                Tasty.Type.Refinement(remapType(parent, fr), name, remapType(info, fr))
                            case Tasty.Type.Annotated(underlying, ann) =>
                                Tasty.Type.Annotated(remapType(underlying, fr), ann)
                            case Tasty.Type.SuperType(thisType, underlying) =>
                                Tasty.Type.SuperType(remapType(thisType, fr), remapType(underlying, fr))
                            case Tasty.Type.Wildcard(lo, hi) =>
                                Tasty.Type.Wildcard(remapType(lo, fr), remapType(hi, fr))
                            case Tasty.Type.MatchType(bound, scrut, cases) =>
                                Tasty.Type.MatchType(remapType(bound, fr), remapType(scrut, fr), cases.map(remapType(_, fr)))
                            case Tasty.Type.FlexibleType(underlying) =>
                                Tasty.Type.FlexibleType(remapType(underlying, fr))
                            case Tasty.Type.MatchCase(pat, rhs) =>
                                Tasty.Type.MatchCase(remapType(pat, fr), remapType(rhs, fr))
                            case Tasty.Type.Rec(parent) =>
                                Tasty.Type.Rec(remapType(parent, fr))
                            case Tasty.Type.RecThis(rec) =>
                                Tasty.Type.RecThis(remapType(rec, fr))
                            case Tasty.Type.Skolem(underlying) =>
                                Tasty.Type.Skolem(remapType(underlying, fr))
                            case Tasty.Type.TermRef(prefix, name) =>
                                Tasty.Type.TermRef(remapType(prefix, fr), name)
                            // F-A-005 fix: remap ThisType using the same PHASE_B_ADDR_OFFSET scheme as Named.
                            case Tasty.Type.ThisType(clsId) =>
                                val v = clsId.value
                                if v >= phaseBOffset then
                                    val addr     = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(addr, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else t
                                end if
                            case _ => t

                    var frIdx2 = 0
                    for fr <- fileResults do
                        val frRemap = fileRemaps(frIdx2)
                        for (sym, parents) <- fr.parentsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count then
                                descs(idx).parentTypes = parents.map(remapType(_, frRemap))
                        end for
                        frIdx2 += 1
                    end for

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

                    var frIdx3 = 0
                    for fr <- fileResults do
                        val frRemap3 = fileRemaps(frIdx3)
                        for (sym, t) <- fr.typeBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).declaredType.isEmpty then
                                descs(idx).declaredType = Maybe(remapType(t, frRemap3))
                        end for
                        frIdx3 += 1
                    end for

                    // Default declaredType for Class/Trait/Object/EnumCase: Type.Named(SymbolId(i)).
                    // N-03 fix: use SymbolId(i) not sym.id (which was SymbolId(-1) for all partial symbols).
                    i = 0
                    for sym <- allPartial do
                        if descs(i).declaredType.isEmpty then
                            val k = sym.kind
                            if k == Tasty.SymbolKind.Class || k == Tasty.SymbolKind.Trait
                                || k == Tasty.SymbolKind.Object || k == Tasty.SymbolKind.EnumCase
                            then
                                descs(i).declaredType = Maybe(Tasty.Type.Named(SymbolId(i)))
                            end if
                        end if
                        i += 1
                    end for

                    for fr <- fileResults do
                        for (sym, text) <- fr.commentsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).scaladoc.isEmpty then
                                descs(idx).scaladoc = Maybe(text)
                        end for
                    end for

                    for fr <- fileResults do
                        for (sym, pos) <- fr.positionsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && descs(idx).sourcePosition.isEmpty then
                                descs(idx).sourcePosition = Maybe(pos)
                        end for
                    end for

                    // F-G-001 fix: populate annotations from ANNOTATION modifier blocks decoded in Phase B.
                    // Each annotation's tycon may contain Named(negId) cross-file refs; remap through
                    // the same FileRemap used for parent types so symbolsAnnotatedWith FQN matching works.
                    var frIdxAnn = 0
                    for fr <- fileResults do
                        val frRemapAnn = fileRemaps(frIdxAnn)
                        for (sym, annBuf) <- fr.annotationsBySymbol do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count then
                                val remapped = annBuf.map: ann =>
                                    Tasty.Annotation(remapType(ann.annotationType, frRemapAnn), ann.args)
                                descs(idx).annotations = Chunk.from(remapped.toSeq)
                            end if
                        end for
                        frIdxAnn += 1
                    end for

                    // F-I-003 / INV-007 fix: populate permittedSubclassIds by mining @Child annotations.
                    //
                    // Scala 3 TASTy encodes sealed children as @scala.annotation.internal.Child[T]
                    // annotations on the sealed parent. Phase 07 extends the annotation decoder in
                    // AstUnpickler.decodeChildAnnotationType to decode the fullAnnotation_Term for
                    // @Child annotations and produce Type.Applied(TermRef(_, "Child"), Chunk(subT)).
                    // The first type argument (subT) is the permitted-subclass TypeRef.
                    //
                    // This loop runs AFTER the annotation-merge loop above so descs(idx).annotations
                    // already contains fully-remapped final SymbolIds. We identify @Child by matching
                    // Type.Applied(Type.TermRef(_, name), args) where name.asString == "Child".
                    //
                    // Two TypeRef shapes seen in practice:
                    //   (a) Type.Named(id) -- direct same-file reference, id >= 0 is a final SymbolId.
                    //   (b) Type.TermRef(Type.Named(qualId), name) -- TYPEREF with a qualifier, i.e.
                    //       "name in the scope of symbol qualId". Construct the FQN and look up in
                    //       state.fqnIndex to get the final SymbolId.
                    import kyo.Tasty.Name.asString

                    // Build a reverse index: final SymbolId value -> FQN string, for use by resolveChildRef.
                    // This is a lightweight reverse of state.fqnIndex used only for permit extraction.
                    val idToFqnForPermits = new java.util.HashMap[Int, String](state.fqnIndex.size * 2)
                    for (fqn, partialSym) <- state.fqnIndex do
                        val idx = symbolIdMap.getOrDefault(partialSym, -1)
                        if idx >= 0 && !idToFqnForPermits.containsKey(idx) then
                            discard(idToFqnForPermits.put(idx, fqn))
                    end for

                    /** Resolve a Type (subclass ref from @Child annotation) to a final SymbolId value.
                      *
                      * Handles Type.Named (direct ref) and Type.TermRef (TYPEREF with qualifier).
                      * For singleton objects like scala.None, the @Child annotation references the
                      * module val FQN (scala.None). We detect this by checking if the resolved symbol
                      * is a Val kind (module val), then resolve to the module class FQN (scala.None$).
                      *
                      * Returns -1 if unresolvable (entry skipped per HARD RULE 2).
                      */
                    /** Resolve a qualifier SymbolId to its FQN string, or null if unresolvable. */
                    def qualIdToFqn(qualId: Int): String | Null =
                        idToFqnForPermits.get(qualId)
                    end qualIdToFqn

                    /** Look up a FQN in fqnIndex and return its final SymbolId, handling module vals.
                      * If the direct lookup hits a Val kind (module val), redirect to the module class "$" FQN.
                      */
                    def fqnToId(fqn: String): Int =
                        state.fqnIndex.get(fqn) match
                            case Some(p) =>
                                val rawId = symbolIdMap.getOrDefault(p, -1)
                                if rawId >= 0 && rawId < count && descs(rawId).kind == Tasty.SymbolKind.Val then
                                    state.fqnIndex.get(fqn + "$") match
                                        case Some(p2) =>
                                            val moduleId = symbolIdMap.getOrDefault(p2, -1)
                                            if moduleId >= 0 then moduleId else rawId
                                        case None => rawId
                                else rawId
                                end if
                            case None =>
                                // Fallback: try the module class FQN (for singleton objects encoded as "$").
                                state.fqnIndex.get(fqn + "$") match
                                    case Some(p) => symbolIdMap.getOrDefault(p, -1)
                                    case None    => -1
                    end fqnToId

                    def resolveChildRef(t: Tasty.Type): Int =
                        t match
                            case Tasty.Type.Named(sid) if sid.value >= 0 =>
                                sid.value
                            case Tasty.Type.TermRef(Tasty.Type.Named(qualSid), memberName)
                                if qualSid.value >= 0 =>
                                val qualFqn = qualIdToFqn(qualSid.value)
                                if qualFqn != null then
                                    val fqn = if qualFqn.nonEmpty then qualFqn + "." + memberName.asString
                                    else memberName.asString
                                    fqnToId(fqn)
                                else -1
                                end if
                            case Tasty.Type.TermRef(Tasty.Type.ThisType(clsSid), memberName)
                                if clsSid.value >= 0 =>
                                // Enum case encoding: TermRef(ThisType(enclosingClassId), caseName).
                                // The qualifier is the enclosing class's ThisType; build the FQN from
                                // enclosingClass FQN + "." + caseName.
                                val qualFqn = qualIdToFqn(clsSid.value)
                                if qualFqn != null then
                                    val fqn = if qualFqn.nonEmpty then qualFqn + "." + memberName.asString
                                    else memberName.asString
                                    fqnToId(fqn)
                                else -1
                                end if
                            case _ => -1
                    end resolveChildRef

                    var permitIdx = 0
                    while permitIdx < count do
                        val desc = descs(permitIdx)
                        val k    = desc.kind
                        if k == Tasty.SymbolKind.Class || k == Tasty.SymbolKind.Trait
                            || k == Tasty.SymbolKind.EnumCase
                        then
                            val anns = desc.annotations
                            if anns.nonEmpty then
                                val buf = new scala.collection.mutable.ArrayBuffer[Int]()
                                var ai  = 0
                                while ai < anns.size do
                                    anns(ai).annotationType match
                                        case Tasty.Type.Applied(Tasty.Type.TermRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // @Child[T] enriched tycon: extract the subclass TypeRef T.
                                            val subId = resolveChildRef(args(0))
                                            if subId >= 0 then buf += subId
                                        case _ => ()
                                    end match
                                    ai += 1
                                end while
                                if buf.nonEmpty then
                                    desc.permittedSubclassIds = Maybe(Chunk.from(buf.toSeq))
                            end if
                        end if
                        permitIdx += 1
                    end while

                    for fr <- fileResults do
                        for (sym, (bodyStart, bodyEnd)) <- fr.bodyDataByAddr do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && bodyStart > 0 && bodyEnd > bodyStart then
                                descs(idx).body = Maybe(Tasty.SymbolBody(
                                    bodyStart = bodyStart,
                                    bodyEnd = bodyEnd,
                                    sectionBytes = fr.sectionBytes,
                                    names = fr.fileNames,
                                    sectionOffset = fr.sectionOffset,
                                    addrMap = scala.collection.immutable.IntMap.empty
                                ))
                            end if
                        end for
                    end for

                    val finalSymbols = materializeSymbols(descs, count)

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

                    val finalErrors = Chunk.from(accErrors)
                    val symsChunk   = Chunk.from(finalSymbols)
                    val fqnIdIdx    = newFqnIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
                    val pkgIdIdx    = newPackageIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
                    val topIds      = Chunk.from(newTopLevelCls.map(_.id))
                    val pkgIds      = Chunk.from(newPackages.map(_.id))
                    val rootId      = if finalSymbols.nonEmpty then SymbolId(0) else SymbolId(-1)

                    val subclassIdx  = buildSubclassIndex(symsChunk)
                    val companionIdx = buildCompanionIndex(symsChunk, fqnIdIdx)

                    Tasty.Classpath.make(
                        symbols = symsChunk,
                        rootSymbolId = rootId,
                        topLevelClassIds = topIds,
                        packageIds = pkgIds,
                        fqnIndex = fqnIdIdx,
                        packageIndex = pkgIdIdx,
                        subclassIndex = subclassIdx,
                        companionIndex = companionIdx,
                        moduleIndex = moduleIndex,
                        errors = finalErrors,
                        canonical = canonical
                    )
            }
    end finalizeMerge

    /** Build subclassIndex by inverting parentTypes: for each symbol, register it as a direct subclass of each Named parent.
      *
      * Handles both direct `Named(pid)` and `Applied(Named(pid), args)` cases, since in TASTy a class parent is often encoded as
      * `APPLY(TYPEREFsymbol(addr), constructor_args)` which decodes to `Applied(Named(baseId), args)`.
      */
    private def buildSubclassIndex(symbols: Chunk[Tasty.Symbol])(using AllowUnsafe): Map[SymbolId, Chunk[SymbolId]] =
        val b = scala.collection.mutable.HashMap.empty[SymbolId, scala.collection.mutable.ArrayBuffer[SymbolId]]

        def extractNamedId(t: Tasty.Type): Maybe[SymbolId] = t match
            case Tasty.Type.Named(pid)       => Maybe(pid)
            case Tasty.Type.Applied(base, _) => extractNamedId(base)
            case Tasty.Type.ThisType(cid)    => Maybe(cid)
            case _                           => Maybe.Absent

        var i = 0
        while i < symbols.length do
            val s = symbols(i)
            (s match
                case c: Tasty.Symbol.ClassLike => c.parentTypes;
                case _                         => Chunk.empty
            ).foreach: parent =>
                extractNamedId(parent) match
                    case Maybe.Present(pid) if pid.value >= 0 =>
                        val buf = b.getOrElseUpdate(pid, scala.collection.mutable.ArrayBuffer.empty)
                        buf += s.id
                    case _ => ()
            i += 1
        end while
        b.iterator.map((pid, buf) => pid -> Chunk.from(buf.toSeq)).toMap
    end buildSubclassIndex

    /** Build companionIndex: for each Class, look up its Object companion (FQN + "$") and vice versa. */
    private def buildCompanionIndex(
        symbols: Chunk[Tasty.Symbol],
        fqnIndex: Map[String, SymbolId]
    )(using AllowUnsafe): Map[SymbolId, SymbolId] =
        val b = Map.newBuilder[SymbolId, SymbolId]
        // We need symbol FQNs. Build a quick index: SymbolId.value -> FQN from fqnIndex inverse.
        val idToFqn = new java.util.HashMap[Int, String](fqnIndex.size * 2)
        for (fqn, sid) <- fqnIndex do idToFqn.put(sid.value, fqn)
        var i = 0
        while i < symbols.length do
            val s   = symbols(i)
            val fqn = idToFqn.get(s.id.value)
            if fqn != null then
                val companionFqn =
                    if s.kind == Tasty.SymbolKind.Class || s.kind == Tasty.SymbolKind.Trait
                        || s.kind == Tasty.SymbolKind.EnumCase
                    then
                        fqn + "$"
                    else if s.kind == Tasty.SymbolKind.Object then
                        if fqn.endsWith("$") then fqn.dropRight(1) else fqn
                    else null
                if companionFqn != null then
                    fqnIndex.get(companionFqn) match
                        case Some(cid) => b += s.id -> cid
                        case None      => ()
                end if
            end if
            i += 1
        end while
        b.result()
    end buildCompanionIndex

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
            out(i) = TypedSymbolFactory.from(d)
            i += 1
        end while
        out
    end materializeSymbols

    /** Read bytes and decode a single TASTy file. Returns FileResult. */
    private def readAndDecodeTastyFile(
        file: String,
        interner: Interner,
        source: FileSource,
        mode: Tasty.ErrorMode
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        Abort.run[TastyError](
            source.read(file).flatMap: bytes =>
                Sync.Unsafe.defer:
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(bytes.length.toLong)
                .andThen(decodeTastyBytes(file, bytes, interner))
        ).map:
            case Result.Success(fr) => fr
            case Result.Failure(err: TastyError) =>
                if mode == Tasty.ErrorMode.FailFast then
                    Abort.fail(err)
                else
                    emptyFileResultWithError(file, err)
            case Result.Panic(t) =>
                val err = TastyError.CorruptedFile(file, 0L, t.getMessage)
                if mode == Tasty.ErrorMode.FailFast then
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
            Chunk.empty[Nothing],
            mutable.HashMap.empty[Tasty.Symbol, Chunk[Tasty.Type]],
            mutable.HashMap.empty[Tasty.Symbol, Chunk[Tasty.Symbol]],
            mutable.HashMap.empty[Tasty.Symbol, Tasty.Type],
            Map.empty,
            Map.empty,
            mutable.HashMap.empty[Tasty.Symbol, Tasty.Symbol],
            mutable.HashMap.empty[Tasty.Symbol, (Int, Int)],
            Array.empty[Byte],
            0,
            Array.empty[Tasty.Name],
            scala.collection.immutable.IntMap.empty[Tasty.Symbol],
            mutable.HashMap.empty[Int, String],
            mutable.HashMap.empty[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]]
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
        interner: Interner
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        // §839 case 3; all Name.asString calls in the yield block read immutable intern-pool strings; no suspension required.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val view          = ByteView(bytes)
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
                    AstUnpickler.readPass1(astView, names, attrs, arena)
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
            // computeFqn walks the ownerBySymbol chain to build the dotted FQN.
            val ownerBySymbol = pass1Result.ownerBySymbol
            val pairs = pass1Result.symbols.flatMap: sym =>
                val fqn = computeFqn(sym, ownerBySymbol)
                if fqn.nonEmpty then Chunk((fqn, sym)) else Chunk.empty
            FileResult(
                pairs,
                arena,
                pass1Result.annotationDecodeErrors,
                Chunk.empty[Nothing],
                pass1Result.parentsBySymbol,
                pass1Result.childrenByOwner,
                pass1Result.typeBySymbol,
                commentsBySymbol,
                positionsBySymbol,
                pass1Result.ownerBySymbol,
                pass1Result.bodyDataByAddr,
                pass1Result.sectionBytes,
                pass1Result.sectionOffset,
                pass1Result.names,
                pass1Result.addrMap,
                pass1Result.unresolvedIdToFqn,
                pass1Result.annotationsBySymbol
            )
        end for
    end decodeTastyBytes

    /** Compute the dotted FQN for `sym` by walking the ownerBySymbol chain.
      *
      * Internal FQN computation used during Pass C construction (before Classpath is fully assembled).
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
        var done    = false
        while !done && cur != null && visited.add(cur) do
            val n = cur.name.asString
            if n.nonEmpty then parts.prepend(n)
            // Package symbols store the full dotted package name (e.g. "scala.collection.immutable")
            // in a single Name field. Walking further up through package owners would re-prepend the
            // individual package segments that are already embedded in that flat name, doubling them.
            // Stop here: the flat name is the entire package prefix for this symbol.
            if cur.kind == Tasty.SymbolKind.Package then done = true
            else cur = ownerBySymbol.getOrElse(cur, null)
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
      * F-G-007 partial (Phase 04): route ALL unresolved fallbacks to the single interned sentinel from TypeUnpickler so that
      * cp.symbols.filter(_.id.value == -1).map(_.name.asString).toSet.size decreases from the Phase 01 baseline.
      * Phase 11 completes the interning by routing the remaining TypeUnpickler call sites.
      */
    @volatile private var _sentinelUnresolvedCached: Tasty.Symbol | Null = null

    private def makeUnresolvedSym(fqn: String)(using AllowUnsafe): Tasty.Symbol =
        // Return the shared sentinel regardless of the fqn argument; the fqn is only used for diagnostics
        // in logging. All unresolved-FQN symbols share id.value == -1 via InternalSymbol.makeSymbol.
        // Using a single interned object collapses the sentinel name-set size toward 1.
        var cached = _sentinelUnresolvedCached
        if cached == null then
            cached = InternalSymbol.makeSymbol(
                Tasty.SymbolKind.Unresolved,
                Tasty.Flags.empty,
                Tasty.Name("<unresolved>")
            )
            _sentinelUnresolvedCached = cached
        end if
        cached.asInstanceOf[Tasty.Symbol]
    end makeUnresolvedSym

end ClasspathOrchestrator
