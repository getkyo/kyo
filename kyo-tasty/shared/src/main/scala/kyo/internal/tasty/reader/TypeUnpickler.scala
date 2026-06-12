package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena
import kyo.internal.tasty.type_.TypeOps
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Decodes TASTy type nodes into Tasty.Type values.
  *
  * Entry point: TypeUnpickler.readTypeIntoSession is called by AstUnpickler after pass 1 has produced addrMap. readType is the
  * internal recursive descent function.
  *
  * Key state per decode session (all mutable, file-scoped):
  *   - addrCache: Addr->Type, used for SHAREDtype back-references.
  *   - inProgressRec: Addr->Rec placeholder, used to break RECtype/RECthis cycles.
  *   - binderAddrMap: Addr->Chunk[Symbol], maps lambda start address to param symbol list.
  *   - cross-file references: produce Named(SymbolId(negId)) with negId < -1, tracked in session.unresolvedIdToFullName for finalizeMerge resolution.
  *
  * Cross-platform: no JVM-only APIs; all bit-pattern conversions use java.lang.Float/Double which are available on Scala.js and Scala
  * Native.
  */
/** Exception wrapper that carries a `TastyError` through synchronous decode paths.
  *
  * Thrown by `decodeTag` when an unrecognised tag is encountered. The `readType` catch block re-lifts this
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

    /** Phase-B temporary SymbolId offset.
      *
      * During per-file decode, `TERMREFdirect` and `TYPEREFdirect` type nodes reference local symbols by TASTy byte address. Instead of
      * using `SymbolId(-1)` (which loses the address information), we encode the address as `SymbolId(PHASE_B_ADDR_OFFSET + address)`. This
      * preserves the address so that `finalizeMerge` can remap these to correct final SymbolIds using the file's addrMap.
      *
      * The offset (2^28 = 268_435_456) is chosen so that it is far above any realistic classpath symbol count and distinguishable from both
      * negative sentinel (-1) and final SymbolIds (0 to N-1).
      */
    private[kyo] val PHASE_B_ADDR_OFFSET: Int = 1 << 28

    /** Sentinel SymbolId for unresolved-type fallbacks in the body-decode path.
      *
      * Used only when `session` is null (lazy `Symbol.body` Tree decode via `readTypeForTree`). In that path the decoded types
      * do not reach `typeBySymbol`, `parentsBySymbol`, or `annotationsBySymbol` in the symbol metadata pipeline, so this
      * sentinel never leaks into the produced public ADT. When session is non-null (pass 1 via `readTypeIntoSession`),
      * `session.nextUnresolvedId()` is used instead to assign a unique tracked negId < -1.
      */
    private[kyo] val sentinelId: kyo.Tasty.SymbolId = kyo.Tasty.SymbolId(-1)

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
      * @return
      *   The decoded Tasty.Type, interned in `arena`.
      */
    def readType(
        view: ByteView,
        names: Array[Tasty.Name],
        addrMap: IntMap[LoadingSymbol.Materialising],
        arena: TypeArena,
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using frame: Frame)(using AllowUnsafe): Result[TastyError, Tasty.Type] =
        try
            val addrCache     = new mutable.HashMap[Int, Tasty.Type]()
            val inProgressRec = new mutable.HashMap[Int, Tasty.Type.Rec]()
            val binderAddrMap = new mutable.HashMap[Int, Chunk[LoadingSymbol.Materialising]]()
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
            Result.Success(readTypeNode(view, ctx))
        catch
            case ex: TastyErrorException =>
                // Preserve the specific TastyError (e.g. UnknownTagInPosition)
                // rather than wrapping it in the generic MalformedSection fallback.
                Result.Failure(ex.error)
            case ex: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"unexpected end reading type: ${ex.getMessage}", view.position))
            case ex: Exception =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"type decode error: ${ex.getMessage}", view.position))
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
        val addrMap: scala.collection.Map[Int, LoadingSymbol.Materialising],
        val arena: TypeArena,
        val sectionBytes: Array[Byte],
        val sectionOffset: Int
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]                             = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]                     = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[LoadingSymbol.Materialising]] = new mutable.HashMap()
    end TreeTypeSession

    /** Read one type node using a shared TreeTypeSession (called by TreeUnpickler).
      *
      * Handles SHAREDtype cache misses by re-decoding the referenced type from sectionBytes on demand.
      */
    private[tasty] def readTypeForTree(view: ByteView, session: TreeTypeSession)(using AllowUnsafe): Tasty.Type =
        // internal frame used here because readTypeForTree is called from
        // TreeUnpickler.decodeSync, which performs lazy Symbol.body decoding.
        // The decode thunk has type () => Tree and cannot accept a Frame parameter.
        // This is the one legitimate Frame.internal site; all other decode paths propagate a real Frame.
        val callFrame = Frame.internal
        val peek      = view.peekByte(view.position) & 0xff
        if peek == TastyFormat.SHAREDtype then
            discard(view.readByte()) // consume SHAREDtype tag
            val address = view.readNat()
            val absAddr = session.sectionOffset + address
            session.addrCache.getOrElseUpdate(
                absAddr, {
                    // Cache miss: re-decode the type at the section-relative address from the full section bytes.
                    // address is section-relative; absAddr = sectionOffset + address is the absolute position.
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
        // Pass session reference for cross-file fully-qualified name tracking so the merge pass can remap parent types.
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
            Maybe.Present(session)
        )
        readTypeNode(view, ctx)
    end readTypeIntoSession

    /** Variant of readTypeIntoSession that also provides sectionBytes, enabling eager annotation arg decode.
      *
      * Used to verify that corrupt annotation arg bytes accumulate errors in `session.annotationDecodeErrors` rather than
      * propagating exceptions.
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
            Maybe.Present(session)
        )
        readTypeNode(view, ctx)
    end readTypeIntoSessionWithBytes

    /** Mutable decode session shared across all type positions in one file. Created by AstUnpickler and passed to readTypeIntoSession.
      *
      * `liveAddrMap` is the mutable HashMap being built by AstUnpickler.walkStats. Passing it directly (not as a snapshot) lets type decode
      * find locally-defined symbols as the walk progresses.
      *
      * `unresolvedIdToFullName` accumulates mappings from the unique negative SymbolIds assigned to cross-file unresolved symbols to their fully-qualified names.
      * The merge pass uses this map to look up the fully-qualified name and resolve to the correct final SymbolId via `fullNameIndex`.
      */
    final private[kyo] class DecodeSession(
        val names: Array[Tasty.Name],
        val liveAddrMap: mutable.HashMap[Int, LoadingSymbol.Materialising],
        val arena: TypeArena
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]                             = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]                     = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[LoadingSymbol.Materialising]] = new mutable.HashMap()
        val unresolvedIdToFullName: mutable.HashMap[Int, String]                    = new mutable.HashMap()
        // Accumulates errors from eager annotation arg decodes in ANNOTATEDtype. Drained into
        // FileResult.errors at the end of AstUnpickler.runPass1 via Pass1Result.annotationDecodeErrors.
        val annotationDecodeErrors: mutable.ArrayBuffer[TastyError] = new mutable.ArrayBuffer()
        // Counter for unique negative IDs: starts at -2 (to distinguish from SymbolId(-1) which is the sentinel).
        private var _unresolvedIdCounter: Int = -2

        // Owner stack maintained by AstUnpickler as it enters/exits DEFDEF and TYPEDEF nodes.
        // The THIS-type branch reads this to resolve THIS to the enclosing class/trait/object symbol.
        val ownerStack: mutable.ArrayDeque[LoadingSymbol.Materialising] = new mutable.ArrayDeque()

        // Parallel stack with the ABSOLUTE node address (from addrMap) of each owner symbol.
        // This allows the THIS-type branch to emit ThisType(PHASE_B_ADDR_OFFSET + address) for
        // proper finalizeMerge remapping via addrToFinal.
        val ownerAddrStack: mutable.ArrayDeque[Int] = new mutable.ArrayDeque()

        def nextUnresolvedId(): Int =
            val id = _unresolvedIdCounter
            _unresolvedIdCounter -= 1
            id
        end nextUnresolvedId
    end DecodeSession

    // Internal decode context passed through all recursive calls.
    // sectionBytes: full AST section bytes for on-demand SHAREDtype re-decode on cache miss.
    // Pass Maybe.Absent when decoding from a live DecodeSession (Pass 1) where addrCache is always pre-populated.
    // addrMap accepts any scala.collection.Map so pass1 can pass liveAddrMap (mutable.HashMap) directly
    // without snapshotting into an IntMap on every type-node decode call.
    // frame: call-site Frame, used for Log.warn in the unknown-tag fallback branch.
    // session: reference to the owning DecodeSession for cross-file fully-qualified name tracking (Maybe.Absent in body decode path).
    final private class DecodeCtx(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, LoadingSymbol.Materialising],
        val arena: TypeArena,
        val addrCache: mutable.HashMap[Int, Tasty.Type],
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec],
        val binderAddrMap: mutable.HashMap[Int, Chunk[LoadingSymbol.Materialising]],
        val sectionBytes: Array[Byte],
        val sectionOffset: Int,
        val frame: Frame,
        val session: Maybe[DecodeSession] = Maybe.Absent
    ):
        // Local id counter for lambda-param Materialising instances created when addrMap lookup misses.
        // These are never placed in allSyms; their ids only need to be unique within this DecodeCtx.
        // Using large positive values avoids collision with Phase-B address-offset or final ids.
        private var _localIdCounter: Int = Int.MaxValue
        def nextLocalId(): Int =
            val id = _localIdCounter
            _localIdCounter -= 1
            id
        end nextLocalId
    end DecodeCtx

    /** Make a unique negative SymbolId for a cross-file fully-qualified name reference and record it in `unresolvedNames`.
      *
      * The caller must provide a unique negative `id` (obtained via `session.nextUnresolvedId()`). Records `id -> fullName` in `unresolvedNames` so
      * finalizeMerge can resolve `Named(SymbolId(id))` to the correct final SymbolId via fullNameIndex lookup.
      */
    private def makeTrackedUnresolvedSym(
        fullName: String,
        unresolvedNames: mutable.HashMap[Int, String],
        id: Int
    )(using AllowUnsafe): kyo.Tasty.SymbolId =
        unresolvedNames(id) = fullName
        kyo.Tasty.SymbolId(id)
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
                // Always add sectionOffset to convert section-relative to absolute, since
                // addrCache keys are absolute (view.positionInt = sectionOffset + sectionRelativeAddr).
                val astRef = view.readNat()
                val absRef = ctx.sectionOffset + astRef
                ctx.addrCache.getOrElse(
                    absRef, {
                        if ctx.sectionBytes != null then
                            // Cache miss during body decode: re-decode the type at absRef from full section bytes.
                            // Pass ctx.session to the fork context so that cross-file fully-qualified name refs (TERMREFpkg,
                            // TYPEREFpkg) encountered during re-decode produce tracked unique negative IDs
                            // rather than the unresolved sentinel. Without this, @Child annotation type args
                            // decoded via SHAREDtype back-references lose their cross-file fully-qualified name tracking.
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
                            // Use a unique tracked negative ID (< -1) so the result
                            // does not collide with the Named(-1) sentinel.
                            ctx.session match
                                case Maybe.Present(s) =>
                                    Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                                case Maybe.Absent =>
                                    Tasty.Type.Named(sentinelId)
                        end if
                    }
                )

            case TastyFormat.TERMREFdirect =>
                val astRef = view.readNat()
                // TASTy ASTRef values are section-relative. addrMap keys are absolute (sectionOffset + sectionRelativeAddr).
                // Add sectionOffset to convert to absolute before lookup and storage.
                val absRef = ctx.sectionOffset + astRef
                if ctx.addrMap.contains(absRef) then Tasty.Type.Named(kyo.Tasty.SymbolId(PHASE_B_ADDR_OFFSET + absRef))
                else
                    // Cross-file TERMREFdirect miss: assign a unique tracked negative ID
                    // so the result is Named(SymbolId(uniqueNeg)) with uniqueNeg < -1, not the Named(-1) sentinel.
                    // finalizeMerge's remapType will attempt fully-qualified name lookup via negIdToFinal; if it fails the type
                    // remains as a unique unresolved ref (value < -1) rather than colliding with the -1 sentinel.
                    ctx.session match
                        case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                        case Maybe.Absent     => Tasty.Type.Named(sentinelId)
                end if

            case TastyFormat.TYPEREFdirect =>
                val astRef = view.readNat()
                // TASTy ASTRef values are section-relative. addrMap keys are absolute (sectionOffset + sectionRelativeAddr).
                // Add sectionOffset to convert to absolute before lookup and storage.
                val absRef = ctx.sectionOffset + astRef
                if ctx.addrMap.contains(absRef) then Tasty.Type.Named(kyo.Tasty.SymbolId(PHASE_B_ADDR_OFFSET + absRef))
                else
                    // Cross-file TYPEREFdirect miss: assign a unique tracked negative ID so the result is
                    // Named(SymbolId(uniqueNeg)) with uniqueNeg < -1, eliminating the Named(-1) sentinel
                    // from declaredType traversals.
                    ctx.session match
                        case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                        case Maybe.Absent     => Tasty.Type.Named(sentinelId)
                end if

            case TastyFormat.TERMREFpkg =>
                val nameRef  = view.readNat()
                val fullName = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case Maybe.Present(s) =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullName, s.unresolvedIdToFullName, s.nextUnresolvedId()))
                    case Maybe.Absent =>
                        // No session context: use interned sentinel instead of per-fullName name.
                        Tasty.Type.Named(sentinelId)
                end match

            case TastyFormat.TYPEREFpkg =>
                val nameRef  = view.readNat()
                val fullName = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case Maybe.Present(s) =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullName, s.unresolvedIdToFullName, s.nextUnresolvedId()))
                    case Maybe.Absent =>
                        // No session context: use interned sentinel instead of per-fullName name.
                        Tasty.Type.Named(sentinelId)
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
                                // The Rec node hasn't been decoded yet. Assign a unique tracked ID (< -1)
                                // so the result is Named(SymbolId(uniqueNeg)) not Named(-1). The unique ID is
                                // unresolvable but does not collide with the Named(-1) sentinel check.
                                val placeholderId = ctx.session match
                                    case Maybe.Present(s) => kyo.Tasty.SymbolId(s.nextUnresolvedId())
                                    case Maybe.Absent     => sentinelId
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
                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                        _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                        _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                        _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                        _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                        _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                        // Resolve THIS through the active scope's enclosing class.
                        // The owner stack is maintained by AstUnpickler around every CLASSDEF / DEFDEF;
                        // the topmost class-kind owner is the canonical "this" referent.
                        // ownerAddrStack provides the Phase-B address-encoded id so that
                        // finalizeMerge's remapType can look it up in addrToFinal and return the real class id.
                        ctx.session match
                            case Maybe.Present(s) =>
                                val enclosingPair = s.ownerStack.reverseIterator
                                    .zip(s.ownerAddrStack.reverseIterator)
                                    .find { case (symbol, _) =>
                                        symbol.kind == SymbolKind.Class ||
                                        symbol.kind == SymbolKind.Trait ||
                                        symbol.kind == SymbolKind.Object
                                    }
                                enclosingPair match
                                    case Some((_, address)) =>
                                        Tasty.Type.ThisType(kyo.Tasty.SymbolId(PHASE_B_ADDR_OFFSET + address))
                                    case None =>
                                        Tasty.Type.ThisType(sentinelId)
                                end match
                            case Maybe.Absent =>
                                Tasty.Type.ThisType(sentinelId)
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
                // inProgressRec is keyed by the RECtype node's start address so RECthis nodes
                // encountered during parent decoding can find the placeholder.
                // Use sentinel id for the RECtype cycle-breaker placeholder.
                // The placeholder is installed in inProgressRec keyed by startAddr and removed
                // via inProgressRec.remove BEFORE the result is returned; no per-address identity needed.
                val placeholder: Tasty.Type.Rec = Tasty.Type.Rec(Tasty.Type.Named(sentinelId))
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
                // Convert section-relative ASTRef to absolute for addrMap lookup.
                val absRefTerm = ctx.sectionOffset + astRef
                ctx.addrMap.get(absRefTerm) match
                    case Some(symbol) => Tasty.Type.TermRef(qual, symbol.name)
                    case None         =>
                        // For forward references (same file, not yet in addrMap),
                        // emit Named(PHASE_B_ADDR_OFFSET + absRef). finalizeMerge remaps to the final
                        // SymbolId via addrToFinal, allowing resolveChildRef to extract it.
                        // If truly unresolvable (different file), finalizeMerge returns Named(-1).
                        Tasty.Type.Named(kyo.Tasty.SymbolId(PHASE_B_ADDR_OFFSET + absRefTerm))
                end match

            case TastyFormat.TERMREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

            case TastyFormat.TYPEREFsymbol =>
                val astRef = view.readNat()
                // qual is present but for Named resolution we only need the symbol.
                discard(readTypeNode(view, ctx)) // consume qual
                // Convert section-relative ASTRef to absolute for addrMap lookup.
                // Always emit PHASE_B_ADDR_OFFSET + absRef regardless of whether addrMap contains absRef yet.
                // For @Child annotation forward references, the referenced symbol may not yet be in addrMap
                // (decoded later in the same file). finalizeMerge's addrToFinal map is built from the COMPLETE
                // addrMap and resolves these. If absRef is from a different file, addrToFinal will not contain
                // it and finalizeMerge leaves it as Named(-1).
                val absRef = ctx.sectionOffset + astRef
                Tasty.Type.Named(kyo.Tasty.SymbolId(PHASE_B_ADDR_OFFSET + absRef))

            case TastyFormat.TYPEREF =>
                // TYPEREF (117) is a type-position reference, distinct from TermRef.
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Tasty.Type.TypeRef(qual, nameAt(ctx.names, nameRef))

            // ── Category 5 (tag + Length + payload) ──────────────────────────────

            case TastyFormat.APPLIEDtype =>
                val end   = view.readEnd()
                val tycon = readTypeNode(view, ctx)
                val args  = readTypesUntil(view, end, ctx)
                val fullNameHint: Maybe[String] = tycon match
                    case Tasty.Type.Named(id) =>
                        import kyo.Tasty.SymbolId.value
                        ctx.session match
                            case Maybe.Present(s) => Maybe.fromOption(s.unresolvedIdToFullName.get(id.value))
                            case Maybe.Absent     => Maybe.Absent
                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                        _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                        _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                        _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                        _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                        _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                        Maybe.Absent
                TypeOps.applied(tycon, Chunk.from(args), fullNameHint)

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
                // Use a tracked unique negId when session is available so finalizeMerge can attempt
                // fully-qualified name resolution for this annotation type. Fall back to sentinelId (-1) only in the
                // body-decode path (session == null) where the result does not reach the produced ADT.
                val annotationType = ctx.session match
                    case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                    case Maybe.Absent     => Tasty.Type.Named(sentinelId)
                val annotation =
                    if ctx.sectionBytes == null then
                        // Intermediate annotation: annotationFullName placeholder set to empty; finalizeMerge resolves.
                        Tasty.Annotation(annotationType, Chunk.empty, Tasty.Name(""))
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
                                        case Maybe.Present(s) => discard(s.annotationDecodeErrors += err)
                                        case Maybe.Absent     => ()
                                    end match
                                    Maybe.Absent
                                case ex: ArrayIndexOutOfBoundsException =>
                                    val err = TastyError.MalformedSection("ASTs", "annotation args truncated", 0L)
                                    ctx.session match
                                        case Maybe.Present(s) => discard(s.annotationDecodeErrors += err)
                                        case Maybe.Absent     => ()
                                    end match
                                    Maybe.Absent
                        val args: Chunk[Tasty.Tree] = maybeTree match
                            case Maybe.Present(t) =>
                                t match
                                    case Tasty.Tree.Apply(_, applyArgs) => applyArgs
                                    case other                          => Chunk(other)
                            case Maybe.Absent => Chunk.empty
                        // Intermediate annotation: annotationFullName placeholder set to empty; finalizeMerge resolves.
                        Tasty.Annotation(annotationType, args, Tasty.Name(""))
                Tasty.Type.Annotated(underlying, annotation)

            case TastyFormat.ANDtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                def namedFullName(t: Tasty.Type): Maybe[String] = t match
                    case Tasty.Type.Named(id) =>
                        import kyo.Tasty.SymbolId.value
                        ctx.session match
                            case Maybe.Present(s) => Maybe.fromOption(s.unresolvedIdToFullName.get(id.value))
                            case Maybe.Absent     => Maybe.Absent
                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                        _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                        _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                        _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                        _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                        _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                        Maybe.Absent
                TypeOps.andType(left, right, namedFullName(left), namedFullName(right))

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
                // register in binderAddrMap (keyed on this node's start address), then decode result.
                // PARAMtype nodes in the result use binder_ASTRef = startAddr to look up params.
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(m => kyo.Tasty.SymbolId(m.id))), resultType)

            case TastyFormat.POLYtype =>
                val end       = view.readEnd()
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(m => kyo.Tasty.SymbolId(m.id))), resultType)

            case TastyFormat.METHODtype =>
                val end       = view.readEnd()
                val paramSyms = readMethodParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(m => kyo.Tasty.SymbolId(m.id))), resultType)

            case TastyFormat.PARAMtype =>
                val end        = view.readEnd()
                val binderAddr = view.readNat()
                val paramNum   = view.readNat()
                view.goto(end)
                // PARAMtype binderAddr is section-relative; binderAddrMap keys are absolute.
                // Add sectionOffset to convert to the absolute key registered by the enclosing lambda handler.
                val absBinderAddr = ctx.sectionOffset + binderAddr
                ctx.binderAddrMap.get(absBinderAddr) match
                    case Some(params) if paramNum < params.length => Tasty.Type.ParamRef(kyo.Tasty.SymbolId(params(paramNum).id), paramNum)
                    // Carve-out: stdlib Option from mutable.HashMap.get; falls through for absent binder or out-of-range paramNum
                    case _ =>
                        // Binder address miss: assign unique tracked ID so the result is Named(SymbolId(uniqueNeg))
                        // with uniqueNeg < -1, not the Named(-1) sentinel.
                        ctx.session match
                            case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                            case Maybe.Absent     => Tasty.Type.Named(sentinelId)
                end match

            case TastyFormat.TYPEREFin =>
                val end        = view.readEnd()
                val nameRef    = view.readNat()
                val simpleName = nameAt(ctx.names, nameRef).asString
                val qual       = readTypeNode(view, ctx) // decode qual to extract package fully-qualified name
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                // Reconstruct full name: qualFullName + "." + simpleName.
                // qual is typically TYPEREFpkg(packageFullName) -> Named(trackedId) where trackedId maps to packageFullName.
                // For finalizeMerge lookup, use qualFullName + "." + simpleName.
                ctx.session match
                    case Maybe.Present(s) =>
                        val qualFullName = qual match
                            case Tasty.Type.Named(sid) if sid.value < -1 =>
                                // Tracked cross-file ref: look up fullName from unresolvedIdToFullName
                                s.unresolvedIdToFullName.getOrElse(sid.value, simpleName)
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
                                simpleName
                        val qualifiedFullName = if qualFullName.nonEmpty then qualFullName + "." + simpleName else simpleName
                        Tasty.Type.Named(makeTrackedUnresolvedSym(qualifiedFullName, s.unresolvedIdToFullName, s.nextUnresolvedId()))
                    case Maybe.Absent =>
                        // No session context: use interned sentinel.
                        Tasty.Type.Named(sentinelId)
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
                        // TYPEBOUNDS with explicit hi is a declared bounds, not a wildcard.
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
            // Nested TPT tags are handled directly here; top-level dispatch in AstUnpickler routes to TreeUnpickler.decodeTptAsType.

            case TastyFormat.IDENTtpt | TastyFormat.IDENT =>
                // IDENTtpt (111) and its term-identifier form IDENT (110): cat-4 (tag + name-ref + AST).
                // The name-ref is the identifier name; the AST is its resolved Type. Discard the name and
                // return the resolved type. IDENT reaches type position via singleton/macro-generated types.
                discard(view.readNat())
                readTypeNode(view, ctx)

            case TastyFormat.SELECTtpt =>
                // SELECTtpt (113): cat-4 (tag + NameRef + qual_Tree). Type-position member selection.
                // Encodes e.g. `scala.Double` as SELECTtpt("Double", TERMREFpkg("scala")).
                // Mirrors TreeUnpickler.decodeTptAsType case for SELECTtpt.
                val nameRef = view.readNat()
                val nm      = nameAt(ctx.names, nameRef).asString
                val qual    = readTypeNode(view, ctx)
                val qualFullName: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case Maybe.Present(s) => s.unresolvedIdToFullName.getOrElse(sid.value, "")
                            case Maybe.Absent     => ""
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
                        ""
                val qualifiedFullName = if qualFullName.nonEmpty then qualFullName + "." + nm else nm
                ctx.session match
                    case Maybe.Present(s) =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(qualifiedFullName, s.unresolvedIdToFullName, s.nextUnresolvedId()))
                    case Maybe.Absent =>
                        Tasty.Type.Named(sentinelId)
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
                // Explicit bounds use Type.Bounds, not Type.Wildcard.
                val end = view.readEnd()
                val lo  = readTypeNode(view, ctx)
                val hi  = if view.position < end then readTypeNode(view, ctx) else lo
                view.goto(end)
                Tasty.Type.Bounds(lo, hi)

            case TastyFormat.ANNOTATEDtpt =>
                // ANNOTATEDtpt (154): cat-5 (tag + Length + tpe_Tree + annot_Tree).
                // The annotation term is wired via the type-decode pass; only the underlying type is extracted here.
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
                discard(readTypeNode(view, ctx)) // owner (namespace); used by finalizeMerge for fully-qualified name resolution
                view.goto(end)
                // Build tracked qualified fully-qualified name for finalizeMerge lookup.
                val qualFullName: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case Maybe.Present(s) => s.unresolvedIdToFullName.getOrElse(sid.value, "")
                            case Maybe.Absent     => ""
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
                        ""
                val qualifiedFullName = if qualFullName.nonEmpty then qualFullName + "." + nm else nm
                ctx.session match
                    case Maybe.Present(s) =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(qualifiedFullName, s.unresolvedIdToFullName, s.nextUnresolvedId()))
                    case Maybe.Absent =>
                        // No session context: use interned sentinel.
                        Tasty.Type.Named(sentinelId)
                end match

            case TastyFormat.REFINEDtpt =>
                // REFINEDtpt (160): cat-5 (tag + Length + parent_Tree + decl_Tree*).
                // Only the parent type is extracted here; refinement decls are skipped.
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
                    val symbol = InternalSymbol.makeSymbol(
                        id = ctx.nextLocalId(),
                        kind = SymbolKind.TypeParam,
                        flags = Tasty.Flags.empty,
                        name = symName
                    )
                    tparamIds += kyo.Tasty.SymbolId(symbol.id)
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
                // Type.MatchCase is a first-class ADT case.
                // This handler is reached for nested decodes (e.g. cases inside MATCHtpt).
                val end = view.readEnd()
                val pat = readTypeNode(view, ctx)
                val rhs = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.MatchCase(pat, rhs)

            // ── Term-tag-in-type-position handlers ────────────────────────────────
            // These TERM tags can appear inside nested type positions (e.g. inside APPLIEDtype
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
                            case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                            case Maybe.Absent     => Tasty.Type.Named(sentinelId)
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
                // SELECT (112): cat-4 (tag + Nat + AST). Build a tracked unresolved fully-qualified name (same as SELECTtpt).
                val nameRef = view.readNat()
                val nm      = nameAt(ctx.names, nameRef).asString
                val qual    = readTypeNode(view, ctx)
                val qualFullName: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        ctx.session match
                            case Maybe.Present(s) => s.unresolvedIdToFullName.getOrElse(sid.value, "")
                            case Maybe.Absent     => ""
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
                        ""
                val qualifiedFullName = if qualFullName.nonEmpty then qualFullName + "." + nm else nm
                ctx.session match
                    case Maybe.Present(s) =>
                        val id = s.nextUnresolvedId()
                        s.unresolvedIdToFullName(id) = qualifiedFullName
                        Tasty.Type.Named(kyo.Tasty.SymbolId(id))
                    case Maybe.Absent => Tasty.Type.Named(sentinelId)
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
                    case Maybe.Present(s) => s.nextUnresolvedId()
                    case Maybe.Absent     => sentinelId.value
                val hiId = ctx.session match
                    case Maybe.Present(s) => s.nextUnresolvedId()
                    case Maybe.Absent     => sentinelId.value
                Tasty.Type.Wildcard(
                    Tasty.Type.Named(kyo.Tasty.SymbolId(loId)),
                    Tasty.Type.Named(kyo.Tasty.SymbolId(hiId))
                )

            case TastyFormat.QUALTHIS =>
                // QUALTHIS (91): cat-3 (tag + AST). Decode the qualifier type and return it.
                readTypeNode(view, ctx)

            case TastyFormat.INLINED =>
                // INLINED (147): cat-5 (tag + Length + ...). Skip; return unique unresolved ID.
                val end = view.readEnd()
                view.goto(end)
                ctx.session match
                    case Maybe.Present(s) => Tasty.Type.Named(kyo.Tasty.SymbolId(s.nextUnresolvedId()))
                    case Maybe.Absent     => Tasty.Type.Named(sentinelId)

            // ── Unknown tag: fail loudly ──────────────────────────────────────────

            case TastyFormat.BIND =>
                // BIND (150) in type position binds a match-type pattern variable: the `t` in a
                // `case List[t] => ...` case, pickled inside the case's APPLIEDtpt pattern. dotty
                // pickles BIND as `name, symInfo_Type, body`; the term decoder reads it the same way
                // (the bound variable's info is not retained, matching Tree.Bind). goto(end) guards the
                // cursor against trailing modifiers.
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameAt(ctx.names, nameRef)
                discard(readTypeNode(view, ctx)) // bound variable's info type
                val pattern = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.Bind(name, pattern)

            case other if other >= TastyFormat.firstLengthTreeTag =>
                // Any cat-5 tag not handled above is a genuinely unrecognised TASTy format extension.
                // TagKind.TypePositionTag.throwFor propagates TastyErrorException(UnknownTagInPosition)
                // to classpath.errors rather than producing a silent sentinel Named(-1) symbol.
                TagKind.TypePositionTag.throwFor(other)

            case other =>
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
      * Actually per the grammar: POLYtype = result_Type TypesNames, where result comes first. PARAMtype uses the binder's start address. We
      * pre-register an empty binding, decode result (PARAMtype will return placeholder), then return params.
      *
      * Simpler correct approach: use a two-pass for lambda bodies:
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
    )(using AllowUnsafe): mutable.ArrayBuffer[LoadingSymbol.Materialising] =
        val resultStart = view.position
        // Skip result type to find TypesNames start.
        skipOneType(view)
        // Collect TypesNames = (typeOrBounds_Type paramName_NameRef)*. The leading entry is a FULL
        // inline type (e.g. a TYPEBOUNDS carrying lo/hi), not a single address nat, so it must be
        // consumed with skipOneType; reading it as one nat consumed the type's tag and payload as a
        // bogus name ref and ran off the end of the section.
        val paramSyms = new mutable.ArrayBuffer[LoadingSymbol.Materialising]()
        while view.position < end do
            skipOneType(view)
            val nameRef = view.readNat()
            val symName = nameAt(ctx.names, nameRef)
            paramSyms += LoadingSymbol.Materialising(
                id = ctx.nextLocalId(),
                kind = SymbolKind.TypeParam,
                flags = Tasty.Flags.empty,
                name = symName
            )
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
    )(using AllowUnsafe): mutable.ArrayBuffer[LoadingSymbol.Materialising] =
        val resultStart = view.position
        // Skip result type.
        skipOneType(view)
        // Collect TypesNames until we hit modifiers or end.
        // typeRef from TypesNames is section-relative; addrMap keys are absolute.
        // Add ctx.sectionOffset to convert before lookup.
        val paramSyms = new mutable.ArrayBuffer[LoadingSymbol.Materialising]()
        while view.position < end do
            val tag = view.peekByte(view.position) & 0xff
            if isModifierOrVarianceTag(tag) then
                view.goto(end)
            else
                // Each entry is `paramType_Type paramName_NameRef`: the type is a full inline type, not a
                // single address nat, so consume it with skipOneType before reading the name.
                skipOneType(view)
                val nameRef = view.readNat()
                val symName = nameAt(ctx.names, nameRef)
                paramSyms += LoadingSymbol.Materialising(
                    id = ctx.nextLocalId(),
                    kind = SymbolKind.Parameter,
                    flags = Tasty.Flags.empty,
                    name = symName
                )
            end if
        end while
        view.goto(resultStart)
        paramSyms
    end readMethodParams

    /** Read type nodes until position reaches `end`. */
    private def readTypesUntil(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Type] =
        val accumulator = new mutable.ArrayBuffer[Tasty.Type]()
        while view.position < end do
            accumulator += readTypeNode(view, ctx)
        accumulator
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
      * AstUnpickler.readDefDefReturnType calls this directly when it detects a METHODtype tag, so that the full param/result lambda is
      * decoded and stored as Type.TypeLambda(paramIds, resultType) in typeBySymbol.
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
            Maybe.Present(session)
        )
        val end       = view.readEnd()
        val paramSyms = readMethodParams(view, end, ctx)
        ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
        val resultType = readTypeNode(view, ctx)
        view.goto(end)
        val t = Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(m => kyo.Tasty.SymbolId(m.id))), resultType)
        ctx.arena.intern(t)
    end decodeMethodType

    /** Decode a POLYtype lambda, consuming the tag byte.
      *
      * AstUnpickler.readDefDefReturnType calls this directly when it detects a POLYtype tag (generic methods), so that the
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
            Maybe.Present(session)
        )
        val end       = view.readEnd()
        val paramSyms = readTypeLambdaParams(view, end, ctx)
        ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
        val resultType = readTypeNode(view, ctx)
        view.goto(end)
        val t = Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(m => kyo.Tasty.SymbolId(m.id))), resultType)
        ctx.arena.intern(t)
    end decodePolyType

end TypeUnpickler
