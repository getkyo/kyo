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

    def eventDefinitionErrorMessages: List[String] =
        scala.compiletime.testing.typeCheckErrors(
            """
            val missing: kyo.EventLog.EventDefinition[Any, Any] = ???
            """
        ).map(_.message)
end EventLogCodecsAccessibilityFixture
