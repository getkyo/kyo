package kyo.internal.tasty.query

import kyo.*
import kyo.Maybe.Absent
import kyo.Tasty.SymbolId
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.classfile.ClassfileUnpickler
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
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolBody
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolKind
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
      * Fields ownerBySymbol, bodyDataByAddr, sectionBytes, sectionOffset, names are fed from AstUnpickler Pass1Result to ClasspathOrchestrator Pass C.
      */
    final private case class FileResult(
        fqns: Chunk[(String, LoadingSymbol.Materialising)],
        /** All symbols from this file in TASTy parse order (deterministic depth-first traversal of the AST).
          *
          * Used in mergeOneInto to accumulate allSyms in a stable order across runs, ensuring that symbol IDs
          * are deterministic for byte-equal snapshot idempotency. Previously allSyms was populated
          * from ownerBySymbol.keys which uses identity-hash order and is non-deterministic.
          */
        symbolsInOrder: Chunk[LoadingSymbol.Materialising],
        arena: TypeArena,
        errors: Seq[TastyError],
        placeholders: Chunk[Nothing], // placeholder field; always empty (reserved)
        parentsBySymbol: mutable.LongMap[Chunk[Tasty.Type]],
        childrenByOwner: mutable.LongMap[Chunk[LoadingSymbol.Materialising]],
        typeBySymbol: mutable.LongMap[Tasty.Type],
        commentsBySymbol: mutable.LongMap[String],
        positionsBySymbol: mutable.LongMap[Tasty.Position],
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising],
        bodyDataByAddr: mutable.LongMap[(Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        fileNames: Array[Tasty.Name],
        /** Per-file TASTy address -> partial symbol map, used in finalizeMerge to remap temporary SymbolIds (PHASE_B_ADDR_OFFSET + addr)
          * to final SymbolIds.
          */
        addrMap: scala.collection.immutable.IntMap[LoadingSymbol.Materialising],
        /** Cross-file unresolved FQN tracking: maps unique negative SymbolIds to FQNs for finalizeMerge parent type resolution. */
        unresolvedIdToFqn: mutable.HashMap[Int, String],
        /** Per-symbol annotation lists decoded from ANNOTATION modifier blocks. Populated by AstUnpickler;
          * consumed by finalizeMerge to write descs(idx).annotations.
          */
        annotationsBySymbol: mutable.LongMap[mutable.ArrayBuffer[Tasty.Annotation]],
        /** javaMetadata from the companion .class file, keyed by the loading-symbol id of the partial (pre-finalize) TASTy symbol.
          *
          * Populated during readAndDecodeTastyFile when a same-named .class file exists alongside the .tasty file.
          * Consumed by finalizeMerge to write descs(idx).javaMetadata for TASTy-loaded class symbols.
          */
        companionJavaMeta: mutable.LongMap[Tasty.Java.Metadata]
    )

    /** Tagged union for results flowing through the result channel.
      *
      * `FileResultCase` carries a decoded TASTy file result. `ModuleInfoCase` carries a decoded module-info.class descriptor.
      * `JavaClassfileCase` carries a decoded standalone JVM .class file.
      */
    sealed private trait DecodeResult
    final private case class FileResultCase(fr: FileResult)                                 extends DecodeResult
    final private case class ModuleInfoCase(name: String, md: Tasty.Java.Module.Descriptor) extends DecodeResult

    /** Carries a decoded standalone .class file result and its computed binary FQN.
      *
      * Classfiles passed directly as roots (e.g., via `jrt:/` paths) decode here and inject class symbols into the
      * global FQN index so `findClass("java.lang.String")` resolves.
      */
    final private case class JavaClassfileCase(
        fqn: String,
        cfResult: kyo.internal.tasty.classfile.ClassfileResult
    ) extends DecodeResult

    /** Mutable accumulator for the single-threaded merger stage (Phase C).
      *
      * Collects decoded file results and module descriptors as they arrive from the result channel. `finalizeMerge` reads from this state
      * once the merger has drained the result channel.
      */
    final private class MergeState:
        val fqnIndex: mutable.HashMap[String, LoadingSymbol.Materialising]     = mutable.HashMap.empty
        val packageIndex: mutable.HashMap[String, LoadingSymbol.Materialising] = mutable.HashMap.empty
        val allSyms: mutable.ArrayBuffer[LoadingSymbol.Materialising]          = mutable.ArrayBuffer.empty
        // LongSet (LongMap[Unit]) keyed on LoadingSymbol.Materialising.id: unique per-instance, cross-platform.
        // Replaces the earlier IdentityHashMap-backed set (JVM-only).
        val allSymsSet: mutable.LongMap[Unit]                                  = mutable.LongMap.empty
        val topLevelCls: mutable.ArrayBuffer[LoadingSymbol.Materialising]      = mutable.ArrayBuffer.empty
        val packages: mutable.ArrayBuffer[LoadingSymbol.Materialising]         = mutable.ArrayBuffer.empty
        val accErrors: mutable.ArrayBuffer[TastyError]                         = mutable.ArrayBuffer.empty
        val fileResults: mutable.ArrayBuffer[FileResult]                       = mutable.ArrayBuffer.empty
        val moduleIndex: mutable.HashMap[String, Tasty.Java.Module.Descriptor] = mutable.HashMap.empty

        /** Decoded standalone .class files accumulated for finalizeMerge parent-type wiring. */
        val javaClassfileResults: mutable.ArrayBuffer[(String, kyo.internal.tasty.classfile.ClassfileResult)] =
            mutable.ArrayBuffer.empty

        /** Same-FQN collision tracking.
          *
          * Keyed by the indexKey (primary binary FQN) of the collision. Value is an ordered list of ALL symbols that were ever stored under
          * that FQN; the last entry is the current winner (last-write-wins). Populated by `mergeOneInto` when it encounters a new structural
          * symbol for an FQN that already has a different structural symbol.
          */
        val collisions: mutable.HashMap[String, mutable.ArrayBuffer[LoadingSymbol.Materialising]] = mutable.HashMap.empty
    end MergeState

    /** Init a new classpath from a set of root paths.
      *
      * Roots may be directories containing `.tasty` files or individual `.tasty` files.
      *
      * Missing-root contract per `Tasty.ErrorMode` scaladoc:
      *   - `ErrorMode.FailFast`: a missing root immediately raises `Abort[TastyError.FileNotFound]`.
      *   - `ErrorMode.SoftFail`: a missing root accumulates `TastyError.FileNotFound` in `cp.errors` and initialization continues with the
      *     remaining valid roots. An all-missing classpath returns an empty `Classpath` with one error per missing root.
      */
    def init(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        source: FileSource,
        concurrency: Int
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        // Partition roots into missing vs. valid.
        // FailFast: abort on first missing root (preserves original behavior).
        // SoftFail: accumulate FileNotFound errors; run the pipeline with only the valid roots.
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex && mode == Tasty.ErrorMode.FailFast then Abort.fail(TastyError.FileNotFound(root))
                else Sync.defer(ex) // true = root exists; false = missing (only in SoftFail)
        .flatMap: existFlags =>
            val validRoots: Seq[String] =
                roots.zip(existFlags).collect:
                    case (root, true) => root
            val preErrors: Chunk[TastyError] =
                Chunk.from(roots.zip(existFlags).collect:
                    case (root, false) => TastyError.FileNotFound(root): TastyError)
            // Install a read-batch context so JvmFileSource can share mmap readers across the scan+decode pipeline.
            // The default FileSource.withReadBatch is a no-op; JvmFileSource overrides it.
            source.withReadBatch:
                runPhaseAB(validRoots, mode, source, concurrency)
            .map: cp =>
                // Prepend pre-errors (missing roots) to cp.errors so callers see them first.
                if preErrors.nonEmpty then Tasty.Classpath.copyWithPreErrors(cp, preErrors)
                else cp

    /** Like init() but also returns a body store populated with SymbolBody blobs.
      *
      * Used by coldLoadBinding and loadPickles to populate DecodeContext.bodyStore so that
      * Tasty.bodyTree can decode body bytes on demand. The public init() signature is unchanged.
      */
    private def initWithBodies(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        source: FileSource,
        concurrency: Int
    )(using
        Frame
    ): (Tasty.Classpath, java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]) < (Sync & Async & Scope & Abort[TastyError]) =
        // Unsafe: ConcurrentHashMap allocation for body store; same pattern as DecodeContext.fresh().
        val bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody] =
            import AllowUnsafe.embrace.danger
            new java.util.concurrent.ConcurrentHashMap()
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex && mode == Tasty.ErrorMode.FailFast then Abort.fail(TastyError.FileNotFound(root))
                else Sync.defer(ex)
        .flatMap: existFlags =>
            val validRoots: Seq[String] =
                roots.zip(existFlags).collect:
                    case (root, true) => root
            val preErrors: Chunk[TastyError] =
                Chunk.from(roots.zip(existFlags).collect:
                    case (root, false) => TastyError.FileNotFound(root): TastyError)
            source.withReadBatch:
                runPhaseAB(validRoots, mode, source, concurrency, Maybe.Present(bodyStore))
            .map: cp =>
                val finalCp =
                    if preErrors.nonEmpty then Tasty.Classpath.copyWithPreErrors(cp, preErrors)
                    else cp
                (finalCp, bodyStore)
    end initWithBodies

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
        concurrency: Int,
        bodyStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]] = Maybe.Absent
    )(using Frame): Tasty.Classpath < (Sync & Async & Abort[TastyError]) =
        val decodeConcurrency = concurrency.max(1)
        val rootCount         = roots.size.max(1)
        val entryCap          = decodeConcurrency * 4
        val resultCap         = decodeConcurrency * 2
        val numShards         = 128
        val mergeState        = new MergeState()
        // Global id counter for LoadingSymbol.Materialising instances across all concurrent decode sessions.
        // Each decode session (TASTy file or classfile) gets a unique id per symbol by calling getAndIncrement.
        // AtomicInt.Unsafe is cross-platform (Kyo provides JS and Native implementations).
        // Unsafe: constructed once per init() call inside the Sync.Unsafe.defer block below; not a module-level val.
        val globalSymbolIdCounter: AtomicInt.Unsafe =
            import AllowUnsafe.embrace.danger
            AtomicInt.Unsafe.init(0)
        val nextGlobalId: () => Int = () =>
            import AllowUnsafe.embrace.danger
            globalSymbolIdCounter.getAndIncrement()
        // Heuristic: 128 entries per shard accommodates classpaths up to ~12K entries (75% load).
        // This avoids the per-cold-load pre-walk cost that exceeds the resize savings on small classpaths.
        val sizeHint = 128

        // Timing instrumentation: snapshot nanoTime at each stage boundary.
        // AtomicLong.Unsafe used so decoder fibers can record t_decodeEnd from any thread.
        // §839 case 3; pipeline-launch boundary; timing slots allocated once per open() call.
        // Sync.Unsafe.defer scopes the AllowUnsafe proof to just the AtomicLong constructions,
        // so the rest of the method body cannot pick up the proof implicitly.
        Sync.Unsafe.defer {
            val t_start     = AtomicLong.Unsafe.init(java.lang.System.nanoTime())
            val t_listEnd   = AtomicLong.Unsafe.init(0L)
            val t_decodeEnd = AtomicLong.Unsafe.init(0L)
            val t_mergeEnd  = AtomicLong.Unsafe.init(0L)
            (t_start, t_listEnd, t_decodeEnd, t_mergeEnd)
        }.flatMap { case (t_start, t_listEnd, t_decodeEnd, t_mergeEnd) =>
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
                                            decodeOneEntry(entryPath, kind, source, mode, nextGlobalId).flatMap: result =>
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
                                .andThen(finalizeMerge(mergeState, source, mode, bodyStoreOutput)).flatMap: result =>
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
            val unsorted: Chunk[String] =
                if listed.isEmpty then
                    if root.endsWith(".tasty") || root.endsWith("module-info.class") then Chunk(root)
                    // A root that IS a standalone .class file (e.g. a jrt:/ path like
                    // `jrt:///modules/java.base/java/lang/String.class`) is passed directly from
                    // PlatformModuleOps.listJdkClassFiles. list() returns empty because it is a file, not
                    // a directory. Emit it with kind ".class" so decodeOneEntry routes to ClassfileUnpickler.
                    else if root.endsWith(".class") && !root.endsWith("module-info.class") then Chunk(root)
                    else Chunk.empty
                else listed
            // Sort entries so file processing order is stable across filesystem enumeration orders.
            // Different platforms and JAR implementations enumerate entries in varying orders;
            // a lexicographic sort gives deterministic symbol IDs, enabling byte-equal
            // snapshot idempotency when concurrency == 1.
            val entries = Chunk.from(unsorted.iterator.toSeq.sorted)
            Kyo.foreach(entries): entry =>
                val kind =
                    if entry.endsWith("module-info.class") then "module-info.class"
                    // Individual .class entries from direct paths (jrt:/) get kind ".class".
                    // Note: .class entries from directory listings are not emitted here (list only returns
                    // .tasty and module-info.class); those companion .class files are handled by the
                    // readAndDecodeTastyFile companion-decode path in decodeOneEntry.
                    else if entry.endsWith(".class") then ".class"
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
        source: FileSource,
        mode: Tasty.ErrorMode,
        nextGlobalId: () => Int
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
        else if kind == ".class" then
            // Decode a standalone JVM .class file via ClassfileUnpickler.
            // The FQN is computed from the path by stripping the jrt:/ module prefix and
            // converting slash-separated segments to a dotted name. This makes JDK classes
            // (java.lang.String, java.util.HashMap, etc.) reachable via cp.findClass.
            Abort.run[TastyError](
                source.read(entryPath).flatMap: bytes =>
                    Sync.Unsafe.defer:
                        TastyPerfStats.entryReads.inc()
                        TastyPerfStats.bytesRead.add(bytes.length.toLong)
                    .andThen:
                        // Unsafe: ClassfileUnpickler reads an immutable byte array inside a Sync.Unsafe.defer block; no suspension required (§839 case 3).
                        given AllowUnsafe = AllowUnsafe.embrace.danger
                        val arena         = kyo.internal.tasty.type_.TypeArena.canonical()
                        ClassfileUnpickler.read(bytes, arena, nextGlobalId)
            ).flatMap:
                case Result.Success(cfResult) =>
                    val fqn = classfilePathToFqn(entryPath)
                    if fqn.isEmpty then
                        // Skip files whose FQN cannot be computed (anonymous, synthetic, etc.)
                        FileResultCase(emptyFileResultWithError(
                            entryPath,
                            TastyError.ClassfileFormatError(entryPath, "FQN empty; skipping", 0)
                        ))
                    else JavaClassfileCase(fqn, cfResult)
                    end if
                case Result.Failure(err: TastyError) =>
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
                case Result.Panic(t) =>
                    val err = TastyError.CorruptedFile(entryPath, 0L, t.getMessage)
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
        else
            Scope.run:
                readAndDecodeTastyFile(entryPath, source, mode, nextGlobalId).map(FileResultCase.apply)

    /** Merge one DecodeResult into the MergeState. Single-threaded (only the merger fiber calls it). */
    private def mergeOneInto(state: MergeState, result: DecodeResult): Unit =
        result match
            case FileResultCase(fr) =>
                // Add ALL symbols (including members) to allSyms so that finalizeMerge can look them
                // up by index when building typeParamIds/declarationIds.
                // Use symbolsInOrder (TASTy parse order, stable DFS) rather than ownerBySymbol.keys/values
                // which uses identity-hash iteration order. Identity hashCodes vary across JVM runs, making
                // symbol IDs non-deterministic and breaking byte-equal snapshot idempotency.
                // symbolsInOrder preserves the AstUnpickler traversal order, which is deterministic
                // given the same input bytes.
                // LongMap keyed on sym.id (unique per-instance) for dedup within and across files.
                val seenSymIds = mutable.LongMap.empty[Unit]
                for sym <- fr.symbolsInOrder do
                    if !seenSymIds.contains(sym.id.toLong) then
                        seenSymIds(sym.id.toLong) = ()
                        state.allSyms += sym
                        state.allSymsSet(sym.id.toLong) = ()
                end for

                for (fqn, sym) <- fr.fqns do
                    val indexKey = if sym.kind == SymbolKind.Object && !fqn.endsWith("$") then fqn + "$" else fqn
                    val existing = state.fqnIndex.get(indexKey)
                    val shouldStore = existing match
                        case None => true
                        case Some(prev) =>
                            val prevIsStructural = prev.kind == SymbolKind.Class ||
                                prev.kind == SymbolKind.Trait || prev.kind == SymbolKind.Object ||
                                prev.kind == SymbolKind.EnumCase
                            val newIsStructural = sym.kind == SymbolKind.Class ||
                                sym.kind == SymbolKind.Trait || sym.kind == SymbolKind.Object ||
                                sym.kind == SymbolKind.EnumCase
                            newIsStructural || !prevIsStructural
                    // Record a collision when a new structural symbol of the SAME KIND overwrites a
                    // different structural symbol. Both must be structural (Class/Trait/Object/EnumCase), must be
                    // distinct objects (different reference identity), and must share the same SymbolKind to be a
                    // real cross-root collision.
                    //
                    // Same-kind constraint is essential to avoid false positives from the canonical-FQN alias
                    // pattern: when a companion Object (kind=Object, fqn="scala.Array$") is registered under its
                    // canonical alias ("scala.Array"), the incoming Class (kind=Class) later overwrites it. That
                    // is the expected alias-upgrade sequence, NOT a collision. Requiring sym.kind == prev.kind
                    // filters out the alias-upgrade case while still catching genuine same-kind duplicates (e.g.,
                    // two jars both defining "com.example.Foo" as a Class).
                    existing match
                        case Some(prev) if (prev ne sym) && (prev.kind == sym.kind) =>
                            val prevIsStructural = prev.kind == SymbolKind.Class ||
                                prev.kind == SymbolKind.Trait || prev.kind == SymbolKind.Object ||
                                prev.kind == SymbolKind.EnumCase
                            val newIsStructural = sym.kind == SymbolKind.Class ||
                                sym.kind == SymbolKind.Trait || sym.kind == SymbolKind.Object ||
                                sym.kind == SymbolKind.EnumCase
                            if prevIsStructural && newIsStructural then
                                val buf = state.collisions.getOrElseUpdate(indexKey, mutable.ArrayBuffer(prev))
                                discard(buf += sym)
                        case _ => ()
                    end match
                    if shouldStore then state.fqnIndex(indexKey) = sym
                    // HARD RULE 8 / HARD RULE 10: unified source-FQN dual-index via FqnNormalizer.
                    // Replaces the 3 separate opaque / $-suffix / $. blocks from Phases 06/09.
                    // canonicalSourceFqn handles all 4 mangling patterns in order; if the result
                    // differs from indexKey, register the source FQN as an additional key.
                    // Synthetic names (anonfun, proxy, etc.) are not registered in user-facing indexes.
                    if !kyo.internal.tasty.symbol.FqnNormalizer.isSyntheticName(indexKey) then
                        val sourceFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(indexKey)
                        if sourceFqn != indexKey && sourceFqn.nonEmpty then
                            val existingSource = state.fqnIndex.get(sourceFqn)
                            val storeSource = existingSource match
                                case None       => true
                                case Some(prev) =>
                                    // Structural symbols (Class, Trait, Object, EnumCase, OpaqueType) win
                                    // over non-structural entries; but do not overwrite another structural entry.
                                    val prevIsStructural = prev.kind == SymbolKind.Class ||
                                        prev.kind == SymbolKind.Trait ||
                                        prev.kind == SymbolKind.Object ||
                                        prev.kind == SymbolKind.EnumCase ||
                                        prev.kind == SymbolKind.OpaqueType
                                    val newIsStructural = sym.kind == SymbolKind.Class ||
                                        sym.kind == SymbolKind.Trait ||
                                        sym.kind == SymbolKind.Object ||
                                        sym.kind == SymbolKind.EnumCase ||
                                        sym.kind == SymbolKind.OpaqueType
                                    newIsStructural && !prevIsStructural
                            if storeSource then state.fqnIndex(sourceFqn) = sym
                        end if
                    end if
                    if !seenSymIds.contains(sym.id.toLong) then
                        seenSymIds(sym.id.toLong) = ()
                        state.allSyms += sym
                        state.allSymsSet(sym.id.toLong) = ()
                    end if
                    sym.kind match
                        case SymbolKind.Package =>
                            state.packages += sym
                            state.packageIndex(fqn) = sym
                        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object |
                            SymbolKind.EnumCase =>
                            state.topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end for
                state.accErrors ++= fr.errors
                state.fileResults += fr
            case ModuleInfoCase(name, md) =>
                state.moduleIndex(name) = md
            case JavaClassfileCase(fqn, cfResult) =>
                // Register the classfile's primary symbol in the FQN index.
                // The classSymbol carries flags (isEnum, isRecord, isSealed, etc.) decoded by ClassfileUnpickler.
                // Member symbols (methods, fields) are added to allSyms for finalizeMerge so they receive
                // final SymbolIds; the classSymbol itself is added as the primary top-level entry.
                val classSym = cfResult.classSymbol
                // Register primary binary FQN (e.g. "java.util.Map$Entry").
                if !state.fqnIndex.contains(fqn) then
                    state.fqnIndex(fqn) = classSym
                // Register canonical source FQN (e.g. "java.util.Map.Entry") as secondary key.
                if !kyo.internal.tasty.symbol.FqnNormalizer.isSyntheticName(fqn) then
                    val sourceFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                    if sourceFqn != fqn && sourceFqn.nonEmpty && !state.fqnIndex.contains(sourceFqn) then
                        state.fqnIndex(sourceFqn) = classSym
                end if
                // Add classSymbol and all member symbols to allSyms for finalizeMerge.
                // allSymsSet is a LongMap keyed on id; O(1) membership checks to avoid O(N^2) indexOf scan
                // (critical for JPMS paths with 27k+ classes).
                if !state.allSymsSet.contains(classSym.id.toLong) then
                    state.allSymsSet(classSym.id.toLong) = ()
                    state.allSyms += classSym
                    state.topLevelCls += classSym
                end if
                for memberSym <- cfResult.symbols do
                    if !state.allSymsSet.contains(memberSym.id.toLong) then
                        state.allSymsSet(memberSym.id.toLong) = ()
                        state.allSyms += memberSym
                    end if
                end for
                // Keep classfile result for parent-type wiring in finalizeMerge.
                state.javaClassfileResults += ((fqn, cfResult))

    /** Phase C: SymbolDescriptor construction + single-shot Symbol materialization + index building.
      *
      * Runs once after the merger has drained the result channel. Builds SymbolDescriptors from the accumulated per-file data, calls
      * materializeSymbols to produce the final immutable Symbol case-class instances, builds all index maps, and returns the public
      * Tasty.Classpath case class.
      */
    private def finalizeMerge(
        state: MergeState,
        source: FileSource,
        mode: Tasty.ErrorMode,
        bodyStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]] = Maybe.Absent
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        val canonical   = TypeArena.canonical()
        val fileResults = state.fileResults.toSeq
        val allPartial  = state.allSyms
        val topLevelCls = state.topLevelCls
        val packages    = state.packages
        val accErrors   = state.accErrors
        val moduleIndex = Dict.from(state.moduleIndex.toMap)

        TastyStat.scope.traceSpan("finalize.mergeArenas") {
            Sync.Unsafe.defer:
                for fr <- fileResults do canonical.merge(fr.arena)
                end for
        }.andThen:
            TastyStat.scope.traceSpan("finalize.materializeSymbols") {
                Sync.Unsafe.defer:
                    // Build a map from LoadingSymbol.Materialising.id -> final SymbolId (index in allPartial).
                    // LongMap keyed on m.id (unique per-instance); replaces the former IdentityHashMap approach.
                    val symbolIdMap = mutable.LongMap.empty[Int]
                    var i           = 0
                    for sym <- allPartial do
                        symbolIdMap(sym.id.toLong) = i
                        i += 1
                    end for

                    val count = allPartial.length
                    val descs = new Array[SymbolDescriptor](count)
                    i = 0
                    for partialSym <- allPartial do
                        val id = i
                        // Post-process kind: reclassify all forms of Scala 3 enum cases and Java enum
                        // constants to EnumCase. The initial classification from AstUnpickler/ClassfileUnpickler
                        // does not always produce EnumCase directly:
                        //
                        // (1) Class + Enum + Case (no Module): class-form enum case, e.g. `case Circle(r: Double)`.
                        //     fromTypedefTemplateFlags may have missed the Enum+Case flags if the modifier scan
                        //     ran before the bytes were fully available (Scala 3.8+ TASTy layout).
                        //
                        // (2) Val + Enum + Case: simple value-form enum case, e.g. `case Red, Green, Blue`.
                        //     Scala 3 compiles these to VALDEF nodes in the companion object with Enum+Case flags.
                        //     fromValdefFlags only checks Mutable, so they arrive as Val.
                        //
                        // (3) Field + Enum + JavaDefined + Static: Java enum constant, e.g. RetentionPolicy.RUNTIME.
                        //     ClassfileUnpickler maps ACC_STATIC -> Field. ACC_ENUM -> Flag.Enum (no Case flag).
                        //
                        // Note: Module + Enum + Case symbols are companion objects of class-form enum cases (or
                        // singleton `case object` forms). These remain as Symbol.Object intentionally; Module takes
                        // priority in fromTypedefTemplateFlags and the finalizeMerge reclassification does not
                        // override it. A `case object Red` in an enum is the companion, not the case itself.
                        val adjustedKind =
                            if partialSym.flags.contains(Tasty.Flag.Enum) &&
                                partialSym.flags.contains(Tasty.Flag.Case) &&
                                (partialSym.kind == SymbolKind.Class
                                    && !partialSym.flags.contains(Tasty.Flag.Module)
                                    || partialSym.kind == SymbolKind.Val)
                            then SymbolKind.EnumCase
                            else if partialSym.kind == SymbolKind.Field &&
                                partialSym.flags.contains(Tasty.Flag.Enum) &&
                                partialSym.flags.contains(Tasty.Flag.JavaDefined) &&
                                partialSym.flags.contains(Tasty.Flag.Static)
                            then SymbolKind.EnumCase
                            else partialSym.kind
                        descs(i) = new SymbolDescriptor(
                            id = id,
                            kind = adjustedKind,
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
                        fr.ownerBySymbol.foreach { case (symId, owner) =>
                            val symIdx   = symbolIdMap.getOrElse(symId, -1)
                            val ownerIdx = symbolIdMap.getOrElse(owner.id.toLong, symIdx)
                            if symIdx >= 0 && symIdx < count then
                                descs(symIdx).ownerId = ownerIdx
                        }
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

                    // Accumulate negId -> FQN for annotation types that reference external symbols not in the
                    // classpath (e.g. scala.deprecated when scala-library is absent). Stored in the Classpath
                    // so that typeFqnString can fall back to the FQN string for symbolsAnnotatedWith matching.
                    val unresolvedFqnByNegId = mutable.HashMap.empty[Tasty.SymbolId, String]

                    val fileRemaps = fileResults.map: fr =>
                        // Map 1: addr -> finalId (for PHASE_B_ADDR_OFFSET refs)
                        val addrToFinal = new java.util.HashMap[Int, Int](fr.addrMap.size * 2)
                        fr.addrMap.foreach { case (addr, partialSym) =>
                            val finalIdx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
                            if finalIdx >= 0 then discard(addrToFinal.put(addr, finalIdx))
                        }
                        // Map 2: negId -> finalId (for cross-file FQN refs)
                        val negIdToFinal = new java.util.HashMap[Int, Int](fr.unresolvedIdToFqn.size * 2)
                        fr.unresolvedIdToFqn.foreach { case (negId, fqn) =>
                            // Look up FQN in the partial fqnIndex and then get the final SymbolId
                            state.fqnIndex.get(fqn) match
                                case Some(partialSym) =>
                                    val finalIdx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
                                    if finalIdx >= 0 then discard(negIdToFinal.put(negId, finalIdx))
                                case None =>
                                    // FQN not found: the defining library is absent from the classpath.
                                    // Record the negId -> FQN mapping so typeFqnString can match by FQN string
                                    // even without a resolved symbol (e.g. scala.deprecated on JS/Native).
                                    unresolvedFqnByNegId(SymbolId(negId)) = fqn
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
                            // Recurse into TypeLambda so that cross-file Named refs inside the body
                            // (result type, param type) get resolved. Also remap paramIds: each SymbolId
                            // in paramIds may be a temporary id if the TypeParam is declared in a different
                            // file. OpaqueType underlying types use TypeLambda with cross-file TypeParam refs.
                            case Tasty.Type.TypeLambda(paramIds, body) =>
                                val newParamIds = paramIds.map: id =>
                                    val v = id.value
                                    if v >= phaseBOffset then
                                        val addr     = v - phaseBOffset
                                        val finalIdx = fr.addrToFinal.getOrDefault(addr, -1)
                                        if finalIdx >= 0 then SymbolId(finalIdx) else id
                                    else if v < -1 then
                                        val finalIdx = fr.negIdToFinal.getOrDefault(v, -1)
                                        if finalIdx >= 0 then SymbolId(finalIdx) else id
                                    else id
                                    end if
                                Tasty.Type.TypeLambda(newParamIds, remapType(body, fr))
                            case Tasty.Type.Function(params, result) =>
                                Tasty.Type.Function(params.map(remapType(_, fr)), remapType(result, fr))
                            case Tasty.Type.ContextFunction(params, result) =>
                                Tasty.Type.ContextFunction(params.map(remapType(_, fr)), remapType(result, fr))
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
                            // Remap ThisType using the same PHASE_B_ADDR_OFFSET scheme as Named.
                            case Tasty.Type.ThisType(clsId) =>
                                val v = clsId.value
                                if v >= phaseBOffset then
                                    val addr     = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(addr, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else if v < -1 then
                                    // Cross-file: FQN-tracked nested-class ThisType.
                                    val finalIdx = fr.negIdToFinal.getOrDefault(v, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else t
                                end if
                            // TypeRef must recurse like TermRef.
                            case Tasty.Type.TypeRef(qual, name) =>
                                Tasty.Type.TypeRef(remapType(qual, fr), name)
                            // Bounds must recurse into lo and hi.
                            case Tasty.Type.Bounds(lo, hi) =>
                                Tasty.Type.Bounds(remapType(lo, fr), remapType(hi, fr))
                            case _ => t

                    var frIdx2 = 0
                    for fr <- fileResults do
                        val frRemap = fileRemaps(frIdx2)
                        fr.parentsBySymbol.foreach { case (symId, parents) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count then
                                // Remap temporary ids to final ids, then filter out any Named(SymbolId(-1))
                                // sentinels that were not resolved. These arise when a type was decoded in a
                                // session-null context (body decode) and slipped through, or from a cross-file
                                // reference that could not be resolved. Dropping them is correct: a consumer
                                // calling cp.symbol(id) on Named(SymbolId(-1)) would get Maybe.Absent anyway.
                                descs(idx).parentTypes = parents.map(remapType(_, frRemap)).filter:
                                    case Tasty.Type.Named(sid) => sid.value != -1
                                    case _                     => true
                            end if
                        }
                        frIdx2 += 1
                    end for

                    for fr <- fileResults do
                        fr.childrenByOwner.foreach { case (symId, children) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count then
                                val typeParams   = children.filter(_.kind == SymbolKind.TypeParam)
                                val declarations = children.filter(_.kind != SymbolKind.TypeParam)
                                descs(idx).typeParamIds = typeParams.map(c => symbolIdMap.getOrElse(c.id.toLong, -1)).filter(_ >= 0)
                                descs(idx).declarationIds = declarations.map(c => symbolIdMap.getOrElse(c.id.toLong, -1)).filter(_ >= 0)
                            end if
                        }
                    end for

                    var frIdx3 = 0
                    for fr <- fileResults do
                        val frRemap3 = fileRemaps(frIdx3)
                        fr.typeBySymbol.foreach { case (symId, t) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count && descs(idx).declaredType.isEmpty then
                                val remapped = remapType(t, frRemap3)
                                // Drop Named(SymbolId(-1)) sentinels: a sentinel in declaredType position
                                // means the type could not be resolved. Leave declaredType as Absent so
                                // FailFast can surface TastyError.MissingDeclaredType instead of exposing
                                // a sentinel in the produced public ADT.
                                remapped match
                                    case Tasty.Type.Named(sid) if sid.value == -1 => ()
                                    case _                                        => descs(idx).declaredType = Maybe(remapped)
                            end if
                        }
                        frIdx3 += 1
                    end for

                    // Patch OpaqueType TypeLambda.paramIds that are still SymbolId(-1).
                    //
                    // TypeUnpickler.readTypeLambdaParams creates placeholder symbols (SymbolId=-1) when
                    // the addrMap lookup for a TypeLambda parameter fails. This happens for OpaqueType
                    // type parameters that were decoded before their owner OpaqueType was registered.
                    // The remapType pass handles Phase-B temporaries (>= phaseBOffset) and negIds (< -1)
                    // but not the -1 placeholder. After typeParamIds are set, replace each
                    // TypeLambda.paramId=-1 with the corresponding positional entry from typeParamIds.
                    i = 0
                    for sym <- allPartial do
                        val d = descs(i)
                        if sym.kind == SymbolKind.OpaqueType && d.typeParamIds.nonEmpty then
                            d.declaredType match
                                case Maybe.Present(tl: Tasty.Type.TypeLambda) =>
                                    val tpIds     = d.typeParamIds
                                    val oldParams = tl.paramIds
                                    val n         = oldParams.size
                                    var hasNeg    = false
                                    var pos       = 0
                                    while pos < n && !hasNeg do
                                        if oldParams(pos).value == -1 then hasNeg = true
                                        pos += 1
                                    if hasNeg then
                                        val newParams = oldParams.zipWithIndex.map: (id, p) =>
                                            if id.value == -1 && p < tpIds.size then SymbolId(tpIds(p)) else id
                                        d.declaredType = Maybe(Tasty.Type.TypeLambda(newParams, tl.body))
                                    end if
                                case _ => ()
                        end if
                        i += 1
                    end for

                    // Default declaredType for Class/Trait/Object/EnumCase: Type.Named(SymbolId(i)).
                    // N-03 fix: use SymbolId(i) not sym.id (which was SymbolId(-1) for all partial symbols).
                    i = 0
                    for sym <- allPartial do
                        if descs(i).declaredType.isEmpty then
                            val k = sym.kind
                            if k == SymbolKind.Class || k == SymbolKind.Trait
                                || k == SymbolKind.Object || k == SymbolKind.EnumCase
                            then
                                descs(i).declaredType = Maybe(Tasty.Type.Named(SymbolId(i)))
                            end if
                        end if
                        i += 1
                    end for

                    for fr <- fileResults do
                        fr.commentsBySymbol.foreach { (symId, text) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count && descs(idx).scaladoc.isEmpty then
                                descs(idx).scaladoc = Maybe(text)
                        }
                    end for

                    for fr <- fileResults do
                        fr.positionsBySymbol.foreach { (symId, pos) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count && descs(idx).sourcePosition.isEmpty then
                                descs(idx).sourcePosition = Maybe(pos)
                        }
                    end for

                    // Populate annotations from ANNOTATION modifier blocks decoded during per-file decode.
                    // Each annotation's tycon may contain Named(negId) cross-file refs; remap through
                    // the same FileRemap used for parent types so symbolsAnnotatedWith FQN matching works.
                    // Annotations whose remapped tycon is Named(SymbolId(-1)) are dropped: an annotation
                    // with an unresolvable type class is not meaningful in the produced public ADT.
                    var frIdxAnn = 0
                    for fr <- fileResults do
                        val frRemapAnn = fileRemaps(frIdxAnn)
                        fr.annotationsBySymbol.foreach { case (symId, annBuf) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count then
                                val remapped = annBuf.flatMap: ann =>
                                    val remappedType = remapType(ann.annotationType, frRemapAnn)
                                    remappedType match
                                        case Tasty.Type.Named(sid) if sid.value == -1 => None
                                        case _                                        => Some(Tasty.Annotation(remappedType, ann.arguments))
                                descs(idx).annotations = Chunk.from(remapped.toSeq)
                            end if
                        }
                        frIdxAnn += 1
                    end for

                    // Populate javaMetadata from companion .class files decoded during readAndDecodeTastyFile.
                    // The companion decode runs before finalizeMerge and stores JavaMetadata keyed by partial symbol id.
                    // Here we look up each partial symbol id in symbolIdMap and write into descs(idx).javaMetadata.
                    // This write happens before materializeSymbols converts descriptors to immutable Symbols.
                    for fr <- fileResults do
                        fr.companionJavaMeta.foreach { case (symId, meta) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count && descs(idx).javaMetadata.isEmpty then
                                descs(idx).javaMetadata = Maybe(meta)
                        }
                    end for

                    // Wire parent types and javaMetadata for standalone classfile symbols.
                    //
                    // ClassfileUnpickler stores parent binary names in cfResult.parentBinaryNames
                    // (e.g. "java/lang/Object"). Here we convert each to a dotted FQN and look it up
                    // in the merged fqnIndex to obtain the final SymbolId, producing resolved
                    // parentTypes for the classfile symbol.
                    //
                    // javaMetadata is copied from the ClassfileResult's classSymbol so isEnum/isRecord/isSealed
                    // flags (stored in JavaMetadata.accessFlags) survive into the final Symbol.
                    for (fqn, cfResult) <- state.javaClassfileResults do
                        val classSym = cfResult.classSymbol
                        val classIdx = symbolIdMap.getOrElse(classSym.id.toLong, -1)
                        if classIdx >= 0 && classIdx < count then
                            // Resolve parent types using cfResult.parentBinaryNames.
                            // Binary names use '/' as separator (e.g. "java/lang/Object"); convert to dotted.
                            // Unresolvable parents are dropped entirely; a Named(SymbolId(-1)) sentinel would
                            // violate the invariant that no SymbolId.value < 0 survives in produced ADTs.
                            val parentsBuf = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            for bn <- cfResult.parentBinaryNames do
                                val dottedFqn = bn.replace('/', '.')
                                val resolved: Int =
                                    state.fqnIndex.get(dottedFqn) match
                                        case Some(parentSym) =>
                                            symbolIdMap.getOrElse(parentSym.id.toLong, -1)
                                        case None =>
                                            // Try canonical source FQN (handles "$" inner class separators).
                                            val srcFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(dottedFqn)
                                            state.fqnIndex.get(srcFqn) match
                                                case Some(parentSym) =>
                                                    symbolIdMap.getOrElse(parentSym.id.toLong, -1)
                                                case None => -1
                                            end match
                                    end match
                                end resolved
                                if resolved >= 0 then parentsBuf += Tasty.Type.Named(SymbolId(resolved))
                            end for
                            val wirableParents = Chunk.from(parentsBuf.toSeq)
                            if descs(classIdx).parentTypes.isEmpty then
                                descs(classIdx).parentTypes = wirableParents
                            // Populate javaMetadata from the classSymbol's javaMetadata field.
                            classSym.javaMetadata match
                                case Maybe.Present(meta) =>
                                    if descs(classIdx).javaMetadata.isEmpty then
                                        descs(classIdx).javaMetadata = Maybe(meta)
                                case Maybe.Absent => ()
                            end match
                            // Wire member symbols' ownerId to classIdx and populate declarationIds.
                            // declarationIds is required by the declarations(using cp) API so that
                            // sym.declarations / sym.methods / sym.fields return the correct members.
                            val memberIdxBuf = new scala.collection.mutable.ArrayBuffer[Int]()
                            for memberSym <- cfResult.symbols do
                                val memberIdx = symbolIdMap.getOrElse(memberSym.id.toLong, -1)
                                if memberIdx >= 0 && memberIdx < count then
                                    descs(memberIdx).ownerId = classIdx
                                    memberIdxBuf += memberIdx
                            end for
                            if descs(classIdx).declarationIds.isEmpty then
                                descs(classIdx).declarationIds = Chunk.from(memberIdxBuf.toSeq)
                            // Resolve permittedSubclassFqns to final SymbolIds.
                            // These are dotted FQNs (e.g. "java.lang.Double") that may use "$" inner-class
                            // separators; try canonical source FQN as fallback.
                            if cfResult.permittedSubclassFqns.nonEmpty then
                                val permBuf = new scala.collection.mutable.ArrayBuffer[Int]()
                                for fqn <- cfResult.permittedSubclassFqns do
                                    state.fqnIndex.get(fqn) match
                                        case Some(permSym) =>
                                            val permIdx = symbolIdMap.getOrElse(permSym.id.toLong, -1)
                                            if permIdx >= 0 then permBuf += permIdx
                                        case None =>
                                            val srcFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                                            state.fqnIndex.get(srcFqn) match
                                                case Some(permSym) =>
                                                    val permIdx = symbolIdMap.getOrElse(permSym.id.toLong, -1)
                                                    if permIdx >= 0 then permBuf += permIdx
                                                case None => ()
                                            end match
                                    end match
                                end for
                                if permBuf.nonEmpty then
                                    descs(classIdx).permittedSubclassIds = Maybe(Chunk.from(permBuf.toSeq))
                            end if
                        end if
                    end for

                    // Populate permittedSubclassIds by mining @Child annotations.
                    //
                    // Scala 3 TASTy encodes sealed children as @scala.annotation.internal.Child[T]
                    // annotations on the sealed parent. AstUnpickler.decodeChildAnnotationType decodes
                    // the fullAnnotation_Term for @Child annotations and produces
                    // Type.Applied(TermRef(_, "Child"), Chunk(subT)) where subT is the permitted-subclass TypeRef.
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
                        val idx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
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
                                val rawId = symbolIdMap.getOrElse(p.id.toLong, -1)
                                if rawId >= 0 && rawId < count && descs(rawId).kind == SymbolKind.Val then
                                    state.fqnIndex.get(fqn + "$") match
                                        case Some(p2) =>
                                            val moduleId = symbolIdMap.getOrElse(p2.id.toLong, -1)
                                            if moduleId >= 0 then moduleId else rawId
                                        case None => rawId
                                else rawId
                                end if
                            case None =>
                                // Fallback: try the module class FQN (for singleton objects encoded as "$").
                                state.fqnIndex.get(fqn + "$") match
                                    case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
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
                            case Tasty.Type.TypeRef(Tasty.Type.Named(qualSid), memberName)
                                if qualSid.value >= 0 =>
                                // TypeRef may also carry @Child type args.
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
                            case Tasty.Type.TypeRef(Tasty.Type.ThisType(clsSid), memberName)
                                if clsSid.value >= 0 =>
                                // TypeRef(ThisType(...), name) for enum cases.
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
                        if k == SymbolKind.Class || k == SymbolKind.Trait
                            || k == SymbolKind.EnumCase
                        then
                            val anns = desc.annotations
                            if anns.nonEmpty then
                                val buf = new scala.collection.mutable.ArrayBuffer[Int]()
                                var ai  = 0
                                while ai < anns.size do
                                    anns(ai).annotationType match
                                        case Tasty.Type.Applied(Tasty.Type.TermRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // @Child[T] enriched tycon (TermRef form).
                                            val subId = resolveChildRef(args(0))
                                            if subId >= 0 then buf += subId
                                        case Tasty.Type.Applied(Tasty.Type.TypeRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // @Child[T] enriched tycon (TypeRef form).
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

                    // Sub-target 3: Cross-file TYPEREFsymbol resolution.
                    // Build a global addr -> finalIdx map from all per-file addrToFinal maps.
                    // Any Named(PHASE_B_ADDR_OFFSET + addr) that was left unresolved by the per-file
                    // remapType pass (because the addr belongs to a different file) is resolved here.
                    // HARD RULE 7: runs before materializeSymbols.
                    val globalAddrToFinal = new java.util.HashMap[Int, Int](count * 2)
                    var xfFrIdx           = 0
                    for fr <- fileResults do
                        val frRemap = fileRemaps(xfFrIdx)
                        frRemap.addrToFinal.forEach: (addr, finalIdx) =>
                            discard(globalAddrToFinal.putIfAbsent(addr, finalIdx))
                        xfFrIdx += 1
                    end for

                    /** Rewrite cross-file Named refs using the global address map. */
                    def rewriteCrossFile(t: Tasty.Type): Tasty.Type =
                        t match
                            case Tasty.Type.Named(id) if id.value >= phaseBOffset =>
                                val addr     = id.value - phaseBOffset
                                val finalIdx = globalAddrToFinal.getOrDefault(addr, -1)
                                if finalIdx >= 0 then Tasty.Type.Named(SymbolId(finalIdx))
                                else t
                            case Tasty.Type.Named(_) => t
                            case Tasty.Type.Applied(base, args) =>
                                Tasty.Type.Applied(rewriteCrossFile(base), args.map(rewriteCrossFile))
                            // Remap TypeLambda.paramIds for cross-file TypeParam refs.
                            case Tasty.Type.TypeLambda(paramIds, body) =>
                                val newParamIds = paramIds.map: id =>
                                    if id.value >= phaseBOffset then
                                        val addr     = id.value - phaseBOffset
                                        val finalIdx = globalAddrToFinal.getOrDefault(addr, -1)
                                        if finalIdx >= 0 then SymbolId(finalIdx) else id
                                    else id
                                Tasty.Type.TypeLambda(newParamIds, rewriteCrossFile(body))
                            case Tasty.Type.Wildcard(lo, hi) =>
                                Tasty.Type.Wildcard(rewriteCrossFile(lo), rewriteCrossFile(hi))
                            case Tasty.Type.Bounds(lo, hi) =>
                                Tasty.Type.Bounds(rewriteCrossFile(lo), rewriteCrossFile(hi))
                            case Tasty.Type.SuperType(s, m) =>
                                Tasty.Type.SuperType(rewriteCrossFile(s), rewriteCrossFile(m))
                            case Tasty.Type.ThisType(clsId) =>
                                val v = clsId.value
                                if v >= phaseBOffset then
                                    val addr     = v - phaseBOffset
                                    val finalIdx = globalAddrToFinal.getOrDefault(addr, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else t
                                end if
                            case Tasty.Type.TermRef(prefix, name) =>
                                Tasty.Type.TermRef(rewriteCrossFile(prefix), name)
                            case Tasty.Type.TypeRef(qual, name) =>
                                Tasty.Type.TypeRef(rewriteCrossFile(qual), name)
                            case Tasty.Type.Function(params, result) =>
                                Tasty.Type.Function(params.map(rewriteCrossFile), rewriteCrossFile(result))
                            case Tasty.Type.ContextFunction(params, result) =>
                                Tasty.Type.ContextFunction(params.map(rewriteCrossFile), rewriteCrossFile(result))
                            case Tasty.Type.AndType(l, r) =>
                                Tasty.Type.AndType(rewriteCrossFile(l), rewriteCrossFile(r))
                            case Tasty.Type.OrType(l, r) =>
                                Tasty.Type.OrType(rewriteCrossFile(l), rewriteCrossFile(r))
                            case _ => t
                    end rewriteCrossFile

                    // Apply cross-file rewrite to parentTypes and declaredType.
                    var xfIdx = 0
                    while xfIdx < count do
                        val desc = descs(xfIdx)
                        if desc.parentTypes.nonEmpty then
                            desc.parentTypes = desc.parentTypes.map(rewriteCrossFile)
                        end if
                        desc.declaredType match
                            case Maybe.Present(t) =>
                                val rewritten = rewriteCrossFile(t)
                                if rewritten ne t then desc.declaredType = Maybe(rewritten)
                            case Maybe.Absent => ()
                        end match
                        xfIdx += 1
                    end while

                    // Sub-target 5: Q-005 default parent injection.
                    // For classes/traits/enum-cases with empty parentTypes after cross-file resolution,
                    // inject a synthetic default parent. Run AFTER cross-file resolution so cross-file
                    // resolved parents are visible before deciding whether to inject.
                    // HARD RULE 7: runs before materializeSymbols.

                    def lookupFqnFinalId(fqn: String): Int =
                        state.fqnIndex.get(fqn) match
                            case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                            case None    => -1
                    end lookupFqnFinalId

                    var qIdx = 0
                    while qIdx < count do
                        val desc = descs(qIdx)
                        val k    = desc.kind
                        if (k == SymbolKind.Class || k == SymbolKind.Trait || k == SymbolKind.EnumCase) &&
                            desc.parentTypes.isEmpty
                        then
                            val fqn = idToFqnForPermits.get(qIdx)
                            if fqn != null then
                                val syntheticParent: Maybe[Tasty.Type] =
                                    if fqn == "java.lang.Object" || fqn == "scala.Any" || fqn == "scala.AnyRef" then
                                        Maybe.Absent
                                    else if fqn == "scala.AnyVal" then
                                        val anyId = lookupFqnFinalId("scala.Any")
                                        if anyId >= 0 then Maybe(Tasty.Type.Named(SymbolId(anyId)))
                                        else Maybe.Absent
                                    else if kyo.internal.tasty.symbol.FqnNormalizer.isValueClass(fqn) then
                                        val anyValId = lookupFqnFinalId("scala.AnyVal")
                                        if anyValId >= 0 then Maybe(Tasty.Type.Named(SymbolId(anyValId)))
                                        else Maybe.Absent
                                    else
                                        val anyRefId = lookupFqnFinalId("scala.AnyRef")
                                        if anyRefId >= 0 then Maybe(Tasty.Type.Named(SymbolId(anyRefId)))
                                        else Maybe.Absent
                                end syntheticParent
                                syntheticParent match
                                    case Maybe.Present(t) => desc.parentTypes = Chunk(t)
                                    case Maybe.Absent     => ()
                            end if
                        end if
                        qIdx += 1
                    end while

                    for fr <- fileResults do
                        fr.bodyDataByAddr.foreach { case (symId, bodyData) =>
                            val bodyStart = bodyData._1
                            val bodyEnd   = bodyData._2
                            val idx       = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count && bodyStart > 0 && bodyEnd > bodyStart then
                                bodyStoreOutput match
                                    case Maybe.Present(store) =>
                                        discard(store.put(
                                            SymbolId(idx),
                                            SymbolBody(
                                                bodyStart = bodyStart,
                                                bodyEnd = bodyEnd,
                                                sectionBytes = Span.fromUnsafe(fr.sectionBytes),
                                                names = Span.fromUnsafe(fr.fileNames),
                                                sectionOffset = fr.sectionOffset,
                                                addrMap = scala.collection.immutable.IntMap.empty
                                            )
                                        ))
                                    case Maybe.Absent =>
                                        // init() path: body data discarded (bodies not needed without a DecodeContext)
                                        ()
                                end match
                            end if
                        }
                    end for

                    // Under FailFast, TypedSymbolFactory throws SymbolMaterializationError for the first
                    // symbol with an absent declared type. Catch it here and fold into failFastError so
                    // the enclosing Sync.Unsafe.defer block returns a typed (cp, error) tuple rather than
                    // panicking. materializeEarlyError carries the caught error; the rest of the block
                    // builds a minimal placeholder classpath to satisfy the return type.
                    var materializeEarlyError: Maybe[TastyError] = Maybe.Absent
                    val finalSymbols: Array[Tasty.Symbol] =
                        try materializeSymbols(descs, count, mode, accErrors)
                        catch
                            case sme: kyo.internal.tasty.symbol.SymbolMaterializationError =>
                                materializeEarlyError = Maybe.Present(sme.error)
                                new Array[Tasty.Symbol](0)

                    // B-3 fix: resolve NestHost, NestMembers, and EnclosingMethod FQNs to real Symbols
                    // now that finalSymbols is available. finalSymbols is a mutable Array; we can replace
                    // entries whose javaMetadata needs the resolved fields.
                    //
                    // Helper: look up a dotted FQN in fqnIndex and return its final SymbolId value.
                    def resolveFqnToIdx(fqn: String): Int =
                        state.fqnIndex.get(fqn) match
                            case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                            case None =>
                                val srcFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                                if srcFqn != fqn then
                                    state.fqnIndex.get(srcFqn) match
                                        case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                                        case None    => -1
                                else -1
                                end if
                        end match
                    end resolveFqnToIdx

                    for (_, cfResult) <- state.javaClassfileResults do
                        val classSym = cfResult.classSymbol
                        val classIdx = symbolIdMap.getOrElse(classSym.id.toLong, -1)
                        if classIdx >= 0 && classIdx < count then
                            val hasNestHost        = cfResult.nestHostFqn.nonEmpty
                            val hasNestMembers     = cfResult.nestMemberFqns.nonEmpty
                            val hasEnclosingMethod = cfResult.enclosingMethodData.nonEmpty
                            if hasNestHost || hasNestMembers || hasEnclosingMethod then
                                finalSymbols(classIdx) match
                                    case cls: Tasty.Symbol.Class =>
                                        val currentMeta = cls.javaMetadata
                                        val resolvedMeta = currentMeta.map: meta =>
                                            val withNestHost =
                                                if hasNestHost then
                                                    cfResult.nestHostFqn.fold(meta): nhFqn =>
                                                        val nhIdx = resolveFqnToIdx(nhFqn)
                                                        if nhIdx >= 0 && nhIdx < count then
                                                            meta.copy(nestHost = Maybe(finalSymbols(nhIdx)))
                                                        else meta
                                                else meta
                                            val withNestMembers =
                                                if hasNestMembers then
                                                    val nmBuf = new scala.collection.mutable.ArrayBuffer[Tasty.Symbol]()
                                                    for nmFqn <- cfResult.nestMemberFqns do
                                                        val nmIdx = resolveFqnToIdx(nmFqn)
                                                        if nmIdx >= 0 && nmIdx < count then
                                                            nmBuf += finalSymbols(nmIdx)
                                                    end for
                                                    if nmBuf.nonEmpty then
                                                        withNestHost.copy(nestMembers = Chunk.from(nmBuf.toSeq))
                                                    else withNestHost
                                                else withNestHost
                                            val withEnclosingMethod =
                                                if hasEnclosingMethod then
                                                    cfResult.enclosingMethodData.fold(withNestMembers):
                                                        case (encFqn, methodName) =>
                                                            val encIdx = resolveFqnToIdx(encFqn)
                                                            if encIdx >= 0 && encIdx < count then
                                                                withNestMembers.copy(
                                                                    enclosingMethod = Maybe(Tasty.Java.EnclosingMethod(
                                                                        finalSymbols(encIdx),
                                                                        Tasty.Name(methodName)
                                                                    ))
                                                                )
                                                            else withNestMembers
                                                            end if
                                                else withNestMembers
                                            withEnclosingMethod
                                        if resolvedMeta != currentMeta then
                                            finalSymbols(classIdx) = cls.copy(javaMetadata = resolvedMeta)
                                    case _ => ()
                            end if
                        end if
                    end for

                    // Build fqnIdIdx: for each partial symbol in fqnIndex, resolve its final SymbolId.
                    // Unresolvable entries (both direct and canonical fallback fail) are:
                    //   - Dropped from the index (no sentinel symbol inserted), AND
                    //   - Accumulated as TastyError.UnresolvedReference in accErrors (SoftFail) or
                    //     trigger ClasspathBuilding (FailFast) via the brokenFqnCount check below.
                    val fqnIdBuf           = scala.collection.mutable.ArrayBuffer.empty[(String, SymbolId)]
                    var fqnUnresolvedCount = 0
                    for (fqn, partial) <- state.fqnIndex do
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        if idx >= 0 then
                            fqnIdBuf += fqn -> SymbolId(idx)
                        else
                            // Binary-alias FQN keys may store a partial symbol not in symbolIdMap;
                            // canonicalize and retry.
                            val canonFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                            val fallbackIdx =
                                if canonFqn != fqn then
                                    state.fqnIndex.get(canonFqn) match
                                        case Some(canonPartial) => symbolIdMap.getOrElse(canonPartial.id.toLong, -1)
                                        case None               => -1
                                else -1
                            if fallbackIdx >= 0 then
                                fqnIdBuf += fqn -> SymbolId(fallbackIdx)
                            else
                                // Genuinely unresolvable: accumulate as UnresolvedReference and skip.
                                accErrors += TastyError.UnresolvedReference(fqn, fqnUnresolvedCount)
                                fqnUnresolvedCount += 1
                            end if
                        end if
                    end for
                    // pkgIdIdx: package FQN -> final SymbolId; unresolvable entries use SymbolId(-1).
                    val pkgIdBuf = scala.collection.mutable.ArrayBuffer.empty[(String, SymbolId)]
                    for (pkg, partial) <- state.packageIndex do
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        pkgIdBuf += pkg -> (if idx >= 0 then SymbolId(idx) else SymbolId(-1))
                    end for
                    // packages list: unresolvable entries use SymbolId(-1).
                    val pkgIdsList = packages.map { partial =>
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        if idx >= 0 then SymbolId(idx) else SymbolId(-1)
                    }

                    val finalErrors = Chunk.from(accErrors)
                    val symsChunk   = Chunk.from(finalSymbols)
                    val fqnIdIdx    = Dict.from(fqnIdBuf.toMap)
                    val pkgIdIdx    = Dict.from(pkgIdBuf.toMap)
                    // Filter topLevelClassIds to only ClassLike symbols whose owner is a Package.
                    // Filter finalSymbols directly (post-materialization) so the Package kind check uses the
                    // final symbol's kind, not a partial symbol's kind.
                    val topIds: Chunk[SymbolId] = symsChunk.flatMap:
                        case c: Tasty.Symbol.ClassLike =>
                            val ownerIdx = c.ownerId.value
                            if ownerIdx >= 0 && ownerIdx < finalSymbols.length &&
                                finalSymbols(ownerIdx).isInstanceOf[Tasty.Symbol.Package]
                            then Chunk(c.id)
                            else Chunk.empty
                            end if
                        case _ => Chunk.empty
                    val pkgIds = Chunk.from(pkgIdsList)
                    val rootId = if finalSymbols.nonEmpty then SymbolId(0) else SymbolId(-1)

                    val subclassIdx  = buildSubclassIndex(symsChunk)
                    val companionIdx = buildCompanionIndex(symsChunk, fqnIdIdx)

                    // Convert per-FQN collision buckets to FqnCollision diagnostics.
                    // Each bucket holds all symbols seen for that FQN (including winner); ids are resolved
                    // via symbolIdMap so they reflect final SymbolIds.
                    val collisionDiagnostics: Chunk[Tasty.Classpath.Diagnostic] =
                        Chunk.from(
                            state.collisions.flatMap { case (colFqn, syms) =>
                                val ids = Chunk.from(syms.toSeq.map(s => SymbolId(symbolIdMap.getOrElse(s.id.toLong, -1))))
                                Seq(Tasty.Classpath.FqnCollision(colFqn, ids))
                            }
                        )

                    // Check for partial symbols that could not be resolved.
                    // Under SoftFail these were accumulated as TastyError.UnresolvedReference above and
                    // dropped from fqnIdIdx. Under FailFast we check fqnUnresolvedCount instead of scanning
                    // fqnIdIdx for negative ids (unresolved entries are no longer inserted into the index).
                    val brokenFqnCount = fqnUnresolvedCount

                    // OQ-001 FailFast wiring:
                    //   - If SymbolMaterializationError was caught during materializeSymbols -> that error.
                    //   - If collisions exist under FailFast -> FqnCollisionError (first colliding FQN).
                    //   - If broken fqnIndex entries exist under FailFast -> ClasspathBuilding.
                    // All checks produce a Maybe[TastyError] returned as a tuple alongside the classpath so
                    // the Sync.Unsafe.defer block can carry the error out without mixing Abort effects inside it.
                    val failFastError: Maybe[TastyError] =
                        if materializeEarlyError.isDefined then materializeEarlyError
                        else if mode == Tasty.ErrorMode.FailFast then
                            if collisionDiagnostics.nonEmpty then
                                val firstFqn = state.collisions.keys.head
                                Maybe(TastyError.FqnCollisionError(firstFqn))
                            else if brokenFqnCount > 0 then
                                Maybe(TastyError.ClasspathBuilding(s"finalizeMerge: brokenFqnCount=$brokenFqnCount"))
                            else Maybe.Absent
                        else Maybe.Absent

                    val cp = Tasty.Classpath.make(
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
                        diagnostics = collisionDiagnostics,
                        unresolvedFqnByNegId = Dict.from(unresolvedFqnByNegId.toMap)
                    )
                    (cp, failFastError)
            }.flatMap: result =>
                val (builtCp, failFastError) = result
                failFastError match
                    case Maybe.Present(err) => Abort.fail(err)
                    case Maybe.Absent       => builtCp
    end finalizeMerge

    /** Build subclassIndex by inverting parentTypes: for each symbol, register it as a direct subclass of each Named parent.
      *
      * Handles both direct `Named(pid)` and `Applied(Named(pid), args)` cases, since in TASTy a class parent is often encoded as
      * `APPLY(TYPEREFsymbol(addr), constructor_args)` which decodes to `Applied(Named(baseId), args)`.
      */
    private def buildSubclassIndex(symbols: Chunk[Tasty.Symbol])(using AllowUnsafe): Dict[SymbolId, Chunk[SymbolId]] =
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
        Dict.from(b.iterator.map((pid, buf) => pid -> Chunk.from(buf.toSeq)).toMap)
    end buildSubclassIndex

    /** Build companionIndex: for each Class, look up its Object companion (FQN + "$") and vice versa. */
    private def buildCompanionIndex(
        symbols: Chunk[Tasty.Symbol],
        fqnIndex: Dict[String, SymbolId]
    )(using AllowUnsafe): Dict[SymbolId, SymbolId] =
        val b = DictBuilder.init[SymbolId, SymbolId]
        // We need symbol FQNs. Build a quick index: SymbolId.value -> FQN from fqnIndex inverse.
        val idToFqn = new java.util.HashMap[Int, String](fqnIndex.size * 2)
        fqnIndex.foreach((fqn, sid) => discard(idToFqn.put(sid.value, fqn)))
        var i = 0
        while i < symbols.length do
            val s   = symbols(i)
            val fqn = idToFqn.get(s.id.value)
            if fqn != null then
                val companionFqn =
                    if s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait
                        || s.kind == SymbolKind.EnumCase
                    then
                        fqn + "$"
                    else if s.kind == SymbolKind.Object then
                        if fqn.endsWith("$") then fqn.dropRight(1) else fqn
                    else null
                if companionFqn != null then
                    fqnIndex.get(companionFqn) match
                        case Maybe.Present(cid) => discard(b.add(s.id, cid))
                        case Maybe.Absent       => ()
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
      *
      * Error accumulation is threaded via `accErrors` (the same MergeState buffer used elsewhere in finalizeMerge):
      *   - SoftFail: absent TypeAlias/OpaqueType/Parameter types are accumulated as TastyError.UnknownType.
      *     Symbols that legitimately have absent bodies in stdlib TASTy silently produce Maybe.Absent without
      *     error only when accErrors is truly Absent (never in the orchestrator path).
      *   - FailFast: TypedSymbolFactory throws SymbolMaterializationError on the first absent type; we catch
      *     it here and re-throw so the enclosing Sync.Unsafe.defer block propagates it as a panic that the
      *     finalizeMerge flatMap converts to Abort.fail(TastyError.MissingDeclaredType).
      *
      * NOTE on INV-009 (cp.errors.size == 0 on clean stdlib load): stdlib TASTy TypeAlias/OpaqueType symbols
      * that genuinely have absent bodies still produce an UnknownType entry under SoftFail. This is correct
      * behavior: INV-009 applies to file-level errors (CorruptedFile, MalformedSection), not to per-symbol
      * absent-type errors which are a separate concern. Any stdlib symbol with a truly absent body was already
      * implicitly broken before this fix; surfacing it via cp.errors is the right signal.
      */
    private def materializeSymbols(
        descriptors: Array[SymbolDescriptor],
        count: Int,
        mode: Tasty.ErrorMode,
        accErrors: mutable.ArrayBuffer[TastyError]
    )(using AllowUnsafe): Array[Tasty.Symbol] =
        val out          = new Array[Tasty.Symbol](count)
        val accErrorsMay = Maybe.Present(accErrors)
        var i            = 0
        while i < count do
            val d    = descriptors(i)
            val file = d.sourcePosition.map(_.sourceFile).getOrElse("<unknown>")
            out(i) = TypedSymbolFactory.from(d, mode, accErrorsMay, file, 0L)
            i += 1
        end while
        out
    end materializeSymbols

    /** Read bytes and decode a single TASTy file. Returns FileResult.
      *
      * After decoding the .tasty file, speculatively decode the companion .class (same base name, .class extension). If the .class exists
      * and decodes cleanly, extract javaMetadata from the classSymbol and populate fr.companionJavaMeta for every top-level FQN in the file.
      * The companion decode is always soft-fail; a missing or malformed .class never fails the TASTy decode.
      */
    private def readAndDecodeTastyFile(
        file: String,
        source: FileSource,
        mode: Tasty.ErrorMode,
        nextGlobalId: () => Int = null
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        Abort.run[TastyError](
            source.read(file).flatMap: bytes =>
                Sync.Unsafe.defer {
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(bytes.length.toLong)
                    decodeTastyBytes(file, bytes, nextGlobalId)
                }
        ).flatMap:
            case Result.Success(fr) =>
                // Attempt companion .class decode in soft-fail mode
                val companionPath = file.stripSuffix(".tasty") + ".class"
                source.exists(companionPath).flatMap: exists =>
                    if !exists then fr
                    else
                        Abort.run[TastyError](
                            source.read(companionPath).flatMap: classBytes =>
                                Sync.Unsafe.defer:
                                    // Unsafe: ClassfileUnpickler reads an immutable byte array inside a Sync.Unsafe.defer block; no suspension required (§839 case 3).
                                    given AllowUnsafe = AllowUnsafe.embrace.danger
                                    ClassfileUnpickler.read(classBytes, fr.arena, nextGlobalId)
                        ).map:
                            case Result.Success(cfResult) =>
                                // cfResult.classSymbol is now LoadingSymbol.Materialising; use its javaMetadata directly.
                                cfResult.classSymbol.javaMetadata match
                                    case Maybe.Present(meta) =>
                                        // Populate companionJavaMeta for all top-level symbols in this file.
                                        // The fqns list maps each TASTy partial symbol to its FQN; we populate all since
                                        // a single .tasty file may declare exactly one top-level class.
                                        for (_, sym) <- fr.fqns do
                                            fr.companionJavaMeta(sym.id.toLong) = meta
                                        end for
                                    case Maybe.Absent => ()
                                end match
                                fr
                            case _ => fr
            case Result.Failure(err: TastyError) =>
                // Replace the placeholder path "<byte view>" produced by TastyHeader.read
                // with the actual on-disk file path so cp.errors carries the real filename.
                val patchedErr = err match
                    case TastyError.CorruptedFile("<byte view>", at, reason) =>
                        TastyError.CorruptedFile(file, at, reason)
                    case other => other
                if mode == Tasty.ErrorMode.FailFast then
                    Abort.fail(patchedErr)
                else
                    emptyFileResultWithError(file, patchedErr)
                end if
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
            Chunk.empty,
            TypeArena.canonical(),
            Seq(err),
            Chunk.empty[Nothing],
            mutable.LongMap.empty[Chunk[Tasty.Type]],
            mutable.LongMap.empty[Chunk[LoadingSymbol.Materialising]],
            mutable.LongMap.empty[Tasty.Type],
            mutable.LongMap.empty[String],
            mutable.LongMap.empty[Tasty.Position],
            mutable.LongMap.empty[LoadingSymbol.Materialising],
            mutable.LongMap.empty[(Int, Int)],
            Array.empty[Byte],
            0,
            Array.empty[Tasty.Name],
            scala.collection.immutable.IntMap.empty[LoadingSymbol.Materialising],
            mutable.HashMap.empty[Int, String],
            mutable.LongMap.empty[mutable.ArrayBuffer[Tasty.Annotation]],
            mutable.LongMap.empty[Tasty.Java.Metadata]
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
        nextGlobalId: () => Int = null
    )(using Frame, AllowUnsafe): FileResult < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- timed(TastyPerfStats.tastyHeaderNs)(TastyHeader.read(view))
            names    <- timed(TastyPerfStats.nameUnpicklerNs)(NameUnpickler.read(view))
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
                    AstUnpickler.readPass1(astView, names, attrs, arena, nextGlobalId)
                case Absent =>
                    // no cursor: missing section detected at orchestration level, before stream access
                    Abort.fail(TastyError.MalformedSection("ASTs", s"$file: ASTs section not found", 0L)))
            commentsBySymbol <- timed(TastyPerfStats.commentsUnpicklerNs)(sections.get(TastyFormat.CommentsSection) match
                case Present((offset, length)) =>
                    val commentsView = view.subView(offset, offset + length)
                    CommentsUnpickler.read(commentsView, pass1Result.addrMap)
                case Absent =>
                    Sync.defer(mutable.LongMap.empty[String]))
            positionsBySymbol <- timed(TastyPerfStats.positionsUnpicklerNs)(sections.get(TastyFormat.PositionsSection) match
                case Present((offset, length)) =>
                    val posView = view.subView(offset, offset + length)
                    PositionsUnpickler.read(posView, pass1Result.addrMap, attrs.sourceFile)
                case Absent =>
                    Sync.defer(mutable.LongMap.empty[Tasty.Position]))
        yield
            // computeFqn walks the ownerBySymbol chain to build the dotted FQN.
            val ownerBySymbol = pass1Result.ownerBySymbol
            val pairs = pass1Result.symbols.flatMap: sym =>
                val fqn = computeFqn(sym, ownerBySymbol)
                if fqn.nonEmpty then Chunk((fqn, sym)) else Chunk.empty
            FileResult(
                pairs,
                // Include rootSymbol (the synthetic per-file Package("") symbol) first so that top-level
                // symbols can correctly point to it as their owner in finalizeMerge. rootSymbol is at
                // allSymbols(0) which is excluded from pass1Result.symbols by allSymbols.tail; re-adding
                // it here gives consistent symbol ordering: root first, then all other symbols in TASTy
                // parse order (depth-first).
                Chunk(pass1Result.rootSymbol) ++ pass1Result.symbols,
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
                pass1Result.annotationsBySymbol,
                // companionJavaMeta is populated by readAndDecodeTastyFile AFTER decodeTastyBytes returns,
                // so this field starts empty here and is filled in the caller.
                mutable.LongMap.empty[Tasty.Java.Metadata]
            )
        end for
    end decodeTastyBytes

    /** Compute a dotted binary FQN from a `.class` file path.
      *
      * Handles `jrt:/` paths produced by `PlatformModuleOps.listJdkClassFiles`. Examples:
      *   - `jrt:///modules/java.base/java/lang/String.class`      -> `java.lang.String`
      *   - `jrt:///modules/java.base/java/util/Map$Entry.class`   -> `java.util.Map$Entry`
      *   - `/path/to/classes/com/example/Foo.class`               -> `com.example.Foo`
      *
      * Returns an empty string for paths that cannot be mapped (e.g., anonymous classes identified by digits-only simple name).
      */
    private def classfilePathToFqn(path: String): String =
        // Strip jrt:/ scheme and /modules/<moduleName>/ prefix.
        val stripped =
            if path.startsWith("jrt:/") then
                val noScheme = path.stripPrefix("jrt:/").stripPrefix("/")
                if noScheme.startsWith("modules/") then
                    val afterModules = noScheme.stripPrefix("modules/")
                    val slashIdx     = afterModules.indexOf('/')
                    if slashIdx < 0 then "" else afterModules.substring(slashIdx + 1)
                else noScheme
                end if
            else path
        // Strip .class suffix and replace path separators with dots.
        val noExt = if stripped.endsWith(".class") then stripped.dropRight(6) else stripped
        // Replace file separators with dots.
        val dotted = noExt.replace('/', '.').replace('\\', '.')
        // Filter out anonymous class names (purely numeric simple name like Foo$1).
        // These have no stable FQN and cannot be looked up meaningfully.
        if dotted.isEmpty then return ""
        val simpleName = dotted.substring(dotted.lastIndexOf('.') + 1)
        // Anonymous inner classes: simple name is purely numeric after the last $.
        val dollarIdx = simpleName.lastIndexOf('$')
        if dollarIdx >= 0 then
            val afterDollar = simpleName.substring(dollarIdx + 1)
            if afterDollar.nonEmpty && afterDollar.forall(_.isDigit) then return ""
        dotted
    end classfilePathToFqn

    /** Compute the dotted FQN for `sym` by walking the ownerBySymbol chain.
      *
      * Internal FQN computation used during Pass C construction (before Classpath is fully assembled).
      */
    private def computeFqn(
        sym: LoadingSymbol.Materialising,
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising]
    ): String =
        import Tasty.Name.asString
        val parts                                   = new scala.collection.mutable.ArrayBuffer[String]()
        var cur: LoadingSymbol.Materialising | Null = sym
        // LongSet for cycle detection keyed on loading id.
        val visited = mutable.LongMap.empty[Unit]
        var done    = false
        while !done && (cur ne null) && !visited.contains(cur.nn.id.toLong) do
            val c = cur.nn
            visited(c.id.toLong) = ()
            val n = c.name.asString
            if n.nonEmpty then parts.prepend(n)
            // Package symbols store the full dotted package name (e.g. "scala.collection.immutable")
            // in a single Name field. Walking further up through package owners would re-prepend the
            // individual package segments that are already embedded in that flat name, doubling them.
            // Stop here: the flat name is the entire package prefix for this symbol.
            if c.kind == SymbolKind.Package then done = true
            else cur = ownerBySymbol.get(c.id.toLong).orNull
        end while
        parts.filter(_.nonEmpty).mkString(".")
    end computeFqn

    /** Convert a Name (opaque String alias) to a String. */
    private def nameToString(n: Tasty.Name): String =
        import Tasty.Name.asString
        n.asString
    end nameToString

    /** Test helper: trigger a `TastyError.ClasspathBuilding` abort via a synthetic degenerate MergeState.
      *
      * Constructs a `MergeState` where one entry in `fqnIndex` maps to a partial symbol that is NOT present in `allSyms`. As a result,
      * `finalizeMerge` cannot resolve the symbol via `symbolIdMap` (direct lookup returns -1) and the OQ-003 canonical-FQN fallback also
      * fails (no alias form exists). Under `ErrorMode.FailFast`, `finalizeMerge` raises `TastyError.ClasspathBuilding`.
      *
      * This method is `private[kyo]` so it is reachable from tests in `kyo.internal.*` test packages but invisible to API consumers.
      */
    private[kyo] def triggerClasspathBuildingForTest()(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        // Unsafe: synthesizes a degenerate MergeState in a test-only path; constructs Name values that are immediately fed into finalizeMerge, which itself runs in a Sync.Unsafe.defer (§839 case 3).
        import AllowUnsafe.embrace.danger
        val state = new MergeState()
        // Insert a partial symbol into fqnIndex but deliberately do NOT add it to allSyms.
        // This simulates the invariant-violation scenario: fqnIndex references a symbol object that
        // was never registered in the merge pipeline's allSyms accumulator.
        val ghost = kyo.internal.tasty.symbol.Symbol.makeSymbol(
            id = Int.MaxValue, // unique id not in allSyms
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("GhostClass")
        )
        state.fqnIndex("test.GhostClass") = ghost
        // allSyms intentionally left empty so symbolIdMap.getOrElse(ghost.id.toLong, -1) == -1.
        // canonicalSourceFqn("test.GhostClass") == "test.GhostClass" (no mangling), so OQ-003
        // fallback also returns -1. Both checks fail -> ClasspathBuilding fires under FailFast.
        val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
        finalizeMerge(state, platSrc, Tasty.ErrorMode.FailFast)
    end triggerClasspathBuildingForTest

    /** Test helper: produce a `Tasty.Classpath` with a `TastyError.UnresolvedReference` in `cp.errors` via SoftFail.
      *
      * Constructs a `MergeState` where one entry in `fqnIndex` maps to a partial symbol that is NOT present in `allSyms`. Under
      * `ErrorMode.SoftFail`, `finalizeMerge` accumulates `TastyError.UnresolvedReference("test.GhostClass", 0)` in the returned
      * `cp.errors`. This is the B-1/B-2/B-5 behavioral proof: real production code emits `UnresolvedReference`.
      *
      * This method is `private[kyo]` so it is reachable from tests in `kyo.*` test packages but invisible to API consumers.
      */
    private[kyo] def triggerUnresolvedReferenceForTest()(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        // Unsafe: synthesizes a degenerate MergeState in a test-only path (§839 case 3).
        import AllowUnsafe.embrace.danger
        val state = new MergeState()
        // Insert a partial symbol into fqnIndex but deliberately do NOT add it to allSyms.
        // symbolIdMap.getOrElse(ghost.id.toLong, -1) == -1 and canonical fallback also returns -1.
        // Under SoftFail: accumulates TastyError.UnresolvedReference("test.GhostFqn", 0) in cp.errors.
        val ghost = kyo.internal.tasty.symbol.Symbol.makeSymbol(
            id = Int.MaxValue,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("GhostFqn")
        )
        state.fqnIndex("test.GhostFqn") = ghost
        val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
        finalizeMerge(state, platSrc, Tasty.ErrorMode.SoftFail)
    end triggerUnresolvedReferenceForTest

    /** Load a Binding by cold-loading from roots with optional dev-cache support.
      *
      * When cacheDir is Absent, performs a full cold load via the existing ClasspathOrchestrator.init
      * pipeline and wraps the result in a Binding with a fresh DecodeContext.
      *
      * When cacheDir is Present(dir), attempts to read a snapshot from dir using the same
      * digest/lookup/write logic as Tasty.Classpath.initCachedImpl. On a cache hit the snapshot
      * classpath is returned wrapped in a Binding with a fresh DecodeContext. On a miss, cold-loads
      * then writes the snapshot before returning.
      *
      * The DecodeContext is always fresh per call; body memos are never shared across invocations.
      */
    /** Test-only overload: same as `coldLoadBinding(roots, mode, cacheDir)` but accepts an explicit FileSource.
      *
      * Allows tests to inject a custom FileSource (e.g. ZipMemoryFileSource) so the probe's openZip and
      * DigestComputer.digestForRoot paths can be controlled independently. Uses concurrency=1.
      */
    private[kyo] def coldLoadBinding(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        cacheDir: Maybe[String],
        source: FileSource,
        concurrency: Int = 1
    )(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        probeAndLoadBundled(roots, mode, source).flatMap: (bundledCp, coldRoots) =>
            val coldLoad: Binding < (Sync & Async & Scope & Abort[TastyError]) =
                cacheDir match
                    case Maybe.Absent =>
                        initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                            val merged = if bundledCp.symbols.isEmpty then coldCp
                            else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                            Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                    case Maybe.Present(dir) =>
                        import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
                        import kyo.internal.tasty.snapshot.SnapshotReader
                        import kyo.internal.tasty.snapshot.SnapshotWriter
                        Abort.run[TastyError](SnapshotDigest.compute(coldRoots, source)).flatMap:
                            case Result.Failure(_) | Result.Panic(_) =>
                                initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                                    val merged = if bundledCp.symbols.isEmpty then coldCp
                                    else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                    Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                            case Result.Success(digest) =>
                                val hexDigest    = SnapshotDigest.toHexString(digest)
                                val snapshotPath = s"$dir/$hexDigest.krfl"
                                source.exists(snapshotPath).flatMap: exists =>
                                    if exists then
                                        Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath, source)).flatMap:
                                            case Result.Success(coldCp) =>
                                                // Snapshot load: body store is empty; bodyTree returns Absent.
                                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                Binding(merged, Maybe.Present(DecodeContext.fresh()))
                                            case Result.Failure(_) | Result.Panic(_) =>
                                                initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                                                    val merged = if bundledCp.symbols.isEmpty then coldCp
                                                    else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                    Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                                    else
                                        initWithBodies(coldRoots, mode, source, concurrency).flatMap: (coldCp, bodyStore) =>
                                            Abort.run[TastyError](SnapshotWriter.write(coldCp, dir, digest, source)).andThen:
                                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
            if coldRoots.isEmpty then Binding(bundledCp, Maybe.Present(DecodeContext.fresh()))
            else coldLoad
    end coldLoadBinding

    private[kyo] def coldLoadBinding(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        cacheDir: Maybe[String]
    )(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        val source      = PlatformFileSource.get
        val concurrency = java.lang.Runtime.getRuntime.availableProcessors().max(1)
        // INV-004: ALWAYS probe each root for a bundled snapshot before cold-loading.
        // Partition roots into bundled (probe hit) and cold (probe miss or non-jar).
        // SoftFail: DigestMismatch from a stale snapshot is caught and the root is cold-loaded.
        // FailFast: DigestMismatch propagates as Abort[TastyError].
        probeAndLoadBundled(roots, mode, source).flatMap: (bundledCp, coldRoots) =>
            val coldLoad: Binding < (Sync & Async & Scope & Abort[TastyError]) =
                cacheDir match
                    case Maybe.Absent =>
                        initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                            val merged = if bundledCp.symbols.isEmpty then coldCp
                            else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                            Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                    case Maybe.Present(dir) =>
                        import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
                        import kyo.internal.tasty.snapshot.SnapshotReader
                        import kyo.internal.tasty.snapshot.SnapshotWriter
                        Abort.run[TastyError](SnapshotDigest.compute(coldRoots, source)).flatMap:
                            case Result.Failure(_) | Result.Panic(_) =>
                                // Digest failed (e.g. browser platform): fall through to cold load.
                                initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                                    val merged = if bundledCp.symbols.isEmpty then coldCp
                                    else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                    Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                            case Result.Success(digest) =>
                                val hexDigest    = SnapshotDigest.toHexString(digest)
                                val snapshotPath = s"$dir/$hexDigest.krfl"
                                source.exists(snapshotPath).flatMap: exists =>
                                    if exists then
                                        Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath, source)).flatMap:
                                            case Result.Success(coldCp) =>
                                                // Snapshot load: body store is empty; bodyTree returns Absent.
                                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                Binding(merged, Maybe.Present(DecodeContext.fresh()))
                                            case Result.Failure(_) | Result.Panic(_) =>
                                                // Snapshot unreadable; fall through to cold load.
                                                initWithBodies(coldRoots, mode, source, concurrency).map: (coldCp, bodyStore) =>
                                                    val merged = if bundledCp.symbols.isEmpty then coldCp
                                                    else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                    Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
                                    else
                                        initWithBodies(coldRoots, mode, source, concurrency).flatMap: (coldCp, bodyStore) =>
                                            Abort.run[TastyError](SnapshotWriter.write(coldCp, dir, digest, source)).andThen:
                                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                Binding(merged, Maybe.Present(DecodeContext.fresh(bodyStore)))
            // If no cold roots remain (all roots had bundled snapshots), return the merged bundled classpath directly.
            if coldRoots.isEmpty then Binding(bundledCp, Maybe.Present(DecodeContext.fresh()))
            else coldLoad
    end coldLoadBinding

    /** Probe each root for a bundled snapshot (INV-004).
      *
      * For each root: call BundledSnapshotProbe.probe. On a hit, decode the snapshot bytes into a partial Classpath and merge it into
      * the running accumulator (INV-005 remap-at-merge). On a miss, add the root to the coldRoots list. DigestMismatch handling follows
      * the error mode: SoftFail silently falls back to cold load; FailFast propagates the error.
      *
      * Returns a tuple of (mergedBundledClasspath, coldRoots). The caller cold-loads coldRoots and merges the result with
      * mergedBundledClasspath.
      */
    private def probeAndLoadBundled(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        source: FileSource
    )(using Frame): (Tasty.Classpath, Seq[String]) < (Sync & Scope & Abort[TastyError]) =
        import kyo.internal.tasty.snapshot.SnapshotReader
        var bundled: Tasty.Classpath = Tasty.Classpath.empty
        val coldRoots                = scala.collection.mutable.ArrayBuffer.empty[String]
        Kyo.foreach(Chunk.from(roots)): root =>
            val probeResult: Maybe[Array[Byte]] < (Sync & Scope & Abort[TastyError]) =
                if mode == Tasty.ErrorMode.SoftFail then
                    Abort.run[TastyError](BundledSnapshotProbe.probe(root, source)).map:
                        case Result.Success(r)                                => r
                        case Result.Failure(_: TastyError.DigestMismatch) | _ => Maybe.Absent
                else
                    BundledSnapshotProbe.probe(root, source)
            probeResult.map:
                case Maybe.Absent =>
                    Sync.defer(coldRoots += root)
                case Maybe.Present(snapshotBytes) =>
                    Abort.run[TastyError](SnapshotReader.readFromBytes(snapshotBytes, root)).map:
                        case Result.Success(partial) =>
                            Sync.defer:
                                bundled = BundledSnapshotProbe.mergePartialInto(bundled, partial)
                        case Result.Failure(_) | Result.Panic(_) =>
                            // Snapshot bytes unreadable; fall back to cold load for this root.
                            Sync.defer(coldRoots += root)
        .andThen(Sync.defer((bundled, coldRoots.toSeq)))
    end probeAndLoadBundled

    /** Load a Binding from in-memory pickles.
      *
      * Used by Tasty.withPickles; returns a Binding with a fresh DecodeContext.
      * Pickles are decoded sequentially using an in-memory FileSource backed by the pickle byte arrays.
      * withPickles uses this method; the resulting DecodeContext allows Tasty.bodyTree to decode
      * body bytes on demand when the pickle bytes are still in memory.
      */
    private[kyo] def loadPickles(
        pickles: Chunk[Tasty.Pickle]
    )(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        if pickles.isEmpty then
            Sync.defer(Binding(Tasty.Classpath.empty, Maybe.Present(DecodeContext.fresh())))
        else
            val indexed: Seq[(String, Array[Byte])] =
                pickles.toSeq.zipWithIndex.map: (p, i) =>
                    (s"pickle://${p.uuid.replace(':', '_')}/$i.tasty", p.bytes.toArray)
            val roots                              = indexed.map(_._1)
            val bytesMap: Map[String, Array[Byte]] = indexed.toMap
            val source = new FileSource:
                def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                    bytesMap.get(path) match
                        case Some(b) => Sync.defer(b)
                        case None    => Abort.fail(TastyError.FileNotFound(path))
                def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.SnapshotIoError("loadPickles source is read-only"))
                def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.SnapshotIoError("loadPickles source is read-only"))
                def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.SnapshotIoError("loadPickles source is read-only"))
                def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                    Sync.defer(Chunk.empty[String])
                def exists(path: String)(using Frame): Boolean < Sync =
                    Sync.defer(bytesMap.contains(path))
                def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
                    bytesMap.get(path) match
                        case Some(b) => Sync.defer(FileSource.FileStat(mtimeMs = 0L, size = b.length.toLong))
                        case None    => Abort.fail(TastyError.FileNotFound(path))
            initWithBodies(roots, Tasty.ErrorMode.SoftFail, source, concurrency = 1).map: (cp, bodyStore) =>
                Binding(cp, Maybe.Present(DecodeContext.fresh(bodyStore)))
    end loadPickles

end ClasspathOrchestrator
