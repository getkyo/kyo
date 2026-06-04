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
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolDescriptor
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
        /** All symbols from this file in TASTy parse order (deterministic depth-first traversal of the AST).
          *
          * Used in mergeOneInto to accumulate allSyms in a stable order across runs, ensuring that symbol IDs
          * are deterministic for byte-equal snapshot idempotency (F-A4-005). Previously allSyms was populated
          * from ownerBySymbol.keys which uses identity-hash order and is non-deterministic.
          */
        symbolsInOrder: Chunk[Tasty.Symbol],
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
        annotationsBySymbol: mutable.HashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]],
        /** F-G-002 fix: javaMetadata from the companion .class file, keyed by the partial (pre-finalize) TASTy symbol.
          *
          * Populated during readAndDecodeTastyFile when a same-named .class file exists alongside the .tasty file.
          * Consumed by finalizeMerge to write descs(idx).javaMetadata for TASTy-loaded class symbols.
          */
        companionJavaMeta: mutable.HashMap[Tasty.Symbol, Tasty.JavaMetadata]
    )

    /** Tagged union for results flowing through the result channel.
      *
      * `FileResultCase` carries a decoded TASTy file result. `ModuleInfoCase` carries a decoded module-info.class descriptor.
      * `JavaClassfileCase` carries a decoded standalone JVM .class file (F-A3-001..004 fix).
      */
    sealed private trait DecodeResult
    final private case class FileResultCase(fr: FileResult)                           extends DecodeResult
    final private case class ModuleInfoCase(name: String, md: Tasty.ModuleDescriptor) extends DecodeResult

    /** Carries a decoded standalone .class file result and its computed binary FQN.
      *
      * F-A3-001..004 fix: classfiles passed directly as roots (e.g., via `jrt:/` paths) decode here and inject class symbols into the
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
        val fqnIndex: mutable.HashMap[String, Tasty.Symbol]              = mutable.HashMap.empty
        val packageIndex: mutable.HashMap[String, Tasty.Symbol]          = mutable.HashMap.empty
        val allSyms: mutable.ArrayBuffer[Tasty.Symbol]                   = mutable.ArrayBuffer.empty
        val allSymsSet: mutable.HashSet[Tasty.Symbol]                    = mutable.HashSet.empty
        val topLevelCls: mutable.ArrayBuffer[Tasty.Symbol]               = mutable.ArrayBuffer.empty
        val packages: mutable.ArrayBuffer[Tasty.Symbol]                  = mutable.ArrayBuffer.empty
        val accErrors: mutable.ArrayBuffer[TastyError]                   = mutable.ArrayBuffer.empty
        val fileResults: mutable.ArrayBuffer[FileResult]                 = mutable.ArrayBuffer.empty
        val moduleIndex: mutable.HashMap[String, Tasty.ModuleDescriptor] = mutable.HashMap.empty

        /** F-A3-001..004 fix: decoded standalone .class files accumulated for finalizeMerge parent-type wiring. */
        val javaClassfileResults: mutable.ArrayBuffer[(String, kyo.internal.tasty.classfile.ClassfileResult)] =
            mutable.ArrayBuffer.empty

        /** F-A1-008: same-FQN collision tracking.
          *
          * Keyed by the indexKey (primary binary FQN) of the collision. Value is an ordered list of ALL symbols that were ever stored under
          * that FQN; the last entry is the current winner (last-write-wins). Populated by `mergeOneInto` when it encounters a new structural
          * symbol for an FQN that already has a different structural symbol.
          */
        val collisions: mutable.HashMap[String, mutable.ArrayBuffer[Tasty.Symbol]] = mutable.HashMap.empty
    end MergeState

    /** Init a new classpath from a set of root paths.
      *
      * Roots may be directories containing `.tasty` files or individual `.tasty` files.
      *
      * Missing-root contract per `Tasty.Classpath.init` scaladoc:
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
                                            decodeOneEntry(entryPath, kind, source, mode).flatMap: result =>
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
            val unsorted: Chunk[String] =
                if listed.isEmpty then
                    if root.endsWith(".tasty") || root.endsWith("module-info.class") then Chunk(root)
                    // F-A3-001..004 fix: a root that IS a standalone .class file (e.g. a jrt:/ path like
                    // `jrt:///modules/java.base/java/lang/String.class`) is passed directly from
                    // PlatformModuleOps.listJdkClassFiles. list() returns empty because it is a file, not
                    // a directory. Emit it with kind ".class" so decodeOneEntry routes to ClassfileUnpickler.
                    else if root.endsWith(".class") && !root.endsWith("module-info.class") then Chunk(root)
                    else Chunk.empty
                else listed
            // F-A4-005 determinism: sort entries so file processing order is stable across filesystem
            // enumeration orders. Different platforms and JAR implementations enumerate entries in
            // varying orders; a lexicographic sort gives deterministic symbol IDs, enabling byte-equal
            // snapshot idempotency when concurrency == 1.
            val entries = Chunk.from(unsorted.iterator.toSeq.sorted)
            Kyo.foreach(entries): entry =>
                val kind =
                    if entry.endsWith("module-info.class") then "module-info.class"
                    // F-A3-001..004 fix: individual .class entries from direct paths (jrt:/) get kind ".class".
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
        else if kind == ".class" then
            // F-A3-001..004 fix: decode a standalone JVM .class file via ClassfileUnpickler.
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
                        ClassfileUnpickler.read(bytes, arena)
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
                readAndDecodeTastyFile(entryPath, source, mode).map(FileResultCase.apply)

    /** Merge one DecodeResult into the MergeState. Single-threaded (only the merger fiber calls it). */
    private def mergeOneInto(state: MergeState, result: DecodeResult): Unit =
        result match
            case FileResultCase(fr) =>
                // Add ALL symbols (including members) to allSyms so that finalizeMerge can look them
                // up by index when building typeParamIds/declarationIds.
                // F-A4-005 determinism fix: use symbolsInOrder (TASTy parse order, stable DFS) rather
                // than ownerBySymbol.keys/values which uses identity-hash iteration order. Identity
                // hashCodes vary across JVM runs, making symbol IDs non-deterministic and breaking
                // byte-equal snapshot idempotency. symbolsInOrder preserves the AstUnpickler traversal
                // order, which is deterministic given the same input bytes.
                val seenSyms = new java.util.HashSet[Tasty.Symbol]()
                for sym <- fr.symbolsInOrder do
                    if seenSyms.add(sym) then
                        state.allSyms += sym
                        state.allSymsSet += sym
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
                    // F-A1-008: record a collision when a new structural symbol of the SAME KIND overwrites a
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
                            val prevIsStructural = prev.kind == Tasty.SymbolKind.Class ||
                                prev.kind == Tasty.SymbolKind.Trait || prev.kind == Tasty.SymbolKind.Object ||
                                prev.kind == Tasty.SymbolKind.EnumCase
                            val newIsStructural = sym.kind == Tasty.SymbolKind.Class ||
                                sym.kind == Tasty.SymbolKind.Trait || sym.kind == Tasty.SymbolKind.Object ||
                                sym.kind == Tasty.SymbolKind.EnumCase
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
                                    val prevIsStructural = prev.kind == Tasty.SymbolKind.Class ||
                                        prev.kind == Tasty.SymbolKind.Trait ||
                                        prev.kind == Tasty.SymbolKind.Object ||
                                        prev.kind == Tasty.SymbolKind.EnumCase ||
                                        prev.kind == Tasty.SymbolKind.OpaqueType
                                    val newIsStructural = sym.kind == Tasty.SymbolKind.Class ||
                                        sym.kind == Tasty.SymbolKind.Trait ||
                                        sym.kind == Tasty.SymbolKind.Object ||
                                        sym.kind == Tasty.SymbolKind.EnumCase ||
                                        sym.kind == Tasty.SymbolKind.OpaqueType
                                    newIsStructural && !prevIsStructural
                            if storeSource then state.fqnIndex(sourceFqn) = sym
                        end if
                    end if
                    if seenSyms.add(sym) then
                        state.allSyms += sym
                        state.allSymsSet += sym
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
            case JavaClassfileCase(fqn, cfResult) =>
                // F-A3-001..004 fix: register the classfile's primary symbol in the FQN index.
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
                // allSymsSet provides O(1) membership checks to avoid the O(N^2) indexOf scan
                // over the growing global list (critical for JPMS paths with 27k+ classes).
                if !state.allSymsSet.contains(classSym) then
                    state.allSyms += classSym
                    state.allSymsSet += classSym
                    state.topLevelCls += classSym
                end if
                for memberSym <- cfResult.symbols do
                    if !state.allSymsSet.contains(memberSym) then
                        state.allSyms += memberSym
                        state.allSymsSet += memberSym
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
        mode: Tasty.ErrorMode
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
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
                                (partialSym.kind == Tasty.SymbolKind.Class
                                    && !partialSym.flags.contains(Tasty.Flag.Module)
                                    || partialSym.kind == Tasty.SymbolKind.Val)
                            then Tasty.SymbolKind.EnumCase
                            else if partialSym.kind == Tasty.SymbolKind.Field &&
                                partialSym.flags.contains(Tasty.Flag.Enum) &&
                                partialSym.flags.contains(Tasty.Flag.JavaDefined) &&
                                partialSym.flags.contains(Tasty.Flag.Static)
                            then Tasty.SymbolKind.EnumCase
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

                    // Accumulate negId -> FQN for annotation types that reference external symbols not in the
                    // classpath (e.g. scala.deprecated when scala-library is absent). Stored in the Classpath
                    // so that typeFqnString can fall back to the FQN string for symbolsAnnotatedWith matching.
                    val unresolvedFqnByNegId = mutable.HashMap.empty[Int, String]

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
                                    // FQN not found: the defining library is absent from the classpath.
                                    // Record the negId -> FQN mapping so typeFqnString can match by FQN string
                                    // even without a resolved symbol (e.g. scala.deprecated on JS/Native).
                                    unresolvedFqnByNegId(negId) = fqn
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
                            // F-A2-008 fix: also remap paramIds: each SymbolId in paramIds may be a
                            // Phase-B temporary id if the TypeParam is declared in a different file.
                            // OpaqueType underlying types use TypeLambda with cross-file TypeParam refs.
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
                            case Tasty.Type.Function(params, result, isCtx) =>
                                Tasty.Type.Function(params.map(remapType(_, fr)), remapType(result, fr), isCtx)
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
                            // F-A-005 fix: remap ThisType using the same PHASE_B_ADDR_OFFSET scheme as Named.
                            case Tasty.Type.ThisType(clsId) =>
                                val v = clsId.value
                                if v >= phaseBOffset then
                                    val addr     = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(addr, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else if v < -1 then
                                    // F-A-005 cross-file: FQN-tracked nested-class ThisType.
                                    val finalIdx = fr.negIdToFinal.getOrDefault(v, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else t
                                end if
                            // F-A-009: TypeRef must recurse like TermRef.
                            case Tasty.Type.TypeRef(qual, name) =>
                                Tasty.Type.TypeRef(remapType(qual, fr), name)
                            // F-A-010: Bounds must recurse into lo and hi.
                            case Tasty.Type.Bounds(lo, hi) =>
                                Tasty.Type.Bounds(remapType(lo, fr), remapType(hi, fr))
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

                    // F-A2-008 fix: patch OpaqueType TypeLambda.paramIds that are still SymbolId(-1).
                    //
                    // TypeUnpickler.readTypeLambdaParams creates placeholder symbols (SymbolId=-1) when
                    // the addrMap lookup for a TypeLambda parameter fails. This happens for OpaqueType
                    // type parameters that were decoded before their owner OpaqueType was registered.
                    // The remapType pass handles Phase-B temporaries (>= phaseBOffset) and negIds (< -1)
                    // but not the -1 placeholder. Fix: after typeParamIds are set, replace each
                    // TypeLambda.paramId=-1 with the corresponding positional entry from typeParamIds.
                    i = 0
                    for sym <- allPartial do
                        val d = descs(i)
                        if sym.kind == Tasty.SymbolKind.OpaqueType && d.typeParamIds.nonEmpty then
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
                                    Tasty.Annotation(remapType(ann.annotationType, frRemapAnn), ann.arguments)
                                descs(idx).annotations = Chunk.from(remapped.toSeq)
                            end if
                        end for
                        frIdxAnn += 1
                    end for

                    // F-G-002 fix: populate javaMetadata from companion .class files decoded during readAndDecodeTastyFile.
                    // The companion decode runs before finalizeMerge and stores JavaMetadata keyed by partial (Phase B) symbols.
                    // Here we look up each partial symbol in symbolIdMap and write into descs(idx).javaMetadata.
                    // HARD RULE 7: this write happens before materializeSymbols converts descriptors to immutable Symbols.
                    for fr <- fileResults do
                        for (sym, meta) <- fr.companionJavaMeta do
                            val idx = symbolIdMap.getOrDefault(sym, -1)
                            if idx >= 0 && idx < count && descs(idx).javaMetadata.isEmpty then
                                descs(idx).javaMetadata = Maybe(meta)
                        end for
                    end for

                    // F-A3-001..004 fix: wire parent types and javaMetadata for standalone classfile symbols.
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
                        val classIdx = symbolIdMap.getOrDefault(classSym, -1)
                        if classIdx >= 0 && classIdx < count then
                            // Resolve parent types using cfResult.parentBinaryNames.
                            // Binary names use '/' as separator (e.g. "java/lang/Object"); convert to dotted.
                            val sentinelId = -1
                            val wirableParents = cfResult.parentBinaryNames.map: bn =>
                                val dottedFqn = bn.replace('/', '.')
                                state.fqnIndex.get(dottedFqn) match
                                    case Some(parentSym) =>
                                        val parentIdx = symbolIdMap.getOrDefault(parentSym, sentinelId)
                                        if parentIdx >= 0 then Tasty.Type.Named(SymbolId(parentIdx))
                                        else Tasty.Type.Named(SymbolId(sentinelId))
                                    case None =>
                                        // Try canonical source FQN (handles "$" inner class separators).
                                        val srcFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(dottedFqn)
                                        state.fqnIndex.get(srcFqn) match
                                            case Some(parentSym) =>
                                                val parentIdx = symbolIdMap.getOrDefault(parentSym, sentinelId)
                                                if parentIdx >= 0 then Tasty.Type.Named(SymbolId(parentIdx))
                                                else Tasty.Type.Named(SymbolId(sentinelId))
                                            case None => Tasty.Type.Named(SymbolId(sentinelId))
                                        end match
                                end match
                            if descs(classIdx).parentTypes.isEmpty then
                                descs(classIdx).parentTypes = wirableParents
                            // Populate javaMetadata from the classSymbol so flags survive finalizeMerge.
                            classSym match
                                case c: Tasty.Symbol.ClassLike =>
                                    c.javaMetadata match
                                        case Maybe.Present(meta) =>
                                            if descs(classIdx).javaMetadata.isEmpty then
                                                descs(classIdx).javaMetadata = Maybe(meta)
                                        case Maybe.Absent => ()
                                case _ => ()
                            end match
                            // Wire member symbols' ownerId to classIdx and populate declarationIds.
                            // declarationIds is required by the declarations(using cp) API so that
                            // sym.declarations / sym.methods / sym.fields return the correct members.
                            val memberIdxBuf = new scala.collection.mutable.ArrayBuffer[Int]()
                            for memberSym <- cfResult.symbols do
                                val memberIdx = symbolIdMap.getOrDefault(memberSym, -1)
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
                                            val permIdx = symbolIdMap.getOrDefault(permSym, -1)
                                            if permIdx >= 0 then permBuf += permIdx
                                        case None =>
                                            val srcFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                                            state.fqnIndex.get(srcFqn) match
                                                case Some(permSym) =>
                                                    val permIdx = symbolIdMap.getOrDefault(permSym, -1)
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
                            case Tasty.Type.TypeRef(Tasty.Type.Named(qualSid), memberName)
                                if qualSid.value >= 0 =>
                                // F-A-009: TypeRef may also carry @Child type args after this phase.
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
                                // F-A-009 parallel: TypeRef(ThisType(...), name) for enum cases.
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
                                            // @Child[T] enriched tycon (TermRef form, pre-Phase-13).
                                            val subId = resolveChildRef(args(0))
                                            if subId >= 0 then buf += subId
                                        case Tasty.Type.Applied(Tasty.Type.TypeRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // F-A-009: @Child[T] enriched tycon now arrives as TypeRef after TYPEREF fix.
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
                            // F-A2-008 fix: remap TypeLambda.paramIds for cross-file TypeParam refs.
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
                            case Tasty.Type.Function(params, result, isCtx) =>
                                Tasty.Type.Function(params.map(rewriteCrossFile), rewriteCrossFile(result), isCtx)
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
                            case Some(p) => symbolIdMap.getOrDefault(p, -1)
                            case None    => -1
                    end lookupFqnFinalId

                    var qIdx = 0
                    while qIdx < count do
                        val desc = descs(qIdx)
                        val k    = desc.kind
                        if (k == Tasty.SymbolKind.Class || k == Tasty.SymbolKind.Trait || k == Tasty.SymbolKind.EnumCase) &&
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
                        for (sym, (bodyStart, bodyEnd)) <- fr.bodyDataByAddr do
                            val idx = symbolIdMap.get(sym)
                            if idx >= 0 && idx < count && bodyStart > 0 && bodyEnd > bodyStart then
                                descs(idx).body = Maybe(Tasty.SymbolBody(
                                    bodyStart = bodyStart,
                                    bodyEnd = bodyEnd,
                                    sectionBytes = Span.fromUnsafe(fr.sectionBytes),
                                    names = Span.fromUnsafe(fr.fileNames),
                                    sectionOffset = fr.sectionOffset,
                                    addrMap = scala.collection.immutable.IntMap.empty
                                ))
                            end if
                        end for
                    end for

                    val finalSymbols = materializeSymbols(descs, count)

                    val newFqnIndex = state.fqnIndex.map { case (fqn, partial) =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then (fqn, finalSymbols(idx))
                        else
                            // F-A4-001 fix: binary-alias FQN keys (e.g. "scala.Predef$") store a partial
                            // symbol whose identity does not appear in symbolIdMap because the same logical
                            // symbol was stored under the canonical source form ("scala.Predef"). Fall back
                            // to a FQN-string lookup: canonicalize this fqn, look up the canonical partial
                            // in state.fqnIndex, then resolve that partial via symbolIdMap.
                            val canonFqn = kyo.internal.tasty.symbol.FqnNormalizer.canonicalSourceFqn(fqn)
                            val fallbackIdx =
                                if canonFqn != fqn then
                                    state.fqnIndex.get(canonFqn) match
                                        case Some(canonPartial) => symbolIdMap.getOrDefault(canonPartial, -1)
                                        case None               => -1
                                else -1
                            if fallbackIdx >= 0 then (fqn, finalSymbols(fallbackIdx))
                            else (fqn, partial)
                        end if
                    }
                    val newPackageIndex = state.packageIndex.map { case (pkg, partial) =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then (pkg, finalSymbols(idx)) else (pkg, partial)
                    }
                    val newPackages = packages.map { partial =>
                        val idx = symbolIdMap.getOrDefault(partial, -1)
                        if idx >= 0 then finalSymbols(idx) else partial
                    }

                    val finalErrors = Chunk.from(accErrors)
                    val symsChunk   = Chunk.from(finalSymbols)
                    val fqnIdIdx    = newFqnIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
                    val pkgIdIdx    = newPackageIndex.map { case (fqn, sym) => fqn -> sym.id }.toMap
                    // F-G-006 fix: filter topLevelClassIds to only ClassLike symbols whose owner is a Package.
                    // The prior approach appended ALL ClassLike symbols to topLevelCls regardless of nesting
                    // depth, producing a count (3,514) larger than allClassLike (1,508). The correct invariant
                    // is topLevelClasses.size <= allClassLike.size. We filter finalSymbols directly (post-
                    // materialization) so the Package kind check uses the final symbol's kind, not a partial
                    // symbol's kind.
                    val topIds: Chunk[SymbolId] = symsChunk.flatMap:
                        case c: Tasty.Symbol.ClassLike =>
                            val ownerIdx = c.ownerId.value
                            if ownerIdx >= 0 && ownerIdx < finalSymbols.length &&
                                finalSymbols(ownerIdx).isInstanceOf[Tasty.Symbol.Package]
                            then Chunk(c.id)
                            else Chunk.empty
                            end if
                        case _ => Chunk.empty
                    val pkgIds = Chunk.from(newPackages.map(_.id))
                    val rootId = if finalSymbols.nonEmpty then SymbolId(0) else SymbolId(-1)

                    val subclassIdx  = buildSubclassIndex(symsChunk)
                    val companionIdx = buildCompanionIndex(symsChunk, fqnIdIdx)

                    // F-A1-008: convert per-FQN collision buckets to FqnCollision diagnostics.
                    // Each bucket holds all symbols seen for that FQN (including winner); ids are resolved
                    // via symbolIdMap so they reflect final SymbolIds.
                    val collisionDiagnostics: Chunk[Tasty.Classpath.Diagnostic] =
                        Chunk.from(
                            state.collisions.flatMap { case (colFqn, syms) =>
                                val ids = Chunk.from(syms.toSeq.map(s => SymbolId(symbolIdMap.getOrDefault(s, -1))))
                                Seq(Tasty.Classpath.FqnCollision(colFqn, ids))
                            }
                        )

                    // F-A5-001 (ClasspathBuilding): check for partial symbols that escaped both symbolIdMap
                    // and the OQ-003 canonFqn fallback. Such symbols retain id.value < 0 in newFqnIndex.
                    // Under FailFast, fire ClasspathBuilding. Under SoftFail, accumulate a diagnostic note
                    // but continue (the classpath is still usable for all unaffected FQNs).
                    val brokenFqnCount = fqnIdIdx.count { case (_, sid) => sid.value < 0 }

                    // OQ-001 FailFast wiring:
                    //   - If collisions exist under FailFast -> FqnCollisionError (first colliding FQN).
                    //   - If broken fqnIndex entries exist under FailFast -> ClasspathBuilding.
                    // Both checks produce Left(error) returned as a tuple alongside the classpath so the
                    // Sync.Unsafe.defer block can carry the error out without mixing Abort effects inside it.
                    val failFastError: Maybe[TastyError] =
                        if mode == Tasty.ErrorMode.FailFast then
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
                        canonical = canonical,
                        diagnostics = collisionDiagnostics,
                        unresolvedFqnByNegId = unresolvedFqnByNegId.toMap
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

    /** Read bytes and decode a single TASTy file. Returns FileResult.
      *
      * F-G-002 fix: after decoding the .tasty file, speculatively decode the companion .class (same base name, .class extension). If the
      * .class exists and decodes cleanly, extract javaMetadata from the classSymbol and populate fr.companionJavaMeta for every top-level
      * FQN in the file. The companion decode is always soft-fail; a missing or malformed .class never fails the TASTy decode.
      */
    private def readAndDecodeTastyFile(
        file: String,
        source: FileSource,
        mode: Tasty.ErrorMode
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        Abort.run[TastyError](
            source.read(file).flatMap: bytes =>
                Sync.Unsafe.defer {
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(bytes.length.toLong)
                    decodeTastyBytes(file, bytes)
                }
        ).flatMap:
            case Result.Success(fr) =>
                // F-G-002: attempt companion .class decode in soft-fail mode
                val companionPath = file.stripSuffix(".tasty") + ".class"
                source.exists(companionPath).flatMap: exists =>
                    if !exists then fr
                    else
                        Abort.run[TastyError](
                            source.read(companionPath).flatMap: classBytes =>
                                Sync.Unsafe.defer:
                                    // Unsafe: ClassfileUnpickler reads an immutable byte array inside a Sync.Unsafe.defer block; no suspension required (§839 case 3).
                                    given AllowUnsafe = AllowUnsafe.embrace.danger
                                    ClassfileUnpickler.read(classBytes, fr.arena)
                        ).map:
                            case Result.Success(cfResult) =>
                                cfResult.classSymbol match
                                    case c: Tasty.Symbol.ClassLike =>
                                        c.javaMetadata match
                                            case Maybe.Present(meta) =>
                                                // Populate companionJavaMeta for all top-level symbols in this file
                                                // that match the classfile's symbol name. The fqns list maps each
                                                // TASTy partial symbol to its FQN; we populate all of them since
                                                // a single .tasty file may declare exactly one top-level class.
                                                for (_, sym) <- fr.fqns do
                                                    fr.companionJavaMeta(sym) = meta
                                                end for
                                            case Maybe.Absent => ()
                                    case _ => ()
                                end match
                                fr
                            case _ => fr
            case Result.Failure(err: TastyError) =>
                // F-A5-006 fix: replace the placeholder path "<byte view>" produced by TastyHeader.read
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
            mutable.HashMap.empty[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]],
            mutable.HashMap.empty[Tasty.Symbol, Tasty.JavaMetadata]
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
        bytes: Array[Byte]
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
                mutable.HashMap.empty[Tasty.Symbol, Tasty.JavaMetadata]
            )
        end for
    end decodeTastyBytes

    /** Compute a dotted binary FQN from a `.class` file path.
      *
      * F-A3-001..004 fix: handles `jrt:/` paths produced by `PlatformModuleOps.listJdkClassFiles`. Examples:
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

    /** Convert a Name (opaque String alias) to a String. */
    private def nameToString(n: Tasty.Name): String =
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
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("GhostClass")
        )
        state.fqnIndex("test.GhostClass") = ghost
        // allSyms intentionally left empty so symbolIdMap.getOrDefault(ghost, -1) == -1.
        // canonicalSourceFqn("test.GhostClass") == "test.GhostClass" (no mangling), so OQ-003
        // fallback also returns -1. Both checks fail -> ClasspathBuilding fires under FailFast.
        val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
        finalizeMerge(state, platSrc, Tasty.ErrorMode.FailFast)
    end triggerClasspathBuildingForTest

end ClasspathOrchestrator
