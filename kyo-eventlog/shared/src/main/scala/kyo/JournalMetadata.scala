package kyo

/** Raised when a logical identity value cannot be honored: an ill-formed [[JournalId]]
  * route-segment, or a [[JournalEntryRef]] URI that is empty, physical (file:// or a segment
  * path), or otherwise not a well-formed logical `journal:` reference. [[JournalId.apply]]
  * and [[JournalEntryRef.parse]] surface identity validation failures through this type on
  * `Abort[JournalIdentityError]`; it does not extend the JournalError append/read/streamInfo
  * hierarchy because an identity validation failure is not a per-operation storage outcome.
  */
final case class JournalIdentityError(reason: String)(using Frame) extends KyoException

/** Logical journal identity: a validated route-segment id used in configurations, references,
  * and resolver routes. It carries no physical path meaning.
  */
opaque type JournalId = String

object JournalId:
    /** Validates a logical journal id: non-empty, lowercase route-segment grammar (letters,
      * digits, and '-'); rejects path separators, '..', and URI schemes.
      */
    def apply(value: String)(using Frame): JournalId < Abort[JournalIdentityError] =
        // The route-segment validator surfaces failure through JournalIdentityError and
        // aborts on any physical or empty form.
        Abort.get(JournalId.validate(value))

    extension (self: JournalId)
        /** The underlying logical id string. */
        def value: String = self

    inline given CanEqual[JournalId, JournalId] = CanEqual.derived

    /** Constructs an id from a value already known valid (an internal invariant, never user input). Reused
      * by the `journalId"..."` compile-time literal interpolator (kyo.internal.EventInterpolatorMacros)
      * once `validate` has already accepted the literal at compile time.
      */
    private[kyo] inline def fromUnchecked(value: String): JournalId = value

    // Lowercase route-segment grammar: non-empty, letters/digits/'-' only, no path separator,
    // no '..', no URI scheme colon. Reused directly (not re-split into a further isValid) by
    // the `journalId"..."` compile-time literal interpolator (kyo.internal.EventInterpolatorMacros),
    // which reuses this validate/apply split directly.
    private[kyo] def validate(value: String)(using Frame): Result[JournalIdentityError, JournalId] =
        if value.isEmpty then Result.fail(JournalIdentityError("journal id must not be empty"))
        else if value.contains("/") || value.contains("..") || value.contains(":") then
            Result.fail(JournalIdentityError(s"journal id '$value' must be a logical route-segment, not a physical path or URI"))
        else if !value.forall(c => (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') then
            Result.fail(JournalIdentityError(s"journal id '$value' must contain only lowercase letters, digits, and '-'"))
        else Result.succeed(value)
end JournalId

/** Stable logical entry reference: (journalId, streamId, offset). It carries a logical URI
  * only, never a physical path, segment file, or byte offset.
  */
final case class JournalEntryRef(journalId: JournalId, streamId: Event.StreamId, offset: Event.StreamOffset) derives CanEqual:
    /** Renders the logical `journal:` URI for this reference (logical ids only). */
    def uri(using Frame): String =
        // Renders journal:<journalId>/<streamId>/<offset>; never a physical path.
        JournalEntryRef.render(this)
end JournalEntryRef

object JournalEntryRef:
    /** Parses a logical `journal:` URI into a reference. Rejects file:// and any physical form
      * (segment path, byteOffset) with Abort[JournalIdentityError].
      */
    def parse(uri: String)(using Frame): JournalEntryRef < Abort[JournalIdentityError] =
        // Parses the logical-URI grammar and rejects every physical URI form.
        Abort.get(JournalEntryRef.parseLogical(uri))

    // journal:<journalId>/<streamId>/<offset> only; file:// and any other scheme or a bare
    // physical path are rejected.
    private[kyo] def parseLogical(uri: String)(using Frame): Result[JournalIdentityError, JournalEntryRef] =
        val Scheme = "journal:"
        if uri.isEmpty then Result.fail(JournalIdentityError("journal entry ref uri must not be empty"))
        else if !uri.startsWith(Scheme) then
            Result.fail(JournalIdentityError(s"journal entry ref uri '$uri' must use the 'journal:' scheme"))
        else
            uri.substring(Scheme.length).split("/", -1) match
                case Array(jidStr, sidStr, offStr) =>
                    for
                        journalId <- JournalId.validate(jidStr)
                        streamId <- Event.StreamId(sidStr).mapFailure(e =>
                            JournalIdentityError(s"journal entry ref uri '$uri' has an invalid stream segment: ${e.getMessage()}")
                        )
                        offsetValue <- Result.catching[NumberFormatException](offStr.toLong)
                            .mapFailure(_ => JournalIdentityError(s"journal entry ref uri '$uri' has a non-numeric offset segment"))
                        offset <- Event.StreamOffset(offsetValue).mapFailure(e =>
                            JournalIdentityError(s"journal entry ref uri '$uri' has an invalid offset: ${e.getMessage()}")
                        )
                    yield JournalEntryRef(journalId, streamId, offset)
                case _ =>
                    Result.fail(JournalIdentityError(
                        s"journal entry ref uri '$uri' must have the form journal:<journalId>/<streamId>/<offset>"
                    ))
        end if
    end parseLogical

    // Renders journal:<journalId>/<streamId>/<offset>; never a physical path.
    private[kyo] def render(ref: JournalEntryRef)(using Frame): String =
        s"journal:${ref.journalId.value}/${ref.streamId.value}/${ref.offset.value}"
end JournalEntryRef
