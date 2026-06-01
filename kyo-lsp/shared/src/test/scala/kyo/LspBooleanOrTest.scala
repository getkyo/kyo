package kyo

/** Tests for BooleanOr[T] and StringOr[T] parameterized schemas (INV-050).
  *
  * Verifies that boolean JSON nodes decode to Bool(v), object JSON nodes decode to Options(T),
  * and that round-trips preserve value identity. Also confirms the fromStructureValue path
  * dispatches correctly.
  */
class LspBooleanOrTest extends Test:

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow
    private def roundtrip[A: Schema](value: A): A   = decode[A](encode[A](value))

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // ---- BooleanOr[T] encode tests ----

    "BooleanOr.Bool(true) encodes to true" in {
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Bool(true)
        assert(encode(v) == "true")
    }

    "BooleanOr.Bool(false) encodes to false" in {
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Bool(false)
        assert(encode(v) == "false")
    }

    "BooleanOr.Options(T) encodes as T object" in {
        val opts                                            = LspHandler.SaveOptions(includeText = Present(true))
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Options(opts)
        val json                                            = encode(v)
        assert(json.contains("includeText"))
        assert(json.contains("true"))
    }

    // ---- BooleanOr[T] decode tests ----

    "BooleanOr decodes true -> Bool(true)" in {
        val v = decode[LspHandler.BooleanOr[LspHandler.SaveOptions]]("true")
        assert(v == LspHandler.BooleanOr.Bool(true))
    }

    "BooleanOr decodes false -> Bool(false)" in {
        val v = decode[LspHandler.BooleanOr[LspHandler.SaveOptions]]("false")
        assert(v == LspHandler.BooleanOr.Bool(false))
    }

    "BooleanOr decodes object -> Options(T)" in {
        val v = decode[LspHandler.BooleanOr[LspHandler.SaveOptions]]("""{"includeText":true}""")
        v match
            case LspHandler.BooleanOr.Options(opts) => assert(opts.includeText == Present(true))
            case other                              => assert(false, s"Expected Options, got $other")
    }

    "BooleanOr decodes empty object -> Options(T)" in {
        val v = decode[LspHandler.BooleanOr[LspHandler.SaveOptions]]("{}")
        v match
            case LspHandler.BooleanOr.Options(opts) =>
                // Verify decoded case and that the nested options is the right type.
                assert(opts.isInstanceOf[LspHandler.SaveOptions], s"Expected SaveOptions inside Options, got ${opts.getClass}")
            case other => assert(false, s"Expected Options, got $other")
        end match
    }

    // ---- BooleanOr[T] round-trip tests ----

    "BooleanOr.Bool(true) round-trips" in {
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Bool(true)
        assert(roundtrip(v) == v)
    }

    "BooleanOr.Bool(false) round-trips" in {
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Bool(false)
        assert(roundtrip(v) == v)
    }

    "BooleanOr.Options round-trips" in {
        val opts                                            = LspHandler.SaveOptions(includeText = Present(false))
        val v: LspHandler.BooleanOr[LspHandler.SaveOptions] = LspHandler.BooleanOr.Options(opts)
        assert(roundtrip(v) == v)
    }

    "BooleanOr works with CompletionOptions.ItemOptions" in {
        val opts = LspHandler.CompletionOptions.ItemOptions(labelDetailsSupport = Present(true))
        val v: LspHandler.BooleanOr[LspHandler.CompletionOptions.ItemOptions] = LspHandler.BooleanOr.Options(opts)
        val back                                                              = roundtrip(v)
        assert(back == v)
    }

    // ---- StringOr[T] encode tests ----

    "StringOr.Str encodes to JSON string" in {
        val v: LspHandler.StringOr[LspHandler.SaveOptions] = LspHandler.StringOr.Str("auto")
        assert(encode(v) == "\"auto\"")
    }

    "StringOr.Options(T) encodes as T object" in {
        val opts                                           = LspHandler.SaveOptions(includeText = Present(true))
        val v: LspHandler.StringOr[LspHandler.SaveOptions] = LspHandler.StringOr.Options(opts)
        val json                                           = encode(v)
        assert(json.contains("includeText"))
    }

    // ---- StringOr[T] decode tests ----

    "StringOr decodes JSON string -> Str(v)" in {
        val v = decode[LspHandler.StringOr[LspHandler.SaveOptions]]("\"auto\"")
        assert(v == LspHandler.StringOr.Str("auto"))
    }

    "StringOr decodes object -> Options(T)" in {
        val v = decode[LspHandler.StringOr[LspHandler.SaveOptions]]("""{"includeText":false}""")
        v match
            case LspHandler.StringOr.Options(opts) => assert(opts.includeText == Present(false))
            case other                             => assert(false, s"Expected Options, got $other")
    }

    // ---- StringOr[T] round-trip tests ----

    "StringOr.Str round-trips" in {
        val v: LspHandler.StringOr[LspHandler.SaveOptions] = LspHandler.StringOr.Str("full")
        assert(roundtrip(v) == v)
    }

    "StringOr.Options round-trips" in {
        val opts                                           = LspHandler.SaveOptions(includeText = Present(true))
        val v: LspHandler.StringOr[LspHandler.SaveOptions] = LspHandler.StringOr.Options(opts)
        assert(roundtrip(v) == v)
    }

    "StringOr empty string round-trips" in {
        val v: LspHandler.StringOr[LspHandler.SaveOptions] = LspHandler.StringOr.Str("")
        assert(roundtrip(v) == v)
    }

    // ---- Nested BooleanOr in a record (TextDocumentSyncOptions.save field) ----

    "TextDocumentSyncOptions with BooleanOr.Bool save round-trips" in {
        val opts = LspHandler.TextDocumentSyncOptions(
            openClose = Present(true),
            save = Present(LspHandler.BooleanOr.Bool(true))
        )
        val back = roundtrip(opts)
        assert(back.openClose == Present(true))
        back.save match
            case Present(LspHandler.BooleanOr.Bool(b)) =>
                assert(b, s"Expected BooleanOr.Bool(true), got BooleanOr.Bool(false)")
            case other => assert(false, s"Expected BooleanOr.Bool(true), got $other")
        end match
    }

    "TextDocumentSyncOptions with BooleanOr.Options save round-trips" in {
        val opts = LspHandler.TextDocumentSyncOptions(
            save = Present(LspHandler.BooleanOr.Options(LspHandler.SaveOptions(includeText = Present(true))))
        )
        val back = roundtrip(opts)
        back.save match
            case Present(LspHandler.BooleanOr.Options(savedOpts)) =>
                assert(savedOpts.includeText == Present(true))
            case other => assert(false, s"Unexpected: $other")
        end match
    }

    // ---- Schema singleton check ----

    "BooleanOr schema has empty segments (transform-based)" in {
        val s = summon[Schema[LspHandler.BooleanOr[LspHandler.SaveOptions]]]
        assert(s.segments.isEmpty)
    }

    "StringOr schema has empty segments (transform-based)" in {
        val s = summon[Schema[LspHandler.StringOr[LspHandler.SaveOptions]]]
        assert(s.segments.isEmpty)
    }

end LspBooleanOrTest
