package kyo.internal.reflect.query

import kyo.*
import kyo.Maybe.Absent
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.classfile.ModuleInfoReader
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.tasty.AstUnpickler
import kyo.internal.reflect.tasty.AttributeUnpickler
import kyo.internal.reflect.tasty.CommentsUnpickler
import kyo.internal.reflect.tasty.FileAttributes
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.PositionsUnpickler
import kyo.internal.reflect.tasty.SectionIndex
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TastyHeader
import kyo.internal.reflect.type_.TypeArena
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
      */
    final private case class FileResult(
        fqns: Chunk[(String, Reflect.Symbol)],
        arena: TypeArena,
        errors: Seq[ReflectError],
        placeholders: Chunk[UnresolvedRef],
        parentsBySymbol: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]],
        childrenByOwner: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]],
        typeBySymbol: mutable.HashMap[Reflect.Symbol, Reflect.Type],
        commentsBySymbol: Map[Reflect.Symbol, String],
        positionsBySymbol: Map[Reflect.Symbol, Reflect.Position]
    )

    /** Tagged union for results flowing through the result channel.
      *
      * `FileResultCase` carries a decoded TASTy file result. `ModuleInfoCase` carries a decoded module-info.class descriptor.
      */
    sealed private trait DecodeResult
    final private case class FileResultCase(fr: FileResult)                             extends DecodeResult
    final private case class ModuleInfoCase(name: String, md: Reflect.ModuleDescriptor) extends DecodeResult

    /** Mutable accumulator for the single-threaded merger stage (Phase C).
      *
      * Collects decoded file results and module descriptors as they arrive from the result channel. `finalizeMerge` reads from this state
      * once the merger has drained the result channel.
      */
    final private class MergeState:
        val fqnIndex: mutable.HashMap[String, Reflect.Symbol]              = mutable.HashMap.empty
        val packageIndex: mutable.HashMap[String, Reflect.Symbol]          = mutable.HashMap.empty
        val allSyms: mutable.ArrayBuffer[Reflect.Symbol]                   = mutable.ArrayBuffer.empty
        val topLevelCls: mutable.ArrayBuffer[Reflect.Symbol]               = mutable.ArrayBuffer.empty
        val packages: mutable.ArrayBuffer[Reflect.Symbol]                  = mutable.ArrayBuffer.empty
        val accErrors: mutable.ArrayBuffer[ReflectError]                   = mutable.ArrayBuffer.empty
        val fileResults: mutable.ArrayBuffer[FileResult]                   = mutable.ArrayBuffer.empty
        val moduleIndex: mutable.HashMap[String, Reflect.ModuleDescriptor] = mutable.HashMap.empty
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
    )(using Frame): Unit < (Sync & Async & Scope & Abort[ReflectError]) =
        // Validate roots exist first (both strict and soft-fail report FileNotFound immediately)
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex then Abort.fail(ReflectError.FileNotFound(root))
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
    )(using Frame): Unit < (Sync & Async & Abort[ReflectError]) =
        val decodeConcurrency = concurrency.max(1)
        val rootCount         = roots.size.max(1)
        val entryCap          = decodeConcurrency * 4
        val resultCap         = decodeConcurrency * 2
        val numShards         = 128
        val mergeState        = new MergeState()
        // Heuristic: 128 entries per shard accommodates classpaths up to ~12K entries (75% load).
        // This avoids the per-cold-load pre-walk cost that exceeds the resize savings on small classpaths.
        val sizeHint = 128
        val interner = new Interner(numShards = numShards, initialShardCapacity = sizeHint)

        // Timing instrumentation: snapshot nanoTime at each stage boundary.
        // AtomicLong used so decoder fibers can record t_decodeEnd from any thread.
        val t_start     = new java.util.concurrent.atomic.AtomicLong(java.lang.System.nanoTime())
        val t_listEnd   = new java.util.concurrent.atomic.AtomicLong(0L)
        val t_decodeEnd = new java.util.concurrent.atomic.AtomicLong(0L)
        val t_mergeEnd  = new java.util.concurrent.atomic.AtomicLong(0L)

        PerfCounters.reset()

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
                                walkRoot(root, entryCh, source)

                            val decoderStage = Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency): _ =>
                                entryCh.streamUntilClosed().foreach: (entryPath, kind) =>
                                    decodeOneEntry(entryPath, kind, interner, source, cp, strict).flatMap: result =>
                                        // If resultCh closed early (strict-mode abort), silently discard
                                        Abort.run[Closed](resultCh.put(result)).unit

                            val mergerStage: Unit < (Async & Abort[ReflectError]) =
                                resultCh.streamUntilClosed().foreach: result =>
                                    Sync.defer(mergeOneInto(mergeState, result))

                            // Producer closes entryCh after all puts complete (closeAwaitEmpty so decoders drain buffer).
                            val producerWithClose: Unit < (Abort[ReflectError] & Async) =
                                producerStage
                                    .andThen(Sync.defer(t_listEnd.set(java.lang.System.nanoTime())))
                                    .andThen(entryCh.closeAwaitEmpty.unit)
                            // Decoders close resultCh after all puts complete.
                            val decoderWithClose: Unit < (Abort[ReflectError] & Async) =
                                decoderStage
                                    .andThen(Sync.defer(t_decodeEnd.set(java.lang.System.nanoTime())))
                                    .andThen(resultCh.closeAwaitEmpty.unit)
                            // Merger records its end time after draining resultCh.
                            val mergerWithTiming: Unit < (Async & Abort[ReflectError]) =
                                mergerStage.andThen(Sync.defer(t_mergeEnd.set(java.lang.System.nanoTime())))

                            // Async.foreach with concurrency=3 and 3 items runs all 3 stages concurrently.
                            // Unlike Async.gather, Async.foreach propagates the first Abort failure and
                            // interrupts the other fibers (including a stuck merger) via IOPromise.interrupts.
                            val stages: Chunk[Unit < (Abort[ReflectError] & Async)] =
                                Chunk(producerWithClose, decoderWithClose, mergerWithTiming)
                            Async.foreach(stages, 3): stage =>
                                stage
                            .andThen(finalizeMerge(mergeState, source, strict, cp))
                                .andThen:
                                    if timingEnabled then
                                        Sync.defer:
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
                                            val jars        = PerfCounters.jarOpenCount.get()
                                            val entries     = PerfCounters.entryReadCount.get()
                                            val bytesRaw    = PerfCounters.bytesReadTotal.get()
                                            val bytesMB     = bytesRaw / (1024L * 1024L)
                                            val constructMs = PerfCounters.jarConstructTimeNs.get() / 1_000_000L
                                            val readMs      = PerfCounters.jarReadTimeNs.get() / 1_000_000L
                                            val headerMs    = PerfCounters.tastyHeaderTimeNs.get() / 1_000_000L
                                            val namesMs     = PerfCounters.nameUnpicklerTimeNs.get() / 1_000_000L
                                            val sectionMs   = PerfCounters.sectionIndexTimeNs.get() / 1_000_000L
                                            val attrMs      = PerfCounters.attributeUnpicklerTimeNs.get() / 1_000_000L
                                            val astMs       = PerfCounters.astPass1TimeNs.get() / 1_000_000L
                                            val posMs       = PerfCounters.positionsUnpicklerTimeNs.get() / 1_000_000L
                                            val commentsMs  = PerfCounters.commentsUnpicklerTimeNs.get() / 1_000_000L
                                            java.lang.System.err.println(
                                                s"[kyo-reflect] cold-load: list=${listMs}ms decode=${decodeMs}ms merge=${mergeMs}ms finalize=${finalizeMs}ms total=${totalMs}ms | jars=$jars (construct=${constructMs}ms read=${readMs}ms) entries=$entries bytes=${bytesMB}MB"
                                            )
                                            java.lang.System.err.println(
                                                s"[kyo-reflect]   decode-breakdown: header=${headerMs}ms names=${namesMs}ms section=${sectionMs}ms attr=${attrMs}ms ast=${astMs}ms pos=${posMs}ms comments=${commentsMs}ms"
                                            )
                                    else
                                        Kyo.unit
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
    )(using Frame): Unit < (Sync & Async & Abort[ReflectError]) =
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
      * In strict mode, decode errors propagate as Abort[ReflectError]. In soft-fail mode they produce empty/error FileResult.
      */
    private def decodeOneEntry(
        entryPath: String,
        kind: String,
        interner: Interner,
        source: FileSource,
        cp: Classpath,
        strict: Boolean
    )(using Frame): DecodeResult < (Sync & Async & Abort[ReflectError]) =
        if kind == "module-info.class" then
            Abort.run[ReflectError](
                source.read(entryPath).flatMap: bytes =>
                    PerfCounters.entryReadCount.incrementAndGet()
                    PerfCounters.bytesReadTotal.addAndGet(bytes.length.toLong)
                    ModuleInfoReader.read(bytes)
            ).flatMap:
                case Result.Success(desc) =>
                    ModuleInfoCase(desc.name, desc)
                case Result.Failure(err: ReflectError) =>
                    if strict then Abort.fail(err)
                    else
                        // Soft-fail: produce an empty FileResult with the error recorded
                        FileResultCase(emptyFileResultWithError(entryPath, err))
                case Result.Panic(t) =>
                    val err = ReflectError.CorruptedFile(entryPath, 0L, t.getMessage)
                    if strict then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
        else
            Scope.run:
                readAndDecodeTastyFile(entryPath, interner, source, cp, strict).map(FileResultCase.apply)

    /** Merge one DecodeResult into the MergeState. Single-threaded (only the merger fiber calls it). */
    private def mergeOneInto(state: MergeState, result: DecodeResult): Unit =
        result match
            case FileResultCase(fr) =>
                for (fqn, sym) <- fr.fqns do
                    val indexKey = if sym.kind == Reflect.SymbolKind.Object && !fqn.endsWith("$") then fqn + "$" else fqn
                    val existing = state.fqnIndex.get(indexKey)
                    val shouldStore = existing match
                        case None => true
                        case Some(prev) =>
                            val prevIsStructural = prev.kind == Reflect.SymbolKind.Class ||
                                prev.kind == Reflect.SymbolKind.Trait || prev.kind == Reflect.SymbolKind.Object
                            val newIsStructural = sym.kind == Reflect.SymbolKind.Class ||
                                sym.kind == Reflect.SymbolKind.Trait || sym.kind == Reflect.SymbolKind.Object
                            newIsStructural || !prevIsStructural
                    if shouldStore then state.fqnIndex(indexKey) = sym
                    state.allSyms += sym
                    sym.kind match
                        case Reflect.SymbolKind.Package =>
                            state.packages += sym
                            state.packageIndex(fqn) = sym
                        case Reflect.SymbolKind.Class | Reflect.SymbolKind.Trait | Reflect.SymbolKind.Object =>
                            state.topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end for
                state.accErrors ++= fr.errors
                state.fileResults += fr
            case ModuleInfoCase(name, md) =>
                state.moduleIndex(name) = md

    /** Phase C: placeholder resolution + final Classpath transition.
      *
      * Runs once after the merger has drained the result channel. Resolves cross-file type references, assigns _parents / _typeParams /
      * _declarations / _declaredType / _scaladoc / _position, and transitions the Classpath from Building to Ready.
      */
    private def finalizeMerge(
        state: MergeState,
        source: FileSource,
        strict: Boolean,
        cp: Classpath
    )(using Frame): Unit < Sync =
        Sync.defer:
            val canonical    = TypeArena.canonical()
            val fileResults  = state.fileResults.toSeq
            val fqnIndex     = state.fqnIndex
            val packageIndex = state.packageIndex
            val allSyms      = state.allSyms
            val topLevelCls  = state.topLevelCls
            val packages     = state.packages
            val accErrors    = state.accErrors
            val moduleIndex  = state.moduleIndex.toMap

            for fr <- fileResults do
                canonical.merge(fr.arena)
            end for

            // Phase C: resolve all UnresolvedRef placeholders accumulated during Phase B decode.
            // All arenas merged and fqnIndex fully populated above, so lookups are complete.
            // Unsafe: replaceSlot.set uses AllowUnsafe (covered by the import below).
            val allPlaceholders = Chunk.from(fileResults).flatMap(_.placeholders)
            import AllowUnsafe.embrace.danger
            for placeholder <- allPlaceholders do
                fqnIndex.get(placeholder.fqn) match
                    case Some(sym) =>
                        placeholder.replaceSlot.set(Reflect.Type.Named(sym))
                    case None =>
                        placeholder.replaceSlot.set(Reflect.Type.Named(makeUnresolvedSym(placeholder.fqn)))
            end for

            // After G13 placeholder resolution: assign _parents, _typeParams, _declarations on TASTy symbols.
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
            for sym <- allSyms do
                if !sym._parents.isSet then sym._parents.set(Chunk.empty)
                if !sym._typeParams.isSet then sym._typeParams.set(Chunk.empty)
                if !sym._declarations.isSet then sym._declarations.set(Chunk.empty)
            end for

            // Phase 5 (G20): assign _declaredType AFTER Phase C placeholder resolution.
            for fr <- fileResults do
                for (sym, t) <- fr.typeBySymbol do
                    if !sym._declaredType.isSet then sym._declaredType.set(t)
                end for
            end for
            for sym <- allSyms do
                if !sym._declaredType.isSet && (sym.kind == Reflect.SymbolKind.Class ||
                        sym.kind == Reflect.SymbolKind.Trait ||
                        sym.kind == Reflect.SymbolKind.Object)
                then
                    sym._declaredType.set(Reflect.Type.Named(sym))
            end for

            // Phase 6 (G3): assign _scaladoc from commentsBySymbol.
            for fr <- fileResults do
                for (sym, text) <- fr.commentsBySymbol do
                    if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe(text))
                end for
            end for
            for sym <- allSyms do
                if !sym._scaladoc.isSet then sym._scaladoc.set(Maybe.Absent)
            end for

            // Phase 7 (G2): assign _position from positionsBySymbol.
            for fr <- fileResults do
                for (sym, pos) <- fr.positionsBySymbol do
                    if !sym._position.isSet then sym._position.set(Maybe(pos))
                end for
            end for
            for sym <- allSyms do
                if !sym._position.isSet then sym._position.set(Maybe.Absent)
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
                Chunk.from(accErrors.toSeq),
                moduleIndex
            )

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
                PerfCounters.entryReadCount.incrementAndGet()
                PerfCounters.bytesReadTotal.addAndGet(bytes.length.toLong)
                decodeTastyBytes(file, bytes, interner, cp)
        ).map:
            case Result.Success(fr) => fr
            case Result.Failure(err: ReflectError) =>
                if strict then
                    Abort.fail(err)
                else
                    emptyFileResultWithError(file, err)
            case Result.Panic(t) =>
                val err = ReflectError.CorruptedFile(file, 0L, t.getMessage)
                if strict then
                    Abort.fail(err)
                else
                    emptyFileResultWithError(file, err)
                end if

    /** Produce an empty FileResult carrying a single error (soft-fail path). */
    private def emptyFileResultWithError(file: String, err: ReflectError): FileResult =
        FileResult(
            Chunk.empty,
            TypeArena.canonical(),
            Seq(err),
            Chunk.empty,
            mutable.HashMap.empty[Reflect.Symbol, Chunk[Reflect.Type]],
            mutable.HashMap.empty[Reflect.Symbol, Chunk[Reflect.Symbol]],
            mutable.HashMap.empty[Reflect.Symbol, Reflect.Type],
            Map.empty,
            Map.empty
        )

    /** Wrap a synchronous Kyo computation, adding elapsed nanoseconds to `counter` after it completes.
      *
      * `t0` is captured when `timed` is invoked -- i.e., when this for-yield step starts. The `.map` fires after `v` returns, so the delta
      * covers exactly the unpickler's execution. Safe only for purely-Sync computations with no Async suspension (all unpicklers in
      * decodeTastyBytes satisfy this invariant).
      */
    private def timed[A, S](counter: java.util.concurrent.atomic.AtomicLong)(v: A < S)(using Frame): A < S =
        val t0 = java.lang.System.nanoTime()
        v.map: a =>
            counter.addAndGet(java.lang.System.nanoTime() - t0)
            a
    end timed

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
            _        <- timed(PerfCounters.tastyHeaderTimeNs)(TastyHeader.read(view))
            names    <- timed(PerfCounters.nameUnpicklerTimeNs)(NameUnpickler.read(view, interner))
            sections <- timed(PerfCounters.sectionIndexTimeNs)(SectionIndex.read(view, names))
            attrs <- timed(PerfCounters.attributeUnpicklerTimeNs)(sections.get(TastyFormat.AttributesSection) match
                case Present((offset, length)) =>
                    val attrView = view.subView(offset, offset + length)
                    AttributeUnpickler.read(attrView, names)
                case Absent =>
                    Sync.defer(FileAttributes.default))
            pass1Result <- timed(PerfCounters.astPass1TimeNs)(sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
                case Absent =>
                    Abort.fail(ReflectError.MalformedSection("ASTs", s"$file: ASTs section not found")))
            commentsBySymbol <- timed(PerfCounters.commentsUnpicklerTimeNs)(sections.get(TastyFormat.CommentsSection) match
                case Present((offset, length)) =>
                    val commentsView = view.subView(offset, offset + length)
                    CommentsUnpickler.read(commentsView, pass1Result.addrMap)
                case Absent =>
                    Sync.defer(Map.empty[Reflect.Symbol, String]))
            positionsBySymbol <- timed(PerfCounters.positionsUnpicklerTimeNs)(sections.get(TastyFormat.PositionsSection) match
                case Present((offset, length)) =>
                    val posView = view.subView(offset, offset + length)
                    PositionsUnpickler.read(posView, pass1Result.addrMap, attrs.sourceFile)
                case Absent =>
                    Sync.defer(Map.empty[Reflect.Symbol, Reflect.Position]))
        yield
            val pairs = pass1Result.symbols.flatMap: sym =>
                val fqn = nameToString(sym.fullName)
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
                positionsBySymbol
            )
        end for
    end decodeTastyBytes

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
            Reflect.Symbol.TastyOrigin.empty,
            Absent
        )

end ClasspathOrchestrator
