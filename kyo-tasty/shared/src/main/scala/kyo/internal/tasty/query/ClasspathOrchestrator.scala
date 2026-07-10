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
  * Every `Scope.acquireRelease` inside an `Async.foreach` worker MUST be wrapped in an inner
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
      * On success: `Present(pairs)` where pairs are (fullName, symbol). On decode error in soft-fail mode: `Absent` (error already accumulated
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
        // Two aligned spans (no per-pair Tuple2): fullNameKeys(i) is the fully-qualified name of fullNameSymbols(i).
        fullNameKeys: Span[String],
        fullNameSymbols: Span[LoadingSymbol.Materialising],
        /** All symbols from this file in TASTy parse order (deterministic depth-first traversal of the AST).
          *
          * Used in mergeOneInto to accumulate allSyms in a stable order across runs, ensuring that symbol IDs
          * are deterministic for byte-equal snapshot idempotency. Identity-hash iteration order from map keys
          * is non-deterministic and must not be used here.
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
        /** Per-symbol declaration full-extent range, keyed by loading-symbol id in lock-step with
          * `positionsBySymbol`. Produced by `PositionsUnpickler.read` alongside the definition points;
          * remapped to final SymbolIds and aggregated into `DecodeContext.declarationRangeStore` in finalizeMerge.
          */
        declarationRangesBySymbol: mutable.LongMap[Tasty.SourceRange],
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising],
        bodyDataByAddr: mutable.LongMap[(Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        fileNames: Array[Tasty.Name],
        /** Per-file TASTy address -> partial symbol map, used in finalizeMerge to remap temporary SymbolIds (PHASE_B_ADDR_OFFSET + address)
          * to final SymbolIds.
          */
        addrMap: scala.collection.immutable.IntMap[LoadingSymbol.Materialising],
        /** Cross-file unresolved fully-qualified name tracking: maps unique negative SymbolIds to fully-qualified names for finalizeMerge parent type resolution. */
        unresolvedIdToFullName: mutable.HashMap[Int, String],
        /** Per-symbol annotation lists decoded from ANNOTATION modifier blocks. Populated by AstUnpickler;
          * consumed by finalizeMerge to write descs(idx).annotations.
          */
        annotationsBySymbol: mutable.LongMap[mutable.ArrayBuffer[Tasty.Annotation]],
        /** javaMetadata from the companion .class file, keyed by the loading-symbol id of the partial (pre-finalize) TASTy symbol.
          *
          * Populated during readAndDecodeTastyFile when a same-named .class file exists alongside the .tasty file.
          * Consumed by finalizeMerge to write descs(idx).javaMetadata for TASTy-loaded class symbols.
          */
        companionJavaMeta: mutable.LongMap[Tasty.Java.Metadata],
        /** Raw Positions-section bytes for this pickle, retained so `PositionsUnpickler.readSpans` can run
          * lazily at first occurrence-index query instead of at load. Empty when the file carries no
          * Positions section. Consumed by finalizeMerge to populate `positionsStore`, keyed by pickle index.
          */
        positionsSectionBytes: Array[Byte],
        /** Per-class-like-symbol extends/with parent type-ref (absolute-address, decoded Type) capture, keyed by
          * loading-symbol id, parallel to `parentsBySymbol`. Produced by `AstUnpickler.decodeTemplateParents`;
          * finalizeMerge resolves each Type to a final parent SymbolId and records the address -> SymbolId
          * use site into `parentOccurrenceStore`, keyed by this file's pickle index.
          */
        parentRefAddrsBySymbol: mutable.LongMap[Chunk[(Int, Tasty.Type)]]
    )

    /** Tagged union for results flowing through the result channel.
      *
      * `FileResultCase` carries a decoded TASTy file result. `ModuleInfoCase` carries a decoded module-info.class descriptor.
      * `JavaClassfileCase` carries a decoded standalone JVM .class file.
      */
    sealed private trait DecodeResult
    final private case class FileResultCase(fr: FileResult)                                 extends DecodeResult
    final private case class ModuleInfoCase(name: String, md: Tasty.Java.Module.Descriptor) extends DecodeResult

    /** Carries a decoded standalone .class file result and its computed binary fully-qualified name.
      *
      * Classfiles passed directly as roots (e.g., via `jrt:/` paths) decode here and inject class symbols into the
      * global fully-qualified name index so `findClass("java.lang.String")` resolves.
      */
    final private case class JavaClassfileCase(
        fullName: String,
        cfResult: kyo.internal.tasty.classfile.ClassfileResult
    ) extends DecodeResult

    /** Mutable accumulator for the single-threaded merger stage (Phase C).
      *
      * Collects decoded file results and module descriptors as they arrive from the result channel. `finalizeMerge` reads from this state
      * once the merger has drained the result channel.
      */
    final private class MergeState:
        val fullNameIndex: mutable.HashMap[String, LoadingSymbol.Materialising] = mutable.HashMap.empty
        val packageIndex: mutable.HashMap[String, LoadingSymbol.Materialising]  = mutable.HashMap.empty
        val allSyms: mutable.ArrayBuffer[LoadingSymbol.Materialising]           = mutable.ArrayBuffer.empty
        // LongSet (LongMap[Unit]) keyed on LoadingSymbol.Materialising.id: unique per-instance, cross-platform.
        val allSymsSet: mutable.LongMap[Unit]                             = mutable.LongMap.empty
        val topLevelCls: mutable.ArrayBuffer[LoadingSymbol.Materialising] = mutable.ArrayBuffer.empty
        val packages: mutable.ArrayBuffer[LoadingSymbol.Materialising]    = mutable.ArrayBuffer.empty

        /** Every Package partial seen for a given fully-qualified name, across every file. A package
          * is legitimately split across files (each file that opens it registers its OWN partial
          * during its own per-file Pass 1), so a single fully-qualified name can carry more than one
          * entry here; `finalizeMerge` collapses each group to one canonical final SymbolId, see
          * `packagesByFullName`'s use there.
          */
        val packagesByFullName: mutable.HashMap[String, mutable.ArrayBuffer[LoadingSymbol.Materialising]] =
            mutable.HashMap.empty
        val accErrors: mutable.ArrayBuffer[TastyError]                         = mutable.ArrayBuffer.empty
        val fileResults: mutable.ArrayBuffer[FileResult]                       = mutable.ArrayBuffer.empty
        val moduleIndex: mutable.HashMap[String, Tasty.Java.Module.Descriptor] = mutable.HashMap.empty

        /** Decoded standalone .class files accumulated for finalizeMerge parent-type wiring. */
        val javaClassfileResults: mutable.ArrayBuffer[(String, kyo.internal.tasty.classfile.ClassfileResult)] =
            mutable.ArrayBuffer.empty

        /** Same-fully-qualified-name collision tracking.
          *
          * Keyed by the indexKey (primary binary fully-qualified name) of the collision. Value is an ordered list of ALL symbols that were
          * ever stored under that fully-qualified name; the last entry is the current winner (last-write-wins). Populated by `mergeOneInto`
          * when it encounters a new structural symbol for a fully-qualified name that already has a different structural symbol.
          */
        val collisions: mutable.HashMap[String, mutable.ArrayBuffer[LoadingSymbol.Materialising]] = mutable.HashMap.empty
    end MergeState

    /** Init a new classpath from a set of root paths.
      *
      * Roots may be directories containing `.tasty` files or individual `.tasty` files.
      *
      * Missing-root contract per `Tasty.ErrorMode` scaladoc:
      *   - `ErrorMode.FailFast`: a missing root immediately raises `Abort[TastyError.FileNotFound]`.
      *   - `ErrorMode.SoftFail`: a missing root accumulates `TastyError.FileNotFound` in `classpath.errors` and initialization continues with the
      *     remaining valid roots. An all-missing classpath returns an empty `Classpath` with one error per missing root.
      */
    def init(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        concurrency: Int
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        // Partition roots into missing vs. valid.
        // FailFast: abort on first missing root (preserves original behavior).
        // SoftFail: accumulate FileNotFound errors; run the pipeline with only the valid roots.
        // jrt:/ roots are always considered present: they reference JRT filesystem paths that
        // cannot be checked via kyo.Path (which uses the default filesystem). They originate
        // from PlatformModuleOps.listJdkClassFiles and are valid when the JRT filesystem is available.
        Kyo.foreach(roots) { root =>
            if root.startsWith("jrt:/") then Sync.defer(true)
            else
                Abort.recover[FileException](_ => false)(Path.runReadOnly(Path(root).exists)).map { ex =>
                    if !ex && mode == Tasty.ErrorMode.FailFast then Abort.fail(TastyError.FileNotFound(root))
                    else Sync.defer(ex) // true = root exists; false = missing (only in SoftFail)
                }
        }.map { existFlags =>
            val validRoots: Seq[String] =
                roots.zip(existFlags).collect {
                    case (root, true) => root
                }
            val preErrors: Chunk[TastyError] =
                Chunk.from(roots.zip(existFlags).collect {
                    case (root, false) => TastyError.FileNotFound(root): TastyError
                })
            // Install a read-batch context: on JVM, installs JarMappedReaderPool so jar reads share mmap buffers.
            // On JS/Native, ZipHandle.withPool is a no-op.
            ZipHandle.withPool {
                runPhaseAB(validRoots, mode, concurrency)
            }.map { classpath =>
                // Prepend pre-errors (missing roots) to classpath.errors so callers see them first.
                if preErrors.nonEmpty then Tasty.Classpath.copyWithPreErrors(classpath, preErrors)
                else classpath
            }
        }

    /** Like init() but also returns a body store populated with SymbolBody blobs.
      *
      * Used by coldLoadBinding and loadPickles to populate DecodeContext.bodyStore so that
      * Tasty.bodyTree can decode body bytes on demand. The public init() signature is unchanged.
      */
    private def initWithBodies(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        concurrency: Int
    )(using
        Frame
    ): (
        Tasty.Classpath,
        java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody],
        java.util.concurrent.ConcurrentHashMap[
            Int,
            Span[Byte]
        ],
        java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange],
        java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]
    ) < (Sync & Async & Scope & Abort[TastyError]) =
        // Unsafe: ConcurrentHashMap allocation for body store; same pattern as DecodeContext.fresh().
        // Sync.Unsafe.defer supplies AllowUnsafe for the bare-Java mutable allocation; the resulting
        // ConcurrentHashMap is shared across decode fibers via runPhaseAB's MultiProducerMultiConsumer
        // channels, so the allocation crosses fiber boundaries cleanly.
        Sync.Unsafe.defer((
            new java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody](),
            new java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]](),
            new java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange](),
            new java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]()
        )).map { (bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
            Kyo.foreach(roots) { root =>
                if root.startsWith("jrt:/") then Sync.defer(true)
                else
                    Abort.recover[FileException](_ => false)(Path.runReadOnly(Path(root).exists)).map { ex =>
                        if !ex && mode == Tasty.ErrorMode.FailFast then Abort.fail(TastyError.FileNotFound(root))
                        else Sync.defer(ex)
                    }
            }.map { existFlags =>
                val validRoots: Seq[String] =
                    roots.zip(existFlags).collect {
                        case (root, true) => root
                    }
                val preErrors: Chunk[TastyError] =
                    Chunk.from(roots.zip(existFlags).collect {
                        case (root, false) => TastyError.FileNotFound(root): TastyError
                    })
                ZipHandle.withPool {
                    runPhaseAB(
                        validRoots,
                        mode,
                        concurrency,
                        Maybe.Present(bodyStore),
                        Maybe.Present(positionsStore),
                        Maybe.Present(declarationRangeStore),
                        Maybe.Present(parentOccurrenceStore)
                    )
                }.map { classpath =>
                    val finalCp =
                        if preErrors.nonEmpty then Tasty.Classpath.copyWithPreErrors(classpath, preErrors)
                        else classpath
                    (finalCp, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore)
                }
            }
        }
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
        concurrency: Int,
        bodyStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]] = Maybe.Absent,
        positionsStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]]] = Maybe.Absent,
        declarationRangeStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange]] = Maybe.Absent,
        parentOccurrenceStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]] = Maybe.Absent
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

        // Pipeline-launch boundary: a single Sync.Unsafe.defer scopes the AllowUnsafe proof for
        //   - the global symbol-id counter (AtomicInt.Unsafe) shared across decoder fibers,
        //   - the nextGlobalId closure (captures the AllowUnsafe proof so each .getAndIncrement
        //     call inside a decoder fiber composes under the same boundary),
        //   - the timing AtomicLong.Unsafe slots used by all stages.
        // The rest of the method body runs without ambient AllowUnsafe, so callers cannot pick
        // up the proof implicitly outside this boundary.
        Sync.Unsafe.defer {
            // AtomicInt.Unsafe is cross-platform (Kyo provides JS and Native implementations).
            val globalSymbolIdCounter: AtomicInt.Unsafe = AtomicInt.Unsafe.init(0)
            val nextGlobalId: () => Int                 = () => globalSymbolIdCounter.getAndIncrement()
            // Timing instrumentation: snapshot nanoTime at each stage boundary.
            // AtomicLong.Unsafe used so decoder fibers can record t_decodeEnd from any thread.
            val t_start     = AtomicLong.Unsafe.init(java.lang.System.nanoTime())
            val t_listEnd   = AtomicLong.Unsafe.init(0L)
            val t_decodeEnd = AtomicLong.Unsafe.init(0L)
            val t_mergeEnd  = AtomicLong.Unsafe.init(0L)
            (nextGlobalId, t_start, t_listEnd, t_decodeEnd, t_mergeEnd)
        }.map { case (nextGlobalId, t_start, t_listEnd, t_decodeEnd, t_mergeEnd) =>
            Scope.run {
                Channel.initUnscoped[(String, String)](entryCap, Access.MultiProducerMultiConsumer).map { entryCh =>
                    Channel.initUnscoped[DecodeResult](resultCap, Access.MultiProducerMultiConsumer).map { resultCh =>
                        // Scope.ensure registrations guarantee channels close on ANY exit (success, abort, interrupt).
                        // Uses close (not closeAwaitEmpty) so that on abort the signal is immediate: interrupted
                        // consumers are no longer draining, and closeAwaitEmpty would block forever waiting for them.
                        // streamUntilClosed handles the Closed signal correctly on any exit path.
                        Scope.ensure(entryCh.close.unit).andThen {
                            Scope.ensure(resultCh.close.unit).andThen {
                                val producerStage = Async.foreach(Chunk.from(roots), rootCount) { root =>
                                    TastyStat.scope.traceSpan(
                                        "walkRoot",
                                        Attributes.empty.add("root", root)
                                    )(walkRoot(root, entryCh))
                                }

                                val decoderStage = Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency) { _ =>
                                    TastyStat.scope.traceSpan("decoder") {
                                        entryCh.streamUntilClosed().foreach { (entryPath, kind) =>
                                            decodeOneEntry(entryPath, kind, mode, nextGlobalId).map { result =>
                                                // If resultCh closed early (FailFast abort), silently discard
                                                Abort.run[Closed](resultCh.put(result)).unit
                                            }
                                        }
                                    }
                                }

                                val mergerStage: Unit < (Async & Abort[TastyError]) =
                                    TastyStat.scope.traceSpan("merger") {
                                        resultCh.streamUntilClosed().foreach { result =>
                                            Sync.defer(mergeOneInto(mergeState, result))
                                        }
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
                                Async.foreach(stages, 3) { stage =>
                                    stage
                                }.andThen(finalizeMerge(
                                    mergeState,
                                    mode,
                                    bodyStoreOutput,
                                    positionsStoreOutput,
                                    declarationRangeStoreOutput,
                                    parentOccurrenceStoreOutput
                                )).map { result =>
                                    (if timingEnabled then
                                         Sync.Unsafe.defer {
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
                                             (
                                                 s"[kyo-tasty] cold-load: list=${listMs}ms decode=${decodeMs}ms merge=${mergeMs}ms finalize=${finalizeMs}ms total=${totalMs}ms | jars=$jars (construct=${constructMs}ms read=${readMs}ms) entries=$entries bytes=${bytesMB}MB",
                                                 s"[kyo-tasty]   decode-breakdown: header=${headerMs}ms names=${namesMs}ms section=${sectionMs}ms attr=${attrMs}ms ast=${astMs}ms pos=${posMs}ms comments=${commentsMs}ms"
                                             )
                                         }.map { (msg1, msg2) =>
                                             Log.info(msg1).andThen(Log.info(msg2))
                                         }
                                     else
                                         Kyo.unit
                                    ).andThen(result)
                                }
                            }
                        }
                    }
                }
            }
        }
    end runPhaseAB

    /** Walk a single root, putting (entryPath, kind) pairs into entryCh.
      *
      * If `root` is itself a `.tasty` or `module-info.class` file, emit it directly. If `entryCh` is already closed
      * (strict-mode abort scenario), the put fails with `Closed`; we discard the error and stop walking this root.
      *
      * Three root types are handled:
      *   - `.jar` root: opened via `ZipHandle.open`; entries listed via `handle.listEntries`; entry paths formatted as `"jar!/entry"`.
      *   - `jrt:/` root: listed via `ZipHandle.listJrtEntries` (JVM-specific; returns empty on JS/Native).
      *   - Directory or direct `.tasty`/`.class` root: walked via `kyo.Path.walk` with suffix filtering.
      */
    private def walkRoot(
        root: String,
        entryCh: Channel[(String, String)]
    )(using Frame): Unit < (Sync & Async & Abort[TastyError]) =
        val suffixes = Chunk(".tasty", "module-info.class")
        // Scope.run introduces Async; use a wider effect type to accommodate both branches.
        val listEffect: Chunk[String] < (Sync & Async & Abort[TastyError]) =
            if root.startsWith("jrt:/") then
                // jrt:/ path: enumerate via platform-specific JRT walk (JVM only).
                ZipHandle.listJrtEntries(root, suffixes)
            else if root.toLowerCase.endsWith(".jar") then
                // JAR root: open a ZipHandle and list matching entries. Scope.run discharges the Scope.
                Scope.run {
                    ZipHandle.open(root).map {
                        case Maybe.Absent => Sync.defer(Chunk.empty[String])
                        case Maybe.Present(handle) =>
                            handle.listEntries(suffixes).map(entryNames => entryNames.map(e => s"$root!/$e"))
                    }
                }
            else
                // Directory, direct .tasty file, or direct .class file: use Path walk.
                Abort.recover[FileException](err => Abort.fail(TastyError.SnapshotIoError(s"list $root: ${err.getMessage}"))) {
                    if root.endsWith(".tasty") || root.endsWith("module-info.class") then
                        Sync.defer(Chunk(root))
                    else if root.endsWith(".class") && !root.endsWith("module-info.class") then
                        Sync.defer(Chunk(root))
                    else
                        // Path.walk returns a Stream requiring Scope; Scope.run discharges the Scope.
                        Scope.run {
                            Path.runReadOnly(Path(root).walk.run).map { paths =>
                                Chunk.from(paths.toSeq.filter { p =>
                                    val name = p.name.getOrElse("")
                                    suffixes.exists(name.endsWith)
                                }.map(_.toString))
                            }
                        }
                }
        listEffect.map { listed =>
            val unsorted: Chunk[String] =
                if listed.isEmpty then
                    if root.endsWith(".tasty") || root.endsWith("module-info.class") then Chunk(root)
                    // A root that IS a standalone .class file (e.g. a jrt:/ path like
                    // `jrt:///modules/java.base/java/lang/String.class`) is passed directly from
                    // PlatformModuleOps.listJdkClassFiles. Walking returns empty because it is a file,
                    // not a directory. Emit it with kind ".class" so decodeOneEntry routes to ClassfileUnpickler.
                    else if root.endsWith(".class") && !root.endsWith("module-info.class") then Chunk(root)
                    else Chunk.empty
                else listed
            // Sort entries so file processing order is stable across filesystem enumeration orders.
            // Different platforms and JAR implementations enumerate entries in varying orders;
            // a lexicographic sort gives deterministic symbol IDs, enabling byte-equal
            // snapshot idempotency when concurrency == 1.
            val entries = Chunk.from(unsorted.iterator.toSeq.sorted)
            Kyo.foreach(entries) { entry =>
                val kind =
                    if entry.endsWith("module-info.class") then "module-info.class"
                    // Individual .class entries from direct paths (jrt:/) get kind ".class".
                    // Note: .class entries from directory listings are not emitted here (walk only returns
                    // .tasty and module-info.class); those companion .class files are handled by the
                    // readAndDecodeTastyFile companion-decode path in decodeOneEntry.
                    else if entry.endsWith(".class") then ".class"
                    else ".tasty"
                // Discard Closed: if entryCh closed early, stop putting
                Abort.run[Closed](entryCh.put((entry, kind))).unit
            }
        }
            .unit
    end walkRoot

    /** Decode one entry by kind and return a DecodeResult.
      *
      * For `.tasty`: reads bytes, decodes TASTy, returns a FileResultCase. For `module-info.class`: reads bytes, decodes module descriptor,
      * returns a ModuleInfoCase.
      *
      * In strict mode, decode errors propagate as Abort[TastyError]. In soft-fail mode they produce empty/error FileResult.
      *
      * Entry paths may be in `"jar!/entry"` format (JAR entries) or plain filesystem paths. JAR reads use `ZipHandle.readJarEntry`
      * (JVM: pooled mmap via JarMappedReaderPool; JS/Native: error, not reachable in practice). Plain paths use `kyo.Path.readBytes`.
      */
    private def decodeOneEntry(
        entryPath: String,
        kind: String,
        mode: Tasty.ErrorMode,
        nextGlobalId: () => Int
    )(using Frame): DecodeResult < (Sync & Async & Abort[TastyError]) =
        if kind == "module-info.class" then
            Abort.run[TastyError](
                readEntryBytes(entryPath).map { span =>
                    Sync.Unsafe.defer {
                        TastyPerfStats.entryReads.inc()
                        TastyPerfStats.bytesRead.add(span.size.toLong)
                        // Unsafe: toArrayUnsafe exposes the underlying array at the ModuleInfoReader (ByteView) boundary.
                    }.andThen(ModuleInfoReader.read(span.toArrayUnsafe))
                }
            ).map {
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
            }
        else if kind == ".class" then
            // Decode a standalone JVM .class file via ClassfileUnpickler.
            // The fully-qualified name is computed from the path by stripping the jrt:/ module prefix and
            // converting slash-separated segments to a dotted name. This makes JDK classes
            // (java.lang.String, java.util.HashMap, etc.) reachable via classpath.findClass.
            Abort.run[TastyError](
                readEntryBytes(entryPath).map { span =>
                    Sync.Unsafe.defer {
                        TastyPerfStats.entryReads.inc()
                        TastyPerfStats.bytesRead.add(span.size.toLong)
                    }.andThen {
                        // Unsafe: ClassfileUnpickler reads an immutable byte array inside a Sync.Unsafe.defer block; no suspension required.
                        // toArrayUnsafe: Span[Byte] boundary -- ClassfileUnpickler (via ByteView) requires Array[Byte].
                        Sync.Unsafe.defer {
                            val arena = kyo.internal.tasty.type_.TypeArena.canonical()
                            Abort.get(ClassfileUnpickler.read(span.toArrayUnsafe, arena, Maybe(nextGlobalId)))
                        }
                    }
                }
            ).map {
                case Result.Success(cfResult) =>
                    // Prefer the authoritative binary name from the bytecode constant pool. Falls back to a
                    // path-derived fully-qualified name only for the (unexpected) case where the bytecode binary name is empty.
                    val fullName =
                        if cfResult.binaryName.nonEmpty then cfResult.binaryName.replace('/', '.')
                        else classfilePathToFullName(entryPath)
                    if fullName.isEmpty then
                        // Skip files whose fully-qualified name cannot be computed (anonymous, synthetic, etc.)
                        FileResultCase(emptyFileResultWithError(
                            entryPath,
                            TastyError.ClassfileFormatError(entryPath, "fully-qualified name empty; skipping", 0)
                        ))
                    else JavaClassfileCase(fullName, cfResult)
                    end if
                case Result.Failure(err: TastyError) =>
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
                case Result.Panic(t) =>
                    val err = TastyError.CorruptedFile(entryPath, 0L, t.getMessage)
                    if mode == Tasty.ErrorMode.FailFast then Abort.fail(err)
                    else FileResultCase(emptyFileResultWithError(entryPath, err))
            }
        else
            Scope.run {
                readAndDecodeTastyFile(entryPath, mode, Maybe(nextGlobalId)).map(FileResultCase.apply)
            }

    /** Read raw bytes for an entry, returning them as Span[Byte].
      *
      * Dispatches on path format:
      *   - `"jar!/entry"` format: reads via `ZipHandle.readJarEntry` (JVM: pooled mmap; JS/Native: error).
      *   - `jrt:/` prefix: reads via `ZipHandle.readJrtEntry` (JVM: JRT filesystem; JS/Native: not reachable).
      *   - Plain filesystem path: reads via `kyo.Path.readBytes`.
      *
      * Callers that pass bytes to a Java-API leaf (ByteView, ClassfileUnpickler, ModuleInfoReader) convert via
      * `.toArrayUnsafe` at the boundary. This keeps Span[Byte] as the type from the read site to the binary parser.
      */
    private def readEntryBytes(entryPath: String)(using Frame): Span[Byte] < (Sync & Abort[TastyError]) =
        val jarSepIdx = entryPath.indexOf("!/")
        if jarSepIdx > 0 then
            ZipHandle.readJarEntry(entryPath.substring(0, jarSepIdx), entryPath.substring(jarSepIdx + 2)).map(Span.fromUnsafe)
        else if entryPath.startsWith("jrt:/") then
            ZipHandle.readJrtEntry(entryPath).map(Span.fromUnsafe)
        else
            Abort.recover[FileException](err => Abort.fail(TastyError.SnapshotIoError(s"read $entryPath: ${err.getMessage}"))) {
                Path.runReadOnly(Path(entryPath).readBytes)
            }
        end if
    end readEntryBytes

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
                // LongMap keyed on symbol.id (unique per-instance) for dedup within and across files.
                val seenSymIds = mutable.LongMap.empty[Unit]
                for symbol <- fr.symbolsInOrder do
                    if !seenSymIds.contains(symbol.id.toLong) then
                        seenSymIds(symbol.id.toLong) = ()
                        state.allSyms += symbol
                        state.allSymsSet(symbol.id.toLong) = ()
                end for

                // The synthetic per-file root Package("") (fr.symbolsInOrder's first element, per
                // FileResult's construction) carries an EMPTY fully-qualified name, so computeFullName's
                // `.nonEmpty` guard excludes it from fullNameKeys/fullNameSymbols below -- it never
                // reaches the fullName-keyed loop that registers every other package into
                // packagesByFullName. Register it here instead, under the same "" key every file's root
                // shares, so finalizeMerge's duplicate-package collapse (see packagesByFullName's use
                // there) also unions every file's top-level declarations onto ONE canonical root, instead
                // of leaving N independent per-file roots where only rootId (hardcoded to whichever root
                // happens to land at final index 0) is ever reachable from Classpath.root.
                if fr.symbolsInOrder.nonEmpty then
                    val fileRoot = fr.symbolsInOrder.head
                    if fileRoot.kind == SymbolKind.Package then
                        state.packagesByFullName.getOrElseUpdate("", mutable.ArrayBuffer.empty) += fileRoot
                end if

                val frKeys = fr.fullNameKeys
                val frSyms = fr.fullNameSymbols
                var fnIdx  = 0
                while fnIdx < frKeys.size do
                    val fullName = frKeys(fnIdx)
                    val symbol   = frSyms(fnIdx)
                    val indexKey = if symbol.kind == SymbolKind.Object && !fullName.endsWith("$") then fullName + "$" else fullName
                    val existing = state.fullNameIndex.get(indexKey)
                    val shouldStore = existing match
                        case None => true
                        case Some(prev) =>
                            val prevIsStructural = prev.kind == SymbolKind.Class ||
                                prev.kind == SymbolKind.Trait || prev.kind == SymbolKind.Object ||
                                prev.kind == SymbolKind.EnumCase
                            val newIsStructural = symbol.kind == SymbolKind.Class ||
                                symbol.kind == SymbolKind.Trait || symbol.kind == SymbolKind.Object ||
                                symbol.kind == SymbolKind.EnumCase
                            newIsStructural || !prevIsStructural
                    // Record a collision when a new structural symbol of the SAME KIND overwrites a
                    // different structural symbol. Both must be structural (Class/Trait/Object/EnumCase), must be
                    // distinct objects (different reference identity), and must share the same SymbolKind to be a
                    // real cross-root collision.
                    //
                    // Same-kind constraint is essential to avoid false positives from the canonical fully-qualified name alias
                    // pattern: when a companion Object (kind=Object, fullName="scala.Array$") is registered under its
                    // canonical alias ("scala.Array"), the incoming Class (kind=Class) later overwrites it. That
                    // is the expected alias-upgrade sequence, NOT a collision. Requiring symbol.kind == prev.kind
                    // filters out the alias-upgrade case while still catching genuine same-kind duplicates (e.g.,
                    // two jars both defining "com.example.Foo" as a Class).
                    existing match
                        case Some(prev) if (prev ne symbol) && (prev.kind == symbol.kind) =>
                            val prevIsStructural = prev.kind == SymbolKind.Class ||
                                prev.kind == SymbolKind.Trait || prev.kind == SymbolKind.Object ||
                                prev.kind == SymbolKind.EnumCase
                            val newIsStructural = symbol.kind == SymbolKind.Class ||
                                symbol.kind == SymbolKind.Trait || symbol.kind == SymbolKind.Object ||
                                symbol.kind == SymbolKind.EnumCase
                            if prevIsStructural && newIsStructural then
                                val accumulator = state.collisions.getOrElseUpdate(indexKey, mutable.ArrayBuffer(prev))
                                discard(accumulator += symbol)
                        // Carve-out: stdlib Option from mutable.HashMap.get; covers absent and unmatched-guard Some
                        case _ => ()
                    end match
                    if shouldStore then state.fullNameIndex(indexKey) = symbol
                    // Unified source fully-qualified name dual-index via FullNameNormalizer.
                    // canonicalSourceFullName handles all 4 mangling patterns in order; if the result
                    // differs from indexKey, register the source fully-qualified name as an additional key.
                    // Synthetic names (anonfun, proxy, etc.) are not registered in user-facing indexes.
                    if !kyo.internal.tasty.symbol.FullNameNormalizer.isSyntheticName(indexKey) then
                        val sourceFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(indexKey)
                        if sourceFullName != indexKey && sourceFullName.nonEmpty then
                            val existingSource = state.fullNameIndex.get(sourceFullName)
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
                                    val newIsStructural = symbol.kind == SymbolKind.Class ||
                                        symbol.kind == SymbolKind.Trait ||
                                        symbol.kind == SymbolKind.Object ||
                                        symbol.kind == SymbolKind.EnumCase ||
                                        symbol.kind == SymbolKind.OpaqueType
                                    newIsStructural && !prevIsStructural
                            if storeSource then state.fullNameIndex(sourceFullName) = symbol
                        end if
                    end if
                    if !seenSymIds.contains(symbol.id.toLong) then
                        seenSymIds(symbol.id.toLong) = ()
                        state.allSyms += symbol
                        state.allSymsSet(symbol.id.toLong) = ()
                    end if
                    symbol.kind match
                        case SymbolKind.Package =>
                            state.packages += symbol
                            state.packageIndex(fullName) = symbol
                            state.packagesByFullName.getOrElseUpdate(fullName, mutable.ArrayBuffer.empty) += symbol
                        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object |
                            SymbolKind.EnumCase =>
                            state.topLevelCls += symbol
                        case SymbolKind.Method | SymbolKind.Field | SymbolKind.Val | SymbolKind.Var |
                            SymbolKind.TypeAlias | SymbolKind.OpaqueType | SymbolKind.AbstractType |
                            SymbolKind.TypeParam | SymbolKind.Parameter =>
                            ()
                    end match
                    fnIdx += 1
                end while
                state.accErrors ++= fr.errors
                state.fileResults += fr
            case ModuleInfoCase(name, md) =>
                state.moduleIndex(name) = md
            case JavaClassfileCase(fullName, cfResult) =>
                // Register the classfile's primary symbol in the fully-qualified name index.
                // The classSymbol carries flags (isEnum, isRecord, isSealed, etc.) decoded by ClassfileUnpickler.
                // Member symbols (methods, fields) are added to allSyms for finalizeMerge so they receive
                // final SymbolIds; the classSymbol itself is added as the primary top-level entry.
                val classSym = cfResult.classSymbol
                // Register primary binary fully-qualified name (e.g. "java.util.Map$Entry").
                if !state.fullNameIndex.contains(fullName) then
                    state.fullNameIndex(fullName) = classSym
                // Register canonical source fully-qualified name (e.g. "java.util.Map.Entry") as secondary key.
                if !kyo.internal.tasty.symbol.FullNameNormalizer.isSyntheticName(fullName) then
                    val sourceFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                    if sourceFullName != fullName && sourceFullName.nonEmpty && !state.fullNameIndex.contains(sourceFullName) then
                        state.fullNameIndex(sourceFullName) = classSym
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
                state.javaClassfileResults += ((fullName, cfResult))

    /** Result of [[globalizeUnresolvedNegIds]]: per-file remaps from a file's raw decode-time negId to either a
      * resolved final symbol index (`perFileNegIdToFinal`, value >= 0) or a classpath-unique unresolved negId
      * (`perFileNegIdToGlobalNeg`, value < -1), plus the merged `unresolvedFullNameByNegId` keyed by those
      * classpath-unique negIds. The arrays are indexed by position in the input `perFileUnresolved` sequence.
      */
    final private[kyo] case class UnresolvedNegIdRemap(
        perFileNegIdToFinal: Array[java.util.HashMap[Int, Int]],
        perFileNegIdToGlobalNeg: Array[java.util.HashMap[Int, Int]],
        unresolvedFullNameByNegId: mutable.HashMap[Tasty.SymbolId, String]
    )

    /** Assign classpath-unique negative SymbolIds to cross-file references that do not resolve to a loaded symbol.
      *
      * Each file decodes its unresolved references with negIds from a per-file counter that always starts at -2
      * (TypeUnpickler.DecodeSession), so distinct files reuse the same negId values for different fully-qualified
      * names. Keeping those per-file values would make them collide in the single `unresolvedFullNameByNegId` map,
      * and the surviving name would then depend on the (non-deterministic) order in which decoded files reach the
      * merger. Each distinct unresolved fully-qualified name instead gets one classpath-unique negId, assigned in
      * sorted-name order so the assignment is a pure function of the unresolved-name set, independent of file order.
      *
      * `resolveFinalIdx` returns the final symbol index for a fully-qualified name that is loaded (>= 0), or a
      * negative value when it is not; the negative case folds the "name present in the index but not materialised"
      * orphan into the unresolved path so its negId is never dropped.
      */
    private[kyo] def globalizeUnresolvedNegIds(
        perFileUnresolved: Seq[scala.collection.Map[Int, String]],
        resolveFinalIdx: String => Int
    ): UnresolvedNegIdRemap =
        val fileCount = perFileUnresolved.size
        // Resolve each distinct fully-qualified name once.
        val finalIdxByName = mutable.HashMap.empty[String, Int]
        def resolved(fullName: String): Int =
            finalIdxByName.getOrElseUpdate(fullName, resolveFinalIdx(fullName))
        // Collect the unresolved names in sorted order so global negId assignment is deterministic.
        val unresolvedNames = mutable.TreeSet.empty[String]
        for fileMap <- perFileUnresolved; fullName <- fileMap.values do
            if resolved(fullName) < 0 then discard(unresolvedNames.add(fullName))
        val globalNegByName           = mutable.HashMap.empty[String, Int]
        val unresolvedFullNameByNegId = mutable.HashMap.empty[Tasty.SymbolId, String]
        var nextGlobalNeg             = -2
        for fullName <- unresolvedNames do
            globalNegByName(fullName) = nextGlobalNeg
            unresolvedFullNameByNegId(SymbolId(nextGlobalNeg)) = fullName
            nextGlobalNeg -= 1
        end for
        val perFileNegIdToFinal     = new Array[java.util.HashMap[Int, Int]](fileCount)
        val perFileNegIdToGlobalNeg = new Array[java.util.HashMap[Int, Int]](fileCount)
        var fi                      = 0
        for fileMap <- perFileUnresolved do
            val toFinal  = new java.util.HashMap[Int, Int](fileMap.size * 2)
            val toGlobal = new java.util.HashMap[Int, Int](fileMap.size * 2)
            fileMap.foreach { case (negId, fullName) =>
                val idx = resolved(fullName)
                if idx >= 0 then discard(toFinal.put(negId, idx))
                else discard(toGlobal.put(negId, globalNegByName(fullName)))
            }
            perFileNegIdToFinal(fi) = toFinal
            perFileNegIdToGlobalNeg(fi) = toGlobal
            fi += 1
        end for
        UnresolvedNegIdRemap(perFileNegIdToFinal, perFileNegIdToGlobalNeg, unresolvedFullNameByNegId)
    end globalizeUnresolvedNegIds

    /** Phase C: SymbolDescriptor construction + single-shot Symbol materialization + index building.
      *
      * Runs once after the merger has drained the result channel. Builds SymbolDescriptors from the accumulated per-file data, calls
      * materializeSymbols to produce the final immutable Symbol case-class instances, builds all index maps, and returns the public
      * Tasty.Classpath case class.
      */
    private def finalizeMerge(
        state: MergeState,
        mode: Tasty.ErrorMode,
        bodyStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]] = Maybe.Absent,
        positionsStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]]] = Maybe.Absent,
        declarationRangeStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange]] = Maybe.Absent,
        parentOccurrenceStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]] = Maybe.Absent
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        val canonical   = TypeArena.canonical()
        val fileResults = state.fileResults.toSeq
        val allPartial  = state.allSyms
        val topLevelCls = state.topLevelCls
        val packages    = state.packages
        val accErrors   = state.accErrors
        val moduleIndex = Dict.from(state.moduleIndex.toMap)

        TastyStat.scope.traceSpan("finalize.mergeArenas") {
            Sync.Unsafe.defer {
                for fr <- fileResults do canonical.merge(fr.arena)
                end for
            }
        }.andThen {
            TastyStat.scope.traceSpan("finalize.materializeSymbols") {
                Sync.Unsafe.defer {
                    // Duplicate-package collapse: a package is legitimately split across every file that
                    // opens it, and each file's own per-file Pass 1 registers its OWN Package partial for
                    // the same fully-qualified name (AstUnpickler.scala:188,301). Left uncollapsed, each
                    // partial would get its own final SymbolId below, producing multiple independent
                    // Package symbols for the SAME fully-qualified name in one classpath, each carrying
                    // only its own file's slice of the package's declarations -- a findMember(package,
                    // name) call resolving through whichever duplicate a given reference happens to embed
                    // would then miss members declared in a sibling file's slice (the parent-clause /
                    // type-param-bound resolution chain hit exactly this: JVM and JS/Native picked
                    // different duplicates, because which file's registration is merged last is decided by
                    // concurrent decode-fiber completion order, which differs per platform's scheduler).
                    // Collapse every fully-qualified-name group with more than one partial down to ONE
                    // canonical partial; every other partial in the group redirects to the canonical
                    // partial's final id instead of receiving its own, and (below, at the declarationIds
                    // population loop) every file's contribution is unioned onto that one id rather than
                    // the last file's contribution overwriting the rest.
                    //
                    // Canonical is chosen by lowest Materialising.id, not by arrival order into this
                    // group (Phase C merge order, itself dictated by concurrent decode-fiber completion,
                    // which differs per platform's scheduler): `id` is the AtomicInt each file's Pass 1
                    // walk claims while decoding, so at the concurrency == 1 configuration this codebase
                    // already treats as its determinism baseline (see the entry-sort comment above, "byte
                    // -equal snapshot idempotency when concurrency == 1"), files decode strictly in
                    // sorted-path order and `minBy(_.id)` reduces to "the alphabetically-first file that
                    // declares this package" -- reproducible run to run, not scheduler-dependent. At
                    // concurrency > 1 the chosen id can still vary by platform, the same tier of
                    // best-effort determinism `fileResults`-ordered scaladoc/sourcePosition assignment
                    // below already has; picking by id value instead of by this group's incidental
                    // insertion order removes an extra, unnecessary source of divergence from Phase C's
                    // own merge scheduling on top of that.
                    val packageCanonicalOf = mutable.LongMap.empty[LoadingSymbol.Materialising]
                    for (_, group) <- state.packagesByFullName do
                        if group.length > 1 then
                            val canonical = group.minBy(_.id)
                            for dup <- group do
                                if dup ne canonical then packageCanonicalOf(dup.id.toLong) = canonical
                            end for
                        end if
                    end for

                    // Build a map from LoadingSymbol.Materialising.id -> final SymbolId (index in
                    // descPartials, i.e. allPartial minus the non-canonical package duplicates collapsed
                    // above). LongMap keyed on m.id (unique per-instance); replaces the former
                    // IdentityHashMap approach. Pre-size the backing arrays to allPartial.length so the
                    // LongMap is allocated once instead of repacking (re-allocating and copying its
                    // long[]/Object[] arrays) ~log2(count) times as it fills with the classpath's symbols.
                    val symbolIdMap  = new mutable.LongMap[Int](allPartial.length * 2)
                    val descPartials = new mutable.ArrayBuffer[LoadingSymbol.Materialising](allPartial.length)
                    var i            = 0
                    for symbol <- allPartial do
                        if !packageCanonicalOf.contains(symbol.id.toLong) then
                            symbolIdMap(symbol.id.toLong) = i
                            descPartials += symbol
                            i += 1
                        end if
                    end for
                    // Redirect every non-canonical package duplicate to its canonical's already-assigned
                    // final id, so every reference to any of the duplicates resolves identically.
                    for (dupId, canonical) <- packageCanonicalOf do
                        symbolIdMap(dupId) = symbolIdMap.getOrElse(canonical.id.toLong, -1)
                    end for

                    val count = descPartials.length
                    val descs = new Array[SymbolDescriptor](count)
                    i = 0
                    for partialSym <- descPartials do
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
                    // 1. PHASE_B_ADDR_OFFSET + address: local same-file symbol references (TYPEREFdirect/TERMREFdirect/TYPEREFsymbol).
                    //    Remapped using the file's addrMap -> symbolIdMap.
                    // 2. Unique negative IDs (< -1): cross-file fully-qualified name references (TYPEREFpkg/TERMREFpkg/TYPEREFin).
                    //    Remapped by looking up the fully-qualified name in fullNameIndex (the merged global map).
                    val phaseBOffset = kyo.internal.tasty.reader.TypeUnpickler.PHASE_B_ADDR_OFFSET

                    // Build the global fullNameIndex BEFORE parent remap (it's built after materializing symbols, need to compute it).
                    // We use state.fullNameIndex (the merged fullNameIndex from MergeState) as the lookup source since fullNameIndex contains partial syms.
                    // After symbolIdMap is built, fullNameIndex partial syms can be resolved to final IDs.

                    final case class FileRemap(
                        addrToFinal: java.util.HashMap[Int, Int],
                        negIdToFinal: java.util.HashMap[Int, Int],
                        negIdToGlobalNeg: java.util.HashMap[Int, Int]
                    )

                    // Give every cross-file reference that does not resolve to a loaded symbol a classpath-unique
                    // negId so the per-file decode-time negIds (all starting at -2) cannot collide in the shared
                    // unresolvedFullNameByNegId map; see globalizeUnresolvedNegIds. A name present in fullNameIndex
                    // but without a materialised final id (symbolIdMap miss) is treated as unresolved here so its
                    // negId is never dropped.
                    val negRemap = globalizeUnresolvedNegIds(
                        fileResults.map(_.unresolvedIdToFullName),
                        fullName =>
                            state.fullNameIndex.get(fullName) match
                                case Some(partialSym) => symbolIdMap.getOrElse(partialSym.id.toLong, -1)
                                case None             => -1
                    )
                    // Stored in the Classpath so that typeFullNameString can recover the fully-qualified name for
                    // symbolsAnnotatedWith matching when the annotation's defining library is absent.
                    val unresolvedFullNameByNegId = negRemap.unresolvedFullNameByNegId

                    val fileRemaps = fileResults.iterator.zipWithIndex.map { case (fr, fi) =>
                        // address -> finalId (for PHASE_B_ADDR_OFFSET refs)
                        val addrToFinal = new java.util.HashMap[Int, Int](fr.addrMap.size * 2)
                        fr.addrMap.foreach { case (address, partialSym) =>
                            val finalIdx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
                            if finalIdx >= 0 then discard(addrToFinal.put(address, finalIdx))
                        }
                        FileRemap(addrToFinal, negRemap.perFileNegIdToFinal(fi), negRemap.perFileNegIdToGlobalNeg(fi))
                    }.toArray

                    // Resolve a cross-file negId (< -1) to its final symbol index (>= 0) or its classpath-unique
                    // unresolved negId (< -1). Returns the original value unchanged when the negId is untracked.
                    def remapNegId(v: Int, fr: FileRemap): Int =
                        val finalIdx = fr.negIdToFinal.getOrDefault(v, -1)
                        if finalIdx >= 0 then finalIdx
                        else
                            // 0 is never a valid global negId (all are < -1); it marks "absent".
                            val globalNeg = fr.negIdToGlobalNeg.getOrDefault(v, 0)
                            if globalNeg != 0 then globalNeg else v
                        end if
                    end remapNegId

                    def remapType(t: Tasty.Type, fr: FileRemap): Tasty.Type =
                        t match
                            case Tasty.Type.Named(sid) =>
                                val v = sid.value
                                if v >= phaseBOffset then
                                    // Local same-file reference
                                    val address  = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(address, -1)
                                    if finalIdx >= 0 then Tasty.Type.Named(SymbolId(finalIdx))
                                    else t
                                else if v < -1 then
                                    // Cross-file fully-qualified name reference (unique negative ID)
                                    val r = remapNegId(v, fr)
                                    if r != v then Tasty.Type.Named(SymbolId(r))
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
                                val newParamIds = paramIds.map { id =>
                                    val v = id.value
                                    if v >= phaseBOffset then
                                        val address  = v - phaseBOffset
                                        val finalIdx = fr.addrToFinal.getOrDefault(address, -1)
                                        if finalIdx >= 0 then SymbolId(finalIdx) else id
                                    else if v < -1 then
                                        val r = remapNegId(v, fr)
                                        if r != v then SymbolId(r) else id
                                    else id
                                    end if
                                }
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
                            case Tasty.Type.Annotated(underlying, annotation) =>
                                Tasty.Type.Annotated(remapType(underlying, fr), annotation)
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
                            case Tasty.Type.Bind(name, pattern) =>
                                Tasty.Type.Bind(name, remapType(pattern, fr))
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
                                    val address  = v - phaseBOffset
                                    val finalIdx = fr.addrToFinal.getOrDefault(address, -1)
                                    if finalIdx >= 0 then Tasty.Type.ThisType(SymbolId(finalIdx))
                                    else t
                                else if v < -1 then
                                    // Cross-file: fully-qualified-name-tracked nested-class ThisType.
                                    val r = remapNegId(v, fr)
                                    if r != v then Tasty.Type.ThisType(SymbolId(r))
                                    else t
                                else t
                                end if
                            // TypeRef must recurse like TermRef.
                            case Tasty.Type.TypeRef(qual, name) =>
                                Tasty.Type.TypeRef(remapType(qual, fr), name)
                            // Bounds must recurse into lo and hi.
                            case Tasty.Type.Bounds(lo, hi) =>
                                Tasty.Type.Bounds(remapType(lo, fr), remapType(hi, fr))
                            case _: Tasty.Type.Tuple | _: Tasty.Type.ConstantType | _: Tasty.Type.ParamRef |
                                Tasty.Type.Nothing | Tasty.Type.Any => t

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
                                // calling classpath.symbol(id) on Named(SymbolId(-1)) would get Maybe.Absent anyway.
                                descs(idx).parentTypes = parents.map(remapType(_, frRemap)).filter {
                                    case Tasty.Type.Named(sid) => sid.value != -1
                                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                                        _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                        _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                        _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                        _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                        _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                                        true
                                }
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
                                // Union, deduped (not overwrite): a package's own childrenByOwner entry is
                                // keyed by its per-file partial id, and every duplicate partial for the
                                // same fully-qualified name now redirects (via symbolIdMap, above) to the
                                // SAME canonical idx, so a package accumulates declarations across every
                                // file that opens it instead of the last file's contribution clobbering
                                // the rest. `.distinct` because a MULTI-LEVEL duplicate chain (e.g. the
                                // root package AND one of its children both split across the same two
                                // files) would otherwise union in the same already-canonical child id once
                                // per contributing file. A no-op for any owner that only ever receives one
                                // contribution.
                                val newTypeParamIds =
                                    typeParams.map(c => symbolIdMap.getOrElse(c.id.toLong, -1)).filter(_ >= 0)
                                val newDeclarationIds =
                                    declarations.map(c => symbolIdMap.getOrElse(c.id.toLong, -1)).filter(_ >= 0)
                                descs(idx).typeParamIds = (descs(idx).typeParamIds ++ newTypeParamIds).distinct
                                descs(idx).declarationIds = (descs(idx).declarationIds ++ newDeclarationIds).distinct
                            end if
                        }
                    end for

                    // Resolve parent-clause TypeRef markers into a genuine final SymbolId, now that
                    // declarationIds is populated for every desc (immediately above). A TypeRef(qual, name)
                    // in parentTypes is produced by TYPEREFin/SELECTtpt/SELECTin when the qualifier is an
                    // address-resolvable same-file reference (e.g. a nested class named via its enclosing
                    // object, `extends Outer.Nested`): Pass 1 cannot synchronously compute the qualifier's
                    // fully-qualified name string (its owner chain isn't built yet), so the decode site defers
                    // to this address-based reference instead of fabricating an unresolvable one. Unlike
                    // declaredType/bounds (resolved lazily and query-time by OccurrenceScanner.classLikeOf),
                    // parentTypes has no TypeRef-aware consumer: parents(), allMembersOf, and
                    // buildSubclassIndex all require a plain Named entry, and empty-parentTypes default-parent
                    // injection (below) would also misfire on a lingering TypeRef, so this must resolve here.
                    def resolveParentTypeRef(t: Tasty.Type): Tasty.Type = t match
                        case Tasty.Type.TypeRef(qual, name) =>
                            resolveParentTypeRef(qual) match
                                case Tasty.Type.Named(ownerSid) if ownerSid.value >= 0 && ownerSid.value < count =>
                                    import kyo.Tasty.Name.asString
                                    val nm = name.asString
                                    descs(ownerSid.value).declarationIds.find(cid =>
                                        cid >= 0 && cid < count && descs(cid).name.asString == nm
                                    ) match
                                        case Some(cid) => Tasty.Type.Named(SymbolId(cid))
                                        case None      => t
                                    end match
                                case _ => t
                        case _ => t
                    end resolveParentTypeRef

                    // The final head SymbolId a parent type denotes: a plain Named parent is that symbol; an
                    // applied parent (`extends Foo[Bar]`) is its constructor `Foo`. Returns -1 for a shape with
                    // no in-range Named head. Used to match a captured parent type-ref address against the
                    // class's actual resolved parents when building parentOccurrenceStore.
                    def parentHeadSid(t: Tasty.Type): Int = t match
                        case Tasty.Type.Named(sid) if sid.value >= 0 && sid.value < count => sid.value
                        case Tasty.Type.Applied(base, _)                                  => parentHeadSid(base)
                        case _                                                            => -1

                    var ptIdx = 0
                    while ptIdx < count do
                        if descs(ptIdx).parentTypes.nonEmpty then
                            descs(ptIdx).parentTypes = descs(ptIdx).parentTypes.map(resolveParentTypeRef)
                        end if
                        ptIdx += 1
                    end while

                    // Copy the Pass-1 parameter-list partition from each Method loading symbol into its
                    // descriptor. The carrier is LoadingSymbol.Materialising.paramListIds (Chunk[Chunk[Int]])
                    // populated by AstUnpickler.recordParamListPartitions (nodeAddr -> symbol.id resolution in
                    // the DEFDEF arm). Apply the same symbolIdMap.getOrElse(_, -1).filter(_ >= 0) mapping
                    // used for typeParamIds / declarationIds. Parameters continue to appear in declarationIds
                    // as well; the two views overlap intentionally (INV-H1 / Pass C contract).
                    for fr <- fileResults do
                        for symbol <- fr.symbolsInOrder do
                            if symbol.kind == SymbolKind.Method && symbol.paramListIds.nonEmpty then
                                val idx = symbolIdMap.getOrElse(symbol.id.toLong, -1)
                                if idx >= 0 && idx < count then
                                    descs(idx).paramListIds = symbol.paramListIds.map { inner =>
                                        inner.map(id => symbolIdMap.getOrElse(id.toLong, -1)).filter(_ >= 0)
                                    }
                                end if
                            end if
                        end for
                    end for

                    var frIdx3 = 0
                    for fr <- fileResults do
                        val frRemap3 = fileRemaps(frIdx3)
                        fr.typeBySymbol.foreach { case (symId, t) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count then
                                val remapped = remapType(t, frRemap3)
                                // TypeParam/AbstractType carry their declared constraint as `bounds: TypeBounds`,
                                // not `declaredType`; neither TypedSymbolFactory case reads `d.declaredType` for
                                // them. OpaqueType is EXCLUDED here even though it also has a `bounds` field: its
                                // ONE `typeBySymbol` entry is the alias body (fed to `d.declaredType`, consumed by
                                // OpaqueType.body), so it keeps the declaredType path below unchanged; its
                                // `bounds` field has no decode source yet and stays the existing sentinel.
                                val isBoundsKind = descs(idx).kind == SymbolKind.TypeParam || descs(idx).kind == SymbolKind.AbstractType
                                if isBoundsKind && descs(idx).bounds.isEmpty then
                                    // A TYPEPARAM/ABSTRACTTYPE node with both a lower and an upper bound decodes
                                    // as Type.Bounds(lo, hi) (TYPEBOUNDS tag); one with only an upper bound (the
                                    // common case, e.g. `A <: SomeTrait`) decodes as that upper type directly.
                                    // Either way, the sentinel TypeBounds(Nothing, Any) never masks a
                                    // genuinely-declared bound.
                                    remapped match
                                        case Tasty.Type.Named(sid) if sid.value == -1 => ()
                                        case Tasty.Type.Bounds(lo, hi)                => descs(idx).bounds = Maybe(Tasty.TypeBounds(lo, hi))
                                        case other => descs(idx).bounds = Maybe(Tasty.TypeBounds(Tasty.Type.Nothing, other))
                                    end match
                                else if !isBoundsKind && descs(idx).declaredType.isEmpty then
                                    // Drop Named(SymbolId(-1)) sentinels: a sentinel in declaredType position
                                    // means the type could not be resolved. Leave declaredType as Absent so
                                    // FailFast can surface TastyError.MissingDeclaredType instead of exposing
                                    // a sentinel in the produced public ADT.
                                    remapped match
                                        case Tasty.Type.Named(sid) if sid.value == -1 => ()
                                        case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                            _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                                            _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                            _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                                            _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                                            _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                                            _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                                            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                                            _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                                            Tasty.Type.Nothing | Tasty.Type.Any =>
                                            descs(idx).declaredType = Maybe(remapped)
                                    end match
                                end if
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
                    for symbol <- descPartials do
                        val d = descs(i)
                        if symbol.kind == SymbolKind.OpaqueType && d.typeParamIds.nonEmpty then
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
                                        val newParams = oldParams.zipWithIndex.map { (id, p) =>
                                            if id.value == -1 && p < tpIds.size then SymbolId(tpIds(p)) else id
                                        }
                                        d.declaredType = Maybe(Tasty.Type.TypeLambda(newParams, tl.body))
                                    end if
                                case Maybe.Absent     => ()
                                case Maybe.Present(_) => ()
                        end if
                        i += 1
                    end for

                    // Default declaredType for Class/Trait/Object/EnumCase: Type.Named(SymbolId(i)).
                    // Use SymbolId(i), not symbol.id (which is SymbolId(-1) for all partial symbols).
                    i = 0
                    for symbol <- descPartials do
                        if descs(i).declaredType.isEmpty then
                            val k = symbol.kind
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

                    // Aggregate each file's declaration full-extent ranges into the runtime declarationRangeStore,
                    // remapping the loading-symbol id to its FINAL SymbolId exactly as the sourcePosition loop above
                    // does. Keyed by final SymbolId so Tasty.declarationRange(symbol) resolves by symbol.id. This
                    // store is never serialized; a pure-snapshot load leaves it empty (declarationRange -> Absent).
                    declarationRangeStoreOutput.foreach { store =>
                        for fr <- fileResults do
                            fr.declarationRangesBySymbol.foreach { (symId, range) =>
                                val idx = symbolIdMap.getOrElse(symId, -1)
                                if idx >= 0 && idx < count then discard(store.putIfAbsent(SymbolId(idx), range))
                            }
                        end for
                    }

                    // Populate annotations from ANNOTATION modifier blocks decoded during per-file decode.
                    // Each annotation's tycon may contain Named(negId) cross-file refs; remap through
                    // the same FileRemap used for parent types so symbolsAnnotatedWith fully-qualified name matching works.
                    // Annotations whose remapped tycon is Named(SymbolId(-1)) are dropped: an annotation
                    // with an unresolvable type class is not meaningful in the produced public ADT.
                    //
                    // resolveTypeFullNameFromDescs: walk the descriptor array to compute the dotted fully-qualified name for a
                    // remapped annotation type. The Classpath is not yet built at this point (line 1702),
                    // so typeFullNameStringUnsafe is not callable here. Instead, we walk descs(sid.value).name
                    // and descs(sid.value).ownerId, following the owner chain up to the root.
                    // Non-Named types (TermRef-encoded fully-qualified names from SnapshotReader warm paths) fall back to
                    // empty string; those annotations are produced via SnapshotReader with annotationFullName
                    // already set, so they never reach this reconstruction path.
                    import kyo.Tasty.Name.asString as nameAsString
                    def resolveTypeFullNameFromDescs(t: Tasty.Type): String =
                        t match
                            case Tasty.Type.Named(sid) =>
                                val startIdx = sid.value
                                if startIdx < 0 || startIdx >= count then ""
                                else
                                    val visited   = new java.util.HashSet[Int]()
                                    val parts     = new java.util.ArrayDeque[String]()
                                    var curIdx    = startIdx
                                    var keepGoing = true
                                    while keepGoing do
                                        if !visited.add(curIdx) || curIdx < 0 || curIdx >= count then
                                            keepGoing = false
                                        else
                                            val desc    = descs(curIdx)
                                            val segment = nameAsString(desc.name)
                                            if segment.nonEmpty then parts.addFirst(segment)
                                            val ownerIdx = desc.ownerId
                                            if ownerIdx == curIdx || ownerIdx < 0 || ownerIdx >= count then
                                                keepGoing = false
                                            else
                                                curIdx = ownerIdx
                                            end if
                                    end while
                                    val sb = new java.lang.StringBuilder()
                                    val it = parts.iterator()
                                    while it.hasNext do
                                        if sb.length() > 0 then discard(sb.append('.'))
                                        discard(sb.append(it.next()))
                                    end while
                                    sb.toString()
                                end if
                            case Tasty.Type.Applied(base, _) => resolveTypeFullNameFromDescs(base)
                            case _: Tasty.Type.TermRef | _: Tasty.Type.TypeLambda | _: Tasty.Type.Function |
                                _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple | _: Tasty.Type.ByName |
                                _: Tasty.Type.Repeated | _: Tasty.Type.Array | _: Tasty.Type.Refinement |
                                _: Tasty.Type.Rec | _: Tasty.Type.RecThis | _: Tasty.Type.AndType |
                                _: Tasty.Type.OrType | _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                                _: Tasty.Type.ThisType | _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef |
                                _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem | _: Tasty.Type.MatchType |
                                _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef |
                                _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                                ""
                        end match
                    end resolveTypeFullNameFromDescs

                    var frIdxAnn = 0
                    for fr <- fileResults do
                        val frRemapAnn = fileRemaps(frIdxAnn)
                        fr.annotationsBySymbol.foreach { case (symId, annBuf) =>
                            val idx = symbolIdMap.getOrElse(symId, -1)
                            if idx >= 0 && idx < count then
                                val remapped = annBuf.flatMap { annotation =>
                                    val remappedType = remapType(annotation.annotationType, frRemapAnn)
                                    remappedType match
                                        case Tasty.Type.Named(sid) if sid.value == -1 => None
                                        case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                            _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                                            _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                            _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                                            _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                                            _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                                            _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                                            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                                            _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                                            Tasty.Type.Nothing | Tasty.Type.Any =>
                                            val resolvedFullName = resolveTypeFullNameFromDescs(remappedType)
                                            Some(Tasty.Annotation(remappedType, annotation.arguments, Tasty.Name(resolvedFullName)))
                                    end match
                                }
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
                    // (e.g. "java/lang/Object"). Here we convert each to a dotted fully-qualified name and look it up
                    // in the merged fullNameIndex to obtain the final SymbolId, producing resolved
                    // parentTypes for the classfile symbol.
                    //
                    // javaMetadata is copied from the ClassfileResult's classSymbol so isEnum/isRecord/isSealed
                    // flags (stored in JavaMetadata.accessFlags) survive into the final Symbol.
                    for (fullName, cfResult) <- state.javaClassfileResults do
                        val classSym = cfResult.classSymbol
                        val classIdx = symbolIdMap.getOrElse(classSym.id.toLong, -1)
                        if classIdx >= 0 && classIdx < count then
                            // Resolve parent types using cfResult.parentBinaryNames.
                            // Binary names use '/' as separator (e.g. "java/lang/Object"); convert to dotted.
                            // Unresolvable parents are dropped entirely; a Named(SymbolId(-1)) sentinel would
                            // violate the invariant that no SymbolId.value < 0 survives in produced ADTs.
                            val parentsBuf = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            for bn <- cfResult.parentBinaryNames do
                                val dottedFullName = bn.replace('/', '.')
                                val resolved: Int =
                                    state.fullNameIndex.get(dottedFullName) match
                                        case Some(parentSym) =>
                                            symbolIdMap.getOrElse(parentSym.id.toLong, -1)
                                        case None =>
                                            // Try canonical source fully-qualified name (handles "$" inner class separators).
                                            val srcFullName =
                                                kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(dottedFullName)
                                            state.fullNameIndex.get(srcFullName) match
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
                            // declarationIds is required by the declarations(using classpath) API so that
                            // symbol.declarations / symbol.methods / symbol.fields return the correct members.
                            val memberIdxBuf = new scala.collection.mutable.ArrayBuffer[Int]()
                            for memberSym <- cfResult.symbols do
                                val memberIdx = symbolIdMap.getOrElse(memberSym.id.toLong, -1)
                                if memberIdx >= 0 && memberIdx < count then
                                    descs(memberIdx).ownerId = classIdx
                                    memberIdxBuf += memberIdx
                            end for
                            if descs(classIdx).declarationIds.isEmpty then
                                descs(classIdx).declarationIds = Chunk.from(memberIdxBuf.toSeq)
                            // Resolve permittedSubclassFullNames to final SymbolIds.
                            // These are dotted fully-qualified names (e.g. "java.lang.Double") that may use "$" inner-class
                            // separators; try canonical source fully-qualified name as fallback.
                            if cfResult.permittedSubclassFullNames.nonEmpty then
                                val permBuf = new scala.collection.mutable.ArrayBuffer[Int]()
                                for fullName <- cfResult.permittedSubclassFullNames do
                                    state.fullNameIndex.get(fullName) match
                                        case Some(permSym) =>
                                            val permIdx = symbolIdMap.getOrElse(permSym.id.toLong, -1)
                                            if permIdx >= 0 then permBuf += permIdx
                                        case None =>
                                            val srcFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                                            state.fullNameIndex.get(srcFullName) match
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
                    //       "name in the scope of symbol qualId". Construct the fully-qualified name and look up in
                    //       state.fullNameIndex to get the final SymbolId.
                    import kyo.Tasty.Name.asString

                    // Build a reverse index: final SymbolId value -> fully-qualified name string, for use by resolveChildRef.
                    // This is a lightweight reverse of state.fullNameIndex used only for permit extraction.
                    val idToFullNameForPermits = new java.util.HashMap[Int, String](state.fullNameIndex.size * 2)
                    for (fullName, partialSym) <- state.fullNameIndex do
                        val idx = symbolIdMap.getOrElse(partialSym.id.toLong, -1)
                        if idx >= 0 && !idToFullNameForPermits.containsKey(idx) then
                            discard(idToFullNameForPermits.put(idx, fullName))
                    end for

                    /** Resolve a Type (subclass ref from @Child annotation) to a final SymbolId value.
                      *
                      * Handles Type.Named (direct ref) and Type.TermRef (TYPEREF with qualifier).
                      * For singleton objects like scala.None, the @Child annotation references the
                      * module val fully-qualified name (scala.None). We detect this by checking if the resolved symbol
                      * is a Val kind (module val), then resolve to the module class fully-qualified name (scala.None$).
                      *
                      * Returns -1 if unresolvable (entry skipped).
                      */
                    /** Resolve a qualifier SymbolId to its fully-qualified name string, or Maybe.Absent if unresolvable. */
                    def qualIdToFullName(qualId: Int): Maybe[String] =
                        if qualId < 0 then Maybe.Absent
                        else Maybe.fromOption(Option(idToFullNameForPermits.get(qualId)))
                    end qualIdToFullName

                    /** Look up a fully-qualified name in fullNameIndex and return its final SymbolId, handling module vals.
                      * If the direct lookup hits a Val kind (module val), redirect to the module class "$" fully-qualified name.
                      */
                    def fullNameToId(fullName: String): Int =
                        state.fullNameIndex.get(fullName) match
                            case Some(p) =>
                                val rawId = symbolIdMap.getOrElse(p.id.toLong, -1)
                                if rawId >= 0 && rawId < count && descs(rawId).kind == SymbolKind.Val then
                                    state.fullNameIndex.get(fullName + "$") match
                                        case Some(p2) =>
                                            val moduleId = symbolIdMap.getOrElse(p2.id.toLong, -1)
                                            if moduleId >= 0 then moduleId else rawId
                                        case None => rawId
                                else rawId
                                end if
                            case None =>
                                // Fallback: try the module class fully-qualified name (for singleton objects encoded as "$").
                                state.fullNameIndex.get(fullName + "$") match
                                    case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                                    case None    => -1
                    end fullNameToId

                    def resolveChildRef(t: Tasty.Type): Int =
                        t match
                            case Tasty.Type.Named(sid) if sid.value >= 0 =>
                                sid.value
                            case Tasty.Type.TermRef(Tasty.Type.Named(qualSid), memberName)
                                if qualSid.value >= 0 =>
                                qualIdToFullName(qualSid.value) match
                                    case Maybe.Present(qualFullName) =>
                                        val fullName = if qualFullName.nonEmpty then qualFullName + "." + memberName.asString
                                        else memberName.asString
                                        fullNameToId(fullName)
                                    case Maybe.Absent => -1
                            case Tasty.Type.TypeRef(Tasty.Type.Named(qualSid), memberName)
                                if qualSid.value >= 0 =>
                                // TypeRef may also carry @Child type args.
                                qualIdToFullName(qualSid.value) match
                                    case Maybe.Present(qualFullName) =>
                                        val fullName = if qualFullName.nonEmpty then qualFullName + "." + memberName.asString
                                        else memberName.asString
                                        fullNameToId(fullName)
                                    case Maybe.Absent => -1
                            case Tasty.Type.TermRef(Tasty.Type.ThisType(clsSid), memberName)
                                if clsSid.value >= 0 =>
                                // Enum case encoding: TermRef(ThisType(enclosingClassId), caseName).
                                // The qualifier is the enclosing class's ThisType; build the fully-qualified name from
                                // enclosingClass fully-qualified name + "." + caseName.
                                qualIdToFullName(clsSid.value) match
                                    case Maybe.Present(qualFullName) =>
                                        val fullName = if qualFullName.nonEmpty then qualFullName + "." + memberName.asString
                                        else memberName.asString
                                        fullNameToId(fullName)
                                    case Maybe.Absent => -1
                            case Tasty.Type.TypeRef(Tasty.Type.ThisType(clsSid), memberName)
                                if clsSid.value >= 0 =>
                                // TypeRef(ThisType(...), name) for enum cases.
                                qualIdToFullName(clsSid.value) match
                                    case Maybe.Present(qualFullName) =>
                                        val fullName = if qualFullName.nonEmpty then qualFullName + "." + memberName.asString
                                        else memberName.asString
                                        fullNameToId(fullName)
                                    case Maybe.Absent => -1
                            case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                                _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                                _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                                _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                                _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                                _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                                _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                                Tasty.Type.Nothing | Tasty.Type.Any =>
                                -1
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
                                val accumulator = new scala.collection.mutable.ArrayBuffer[Int]()
                                var ai          = 0
                                while ai < anns.size do
                                    anns(ai).annotationType match
                                        case Tasty.Type.Applied(Tasty.Type.TermRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // @Child[T] enriched tycon (TermRef form).
                                            val subId = resolveChildRef(args(0))
                                            if subId >= 0 then accumulator += subId
                                        case Tasty.Type.Applied(Tasty.Type.TypeRef(_, childName), args)
                                            if args.size == 1 && childName.asString == "Child" =>
                                            // @Child[T] enriched tycon (TypeRef form).
                                            val subId = resolveChildRef(args(0))
                                            if subId >= 0 then accumulator += subId
                                        case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                                            _: Tasty.Type.TypeLambda | _: Tasty.Type.Function |
                                            _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                            _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                            _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                            _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                            _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                                            _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                                            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                                            _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                                            Tasty.Type.Nothing | Tasty.Type.Any =>
                                            ()
                                    end match
                                    ai += 1
                                end while
                                if accumulator.nonEmpty then
                                    desc.permittedSubclassIds = Maybe(Chunk.from(accumulator.toSeq))
                            end if
                        end if
                        permitIdx += 1
                    end while

                    // Cross-file TYPEREFsymbol resolution.
                    // Build a global address -> finalIdx map from all per-file addrToFinal maps.
                    // Any Named(PHASE_B_ADDR_OFFSET + address) that was left unresolved by the per-file
                    // remapType pass (because the address belongs to a different file) is resolved here.
                    // Runs before materializeSymbols.
                    val globalAddrToFinal = new java.util.HashMap[Int, Int](count * 2)
                    var xfFrIdx           = 0
                    for fr <- fileResults do
                        val frRemap = fileRemaps(xfFrIdx)
                        frRemap.addrToFinal.forEach { (address, finalIdx) =>
                            discard(globalAddrToFinal.putIfAbsent(address, finalIdx))
                        }
                        xfFrIdx += 1
                    end for

                    /** Rewrite cross-file Named refs using the global address map. */
                    def rewriteCrossFile(t: Tasty.Type): Tasty.Type =
                        t match
                            case Tasty.Type.Named(id) if id.value >= phaseBOffset =>
                                val address  = id.value - phaseBOffset
                                val finalIdx = globalAddrToFinal.getOrDefault(address, -1)
                                if finalIdx >= 0 then Tasty.Type.Named(SymbolId(finalIdx))
                                else t
                            case Tasty.Type.Named(_) => t
                            case Tasty.Type.Applied(base, args) =>
                                Tasty.Type.Applied(rewriteCrossFile(base), args.map(rewriteCrossFile))
                            // Remap TypeLambda.paramIds for cross-file TypeParam refs.
                            case Tasty.Type.TypeLambda(paramIds, body) =>
                                val newParamIds = paramIds.map { id =>
                                    if id.value >= phaseBOffset then
                                        val address  = id.value - phaseBOffset
                                        val finalIdx = globalAddrToFinal.getOrDefault(address, -1)
                                        if finalIdx >= 0 then SymbolId(finalIdx) else id
                                    else id
                                }
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
                                    val address  = v - phaseBOffset
                                    val finalIdx = globalAddrToFinal.getOrDefault(address, -1)
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
                            case _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                                _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                                _: Tasty.Type.RecThis | _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                                _: Tasty.Type.ParamRef | _: Tasty.Type.Skolem | _: Tasty.Type.MatchType |
                                _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | Tasty.Type.Nothing |
                                Tasty.Type.Any => t
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

                    // Default parent injection.
                    // For classes/traits/enum-cases with empty parentTypes after cross-file resolution,
                    // inject a synthetic default parent. Run AFTER cross-file resolution so cross-file
                    // resolved parents are visible before deciding whether to inject.
                    // Runs before materializeSymbols.

                    def lookupFullNameFinalId(fullName: String): Int =
                        state.fullNameIndex.get(fullName) match
                            case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                            case None    => -1
                    end lookupFullNameFinalId

                    var qIdx = 0
                    while qIdx < count do
                        val desc = descs(qIdx)
                        val k    = desc.kind
                        if (k == SymbolKind.Class || k == SymbolKind.Trait || k == SymbolKind.EnumCase) &&
                            desc.parentTypes.isEmpty
                        then
                            val fullName = idToFullNameForPermits.get(qIdx)
                            if fullName != null then
                                val syntheticParent: Maybe[Tasty.Type] =
                                    if fullName == "java.lang.Object" || fullName == "scala.Any" || fullName == "scala.AnyRef" then
                                        Maybe.Absent
                                    else if fullName == "scala.AnyVal" then
                                        val anyId = lookupFullNameFinalId("scala.Any")
                                        if anyId >= 0 then Maybe(Tasty.Type.Named(SymbolId(anyId)))
                                        else Maybe.Absent
                                    else if kyo.internal.tasty.symbol.FullNameNormalizer.isValueClass(fullName) then
                                        val anyValId = lookupFullNameFinalId("scala.AnyVal")
                                        if anyValId >= 0 then Maybe(Tasty.Type.Named(SymbolId(anyValId)))
                                        else Maybe.Absent
                                    else
                                        val anyRefId = lookupFullNameFinalId("scala.AnyRef")
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

                    for (fr, fi) <- fileResults.zipWithIndex do
                        // Resolve every in-file definition address to its FINAL SymbolId so a lazily-decoded
                        // body resolves use-site IDENT/SELECT references; the addrToFinal map computed at
                        // fileRemaps supplies the address-to-id mapping.
                        val fileAddrMap: scala.collection.immutable.IntMap[SymbolId] =
                            var m = scala.collection.immutable.IntMap.empty[SymbolId]
                            fileRemaps(fi).addrToFinal.forEach((addr, finalIdx) => m = m.updated(addr, SymbolId(finalIdx)))
                            m
                        end fileAddrMap
                        // Retain this pickle's Positions-section bytes, keyed by the pickle index fi (Concern 2:
                        // a String(sourceFile) key collides across pickles of one .scala). A per-pickle copy of just
                        // the Positions-section bytes (not the whole-file array), so readSpans runs lazily at first query, not at load.
                        positionsStoreOutput.foreach { store =>
                            if fr.positionsSectionBytes.nonEmpty then
                                discard(store.put(fi, Span.fromUnsafe(fr.positionsSectionBytes)))
                        }
                        // Capture this pickle's extends/with parent-clause use sites, keyed by the pickle index fi
                        // (the key occurrencesInFile joins against this pickle's PositionMap). For each class-like
                        // symbol, resolve every captured parent type-ref address to a final SymbolId via the same
                        // remap/resolve path parentTypes takes, keeping only addresses that point at one of the
                        // class's actual resolved parents. distinct because one parent name can decode into more
                        // than one type node at the same address span (the constructor call and its inner type ref).
                        parentOccurrenceStoreOutput.foreach { store =>
                            val refs = new mutable.ArrayBuffer[(Int, SymbolId)]()
                            fr.parentRefAddrsBySymbol.foreach { case (symId, captured) =>
                                val idx = symbolIdMap.getOrElse(symId, -1)
                                if idx >= 0 && idx < count then
                                    val parentHeadSids: Set[Int] =
                                        descs(idx).parentTypes.iterator.map(parentHeadSid).filter(_ >= 0).toSet
                                    if parentHeadSids.nonEmpty then
                                        captured.foreach { case (addr, cachedType) =>
                                            val resolved =
                                                parentHeadSid(resolveParentTypeRef(remapType(cachedType, fileRemaps(fi))))
                                            if resolved >= 0 && parentHeadSids.contains(resolved) then
                                                discard(refs += ((addr, SymbolId(resolved))))
                                        }
                                    end if
                                end if
                            }
                            if refs.nonEmpty then discard(store.put(fi, Chunk.from(refs).distinct))
                        }
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
                                                addrMap = fileAddrMap,
                                                pickleId = fi
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
                    // the enclosing Sync.Unsafe.defer block returns a typed (classpath, error) tuple rather than
                    // panicking. materializeEarlyError carries the caught error; the rest of the block
                    // builds a minimal placeholder classpath to satisfy the return type.
                    var materializeEarlyError: Maybe[TastyError] = Maybe.Absent
                    val finalSymbols: Array[Tasty.Symbol] =
                        try materializeSymbols(descs, count, mode, accErrors)
                        catch
                            case sme: kyo.internal.tasty.symbol.SymbolMaterializationError =>
                                materializeEarlyError = Maybe.Present(sme.error)
                                new Array[Tasty.Symbol](0)

                    // Resolve NestHost, NestMembers, and EnclosingMethod fully-qualified names to real Symbols
                    // now that finalSymbols is available. finalSymbols is a mutable Array; we can replace
                    // entries whose javaMetadata needs the resolved fields.
                    //
                    // Helper: look up a dotted fully-qualified name in fullNameIndex and return its final SymbolId value.
                    def resolveFullNameToIdx(fullName: String): Int =
                        state.fullNameIndex.get(fullName) match
                            case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                            case None =>
                                val srcFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                                if srcFullName != fullName then
                                    state.fullNameIndex.get(srcFullName) match
                                        case Some(p) => symbolIdMap.getOrElse(p.id.toLong, -1)
                                        case None    => -1
                                else -1
                                end if
                        end match
                    end resolveFullNameToIdx

                    for (_, cfResult) <- state.javaClassfileResults do
                        val classSym = cfResult.classSymbol
                        val classIdx = symbolIdMap.getOrElse(classSym.id.toLong, -1)
                        if classIdx >= 0 && classIdx < count then
                            val hasNestHost        = cfResult.nestHostFullName.nonEmpty
                            val hasNestMembers     = cfResult.nestMemberFullNames.nonEmpty
                            val hasEnclosingMethod = cfResult.enclosingMethodData.nonEmpty
                            if hasNestHost || hasNestMembers || hasEnclosingMethod then
                                finalSymbols(classIdx) match
                                    case cls: Tasty.Symbol.Class =>
                                        val currentMeta = cls.javaMetadata
                                        val resolvedMeta = currentMeta.map { meta =>
                                            val withNestHost =
                                                if hasNestHost then
                                                    cfResult.nestHostFullName.fold(meta) { nhFullName =>
                                                        val nhIdx = resolveFullNameToIdx(nhFullName)
                                                        if nhIdx >= 0 && nhIdx < count then
                                                            meta.copy(nestHost = Maybe(finalSymbols(nhIdx)))
                                                        else meta
                                                    }
                                                else meta
                                            val withNestMembers =
                                                if hasNestMembers then
                                                    val nmBuf = new scala.collection.mutable.ArrayBuffer[Tasty.Symbol]()
                                                    for nmFullName <- cfResult.nestMemberFullNames do
                                                        val nmIdx = resolveFullNameToIdx(nmFullName)
                                                        if nmIdx >= 0 && nmIdx < count then
                                                            nmBuf += finalSymbols(nmIdx)
                                                    end for
                                                    if nmBuf.nonEmpty then
                                                        withNestHost.copy(nestMembers = Chunk.from(nmBuf.toSeq))
                                                    else withNestHost
                                                else withNestHost
                                            val withEnclosingMethod =
                                                if hasEnclosingMethod then
                                                    cfResult.enclosingMethodData.fold(withNestMembers) {
                                                        case (encFullName, methodName) =>
                                                            val encIdx = resolveFullNameToIdx(encFullName)
                                                            if encIdx >= 0 && encIdx < count then
                                                                withNestMembers.copy(
                                                                    enclosingMethod = Maybe(Tasty.Java.EnclosingMethod(
                                                                        finalSymbols(encIdx),
                                                                        Tasty.Name(methodName)
                                                                    ))
                                                                )
                                                            else withNestMembers
                                                            end if
                                                    }
                                                else withNestMembers
                                            withEnclosingMethod
                                        }
                                        if resolvedMeta != currentMeta then
                                            finalSymbols(classIdx) = cls.copy(javaMetadata = resolvedMeta)
                                    case _: Tasty.Symbol.EnumCase | _: Tasty.Symbol.Trait | _: Tasty.Symbol.Object |
                                        _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                                        _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                                        _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                                        _: Tasty.Symbol.Parameter | _: Tasty.Symbol.Package =>
                                        ()
                            end if
                        end if
                    end for

                    // Build fullNameIdIdx: for each partial symbol in fullNameIndex, resolve its final SymbolId.
                    // Unresolvable entries (both direct and canonical fallback fail) are:
                    //   - Dropped from the index (no sentinel symbol inserted), AND
                    //   - Accumulated as TastyError.UnresolvedReference in accErrors (SoftFail) or
                    //     trigger ClasspathBuilding (FailFast) via the brokenFullNameCount check below.
                    val fullNameIdBuf           = scala.collection.mutable.ArrayBuffer.empty[(String, SymbolId)]
                    var fullNameUnresolvedCount = 0
                    for (fullName, partial) <- state.fullNameIndex do
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        if idx >= 0 then
                            fullNameIdBuf += fullName -> SymbolId(idx)
                        else
                            // Binary-alias fully-qualified name keys may store a partial symbol not in symbolIdMap;
                            // canonicalize and retry.
                            val canonicalFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                            val fallbackIdx =
                                if canonicalFullName != fullName then
                                    state.fullNameIndex.get(canonicalFullName) match
                                        case Some(canonPartial) => symbolIdMap.getOrElse(canonPartial.id.toLong, -1)
                                        case None               => -1
                                else -1
                            if fallbackIdx >= 0 then
                                fullNameIdBuf += fullName -> SymbolId(fallbackIdx)
                            else
                                // Genuinely unresolvable: accumulate as UnresolvedReference and skip.
                                accErrors += TastyError.UnresolvedReference(fullName, fullNameUnresolvedCount)
                                fullNameUnresolvedCount += 1
                            end if
                        end if
                    end for
                    // pkgIdIdx: package fully-qualified name -> final SymbolId; unresolvable entries use SymbolId(-1).
                    val pkgIdBuf = scala.collection.mutable.ArrayBuffer.empty[(String, SymbolId)]
                    for (pkg, partial) <- state.packageIndex do
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        pkgIdBuf += pkg -> (if idx >= 0 then SymbolId(idx) else SymbolId(-1))
                    end for
                    // packages list: unresolvable entries use SymbolId(-1). Distinct because every
                    // non-canonical package duplicate (see the collapse above) redirects to its
                    // canonical's id, so `packages` (the raw per-file partial list) can carry the SAME
                    // final id more than once; the public listing must show each package exactly once.
                    val pkgIdsList = packages.map { partial =>
                        val idx = symbolIdMap.getOrElse(partial.id.toLong, -1)
                        if idx >= 0 then SymbolId(idx) else SymbolId(-1)
                    }.distinct

                    val finalErrors   = Chunk.from(accErrors)
                    val symsChunk     = Chunk.from(finalSymbols)
                    val fullNameIdIdx = Dict.from(fullNameIdBuf.toMap)
                    val pkgIdIdx      = Dict.from(pkgIdBuf.toMap)
                    // Filter topLevelClassIds to only ClassLike symbols whose owner is a Package.
                    // Filter finalSymbols directly (post-materialization) so the Package kind check uses the
                    // final symbol's kind, not a partial symbol's kind.
                    val topIds: Chunk[SymbolId] = symsChunk.flatMap {
                        case c: Tasty.Symbol.ClassLike =>
                            val ownerIdx = c.ownerId.value
                            if ownerIdx >= 0 && ownerIdx < finalSymbols.length &&
                                finalSymbols(ownerIdx).isInstanceOf[Tasty.Symbol.Package]
                            then Chunk(c.id)
                            else Chunk.empty
                            end if
                        case _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                            _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                            _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                            _: Tasty.Symbol.Parameter | _: Tasty.Symbol.Package =>
                            Chunk.empty
                    }
                    val pkgIds = Chunk.from(pkgIdsList)
                    val rootId = if finalSymbols.nonEmpty then SymbolId(0) else SymbolId(-1)

                    val subclassIdx  = buildSubclassIndex(symsChunk)
                    val companionIdx = buildCompanionIndex(symsChunk, fullNameIdIdx)

                    // Convert per-fully-qualified-name collision buckets to FullNameCollision diagnostics.
                    // Each bucket holds all symbols seen for that fully-qualified name (including winner); ids are resolved
                    // via symbolIdMap so they reflect final SymbolIds.
                    val collisionDiagnostics: Chunk[Tasty.Classpath.Diagnostic] =
                        Chunk.from(
                            state.collisions.flatMap { case (collisionFullName, syms) =>
                                val ids = Chunk.from(syms.toSeq.map(s => SymbolId(symbolIdMap.getOrElse(s.id.toLong, -1))))
                                Seq(Tasty.Classpath.FullNameCollision(collisionFullName, ids))
                            }
                        )

                    // Check for partial symbols that could not be resolved.
                    // Under SoftFail these were accumulated as TastyError.UnresolvedReference above and
                    // dropped from fullNameIdIdx. Under FailFast we check fullNameUnresolvedCount instead of scanning
                    // fullNameIdIdx for negative ids (unresolved entries are not inserted into the index).
                    val brokenFullNameCount = fullNameUnresolvedCount

                    // FailFast error selection:
                    //   - If SymbolMaterializationError was caught during materializeSymbols -> that error.
                    //   - If collisions exist under FailFast -> FullNameCollisionError (first colliding fully-qualified name).
                    //   - If broken fullNameIndex entries exist under FailFast -> ClasspathBuilding.
                    // All checks produce a Maybe[TastyError] returned as a tuple alongside the classpath so
                    // the Sync.Unsafe.defer block can carry the error out without mixing Abort effects inside it.
                    val failFastError: Maybe[TastyError] =
                        if materializeEarlyError.isDefined then materializeEarlyError
                        else if mode == Tasty.ErrorMode.FailFast then
                            if collisionDiagnostics.nonEmpty then
                                val firstFullName = state.collisions.keys.head
                                Maybe(TastyError.FullNameCollisionError(firstFullName))
                            else if brokenFullNameCount > 0 then
                                Maybe(TastyError.ClasspathBuilding(s"finalizeMerge: brokenFullNameCount=$brokenFullNameCount"))
                            else Maybe.Absent
                        else Maybe.Absent

                    val classpath = Tasty.Classpath.make(
                        symbols = symsChunk,
                        rootSymbolId = rootId,
                        topLevelClassIds = topIds,
                        packageIds = pkgIds,
                        fullNameIndex = fullNameIdIdx,
                        packageIndex = pkgIdIdx,
                        subclassIndex = subclassIdx,
                        companionIndex = companionIdx,
                        moduleIndex = moduleIndex,
                        errors = finalErrors,
                        diagnostics = collisionDiagnostics,
                        unresolvedFullNameByNegId = Dict.from(unresolvedFullNameByNegId.toMap)
                    )
                    (classpath, failFastError)
                }
            }
        }.map { result =>
            val (builtCp, failFastError) = result
            failFastError match
                case Maybe.Present(err) => Abort.fail(err)
                case Maybe.Absent       => builtCp
        }
    end finalizeMerge

    /** Build subclassIndex by inverting parentTypes: for each symbol, register it as a direct subclass of each Named parent.
      *
      * Handles both direct `Named(pid)` and `Applied(Named(pid), args)` cases, since in TASTy a class parent is often encoded as
      * `APPLY(TYPEREFsymbol(address), constructor_args)` which decodes to `Applied(Named(baseId), args)`.
      */
    private def buildSubclassIndex(symbols: Chunk[Tasty.Symbol])(using AllowUnsafe): Dict[SymbolId, Chunk[SymbolId]] =
        val b = scala.collection.mutable.HashMap.empty[SymbolId, scala.collection.mutable.ArrayBuffer[SymbolId]]

        def extractNamedId(t: Tasty.Type): Maybe[SymbolId] = t match
            case Tasty.Type.Named(pid)       => Maybe(pid)
            case Tasty.Type.Applied(base, _) => extractNamedId(base)
            case Tasty.Type.ThisType(cid)    => Maybe(cid)
            case _: Tasty.Type.TermRef | _: Tasty.Type.TypeLambda | _: Tasty.Type.Function |
                _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple | _: Tasty.Type.ByName |
                _: Tasty.Type.Repeated | _: Tasty.Type.Array | _: Tasty.Type.Refinement |
                _: Tasty.Type.Rec | _: Tasty.Type.RecThis | _: Tasty.Type.AndType |
                _: Tasty.Type.OrType | _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                Tasty.Type.Nothing | Tasty.Type.Any =>
                Maybe.Absent

        var i = 0
        while i < symbols.length do
            val s = symbols(i)
            (s match
                case c: Tasty.Symbol.ClassLike => c.parentTypes
                case _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                    _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                    _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                    _: Tasty.Symbol.Parameter | _: Tasty.Symbol.Package =>
                    Chunk.empty
            ).foreach { parent =>
                extractNamedId(parent) match
                    case Maybe.Present(pid) if pid.value >= 0 =>
                        val accumulator = b.getOrElseUpdate(pid, scala.collection.mutable.ArrayBuffer.empty)
                        accumulator += s.id
                    case Maybe.Present(_) => ()
                    case Maybe.Absent     => ()
            }
            i += 1
        end while
        Dict.from(b.iterator.map((pid, accumulator) => pid -> Chunk.from(accumulator.toSeq)).toMap)
    end buildSubclassIndex

    /** Build companionIndex: for each Class, look up its Object companion (fully-qualified name + "$") and vice versa.
      *
      * For OpaqueType symbols, the companion Object may be registered under a mangled fully-qualified name (e.g.
      * `kyo.fixtures.FixtureClasses$package$.Meters$`) whose canonical source fully-qualified name (after Rule 1
      * `$package$.` strip) is `kyo.fixtures.Meters`. There is NO `kyo.fixtures.Meters$` entry in
      * fullNameIndex; the simple `fullName + "$"` lookup would return Absent.
      *
      * The fix: build a secondary map from `canonicalSourceFullName(fullName)` -> SymbolId for all fullNameIndex
      * entries whose raw fully-qualified name ends with "$". For OpaqueType, look up `opaqueTypeFullName + "$"` (where
      * `opaqueTypeFullName` is the OpaqueType's registered fully-qualified name, e.g. "kyo.fixtures.Meters") in this map.
      * The entry for the companion Object is found via its canonical form.
      *
      * For Class / Trait / EnumCase / Object, the existing direct `fullName + "$"` / `fullName.dropRight(1)`
      * lookup continues to work because TASTy-decoded Objects are always registered at the canonical
      * fully-qualified name with the "$" suffix and fullNameIndex has a direct entry.
      */
    private def buildCompanionIndex(
        symbols: Chunk[Tasty.Symbol],
        fullNameIndex: Dict[String, SymbolId]
    )(using AllowUnsafe): Dict[SymbolId, SymbolId] =
        val b            = DictBuilder.init[SymbolId, SymbolId]
        val idToFullName = new java.util.HashMap[Int, String](fullNameIndex.size * 2)
        // For OpaqueType: map from `canonicalSourceFullName(fullName-with-dollar) + "$"` -> Int (sid.value).
        // This covers companion Objects registered under mangled fully-qualified names that Rule 1 normalizes
        // to the OpaqueType's fully-qualified name. The key stored is `opaqueTypeFullName + "$"` (the lookup key).
        // Uses Integer (boxed) so java.util.HashMap.get returns null for missing entries.
        val canonDollarToSidValue = new java.util.HashMap[String, Integer](fullNameIndex.size * 2)
        fullNameIndex.foreach { (fullName, sid) =>
            discard(idToFullName.put(sid.value, fullName))
            if fullName.endsWith("$") then
                val canon = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                // canon is the OpaqueType's fully-qualified name (after stripping $package$. and trailing $).
                // Store as "canonicalFullName + $" so OpaqueType can look up via its own fullName + "$".
                if canon.nonEmpty && !canonDollarToSidValue.containsKey(canon + "$") then
                    discard(canonDollarToSidValue.put(canon + "$", java.lang.Integer.valueOf(sid.value)))
            end if
        }
        var i = 0
        while i < symbols.length do
            val s        = symbols(i)
            val fullName = idToFullName.get(s.id.value)
            if fullName != null then
                if s.kind == SymbolKind.OpaqueType then
                    // For OpaqueType, normalize the fullName to its canonical form then look up the
                    // companion via two approaches:
                    // 1. Direct fullNameIndex lookup: canonicalFullName + "$" (preferred; finds TASTy-decoded Objects
                    //    registered at the canonical key, e.g. "kyo.Maybe$" -> Symbol.Object(Maybe)).
                    // 2. Canonical-map fallback: for OpaqueTypes whose companion is only reachable via
                    //    a mangled fully-qualified name (e.g. "kyo.fixtures.FixtureClasses$package$.Meters$" -> Meters
                    //    companion), the direct lookup returns Absent and we fall back to
                    //    canonDollarToSidValue which maps canonical_key -> SymbolId.
                    val canonicalFullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(fullName)
                    val directKey         = if canonicalFullName.nonEmpty then canonicalFullName + "$" else fullName + "$"
                    val directMatch       = fullNameIndex.get(directKey)
                    directMatch match
                        case Maybe.Present(cid) =>
                            // Direct match: TASTy-decoded companion at canonical "X$" key.
                            discard(b.add(s.id, cid))
                        case Maybe.Absent =>
                            // Fallback: canonical-map lookup for mangled fully-qualified name companions.
                            val cidBoxed = canonDollarToSidValue.get(directKey)
                            if cidBoxed != null then discard(b.add(s.id, SymbolId(cidBoxed.intValue())))
                    end match
                else
                    val companionFullName: Maybe[String] =
                        if s.kind == SymbolKind.Class || s.kind == SymbolKind.Trait
                            || s.kind == SymbolKind.EnumCase
                        then
                            Maybe(fullName + "$")
                        else if s.kind == SymbolKind.Object then
                            Maybe(if fullName.endsWith("$") then fullName.dropRight(1) else fullName)
                        else Maybe.Absent
                    companionFullName match
                        case Maybe.Present(cfn) =>
                            fullNameIndex.get(cfn) match
                                case Maybe.Present(cid) => discard(b.add(s.id, cid))
                                case Maybe.Absent       => ()
                        case Maybe.Absent => ()
                    end match
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
      * Stdlib TASTy TypeAlias/OpaqueType symbols that genuinely have absent bodies still produce an
      * UnknownType entry under SoftFail. The clean-load invariant applies to file-level errors
      * (CorruptedFile, MalformedSection), not to per-symbol absent-type errors which are a separate
      * concern. Any stdlib symbol with a truly absent body was already implicitly broken; surfacing
      * it via classpath.errors is the right signal.
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
      * and decodes cleanly, extract javaMetadata from the classSymbol and populate fr.companionJavaMeta for every top-level fully-qualified name in the file.
      * The companion decode is always soft-fail; a missing or malformed .class never fails the TASTy decode.
      *
      * Entry paths may be in `"jar!/entry"` format or plain filesystem paths. JAR companion reads are also attempted in `"jar!/entry"` format.
      */
    private def readAndDecodeTastyFile(
        file: String,
        mode: Tasty.ErrorMode,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame): FileResult < (Sync & Abort[TastyError]) =
        Abort.run[TastyError](
            readEntryBytes(file).map { span =>
                Sync.Unsafe.defer {
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(span.size.toLong)
                    // toArrayUnsafe: Span[Byte] boundary -- decodeTastyBytes (via ByteView) requires Array[Byte].
                    Abort.get(decodeTastyBytes(file, span.toArrayUnsafe, nextGlobalId))
                }
            }
        ).map {
            case Result.Success(fr) =>
                // Attempt companion .class decode in soft-fail mode.
                // For jar!/entry paths, the companion is also inside the jar (jar!/entryBase.class).
                val companionPath =
                    val jarSep = file.indexOf("!/")
                    if jarSep > 0 then
                        val jarPath   = file.substring(0, jarSep)
                        val entryName = file.substring(jarSep + 2).stripSuffix(".tasty") + ".class"
                        s"$jarPath!/$entryName"
                    else
                        file.stripSuffix(".tasty") + ".class"
                    end if
                end companionPath
                val companionExistsEffect: Boolean < Sync =
                    val jarSep = companionPath.indexOf("!/")
                    if jarSep > 0 then
                        // For jar entries, try reading and treat IOException as absent.
                        Sync.defer(true) // attempt the read; soft-fail handles the error
                    else
                        Abort.recover[FileException](_ => false)(Path.runReadOnly(Path(companionPath).exists))
                    end if
                end companionExistsEffect
                companionExistsEffect.map { exists =>
                    if !exists then fr
                    else
                        Abort.run[TastyError](
                            readEntryBytes(companionPath).map { classSpan =>
                                Sync.Unsafe.defer {
                                    // Unsafe: ClassfileUnpickler reads an immutable byte array inside a Sync.Unsafe.defer block; no suspension required.
                                    // toArrayUnsafe: Span[Byte] boundary -- ClassfileUnpickler (via ByteView) requires Array[Byte].
                                    Abort.get(ClassfileUnpickler.read(classSpan.toArrayUnsafe, fr.arena, nextGlobalId))
                                }
                            }
                        ).map {
                            case Result.Success(cfResult) =>
                                // cfResult.classSymbol is now LoadingSymbol.Materialising; use its javaMetadata directly.
                                cfResult.classSymbol.javaMetadata match
                                    case Maybe.Present(meta) =>
                                        // Populate companionJavaMeta for all top-level symbols in this file.
                                        // fullNameSymbols carries each TASTy partial symbol that has a fully-qualified name; we
                                        // populate all since a single .tasty file may declare exactly one top-level class.
                                        fr.fullNameSymbols.foreach { symbol =>
                                            fr.companionJavaMeta(symbol.id.toLong) = meta
                                        }
                                    case Maybe.Absent => ()
                                end match
                                fr
                            case _: Result.Failure[TastyError] @unchecked | _: Result.Panic => fr
                        }
                }
            case Result.Failure(err: TastyError) =>
                // Replace the placeholder path "<byte view>" produced by TastyHeader.read
                // with the actual on-disk file path so classpath.errors carries the real filename.
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
        }

    /** Produce an empty FileResult carrying a single error (soft-fail path). */
    private def emptyFileResultWithError(file: String, err: TastyError): FileResult =
        FileResult(
            Span.empty[String],
            Span.empty[LoadingSymbol.Materialising],
            Chunk.empty,
            TypeArena.canonical(),
            Seq(err),
            Chunk.empty[Nothing],
            mutable.LongMap.empty[Chunk[Tasty.Type]],
            mutable.LongMap.empty[Chunk[LoadingSymbol.Materialising]],
            mutable.LongMap.empty[Tasty.Type],
            mutable.LongMap.empty[String],
            mutable.LongMap.empty[Tasty.Position],
            mutable.LongMap.empty[Tasty.SourceRange],
            mutable.LongMap.empty[LoadingSymbol.Materialising],
            mutable.LongMap.empty[(Int, Int)],
            Array.empty[Byte],
            0,
            Array.empty[Tasty.Name],
            scala.collection.immutable.IntMap.empty[LoadingSymbol.Materialising],
            mutable.HashMap.empty[Int, String],
            mutable.LongMap.empty[mutable.ArrayBuffer[Tasty.Annotation]],
            mutable.LongMap.empty[Tasty.Java.Metadata],
            Array.empty[Byte],
            mutable.LongMap.empty[Chunk[(Int, Tasty.Type)]]
        )

    /** Time a synchronous Result-returning computation, adding elapsed nanoseconds to `counter`.
      *
      * `t0` is captured before `v` evaluates; the delta covers exactly the unpickler's execution. The by-name
      * parameter captures the body so the timing brackets the actual call rather than just the construction
      * of the Result value.
      *
      * Requires AllowUnsafe because UnsafeCounter.add() is an unsafe operation.
      */
    private def timed[A](counter: kyo.stats.internal.UnsafeCounter)(v: => Result[TastyError, A])(using AllowUnsafe): Result[TastyError, A] =
        val t0     = java.lang.System.nanoTime()
        val result = v
        counter.add(java.lang.System.nanoTime() - t0)
        result
    end timed

    /** Decode TASTy bytes into a FileResult (fullName-symbol pairs + arena). */
    private def decodeTastyBytes(
        file: String,
        bytes: Array[Byte],
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame, AllowUnsafe): Result[TastyError, FileResult] =
        val view  = ByteView(bytes)
        val arena = new TypeArena
        for
            _        <- timed(TastyPerfStats.tastyHeaderNs)(TastyHeader.read(view))
            names    <- timed(TastyPerfStats.nameUnpicklerNs)(NameUnpickler.readUnsafeResult(view))
            sections <- timed(TastyPerfStats.sectionIndexNs)(SectionIndex.readUnsafe(view, names))
            attrs <- timed(TastyPerfStats.attributeUnpicklerNs)(sections.get(TastyFormat.AttributesSection) match
                case Present((offset, length)) =>
                    val attrView = view.subView(offset, offset + length)
                    AttributeUnpickler.readUnsafe(attrView, names)
                case Absent =>
                    Result.Success(FileAttributes.default))
            pass1Result <- timed(TastyPerfStats.astPass1Ns)(sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1Unsafe(astView, names, attrs, arena, nextGlobalId)
                case Absent =>
                    // no cursor: missing section detected at orchestration level, before stream access
                    Result.Failure(TastyError.MalformedSection("ASTs", s"$file: ASTs section not found", 0L)))
            commentsBySymbol <- timed(TastyPerfStats.commentsUnpicklerNs)(sections.get(TastyFormat.CommentsSection) match
                case Present((offset, length)) =>
                    val commentsView = view.subView(offset, offset + length)
                    CommentsUnpickler.read(commentsView, pass1Result.addrMap, pass1Result.sectionOffset)
                case Absent =>
                    Result.Success(mutable.LongMap.empty[String]))
            positions <- timed(TastyPerfStats.positionsUnpicklerNs)(sections.get(TastyFormat.PositionsSection) match
                case Present((offset, length)) =>
                    val posView = view.subView(offset, offset + length)
                    PositionsUnpickler.read(posView, pass1Result.addrMap, attrs.sourceFile, pass1Result.sectionOffset)
                case Absent =>
                    Result.Success((mutable.LongMap.empty[Tasty.Position], mutable.LongMap.empty[Tasty.SourceRange])))
        yield
            val (positionsBySymbol, declarationRangesBySymbol) = positions
            // Retain the Positions section's own bytes (a copy, not the whole-file shared array) so
            // readSpans can decode it lazily at first occurrence-index query, without keeping the
            // Positions section's decode context (typeSession, addrMap) alive past load.
            val positionsSectionBytes: Array[Byte] = sections.get(TastyFormat.PositionsSection) match
                case Present((offset, length)) => java.util.Arrays.copyOfRange(bytes, offset, offset + length)
                case Absent                    => Array.empty[Byte]
            // computeFullName walks the ownerBySymbol chain to build the dotted fully-qualified name.
            val ownerBySymbol = pass1Result.ownerBySymbol
            // Accumulate the (fullName, symbol) associations as two aligned arrays: no per-pair Tuple2 and no
            // builder. Over-allocate to the symbol count, fill the prefix with the symbols that have a
            // fully-qualified name, then trim and wrap as Span. fullNameKeys(i) names fullNameSymbols(i).
            val srcSymbols = pass1Result.symbols
            val keys       = new Array[String](srcSymbols.length)
            val syms       = new Array[LoadingSymbol.Materialising](srcSymbols.length)
            var named      = 0
            srcSymbols.foreach { symbol =>
                val fullName = computeFullName(symbol, ownerBySymbol)
                if fullName.nonEmpty then
                    keys(named) = fullName
                    syms(named) = symbol
                    named += 1
                end if
            }
            FileResult(
                Span.fromUnsafe(java.util.Arrays.copyOf(keys, named)),
                Span.fromUnsafe(java.util.Arrays.copyOf(syms, named)),
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
                declarationRangesBySymbol,
                pass1Result.ownerBySymbol,
                pass1Result.bodyDataByAddr,
                pass1Result.sectionBytes,
                pass1Result.sectionOffset,
                pass1Result.names,
                pass1Result.addrMap,
                pass1Result.unresolvedIdToFullName,
                pass1Result.annotationsBySymbol,
                // companionJavaMeta is populated by readAndDecodeTastyFile AFTER decodeTastyBytes returns,
                // so this field starts empty here and is filled in the caller.
                mutable.LongMap.empty[Tasty.Java.Metadata],
                positionsSectionBytes,
                pass1Result.parentRefAddrsBySymbol
            )
        end for
    end decodeTastyBytes

    /** Compute a dotted binary fully-qualified name from a `.class` file path.
      *
      * Handles `jrt:/` paths produced by `PlatformModuleOps.listJdkClassFiles`. Examples:
      *   - `jrt:///modules/java.base/java/lang/String.class`      -> `java.lang.String`
      *   - `jrt:///modules/java.base/java/util/Map$Entry.class`   -> `java.util.Map$Entry`
      *   - `/path/to/classes/com/example/Foo.class`               -> `com.example.Foo`
      *
      * Returns an empty string for paths that cannot be mapped (e.g., anonymous classes identified by digits-only simple name).
      */
    private def classfilePathToFullName(path: String): String =
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
        // These have no stable fully-qualified name and cannot be looked up meaningfully.
        if dotted.isEmpty then return ""
        val simpleName = dotted.substring(dotted.lastIndexOf('.') + 1)
        // Anonymous inner classes: simple name is purely numeric after the last $.
        val dollarIdx = simpleName.lastIndexOf('$')
        if dollarIdx >= 0 then
            val afterDollar = simpleName.substring(dollarIdx + 1)
            if afterDollar.nonEmpty && afterDollar.forall(_.isDigit) then return ""
        dotted
    end classfilePathToFullName

    /** Compute the dotted fully-qualified name for `symbol` by walking the ownerBySymbol chain.
      *
      * Internal fully-qualified name computation used during Pass C construction (before Classpath is fully assembled).
      */
    private def computeFullName(
        symbol: LoadingSymbol.Materialising,
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising]
    ): String =
        import Tasty.Name.asString
        val parts                                   = new scala.collection.mutable.ArrayBuffer[String]()
        var cur: Maybe[LoadingSymbol.Materialising] = Maybe.Present(symbol)
        // LongMap keyed on loading id for cycle detection.
        val visited = mutable.LongMap.empty[Unit]
        var done    = false
        while !done do
            cur match
                case Maybe.Present(c) if !visited.contains(c.id.toLong) =>
                    visited(c.id.toLong) = ()
                    val n = c.name.asString
                    if n.nonEmpty then parts.prepend(n)
                    // Package symbols store the full dotted package name (e.g. "scala.collection.immutable")
                    // in a single Name field. Walking further up through package owners would re-prepend the
                    // individual package segments that are already embedded in that flat name, doubling them.
                    // Stop here: the flat name is the entire package prefix for this symbol.
                    if c.kind == SymbolKind.Package then done = true
                    else cur = Maybe.fromOption(ownerBySymbol.get(c.id.toLong))
                case Maybe.Present(_) => done = true
                case Maybe.Absent     => done = true
            end match
        end while
        parts.filter(_.nonEmpty).mkString(".")
    end computeFullName

    /** Convert a Name (opaque String alias) to a String. */
    private def nameToString(n: Tasty.Name): String =
        import Tasty.Name.asString
        n.asString
    end nameToString

    /** Test helper: trigger a `TastyError.ClasspathBuilding` abort via a synthetic degenerate MergeState.
      *
      * Constructs a `MergeState` where one entry in `fullNameIndex` maps to a partial symbol that is NOT present in `allSyms`. As a result,
      * `finalizeMerge` cannot resolve the symbol via `symbolIdMap` (direct lookup returns -1) and the canonical fallback also
      * fails (no alias form exists). Under `ErrorMode.FailFast`, `finalizeMerge` raises `TastyError.ClasspathBuilding`.
      *
      * This method is `private[kyo]` so it is reachable from tests in `kyo.internal.*` test packages but invisible to API consumers.
      */
    private[kyo] def triggerClasspathBuildingForTest()(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        // Unsafe: synthesizes a degenerate MergeState in a test-only path; Sync.Unsafe.defer supplies
        // AllowUnsafe for the makeSymbol-and-finalizeMerge boundary; finalizeMerge itself runs further
        // Sync.Unsafe.defer blocks internally.
        Sync.Unsafe.defer {
            val state = new MergeState()
            // Insert a partial symbol into fullNameIndex but deliberately do NOT add it to allSyms.
            // This simulates the invariant-violation scenario: fullNameIndex references a symbol object that
            // was never registered in the merge pipeline's allSyms accumulator.
            val ghost = kyo.internal.tasty.symbol.Symbol.makeSymbol(
                id = Int.MaxValue, // unique id not in allSyms
                kind = SymbolKind.Class,
                flags = Tasty.Flags.empty,
                name = Tasty.Name("GhostClass")
            )
            state.fullNameIndex("test.GhostClass") = ghost
            // allSyms intentionally left empty so symbolIdMap.getOrElse(ghost.id.toLong, -1) == -1.
            // canonicalSourceFullName("test.GhostClass") == "test.GhostClass" (no mangling), so the canonical
            // fallback also returns -1. Both checks fail -> ClasspathBuilding fires under FailFast.
            finalizeMerge(state, Tasty.ErrorMode.FailFast)
        }
    end triggerClasspathBuildingForTest

    /** Test helper: produce a `Tasty.Classpath` with a `TastyError.UnresolvedReference` in `classpath.errors` via SoftFail.
      *
      * Constructs a `MergeState` where one entry in `fullNameIndex` maps to a partial symbol that is NOT present in `allSyms`. Under
      * `ErrorMode.SoftFail`, `finalizeMerge` accumulates `TastyError.UnresolvedReference("test.GhostClass", 0)` in the returned
      * `classpath.errors`. Used by tests to verify that real production code emits `UnresolvedReference`.
      *
      * This method is `private[kyo]` so it is reachable from tests in `kyo.*` test packages but invisible to API consumers.
      */
    private[kyo] def triggerUnresolvedReferenceForTest()(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        // Unsafe: synthesizes a degenerate MergeState in a test-only path; Sync.Unsafe.defer supplies
        // AllowUnsafe for the makeSymbol-and-finalizeMerge boundary.
        Sync.Unsafe.defer {
            val state = new MergeState()
            // Insert a partial symbol into fullNameIndex but deliberately do NOT add it to allSyms.
            // symbolIdMap.getOrElse(ghost.id.toLong, -1) == -1 and canonical fallback also returns -1.
            // Under SoftFail: accumulates TastyError.UnresolvedReference("test.GhostFullName", 0) in classpath.errors.
            val ghost = kyo.internal.tasty.symbol.Symbol.makeSymbol(
                id = Int.MaxValue,
                kind = SymbolKind.Class,
                flags = Tasty.Flags.empty,
                name = Tasty.Name("GhostFullName")
            )
            state.fullNameIndex("test.GhostFullName") = ghost
            finalizeMerge(state, Tasty.ErrorMode.SoftFail)
        }
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
    private[kyo] def coldLoadBinding(
        roots: Seq[String],
        mode: Tasty.ErrorMode,
        cacheDir: Maybe[String],
        concurrency: Int = java.lang.Runtime.getRuntime.availableProcessors().max(1)
    )(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        // ALWAYS probe each root for a bundled snapshot before cold-loading.
        // Partition roots into bundled (probe hit) and cold (probe miss or non-jar).
        // SoftFail: DigestMismatch from a stale snapshot is caught and the root is cold-loaded.
        // FailFast: DigestMismatch propagates as Abort[TastyError].
        probeAndLoadBundled(roots, mode).map { (bundledCp, coldRoots) =>
            val coldLoad: Binding < (Sync & Async & Scope & Abort[TastyError]) =
                cacheDir match
                    case Maybe.Absent =>
                        initWithBodies(coldRoots, mode, concurrency).map {
                            (coldCp, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                Binding(
                                    merged,
                                    Maybe.Present(DecodeContext.fresh(
                                        bodyStore,
                                        positionsStore,
                                        declarationRangeStore,
                                        parentOccurrenceStore
                                    ))
                                )
                        }
                    case Maybe.Present(dir) =>
                        import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
                        import kyo.internal.tasty.snapshot.SnapshotReader
                        import kyo.internal.tasty.snapshot.SnapshotWriter
                        Abort.run[TastyError](SnapshotDigest.compute(coldRoots)).map {
                            case Result.Failure(_) | Result.Panic(_) =>
                                // Digest failed (e.g. browser platform): fall through to cold load.
                                initWithBodies(coldRoots, mode, concurrency).map {
                                    (coldCp, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
                                        val merged = if bundledCp.symbols.isEmpty then coldCp
                                        else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                        Binding(
                                            merged,
                                            Maybe.Present(DecodeContext.fresh(
                                                bodyStore,
                                                positionsStore,
                                                declarationRangeStore,
                                                parentOccurrenceStore
                                            ))
                                        )
                                }
                            case Result.Success(digest) =>
                                val hexDigest    = SnapshotDigest.toHexString(digest)
                                val snapshotPath = s"$dir/$hexDigest.krfl"
                                Abort.recover[FileException](_ => false)(Path.runReadOnly(Path(snapshotPath).exists)).map { exists =>
                                    if exists then
                                        Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath)).map {
                                            case Result.Success(coldCp) =>
                                                // Snapshot load: body store is empty; bodyTree returns Absent.
                                                val merged = if bundledCp.symbols.isEmpty then coldCp
                                                else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                Binding(merged, Maybe.Present(DecodeContext.fresh()))
                                            case Result.Failure(_) | Result.Panic(_) =>
                                                // Snapshot unreadable; fall through to cold load.
                                                initWithBodies(coldRoots, mode, concurrency).map {
                                                    (coldCp, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
                                                        val merged = if bundledCp.symbols.isEmpty then coldCp
                                                        else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                        Binding(
                                                            merged,
                                                            Maybe.Present(DecodeContext.fresh(
                                                                bodyStore,
                                                                positionsStore,
                                                                declarationRangeStore,
                                                                parentOccurrenceStore
                                                            ))
                                                        )
                                                }
                                        }
                                    else
                                        initWithBodies(coldRoots, mode, concurrency).map {
                                            (coldCp, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
                                                Abort.run[TastyError](SnapshotWriter.write(coldCp, dir, digest)).andThen {
                                                    val merged = if bundledCp.symbols.isEmpty then coldCp
                                                    else BundledSnapshotProbe.mergePartialInto(bundledCp, coldCp)
                                                    Binding(
                                                        merged,
                                                        Maybe.Present(DecodeContext.fresh(
                                                            bodyStore,
                                                            positionsStore,
                                                            declarationRangeStore,
                                                            parentOccurrenceStore
                                                        ))
                                                    )
                                                }
                                        }
                                }
                        }
            // If no cold roots remain (all roots had bundled snapshots), return the merged bundled classpath directly.
            if coldRoots.isEmpty then Binding(bundledCp, Maybe.Present(DecodeContext.fresh()))
            else coldLoad
        }
    end coldLoadBinding

    /** Probe each root for a bundled snapshot.
      *
      * For each root: call BundledSnapshotProbe.probe. On a hit, decode the snapshot bytes into a partial Classpath and merge it into
      * the running accumulator (remap-at-merge). On a miss, add the root to the coldRoots list. DigestMismatch handling follows
      * the error mode: SoftFail silently falls back to cold load; FailFast propagates the error.
      *
      * Returns a tuple of (mergedBundledClasspath, coldRoots). The caller cold-loads coldRoots and merges the result with
      * mergedBundledClasspath.
      */
    private def probeAndLoadBundled(
        roots: Seq[String],
        mode: Tasty.ErrorMode
    )(using Frame): (Tasty.Classpath, Seq[String]) < (Sync & Scope & Abort[TastyError]) =
        import kyo.internal.tasty.snapshot.SnapshotReader
        var bundled: Tasty.Classpath = Tasty.Classpath.empty
        val coldRoots                = scala.collection.mutable.ArrayBuffer.empty[String]
        Kyo.foreach(Chunk.from(roots)) { root =>
            val probeResult: Maybe[Array[Byte]] < (Sync & Scope & Abort[TastyError]) =
                if mode == Tasty.ErrorMode.SoftFail then
                    Abort.run[TastyError](BundledSnapshotProbe.probe(root)).map {
                        case Result.Success(r)                                => r
                        case Result.Failure(_: TastyError.DigestMismatch) | _ => Maybe.Absent
                    }
                else
                    BundledSnapshotProbe.probe(root)
            probeResult.map {
                case Maybe.Absent =>
                    Sync.defer(coldRoots += root)
                case Maybe.Present(snapshotBytes) =>
                    Abort.run[TastyError](SnapshotReader.readFromBytes(snapshotBytes, root)).map {
                        case Result.Success(partial) =>
                            Sync.defer {
                                bundled = BundledSnapshotProbe.mergePartialInto(bundled, partial)
                            }
                        case Result.Failure(_) | Result.Panic(_) =>
                            // Snapshot bytes unreadable; fall back to cold load for this root.
                            Sync.defer(coldRoots += root)
                    }
            }
        }.andThen(Sync.defer((bundled, coldRoots.toSeq)))
    end probeAndLoadBundled

    /** Load a Binding from in-memory pickles.
      *
      * Used by Tasty.withPickles; returns a Binding with a fresh DecodeContext.
      * Pickles are decoded sequentially using an in-memory bytes map (no filesystem access).
      * The resulting DecodeContext allows Tasty.bodyTree to decode body bytes on demand
      * when the pickle bytes are still in memory.
      */
    private[kyo] def loadPickles(
        pickles: Chunk[Tasty.Pickle]
    )(using Frame): Binding < (Sync & Async & Scope & Abort[TastyError]) =
        if pickles.isEmpty then
            Sync.defer(Binding(Tasty.Classpath.empty, Maybe.Present(DecodeContext.fresh())))
        else
            val indexed: Seq[(String, Array[Byte])] =
                pickles.toSeq.zipWithIndex.map { (p, i) =>
                    (s"pickle://${p.uuid.replace(':', '_')}/$i.tasty", p.bytes.toArray)
                }
            val roots                              = indexed.map(_._1)
            val bytesMap: Map[String, Array[Byte]] = indexed.toMap
            initWithBodiesFromBytesMap(roots, bytesMap).map {
                (classpath, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
                    Binding(
                        classpath,
                        Maybe.Present(DecodeContext.fresh(bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore))
                    )
            }
    end loadPickles

    /** Private bridge for loadPickles: runs the full Phase A/B/C pipeline against an in-memory bytes map.
      *
      * Roots are treated as always-existing (no Path.exists check). Bytes are served from `bytesMap`. The `list` operation returns
      * the full list of roots (all are "files" in the map). Used only by `loadPickles`.
      */
    private def initWithBodiesFromBytesMap(
        roots: Seq[String],
        bytesMap: Map[String, Array[Byte]]
    )(using
        Frame
    ): (
        Tasty.Classpath,
        java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody],
        java.util.concurrent.ConcurrentHashMap[
            Int,
            Span[Byte]
        ],
        java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange],
        java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]
    ) < (Sync & Async & Scope & Abort[TastyError]) =
        // Unsafe: ConcurrentHashMap allocation for body store; same pattern as DecodeContext.fresh().
        // Sync.Unsafe.defer supplies AllowUnsafe for the bare-Java mutable allocation; the resulting
        // ConcurrentHashMap is shared across the Phase A/B pipeline via runPhaseABFromBytesMap.
        Sync.Unsafe.defer((
            new java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody](),
            new java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]](),
            new java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange](),
            new java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]()
        )).map { (bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore) =>
            // All roots exist (they are in-memory keys). No existence check needed.
            // No pool for in-memory reads (no jar mmap needed).
            runPhaseABFromBytesMap(
                roots,
                bytesMap,
                Maybe.Present(bodyStore),
                Maybe.Present(positionsStore),
                Maybe.Present(declarationRangeStore),
                Maybe.Present(parentOccurrenceStore)
            ).map { classpath =>
                (classpath, bodyStore, positionsStore, declarationRangeStore, parentOccurrenceStore)
            }
        }
    end initWithBodiesFromBytesMap

    /** Phase A/B/C pipeline variant for in-memory bytes maps. Used by loadPickles. */
    private def runPhaseABFromBytesMap(
        roots: Seq[String],
        bytesMap: Map[String, Array[Byte]],
        bodyStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]] = Maybe.Absent,
        positionsStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]]] = Maybe.Absent,
        declarationRangeStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange]] = Maybe.Absent,
        parentOccurrenceStoreOutput: Maybe[java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]] = Maybe.Absent
    )(using Frame): Tasty.Classpath < (Sync & Async & Abort[TastyError]) =
        val decodeConcurrency = 1
        val rootCount         = roots.size.max(1)
        val entryCap          = decodeConcurrency * 4
        val resultCap         = decodeConcurrency * 2
        val numShards         = 128
        val mergeState        = new MergeState()
        // Pipeline-launch boundary: a single Sync.Unsafe.defer scopes the AllowUnsafe proof for
        // the global symbol-id counter, the nextGlobalId closure, and the timing AtomicLong slots.
        // Same pattern as runPhaseAB.
        Sync.Unsafe.defer {
            val globalSymbolIdCounter: AtomicInt.Unsafe = AtomicInt.Unsafe.init(0)
            val nextGlobalId: () => Int                 = () => globalSymbolIdCounter.getAndIncrement()
            val t_start                                 = AtomicLong.Unsafe.init(java.lang.System.nanoTime())
            val t_listEnd                               = AtomicLong.Unsafe.init(0L)
            val t_decodeEnd                             = AtomicLong.Unsafe.init(0L)
            val t_mergeEnd                              = AtomicLong.Unsafe.init(0L)
            (nextGlobalId, t_start, t_listEnd, t_decodeEnd, t_mergeEnd)
        }.map { case (nextGlobalId, t_start, t_listEnd, t_decodeEnd, t_mergeEnd) =>
            Scope.run {
                Channel.initUnscoped[(String, String)](entryCap, Access.MultiProducerMultiConsumer).map { entryCh =>
                    Channel.initUnscoped[DecodeResult](resultCap, Access.MultiProducerMultiConsumer).map { resultCh =>
                        Scope.ensure(entryCh.close.unit).andThen {
                            Scope.ensure(resultCh.close.unit).andThen {
                                // Put entries in strict `roots` order (parallelism 1), not concurrently: the
                                // single decoder consumes the channel FIFO, so a deterministic put order gives a
                                // deterministic decode/merge order and therefore deterministic symbol ids across
                                // byte-equal loads, independent of decode timing. A concurrent producer would let
                                // per-file decode-time variance (e.g. the eager parent-clause capture) reorder
                                // arrivals and shift ids run to run. In-memory reads are instant, so ordering the
                                // puts costs no parallelism (the decoder is already single).
                                val producerStage = Async.foreach(Chunk.from(roots), 1) { root =>
                                    // In-memory: all roots are .tasty "files" in the map.
                                    val entry = root
                                    Abort.run[Closed](entryCh.put((entry, ".tasty"))).unit
                                }

                                val decoderStage = Async.foreach(Chunk.fill(decodeConcurrency)(()), decodeConcurrency) { _ =>
                                    entryCh.streamUntilClosed().foreach { (entryPath, kind) =>
                                        decodeOneEntryFromBytesMap(entryPath, kind, bytesMap, nextGlobalId).map { result =>
                                            Abort.run[Closed](resultCh.put(result)).unit
                                        }
                                    }
                                }

                                val mergerStage: Unit < (Async & Abort[TastyError]) =
                                    resultCh.streamUntilClosed().foreach { result =>
                                        Sync.defer(mergeOneInto(mergeState, result))
                                    }

                                val producerWithClose: Unit < (Abort[TastyError] & Async) =
                                    producerStage
                                        .andThen(Sync.Unsafe.defer(t_listEnd.set(java.lang.System.nanoTime())))
                                        .andThen(entryCh.closeAwaitEmpty.unit)
                                val decoderWithClose: Unit < (Abort[TastyError] & Async) =
                                    decoderStage
                                        .andThen(Sync.Unsafe.defer(t_decodeEnd.set(java.lang.System.nanoTime())))
                                        .andThen(resultCh.closeAwaitEmpty.unit)
                                val mergerWithTiming: Unit < (Async & Abort[TastyError]) =
                                    mergerStage.andThen(Sync.Unsafe.defer(t_mergeEnd.set(java.lang.System.nanoTime())))

                                val stages: Chunk[Unit < (Abort[TastyError] & Async)] =
                                    Chunk(producerWithClose, decoderWithClose, mergerWithTiming)
                                Async.foreach(stages, 3) { stage =>
                                    stage
                                }.andThen(finalizeMerge(
                                    mergeState,
                                    Tasty.ErrorMode.SoftFail,
                                    bodyStoreOutput,
                                    positionsStoreOutput,
                                    declarationRangeStoreOutput,
                                    parentOccurrenceStoreOutput
                                ))
                            }
                        }
                    }
                }
            }
        }
    end runPhaseABFromBytesMap

    /** Decode one entry from an in-memory bytes map. Used by runPhaseABFromBytesMap. */
    private def decodeOneEntryFromBytesMap(
        entryPath: String,
        kind: String,
        bytesMap: Map[String, Array[Byte]],
        nextGlobalId: () => Int
    )(using Frame): DecodeResult < (Sync & Async & Abort[TastyError]) =
        val readBytes: Array[Byte] < (Sync & Abort[TastyError]) =
            bytesMap.get(entryPath) match
                case Some(b) => Sync.defer(b)
                case None    => Abort.fail(TastyError.FileNotFound(entryPath))
        Abort.run[TastyError](
            readBytes.map { bytes =>
                Sync.Unsafe.defer {
                    TastyPerfStats.entryReads.inc()
                    TastyPerfStats.bytesRead.add(bytes.length.toLong)
                    Abort.get(decodeTastyBytes(entryPath, bytes, Maybe(nextGlobalId)))
                }
            }
        ).map {
            case Result.Success(fr)              => FileResultCase(fr)
            case Result.Failure(err: TastyError) => FileResultCase(emptyFileResultWithError(entryPath, err))
            case Result.Panic(t) =>
                FileResultCase(emptyFileResultWithError(entryPath, TastyError.CorruptedFile(entryPath, 0L, t.getMessage)))
        }
    end decodeOneEntryFromBytesMap

end ClasspathOrchestrator
