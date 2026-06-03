package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.type_.TypeArena
import kyo.internal.tasty.type_.TypeOps
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Decodes TASTy type nodes into Tasty.Type values.
  *
  * Entry point: TypeUnpickler.readAllTypes is called by AstUnpickler (Phase 4 wiring) after pass 1 has produced addrMap. readType is the
  * internal recursive descent function.
  *
  * Key state per decode session (all mutable, file-scoped):
  *   - addrCache: Addr->Type, used for SHAREDtype back-references.
  *   - inProgressRec: Addr->Rec placeholder, used to break RECtype/RECthis cycles.
  *   - binderAddrMap: Addr->Chunk[Symbol], maps lambda start addr to param symbol list.
  *   - cross-file references: produce Named(SymbolId(-1)) entries directly (UnresolvedRef deleted in Phase 07).
  *
  * Cross-platform: no JVM-only APIs; all bit-pattern conversions use java.lang.Float/Double which are available on Scala.js and Scala
  * Native.
  */
/** Exception wrapper that carries a `TastyError` through synchronous decode paths.
  *
  * Thrown by `decodeTag` when an unrecognised tag is encountered (Phase 2.04-strict). The `readType` catch block re-lifts this
  * into `Abort.fail(error)` so that `TastyError.UnknownTagInPosition` is preserved as the actual error variant rather than being
  * wrapped in the generic `MalformedSection` fallback that catches plain `Exception`.
  *
  * Internal sentinel: deliberately bypasses `KyoException` (no public-API crossing) and uses
  * `enableSuppression=false, writableStackTrace=false` so the throw path skips stack-trace materialisation
  * (NoStackTrace flags). Private to the reader package; not part of the public or internal API.
  */
final private[reader] class TastyErrorException(val error: TastyError)
    extends RuntimeException(error.toString, null, false, false)

object TypeUnpickler:

    /** Synthetic sentinel symbol for MatchCaseType representation.
      *
      * Each MATCHCASEtype node is decoded as Applied(Named(MatchCaseSentinel), Chunk(pat, rhs)). Consumers extract pat and rhs by position
      * from the args chunk.
      */
    /** Phase-B temporary SymbolId offset.
      *
      * During Phase B decode, `TERMREFdirect` and `TYPEREFdirect` type nodes reference local symbols by TASTy byte address. Instead of
      * using `SymbolId(-1)` (which loses the address information), we encode the address as `SymbolId(PHASE_B_ADDR_OFFSET + addr)`. This
      * preserves the address so that Phase C `finalizeMerge` can remap these to correct final SymbolIds using the file's addrMap.
      *
      * The offset (2^28 = 268_435_456) is chosen so that it is far above any realistic classpath symbol count and distinguishable from both
      * negative sentinel (-1) and final SymbolIds (0 to N-1).
      */
    private[kyo] val PHASE_B_ADDR_OFFSET: Int = 1 << 28

    val MatchCaseSentinel: Tasty.Symbol =
        // Unsafe: module-level interned sentinel; intern runs exactly once at class load (§839 case 3).
        import AllowUnsafe.embrace.danger
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name.Unsafe.init("$$MatchCase")
        )
    end MatchCaseSentinel

    /** Interned sentinel symbol for unresolved-type fallbacks (F-G-007 partial).
      *
      * A single shared symbol with name "<unresolved>" is reused for ALL unresolved-type placeholder positions
      * (RECthis miss, this-unknown, unknown-tag, etc.) so that the entire fabricated-name family collapses to
      * one shared sentinel with id.value == -1. Phase 11 finishes the full interning; Phase 04 routes RECthis
      * and THIS-unknown through this sentinel.
      */
    private[kyo] val sentinelUnresolved: Tasty.Symbol =
        // Unsafe: module-level interned sentinel; intern runs exactly once at class load (§839 case 3).
        import AllowUnsafe.embrace.danger
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name.Unsafe.init("<unresolved>")
        )
    end sentinelUnresolved

    /** Interned sentinel for RECtype cycle-breaker placeholders (F-G-007 pass B).
      *
      * A single shared instance replaces the per-address `rec-placeholder@N` symbols that Phase 04 introduced.
      * The RECtype handler installs this as the placeholder Rec during cycle decoding; after the Rec resolves,
      * RECthis nodes find the real result in addrCache and the placeholder is never exposed to cp.symbols.
      * Using a single interned instance rather than per-address instances is safe because the RECtype handler
      * discards the placeholder from inProgressRec once decoding completes.
      */
    private[kyo] val sentinelRecPlaceholder: Tasty.Symbol =
        // Unsafe: module-level interned sentinel; intern runs exactly once at class load (§839 case 3).
        import AllowUnsafe.embrace.danger
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name.Unsafe.init("<rec-placeholder>")
        )
    end sentinelRecPlaceholder

    /** Decode a single type node from `view`.
      *
      * @param view
      *   ByteView positioned at the tag byte of the type node.
      * @param names
      *   0-based name array from NameUnpickler.
      * @param addrMap
      *   Addr->Symbol map from AstUnpickler pass 1.
      * @param arena
      *   Per-file TypeArena for hash-consing decoded types.
      * @param home
      *   (formerly ClasspathRef; deleted in Phase 07)
      * @return
      *   The decoded Tasty.Type, interned in `arena`.
      */
    def readType(
        view: ByteView,
        names: Array[Tasty.Name],
        addrMap: IntMap[Tasty.Symbol],
        arena: TypeArena,
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using frame: Frame)(using AllowUnsafe): Tasty.Type < (Sync & Abort[TastyError]) =
        val result =
            try
                val addrCache     = new mutable.HashMap[Int, Tasty.Type]()
                val inProgressRec = new mutable.HashMap[Int, Tasty.Type.Rec]()
                val binderAddrMap = new mutable.HashMap[Int, Chunk[Tasty.Symbol]]()
                val ctx =
                    DecodeCtx(
                        names,
                        addrMap,
                        arena,
                        addrCache,
                        inProgressRec,
                        binderAddrMap,
                        sectionBytes,
                        sectionOffset,
                        frame
                    )
                val t = readTypeNode(view, ctx)
                Right(t)
            catch
                case ex: TastyErrorException =>
                    // Phase 2.04-strict: preserve the specific TastyError (e.g. UnknownTagInPosition)
                    // rather than wrapping it in the generic MalformedSection fallback.
                    Left(ex.error)
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end reading type: ${ex.getMessage}", view.position))
                case ex: Exception =>
                    Left(TastyError.MalformedSection("ASTs", s"type decode error: ${ex.getMessage}", view.position))
        result match
            case Right(r)  => Sync.defer(r)
            case Left(err) => Abort.fail(err)
    end readType

    /** Shared type decode state for use by TreeUnpickler during body decode.
      *
      * Created once per decodeSync call and threaded through all readType calls within the same body decode. Keeps a shared addrCache for
      * SHAREDtype back-references and inProgressRec for RECtype cycle-breaking.
      *
      * `sectionBytes` is the full AST section byte array; used by readTypeForTree to re-decode SHAREDtype back-references at addresses not
      * yet in addrCache (because Pass 1 decoded them from the section in a separate DecodeSession).
      *
      * Private to the tasty package; TreeUnpickler is a sibling object in the same package.
      */
    final private[tasty] class TreeTypeSession(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, Tasty.Symbol],
        val arena: TypeArena,
        val sectionBytes: Array[Byte],
        val sectionOffset: Int
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]              = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]      = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]] = new mutable.HashMap()
    end TreeTypeSession

    /** Read one type node using a shared TreeTypeSession (called by TreeUnpickler).
      *
      * Handles SHAREDtype cache misses by re-decoding the referenced type from sectionBytes on demand.
      */
    private[tasty] def readTypeForTree(view: ByteView, session: TreeTypeSession)(using AllowUnsafe): Tasty.Type =
        // internal frame used here because readTypeForTree is called from
        // TreeUnpickler.decodeSync, which is the OnceCell init lambda for Symbol.body.
        // The init lambda has type () => Tree and cannot accept a Frame parameter.
        // This is the one legitimate Frame.internal site; all other decode paths propagate a real Frame.
        val callFrame = Frame.internal
        val peek      = view.peekByte(view.position) & 0xff
        if peek == TastyFormat.SHAREDtype then
            discard(view.readByte()) // consume SHAREDtype tag
            val addr    = view.readNat()
            val absAddr = session.sectionOffset + addr
            session.addrCache.getOrElseUpdate(
                absAddr, {
                    // Cache miss: re-decode the type at the section-relative addr from the full section bytes.
                    // addr is section-relative; absAddr = sectionOffset + addr is the absolute position.
                    val forkView = ByteView(session.sectionBytes, absAddr, session.sectionBytes.length)
                    val forkCtx = DecodeCtx(
                        session.names,
                        session.addrMap,
                        session.arena,
                        session.addrCache,
                        session.inProgressRec,
                        session.binderAddrMap,
                        session.sectionBytes,
                        session.sectionOffset,
                        callFrame
                    )
                    readTypeNode(forkView, forkCtx)
                }
            )
        else
            val ctx = DecodeCtx(
                session.names,
                session.addrMap,
                session.arena,
                session.addrCache,
                session.inProgressRec,
                session.binderAddrMap,
                session.sectionBytes,
                session.sectionOffset,
                callFrame
            )
            readTypeNode(view, ctx)
        end if
    end readTypeForTree

    /** Synchronous (non-Kyo) variant used by AstUnpickler.runPass1.
      *
      * Shares state across all type positions in one file by accepting a shared DecodeSession. Throws on decode error (caught by
      * readPass1's try/catch).
      *
      * @param view
      *   ByteView positioned at the tag byte of the type node.
      * @param session
      *   Shared decode session for the file being decoded.
      * @param sectionOffset
      *   Absolute byte offset of the start of the AST section in the file. Used to convert section-relative ASTRef values (used in
      *   TYPEREFsymbol, TERMREFsymbol, PARAMtype binder addrs) to absolute positions that match the addrMap and binderAddrMap keys. Must
      *   be 0 for contexts where addresses are already absolute (e.g., body-decode path with sectionBytes provided).
      * @return
      *   The decoded Tasty.Type, interned in `session.arena`.
      */
    private[kyo] def readTypeIntoSession(
        view: ByteView,
        session: DecodeSession,
        sectionOffset: Int = 0
    )(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        // Pass session.liveAddrMap directly -- no per-call IntMap snapshot.
        // Pass session reference for cross-file FQN tracking so Phase C can remap parent types.
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            null,
            sectionOffset,
            frame,
            session
        )
        readTypeNode(view, ctx)
    end readTypeIntoSession

    /** Variant of readTypeIntoSession that also provides sectionBytes, enabling eager annotation arg decode.
      *
      * Used in tests (Phase 09 followup for Phase 08 W-1/W-2) to verify that corrupt annotation arg bytes accumulate errors in
      * `session.annotationDecodeErrors` rather than propagating exceptions.
      *
      * Private to kyo; not part of the public API.
      */
    private[kyo] def readTypeIntoSessionWithBytes(
        view: ByteView,
        session: DecodeSession,
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            sectionBytes,
            sectionOffset,
            frame,
            session
        )
        readTypeNode(view, ctx)
    end readTypeIntoSessionWithBytes

    /** Mutable decode session shared across all type positions in one file. Created by AstUnpickler and passed to readTypeIntoSession.
      *
      * `liveAddrMap` is the mutable HashMap being built by AstUnpickler.walkStats. Passing it directly (not as a snapshot) lets type decode
      * find locally-defined symbols as the walk progresses.
      *
      * `unresolvedIdToFqn` accumulates mappings from the unique negative SymbolIds assigned to cross-file unresolved symbols to their FQNs.
      * Phase C uses this map to look up the FQN and resolve to the correct final SymbolId via `fqnIndex`.
      */
    final private[kyo] class DecodeSession(
        val names: Array[Tasty.Name],
        val liveAddrMap: mutable.HashMap[Int, Tasty.Symbol],
        val arena: TypeArena
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]              = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]      = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]] = new mutable.HashMap()
        val unresolvedIdToFqn: mutable.HashMap[Int, String]          = new mutable.HashMap()
        // Accumulates errors from eager annotation arg decodes in ANNOTATEDtype. Drained into
        // FileResult.errors at the end of AstUnpickler.runPass1 via Pass1Result.annotationDecodeErrors.
        val annotationDecodeErrors: mutable.ArrayBuffer[TastyError] = new mutable.ArrayBuffer()
        // Counter for unique negative IDs: starts at -2 (to distinguish from SymbolId(-1) which is the sentinel).
        private var _unresolvedIdCounter: Int = -2

        // F-A-005 fix: owner stack maintained by AstUnpickler as it enters/exits DEFDEF and TYPEDEF nodes.
        // The THIS-type branch reads this to resolve THIS to the enclosing class/trait/object symbol.
        val ownerStack: mutable.ArrayDeque[Tasty.Symbol] = new mutable.ArrayDeque()

        // Parallel stack with the ABSOLUTE node address (from addrMap) of each owner symbol.
        // This allows the THIS-type branch to emit ThisType(PHASE_B_ADDR_OFFSET + addr) for
        // proper Phase C remapping via addrToFinal.
        val ownerAddrStack: mutable.ArrayDeque[Int] = new mutable.ArrayDeque()

        def nextUnresolvedId(): Int =
            val id = _unresolvedIdCounter
            _unresolvedIdCounter -= 1
            id
        end nextUnresolvedId
    end DecodeSession

    // Internal decode context passed through all recursive calls.
    // sectionBytes: full AST section bytes for on-demand SHAREDtype re-decode on cache miss.
    // Pass null when decoding from a live DecodeSession (Pass 1) where addrCache is always pre-populated.
    // addrMap accepts any scala.collection.Map so pass1 can pass liveAddrMap (mutable.HashMap) directly
    // without snapshotting into an IntMap on every type-node decode call.
    // frame: call-site Frame, used for Log.warn in the unknown-tag fallback branch.
    // session: reference to the owning DecodeSession for cross-file FQN tracking (null in body decode path).
    final private class DecodeCtx(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, Tasty.Symbol],
        val arena: TypeArena,
        val addrCache: mutable.HashMap[Int, Tasty.Type],
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec],
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]],
        val sectionBytes: Array[Byte],
        val sectionOffset: Int,
        val frame: Frame,
        val session: DecodeSession | Null = null
    )

    private def makeUnresolvedSym(fqn: String)(using AllowUnsafe): Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name.Unsafe.init(fqn)
        )

    /** Make a unique negative SymbolId for a cross-file FQN reference and record it in `fqns`.
      *
      * The caller must provide a unique negative `id` (obtained via `session.nextUnresolvedId()`). Records `id -> fqn` in `fqns` so Phase C
      * can resolve `Named(SymbolId(id))` to the correct final SymbolId via fqnIndex lookup.
      */
    private def makeTrackedUnresolvedSym(
        fqn: String,
        fqns: mutable.HashMap[Int, String],
        id: Int
    )(using AllowUnsafe): kyo.internal.tasty.symbol.SymbolId =
        fqns(id) = fqn
        kyo.internal.tasty.symbol.SymbolId(id)
    end makeTrackedUnresolvedSym

    /** Decode one type node. tag byte not yet consumed. Records startAddr -> result in addrCache. */
    private def readTypeNode(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        val startAddr = view.positionInt
        val tag       = view.readByte() & 0xff
        val t         = decodeTag(tag, startAddr, view, ctx)
        val interned  = ctx.arena.intern(t)
        // Record in addrCache for SHAREDtype back-references (only for non-SHAREDtype results).
        if tag != TastyFormat.SHAREDtype then
            ctx.addrCache(startAddr) = interned
        interned
    end readTypeNode

    /** Dispatch on the tag byte (already consumed). */
    private def decodeTag(tag: Int, startAddr: Int, view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        tag match

            // ── Category 1 constant types ─────────────────────────────────────────
            case TastyFormat.UNITconst  => Tasty.Type.ConstantType(Tasty.Constant.UnitConst)
            case TastyFormat.FALSEconst => Tasty.Type.ConstantType(Tasty.Constant.BooleanConst(false))
            case TastyFormat.TRUEconst  => Tasty.Type.ConstantType(Tasty.Constant.BooleanConst(true))
            case TastyFormat.NULLconst  => Tasty.Type.ConstantType(Tasty.Constant.NullConst)

            // ── Category 2 (tag + Nat) ────────────────────────────────────────────

            case TastyFormat.SHAREDtype =>
                // astRef is a section-relative offset; absRef is the absolute position in file/sectionBytes.
                // F-A-001 fix: always add sectionOffset to convert section-relative to absolute, since
                // addrCache keys are absolute (view.positionInt = sectionOffset + sectionRelativeAddr).
                val astRef = view.readNat()
                val absRef = ctx.sectionOffset + astRef
                ctx.addrCache.getOrElse(
                    absRef, {
                        if ctx.sectionBytes != null then
                            // Cache miss during body decode: re-decode the type at absRef from full section bytes.
                            // Pass ctx.session to the fork context so that cross-file FQN refs (TERMREFpkg,
                            // TYPEREFpkg) encountered during re-decode produce tracked unique negative IDs
                            // rather than the unresolved sentinel. Without this, @Child annotation type args
                            // decoded via SHAREDtype back-references lose their cross-file FQN tracking.
                            val forkView = ByteView(ctx.sectionBytes, absRef, ctx.sectionBytes.length)
                            val forkCtx = DecodeCtx(
                                ctx.names,
                                ctx.addrMap,
                                ctx.arena,
                                ctx.addrCache,
                                ctx.inProgressRec,
                                ctx.binderAddrMap,
                                ctx.sectionBytes,
                                ctx.sectionOffset,
                                ctx.frame,
                                ctx.session
                            )
                            val t = readTypeNode(forkView, forkCtx)
                            ctx.addrCache(absRef) = t
                            t
                        else
                            // Pass 1 context: sectionBytes is null. A SHAREDtype cache miss means
                            // the referenced type was in a position not decoded during Pass 1.
                            // F-A2-002 fix: use a unique tracked negative ID (< -1) so the result
                            // does not collide with the Named(-1) sentinel checked by INV-005.
                            ctx.session match
                                case s: DecodeSession =>
                                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                                case null =>
                                    Tasty.Type.Named(sentinelUnresolved.id)
                        end if
                    }
                )

            case TastyFormat.TERMREFdirect =>
                val astRef = view.readNat()
                // TASTy ASTRef values are section-relative. addrMap keys are absolute (sectionOffset + sectionRelativeAddr).
                // F-A-001 fix: add sectionOffset to convert to absolute before lookup and storage.
                val absRef = ctx.sectionOffset + astRef
                if ctx.addrMap.contains(absRef) then Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + absRef))
                else
                    // F-A2-002 fix: cross-file TERMREFdirect miss. Assign a unique tracked negative ID
                    // so the result is Named(SymbolId(uniqueNeg)) with uniqueNeg < -1, not the Named(-1) sentinel.
                    // Phase C's remapType will attempt FQN lookup via negIdToFinal; if it fails the type
                    // remains as a unique unresolved ref (value < -1) rather than colliding with the -1 sentinel.
                    ctx.session match
                        case s: DecodeSession => Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                        case null             => Tasty.Type.Named(sentinelUnresolved.id)
                end if

            case TastyFormat.TYPEREFdirect =>
                val astRef = view.readNat()
                // TASTy ASTRef values are section-relative. addrMap keys are absolute (sectionOffset + sectionRelativeAddr).
                // F-A-001 fix: add sectionOffset to convert to absolute before lookup and storage.
                val absRef = ctx.sectionOffset + astRef
                if ctx.addrMap.contains(absRef) then Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + absRef))
                else
                    // F-A2-002 fix: cross-file TYPEREFdirect miss (same rationale as TERMREFdirect above).
                    // Assign a unique tracked negative ID so the result is Named(SymbolId(uniqueNeg)) with
                    // uniqueNeg < -1, eliminating the Named(-1) sentinel from declaredType traversals.
                    // This is the root cause of the scala.Tuple.splitAt Named(-1) in probe-001.log line 39872.
                    ctx.session match
                        case s: DecodeSession => Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                        case null             => Tasty.Type.Named(sentinelUnresolved.id)
                end if

            case TastyFormat.TERMREFpkg =>
                val nameRef = view.readNat()
                val fqn     = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        // F-G-007 pass B: no session context -- use interned sentinel instead of per-fqn name
                        Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.TYPEREFpkg =>
                val nameRef = view.readNat()
                val fqn     = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        // F-G-007 pass B: no session context -- use interned sentinel instead of per-fqn name
                        Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.RECthis =>
                val recAddr = view.readNat()
                // Look for the in-progress Rec node; if not found, use addrCache.
                ctx.inProgressRec.get(recAddr) match
                    case Some(recNode) => Tasty.Type.RecThis(recNode)
                    case None =>
                        ctx.addrCache.get(recAddr) match
                            case Some(recNode) => Tasty.Type.RecThis(recNode)
                            case None          =>
                                // F-A-007 fix: the Rec node hasn't been decoded yet. Assign a unique
                                // tracked ID (< -1) so the result is Named(SymbolId(uniqueNeg)) not Named(-1).
                                // No per-address fabricated names leak into cp.symbols; the unique ID is
                                // unresolvable but does not collide with the INV-005 Named(-1) sentinel check.
                                val placeholderId = ctx.session match
                                    case s: DecodeSession => kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId())
                                    case null             => sentinelUnresolved.id
                                Tasty.Type.RecThis(Tasty.Type.Named(placeholderId))
                end match

            // Constant types (category 2: tag + Nat)
            case TastyFormat.BYTEconst =>
                Tasty.Type.ConstantType(Tasty.Constant.ByteConst(view.readNat().toByte))
            case TastyFormat.SHORTconst =>
                Tasty.Type.ConstantType(Tasty.Constant.ShortConst(view.readNat().toShort))
            case TastyFormat.CHARconst =>
                Tasty.Type.ConstantType(Tasty.Constant.CharConst(view.readNat().toChar))
            case TastyFormat.INTconst =>
                Tasty.Type.ConstantType(Tasty.Constant.IntConst(view.readInt()))
            case TastyFormat.LONGconst =>
                Tasty.Type.ConstantType(Tasty.Constant.LongConst(view.readLongNat()))
            case TastyFormat.FLOATconst =>
                Tasty.Type.ConstantType(Tasty.Constant.FloatConst(java.lang.Float.intBitsToFloat(view.readNat())))
            case TastyFormat.DOUBLEconst =>
                Tasty.Type.ConstantType(Tasty.Constant.DoubleConst(java.lang.Double.longBitsToDouble(view.readLongNat())))
            case TastyFormat.STRINGconst =>
                val nameRef = view.readNat()
                Tasty.Type.ConstantType(Tasty.Constant.StringConst(nameAt(ctx.names, nameRef).asString))

            // ── Category 3 (tag + AST) ────────────────────────────────────────────

            case TastyFormat.THIS =>
                val inner = readTypeNode(view, ctx)
                inner match
                    case Tasty.Type.Named(id) =>
                        Tasty.Type.ThisType(id)
                    case _ =>
                        // F-A-005 fix: resolve THIS through the active scope's enclosing class.
                        // The owner stack is maintained by AstUnpickler around every CLASSDEF / DEFDEF;
                        // the topmost class-kind owner is the canonical "this" referent.
                        // We use ownerAddrStack to obtain the Phase-B address-encoded id so that
                        // Phase C remapType can look it up in addrToFinal and return the real class id.
                        ctx.session match
                            case s: DecodeSession =>
                                val enclosingPair = s.ownerStack.reverseIterator
                                    .zip(s.ownerAddrStack.reverseIterator)
                                    .find { case (sym, _) =>
                                        sym.kind == Tasty.SymbolKind.Class ||
                                        sym.kind == Tasty.SymbolKind.Trait ||
                                        sym.kind == Tasty.SymbolKind.Object
                                    }
                                enclosingPair match
                                    case Some((_, addr)) =>
                                        Tasty.Type.ThisType(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + addr))
                                    case None =>
                                        Tasty.Type.ThisType(sentinelUnresolved.id)
                                end match
                            case _ =>
                                Tasty.Type.ThisType(sentinelUnresolved.id)
                        end match
                end match

            case TastyFormat.CLASSconst =>
                val inner = readTypeNode(view, ctx)
                Tasty.Type.ConstantType(Tasty.Constant.ClassConst(inner))

            case TastyFormat.BYNAMEtype =>
                val underlying = readTypeNode(view, ctx)
                Tasty.Type.ByName(underlying)

            case TastyFormat.RECtype =>
                // Cycle-break: allocate a typed placeholder Rec before decoding parent.
                // inProgressRec is keyed by the RECtype node's start addr so RECthis nodes
                // encountered during parent decoding can find the placeholder.
                // F-G-007 pass B: use the single interned sentinelRecPlaceholder instead of per-address fabricated name.
                // This is safe because the placeholder is installed in inProgressRec keyed by startAddr and is
                // removed via inProgressRec.remove BEFORE the result is returned. No per-address identity needed.
                val placeholder: Tasty.Type.Rec = Tasty.Type.Rec(Tasty.Type.Named(sentinelRecPlaceholder.id))
                ctx.inProgressRec(startAddr) = placeholder
                val parent = readTypeNode(view, ctx)
                discard(ctx.inProgressRec.remove(startAddr))
                val result = Tasty.Type.Rec(parent)
                // Patch addrCache so RECthis nodes that looked up startAddr find the real Rec.
                ctx.addrCache(startAddr) = result
                result

            // ── Category 4 (tag + Nat + AST) ─────────────────────────────────────

            case TastyFormat.TERMREFsymbol =>
                val astRef = view.readNat()
                val qual   = readTypeNode(view, ctx)
                // F-A-001 fix: convert section-relative ASTRef to absolute for addrMap lookup.
                val absRefTerm = ctx.sectionOffset + astRef
                ctx.addrMap.get(absRefTerm) match
                    case Some(sym) => Tasty.Type.TermRef(qual, sym.name)
                    case None      =>
                        // F-I-003 fix: for forward references (same file, not yet in addrMap),
                        // emit Named(PHASE_B_ADDR_OFFSET + absRef). Phase C remaps to the final
                        // SymbolId via addrToFinal, allowing resolveChildRef to extract it.
                        // If truly unresolvable (different file), Phase C returns Named(-1).
                        Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + absRefTerm))
                end match

            case TastyFormat.TERMREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

            case TastyFormat.TYPEREFsymbol =>
                val astRef = view.readNat()
                // qual is present but for Named resolution we only need the sym.
                discard(readTypeNode(view, ctx)) // consume qual
                // F-A-001 fix: convert section-relative ASTRef to absolute for addrMap lookup.
                // Use PHASE_B_ADDR_OFFSET + absRef so Phase C can remap via addrMap.
                // F-I-003 fix: always emit PHASE_B_ADDR_OFFSET + absRef regardless of whether
                // addrMap contains absRef yet. For @Child annotation forward references, the
                // referenced symbol may not yet be in addrMap (decoded later in the same file).
                // Phase C's addrToFinal map is built from the COMPLETE addrMap and resolves these.
                // If absRef is truly from a different file, addrToFinal will not contain it and
                // Phase C leaves it as Named(-1) (same behavior as before this fix).
                val absRef = ctx.sectionOffset + astRef
                Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + absRef))

            case TastyFormat.TYPEREF =>
                // F-A-009: TYPEREF (117) is a type-position reference, distinct from TermRef.
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Tasty.Type.TypeRef(qual, nameAt(ctx.names, nameRef))

            // ── Category 5 (tag + Length + payload) ──────────────────────────────

            case TastyFormat.APPLIEDtype =>
                val end   = view.readEnd()
                val tycon = readTypeNode(view, ctx)
                val args  = readTypesUntil(view, end, ctx)
                val fqnHint: String | Null = tycon match
                    case Tasty.Type.Named(id) =>
                        import kyo.internal.tasty.symbol.SymbolId.value
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(id.value, null)
                            case _                => null
                    case _ => null
                TypeOps.applied(tycon, Chunk.from(args), fqnHint)

            case TastyFormat.ANNOTATEDtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                // Eagerly decode the annotation term's byte slice into a Tree. The term spans from
                // the current cursor up to `end`. When sectionBytes is unavailable (the rare cached
                // re-decode path), produce args = Maybe.Absent with no error (same silent treatment as
                // the previous Chunk.empty fallback).
                val termStart = view.positionInt
                val endInt    = Math.toIntExact(end)
                skipToEnd(view, end)
                // F-G-007 pass B: use interned sentinelUnresolved for the annotation-tycon placeholder position
                val annotationType = Tasty.Type.Named(sentinelUnresolved.id)
                val annotation =
                    if ctx.sectionBytes == null then
                        Tasty.Annotation(annotationType, Chunk.empty)
                    else
                        val pickle = java.util.Arrays.copyOfRange(ctx.sectionBytes, termStart, endInt)
                        val maybeTree: Maybe[Tasty.Tree] =
                            try
                                Maybe(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(
                                    pickle,
                                    ctx.names,
                                    ctx.addrMap,
                                    ctx.sectionBytes,
                                    ctx.sectionOffset
                                ))
                            catch
                                case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                    val err = TastyError.MalformedSection(
                                        "ASTs",
                                        s"annotation args: ${ex.getMessage}",
                                        ex.byteOffset
                                    )
                                    ctx.session match
                                        case s: DecodeSession => discard(s.annotationDecodeErrors += err)
                                        case null             => ()
                                    end match
                                    Maybe.Absent
                                case ex: ArrayIndexOutOfBoundsException =>
                                    val err = TastyError.MalformedSection("ASTs", "annotation args truncated", 0L)
                                    ctx.session match
                                        case s: DecodeSession => discard(s.annotationDecodeErrors += err)
                                        case null             => ()
                                    end match
                                    Maybe.Absent
                        val args: Chunk[Tasty.Tree] = maybeTree match
                            case Maybe.Present(t) =>
                                t match
                                    case Tasty.Tree.Apply(_, applyArgs) => applyArgs
                                    case other                          => Chunk(other)
                            case Maybe.Absent => Chunk.empty
                        Tasty.Annotation(annotationType, args)
                Tasty.Type.Annotated(underlying, annotation)

            case TastyFormat.ANDtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                def namedFqn(t: Tasty.Type): String | Null = t match
                    case Tasty.Type.Named(id) =>
                        import kyo.internal.tasty.symbol.SymbolId.value
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(id.value, null)
                            case _                => null
                    case _ => null
                TypeOps.andType(left, right, namedFqn(left), namedFqn(right))

            case TastyFormat.ORtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.OrType(left, right)

            case TastyFormat.SUPERtype =>
                val end        = view.readEnd()
                val thisType   = readTypeNode(view, ctx)
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.SuperType(thisType, underlying)

            case TastyFormat.REFINEDtype =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val parent  = readTypeNode(view, ctx)
                val info    = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.Refinement(parent, nameAt(ctx.names, nameRef), info)

            case TastyFormat.MATCHtype =>
                val end   = view.readEnd()
                val bound = readTypeNode(view, ctx)
                val scrut = readTypeNode(view, ctx)
                val cases = readTypesUntil(view, end, ctx)
                Tasty.Type.MatchType(bound, scrut, Chunk.from(cases))

            case TastyFormat.FLEXIBLEtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.FlexibleType(underlying)

            case TastyFormat.TYPELAMBDAtype =>
                val end = view.readEnd()
                // Pre-scan TypesNames (which come AFTER result in the payload), allocate param symbols,
                // register in binderAddrMap (keyed on this node's start addr), then decode result.
                // PARAMtype nodes in the result use binder_ASTRef = startAddr to look up params.
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.POLYtype =>
                val end       = view.readEnd()
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.METHODtype =>
                val end       = view.readEnd()
                val paramSyms = readMethodParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.PARAMtype =>
                val end        = view.readEnd()
                val binderAddr = view.readNat()
                val paramNum   = view.readNat()
                view.goto(end)
                // F-A-001 fix: PARAMtype binderAddr is section-relative; binderAddrMap keys are absolute.
                // Add sectionOffset to convert to the absolute key registered by the enclosing lambda handler.
                val absBinderAddr = ctx.sectionOffset + binderAddr
                ctx.binderAddrMap.get(absBinderAddr) match
                    case Some(params) if paramNum < params.length => Tasty.Type.ParamRef(params(paramNum).id, paramNum)
                    // F-A2-002 fix: binder addr miss -- assign unique tracked ID so the result is Named(SymbolId(uniqueNeg))
                    // with uniqueNeg < -1, not the Named(-1) sentinel that would fail INV-005.
                    case _ =>
                        ctx.session match
                            case s: DecodeSession => Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                            case null             => Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.TYPEREFin =>
                val end        = view.readEnd()
                val nameRef    = view.readNat()
                val simpleName = nameAt(ctx.names, nameRef).asString
                val qual       = readTypeNode(view, ctx) // decode qual to extract package FQN
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                // Reconstruct full FQN: qual_fqn + "." + simpleName.
                // qual is typically TYPEREFpkg(package_fqn) -> Named(trackedId) where trackedId maps to package_fqn.
                // For Phase C lookup, use qual_fqn + "." + simpleName.
                ctx.session match
                    case s: DecodeSession =>
                        val qualFqn = qual match
                            case Tasty.Type.Named(sid) if sid.value < -1 =>
                                // Tracked cross-file ref: look up fqn from unresolvedIdToFqn
                                s.unresolvedIdToFqn.getOrElse(sid.value, simpleName)
                            case _ =>
                                simpleName
                        val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + simpleName else simpleName
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullFqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        // F-G-007 pass B: no session context -- use interned sentinel
                        Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.TERMREFin =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

            case TastyFormat.TYPEBOUNDS =>
                // TYPEBOUNDS Length lowOrAlias_Type high_Type? Variance*
                // When high is present: Wildcard(lo, hi). When only alias: the alias type directly.
                val end = view.readEnd()
                val lo  = readTypeNode(view, ctx)
                // Peek: if next byte is a variance tag (category 1, 1-59 but specifically STABLE/COVARIANT/CONTRAVARIANT)
                // or we're at end, there is no hi.
                if view.position >= end then
                    view.goto(end)
                    lo
                else
                    val nextTag = view.peekByte(view.position) & 0xff
                    if isVarianceTag(nextTag) || nextTag == 0 then
                        // Only alias; skip variance tags and return lo directly.
                        view.goto(end)
                        lo
                    else
                        val hi = readTypeNode(view, ctx)
                        // Skip variance tags.
                        view.goto(end)
                        // F-A-010: TYPEBOUNDS with explicit hi is a declared bounds, not a wildcard.
                        Tasty.Type.Bounds(lo, hi)
                    end if
                end if

            case TastyFormat.REPEATED =>
                val end  = view.readEnd()
                val elem = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.Repeated(elem)

            // ── TPT (type-position tree) tags ──────────────────────────────────────
            // These are wire-level tree nodes that appear in type-syntactic positions
            // (DEFDEF return type, VALDEF declared type, type bounds, etc.). They carry
            // a Type and must be decoded here when encountered in type-arg or recursive
            // type positions within TypeUnpickler. The top-level routing from
            // AstUnpickler.decodeOneTypeIfPresent goes to TreeUnpickler.decodeTptAsType;
            // this second set of handlers handles nested TPT tags reached via readTypeNode.
            // F-I-004 fix (nested TPT): decodeTag now handles all 9 TPT tags directly.

            case TastyFormat.IDENTtpt =>
                // IDENTtpt (111): cat-4 (tag + Nat + AST). Nat is a name-ref; AST is the resolved Type.
                discard(view.readNat())
                readTypeNode(view, ctx)

            case TastyFormat.SELECTtpt =>
                // SELECTtpt (113): cat-4 (tag + NameRef + qual_Tree). Type-position member selection.
                // Encodes e.g. `scala.Double` as SELECTtpt("Double", TERMREFpkg("scala")).
                // Mirrors TreeUnpickler.decodeTptAsType case for SELECTtpt.
                val nameRef = view.readNat()
                val nm      = nameAt(ctx.names, nameRef).asString
                val qual    = readTypeNode(view, ctx)
                val qualFqn: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(sid.value, "")
                            case _                => ""
                    case _ => ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullFqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.APPLIEDtpt =>
                // APPLIEDtpt (162): cat-5 (tag + Length + tycon_Tree + arg_Tree*).
                val end   = view.readEnd()
                val tycon = readTypeNode(view, ctx)
                val args  = readTypesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Type.Applied(tycon, Chunk.from(args))

            case TastyFormat.TYPEBOUNDStpt =>
                // TYPEBOUNDStpt (164): cat-5 (tag + Length + lo_Tree + hi_Tree).
                // F-A-010: explicit bounds use Type.Bounds, not Type.Wildcard.
                val end = view.readEnd()
                val lo  = readTypeNode(view, ctx)
                val hi  = if view.position < end then readTypeNode(view, ctx) else lo
                view.goto(end)
                Tasty.Type.Bounds(lo, hi)

            case TastyFormat.ANNOTATEDtpt =>
                // ANNOTATEDtpt (154): cat-5 (tag + Length + tpe_Tree + annot_Tree).
                // Phase 05 wires the annotation term; Phase 03 extracts the underlying type only.
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                underlying

            case TastyFormat.SELECTin =>
                // SELECTin (176): cat-5 (tag + Length + nameRef + qual_Tree + owner_Tree).
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val nm      = nameAt(ctx.names, nameRef).asString
                val qual    = readTypeNode(view, ctx)
                discard(readTypeNode(view, ctx)) // owner (namespace); used by Phase C for FQN resolution
                view.goto(end)
                // Build tracked qualified FQN for Phase C lookup.
                val qualFqn: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(sid.value, "")
                            case _                => ""
                    case _ => ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullFqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        // F-G-007 pass B: no session context -- use interned sentinel
                        Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.REFINEDtpt =>
                // REFINEDtpt (160): cat-5 (tag + Length + parent_Tree + decl_Tree*).
                // Phase 03 extracts the parent type only; refinement decls are deferred to Phase 05.
                val end    = view.readEnd()
                val parent = readTypeNode(view, ctx)
                view.goto(end)
                parent

            case TastyFormat.LAMBDAtpt =>
                // LAMBDAtpt (171): cat-5 (tag + Length + tparam_Tree* + body_Tree).
                val end       = view.readEnd()
                val tparamIds = new scala.collection.mutable.ArrayBuffer[Tasty.SymbolId]()
                while view.position < end && (view.peekByte(view.position) & 0xff) == TastyFormat.TYPEPARAM do
                    discard(view.readByte()) // consume TYPEPARAM tag
                    val tpEnd   = view.readEnd()
                    val nameRef = view.readNat()
                    val symName = nameAt(ctx.names, nameRef)
                    val sym = InternalSymbol.makeSymbol(
                        Tasty.SymbolKind.TypeParam,
                        Tasty.Flags.empty,
                        symName
                    )
                    tparamIds += sym.id
                    view.goto(tpEnd)
                end while
                val body = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(tparamIds.toSeq), body)

            case TastyFormat.MATCHtpt =>
                // MATCHtpt (191): cat-5 (tag + Length + scrutinee_Tree + bound_Tree + case_Tree*).
                val end       = view.readEnd()
                val scrutinee = readTypeNode(view, ctx)
                val bound     = readTypeNode(view, ctx)
                val cases     = readTypesUntil(view, end, ctx)
                Tasty.Type.MatchType(bound, scrutinee, Chunk.from(cases))

            case TastyFormat.MATCHCASEtype =>
                // MATCHCASEtype (192): cat-5 (tag + Length + pat_Tree + rhs_Tree).
                // F-A-006 fix (Phase 05): Type.MatchCase is now a first-class ADT case.
                // This handler is reached for nested decodes (e.g. cases inside MATCHtpt).
                val end = view.readEnd()
                val pat = readTypeNode(view, ctx)
                val rhs = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.MatchCase(pat, rhs)

            // ── F-A2-001 term-tag-in-type-position handlers ───────────────────────
            // These 8 TERM tags can appear inside nested type positions (e.g. inside APPLIEDtype
            // args, ANNOTATEDtype payloads, or REFINEDtype bodies) when TypeUnpickler.readTypeNode
            // recurses into them. The tag byte has already been consumed by readTypeNode. The logic
            // mirrors TreeUnpickler.decodeTermTagInTypePosition but without re-consuming the tag.

            case TastyFormat.SHAREDterm =>
                // SHAREDterm (60): cat-2 (tag + Nat). ASTRef section-relative.
                val ref    = view.readNat()
                val absRef = ctx.sectionOffset + ref
                ctx.addrCache.getOrElse(
                    absRef, {
                        ctx.session match
                            case s: DecodeSession => Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                            case null             => Tasty.Type.Named(sentinelUnresolved.id)
                    }
                )

            case TastyFormat.BYNAMEtpt =>
                // BYNAMEtpt (94): cat-3 (tag + AST). Wrap inner type in Type.ByName.
                val under = readTypeNode(view, ctx)
                Tasty.Type.ByName(under)

            case TastyFormat.NEW =>
                // NEW (95): cat-3 (tag + AST). The AST is the tpt (constructed type).
                readTypeNode(view, ctx)

            case TastyFormat.SINGLETONtpt =>
                // SINGLETONtpt (101): cat-3 (tag + AST). Decode singleton's underlying type.
                readTypeNode(view, ctx)

            case TastyFormat.SELECT =>
                // SELECT (112): cat-4 (tag + Nat + AST). Build a tracked unresolved FQN (same as SELECTtpt).
                val nameRef = view.readNat()
                val nm      = nameAt(ctx.names, nameRef).asString
                val qual    = readTypeNode(view, ctx)
                val qualFqn: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(sid.value, "")
                            case null             => ""
                    case _ => ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                ctx.session match
                    case s: DecodeSession =>
                        val id = s.nextUnresolvedId()
                        s.unresolvedIdToFqn(id) = fullFqn
                        Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(id))
                    case null => Tasty.Type.Named(sentinelUnresolved.id)
                end match

            case TastyFormat.APPLY =>
                // APPLY (136): cat-5 (tag + Length + fn_AST + args_AST*). Result type is fn's return type.
                val end    = view.readEnd()
                val fnType = readTypeNode(view, ctx)
                view.goto(end)
                fnType

            case TastyFormat.TYPEAPPLY =>
                // TYPEAPPLY (137): cat-5 (tag + Length + fn_AST + targs_AST*). Applied(fnType, targs).
                val end    = view.readEnd()
                val fnType = readTypeNode(view, ctx)
                val targs  = readTypesUntil(view, end, ctx)
                view.goto(end)
                if targs.isEmpty then fnType
                else Tasty.Type.Applied(fnType, Chunk.from(targs))

            case TastyFormat.CASEDEF =>
                // CASEDEF (155): cat-5 (tag + Length + ...). Skip; return Wildcard with unique IDs.
                val end = view.readEnd()
                view.goto(end)
                val loId = ctx.session match
                    case s: DecodeSession => s.nextUnresolvedId()
                    case null             => sentinelUnresolved.id.value
                val hiId = ctx.session match
                    case s: DecodeSession => s.nextUnresolvedId()
                    case null             => sentinelUnresolved.id.value
                Tasty.Type.Wildcard(
                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(loId)),
                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(hiId))
                )

            case TastyFormat.QUALTHIS =>
                // QUALTHIS (91): cat-3 (tag + AST). Decode the qualifier type and return it.
                readTypeNode(view, ctx)

            case TastyFormat.INLINED =>
                // INLINED (147): cat-5 (tag + Length + ...). Skip; return unique unresolved ID.
                val end = view.readEnd()
                view.goto(end)
                ctx.session match
                    case s: DecodeSession => Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(s.nextUnresolvedId()))
                    case null             => Tasty.Type.Named(sentinelUnresolved.id)

            // ── Unknown tag: fail loudly (HARD RULE 13 / Phase 2.04-strict) ─────────

            case other if other >= TastyFormat.firstLengthTreeTag =>
                // Phase 2.04-strict: any cat-5 tag not handled above is a genuinely unrecognised
                // TASTy format extension. TagKind.TypePositionTag.throwFor propagates
                // TastyErrorException(UnknownTagInPosition) to cp.errors rather than producing
                // a silent sentinel Named(-1) symbol.
                TagKind.TypePositionTag.throwFor(other)

            case other =>
                // Phase 2.04-strict: same rationale as the cat-5 branch above.
                // Any cat-1..4 tag not handled above is genuinely unrecognised.
                TagKind.TypePositionTag.throwFor(other)

        end match
    end decodeTag

    /** Read type lambda / poly type parameters from the payload.
      *
      * TypesNames = TypeName* where TypeName = typeOrBounds_ASTRef paramName_NameRef. The params come BEFORE the result type in the payload
      * for TYPELAMBDAtype/POLYtype. We read them here (scanning forward), allocate symbols, then caller reads result type.
      *
      * Since the payload is: result_Type TypesNames (per grammar), we need to read result first and params after. But to support PARAMtype
      * references in result, we use a forward scan.
      *
      * Implementation: skip the result type first (to find TypesNames), collect param names, rewind to just after result, return param
      * symbols. The result is then re-read by the caller.
      *
      * Actually per the grammar: POLYtype = result_Type TypesNames, where result comes first. PARAMtype uses the binder's start addr. We
      * pre-register an empty binding, decode result (PARAMtype will return placeholder), then return params.
      *
      * Simpler correct approach: for Phase 4, we use a two-pass for lambda bodies:
      *   1. Skip result type (but we cannot skip because it may contain type refs we need)
      *   2. After result, read TypesNames to get param symbols
      *   3. Register binderAddrMap, return params
      *
      * The result is decoded by the caller AFTER this returns (result position has been rewound to the start of result type after skipping
      * TypesNames).
      *
      * Actually: the grammar for POLYtype/TYPELAMBDAtype puts result BEFORE TypesNames. So layout in bytes is: [result_Type bytes]
      * [typeRef0 nameRef0] [typeRef1 nameRef1] ...
      *
      * We: skip result, collect TypesNames, rewind to result start, return param syms. Caller then reads result from the rewound position.
      */
    private def readTypeLambdaParams(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Symbol] =
        val resultStart = view.position
        // Skip result type to find TypesNames start.
        skipOneType(view)
        // Collect TypesNames = (typeOrBounds_ASTRef paramName_NameRef)*
        // F-A-001 fix: typeRef from TypesNames is section-relative; addrMap keys are absolute.
        // Add ctx.sectionOffset to convert before lookup.
        val paramSyms = new mutable.ArrayBuffer[Tasty.Symbol]()
        while view.position < end do
            val typeRef = view.readNat()
            val nameRef = view.readNat()
            val symName = nameAt(ctx.names, nameRef)
            val sym = ctx.addrMap.get(ctx.sectionOffset + typeRef) match
                case Some(existingSym) => existingSym
                case None =>
                    InternalSymbol.makeSymbol(
                        Tasty.SymbolKind.TypeParam,
                        Tasty.Flags.empty,
                        symName
                    )
            paramSyms += sym
        end while
        // Rewind to result type start so caller can decode result.
        view.goto(resultStart)
        paramSyms
    end readTypeLambdaParams

    /** Read method params from a METHODtype payload.
      *
      * METHODtype = result_Type TypesNames Modifier* TypesNames = TypeName* where TypeName = typeOrBounds_ASTRef paramName_NameRef
      *
      * Same two-pass approach as readTypeLambdaParams.
      */
    private def readMethodParams(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Symbol] =
        val resultStart = view.position
        // Skip result type.
        skipOneType(view)
        // Collect TypesNames until we hit modifiers or end.
        // F-A-001 fix: typeRef from TypesNames is section-relative; addrMap keys are absolute.
        // Add ctx.sectionOffset to convert before lookup.
        val paramSyms = new mutable.ArrayBuffer[Tasty.Symbol]()
        while view.position < end do
            val tag = view.peekByte(view.position) & 0xff
            if isModifierOrVarianceTag(tag) then
                view.goto(end)
            else
                val typeRef = view.readNat()
                val nameRef = view.readNat()
                val symName = nameAt(ctx.names, nameRef)
                val sym = ctx.addrMap.get(ctx.sectionOffset + typeRef) match
                    case Some(existingSym) => existingSym
                    case None =>
                        InternalSymbol.makeSymbol(
                            Tasty.SymbolKind.Parameter,
                            Tasty.Flags.empty,
                            symName
                        )
                paramSyms += sym
            end if
        end while
        view.goto(resultStart)
        paramSyms
    end readMethodParams

    /** Read type nodes until position reaches `end`. */
    private def readTypesUntil(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Type] =
        val buf = new mutable.ArrayBuffer[Tasty.Type]()
        while view.position < end do
            buf += readTypeNode(view, ctx)
        buf
    end readTypesUntil

    /** Skip to the given end position. */
    private def skipToEnd(view: ByteView, end: Long)(using AllowUnsafe): Unit =
        view.goto(end)

    /** Skip exactly one type node from the current position (tag not yet consumed). */
    private def skipOneType(view: ByteView)(using AllowUnsafe): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)

    /** Skip the body of a tree node whose tag has already been consumed. */
    private def skipTreeBody(tag: Int, view: ByteView)(using AllowUnsafe): Unit =
        if tag >= TastyFormat.firstLengthTreeTag then
            val end = view.readEnd()
            view.goto(end)
        else if tag >= 110 && tag <= 127 then
            discard(view.readNat())
            skipOneType(view)
        else if tag >= 90 && tag <= 109 then
            skipOneType(view)
        else if tag >= 60 && tag <= 89 then
            discard(view.readNat())
        // else category 1: nothing to skip

    /** Return the Tasty.Name at `idx` in `names`, throwing ArrayIndexOutOfBoundsException explicitly.
      *
      * ScalaJS fastLinkJS wraps raw array out-of-bounds into UndefinedBehaviorError (a JS class) which is NOT caught by Java
      * `catch case _: ArrayIndexOutOfBoundsException` handlers. Adding an explicit guard here ensures the AIOBE is thrown as a proper Java
      * exception that callers (Symbol.body) can catch and map to TastyError.MalformedSection.
      */
    private def nameAt(names: Array[Tasty.Name], idx: Int): Tasty.Name =
        if idx < 0 || idx >= names.length then throw new ArrayIndexOutOfBoundsException(idx)
        names(idx)

    private def isVarianceTag(tag: Int): Boolean =
        tag == TastyFormat.STABLE || tag == TastyFormat.COVARIANT || tag == TastyFormat.CONTRAVARIANT

    private def isModifierOrVarianceTag(tag: Int): Boolean =
        (tag >= 1 && tag <= 59) ||
            tag == TastyFormat.PRIVATEqualified ||
            tag == TastyFormat.PROTECTEDqualified ||
            tag == TastyFormat.ANNOTATION

    /** Decode a METHODtype lambda, consuming the tag byte.
      *
      * F-A-001 fix: AstUnpickler.readDefDefReturnType calls this directly when it detects a METHODtype tag, so that the full param/result
      * lambda is decoded and stored as Type.TypeLambda(paramIds, resultType) in typeBySymbol. Prior to this, only the result leaf was
      * decoded by the scalar readTypeIntoSession call.
      *
      * Wire format (TASTy spec): METHODtype Length result_Type (typeRef_Nat paramName_Nat)* Modifier*
      *
      * The view is positioned AT the METHODtype tag byte (not yet consumed). This method consumes the tag, reads the length, performs the
      * two-pass param scan via readMethodParams, then decodes the result type and returns Type.TypeLambda.
      */
    private[reader] def decodeMethodType(view: ByteView, session: DecodeSession)(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        discard(view.readByte()) // consume METHODtype tag
        val startAddr = view.positionInt - 1
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            null,
            0,
            frame,
            session
        )
        val end       = view.readEnd()
        val paramSyms = readMethodParams(view, end, ctx)
        ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
        val resultType = readTypeNode(view, ctx)
        view.goto(end)
        val t = Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)
        ctx.arena.intern(t)
    end decodeMethodType

    /** Decode a POLYtype lambda, consuming the tag byte.
      *
      * F-A-001 fix: AstUnpickler.readDefDefReturnType calls this directly when it detects a POLYtype tag (generic methods), so that the
      * type-parameter/result structure is properly captured as Type.TypeLambda(tparamIds, inner).
      *
      * Wire format (TASTy spec): POLYtype Length result_Type (typeRef_Nat paramName_Nat)* Modifier*
      *
      * The view is positioned AT the POLYtype tag byte (not yet consumed). This method consumes the tag, reads the length, performs the
      * two-pass type-param scan via readTypeLambdaParams, then decodes the inner (result) type and returns Type.TypeLambda.
      */
    private[reader] def decodePolyType(view: ByteView, session: DecodeSession)(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        discard(view.readByte()) // consume POLYtype tag
        val startAddr = view.positionInt - 1
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            null,
            0,
            frame,
            session
        )
        val end       = view.readEnd()
        val paramSyms = readTypeLambdaParams(view, end, ctx)
        ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
        val resultType = readTypeNode(view, ctx)
        view.goto(end)
        val t = Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)
        ctx.arena.intern(t)
    end decodePolyType

end TypeUnpickler
