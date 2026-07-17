package external

/** Compile-only accessibility fixture for [[kyo.EventLogCodecTest]]'s authority-guard leaf. Lives
  * in a top-level package with no relation to `kyo` (not a subpackage of it), so
  * `kyo.EventLogCodecs.Codecs`'s `private[kyo]` primary constructor is genuinely inaccessible from
  * here, unlike a check embedded in a `package kyo` test file where the constructor would be
  * callable.
  */
object EventLogCodecsAccessibilityFixture:
    def errorMessages: List[String] =
        scala.compiletime.testing.typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val bad = kyo.EventLogCodecs.Codecs(
                kyo.EventLogCodecs.ValueCodec.BytesValue,
                kyo.EventLogCodecs.MetadataCodec(kyo.IonBinary())
            )
            """
        ).map(_.message)

    /** Positive "no codec slot" guard: [[kyo.Event.Definition]] carries exactly
      * `eventType`/`stream`/`eventId`/`metadata` and no codec-typed member, so a snippet reading a
      * `.codec` (or `.valueCodec`/`.metadataCodec`) member off a constructed value fails to
      * type-check. An absence check (asserting `Event.Definition` does not exist at all) would
      * type-check cleanly and silently pass now that the type exists, so this fixture asserts the
      * stronger, always-meaningful property instead.
      */
    def eventDefinitionErrorMessages: List[String] =
        scala.compiletime.testing.typeCheckErrors(
            """
            val defn: kyo.Event.Definition[String, String] = ???
            defn.codec
            """
        ).map(_.message)
end EventLogCodecsAccessibilityFixture
