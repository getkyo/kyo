package kyo.internal

import kyo.*

/** Base64 decoders for CDP wire payloads.
  *
  * Both helpers translate a malformed Base64 input (which the cross-platform [[kyo.Base64]] decoder reports as [[Result.Failure]] carrying
  * an [[IllegalArgumentException]]) into a typed [[BrowserDecodingException]] keyed by the originating CDP `method`, so the malformed-wire
  * path stays inside the typed-error channel rather than escaping as a thrown exception.
  *
  * The helpers live in `kyo.internal` next to the rest of the wire-translation utilities ([[CdpEvalDecoder]]). Exposed as `private[kyo]`
  * so unit tests in `kyo` and `kyo.internal` can exercise the translation without round-tripping through a live CDP connection.
  */
private[kyo] object CdpBase64Decode:

    /** Decodes a CDP wire Base64 payload to raw bytes, translating malformed input to a typed [[BrowserDecodingException]]. */
    def decodeWireBase64(method: String, data: String)(using Frame): Span[Byte] < Abort[BrowserDecodingException] =
        Base64.decode(data) match
            case Result.Success(bytes) => bytes
            case Result.Failure(err)   => Abort.fail(BrowserDecodingException(method, err.getMessage))
            case Result.Panic(t)       => Abort.panic(t)

    /** Decodes a CDP screenshot/data Base64 payload into an [[Image]], translating malformed input to a typed [[BrowserDecodingException]]
      * (mirrors [[decodeWireBase64]]).
      */
    def decodeScreenshotImage(method: String, data: String)(using Frame): Image < Abort[BrowserDecodingException] =
        Image.fromBase64(data) match
            case Result.Success(img) => img
            case Result.Failure(err) => Abort.fail(BrowserDecodingException(method, err.getMessage))
            case Result.Panic(t)     => Abort.panic(t)

end CdpBase64Decode

/** Renders a CDP `ExceptionDetails` into a human-readable diagnostic.
  *
  * CDP's `text` is a short prefix like "Uncaught"; the full message lives in `exception.description`. Frames are rendered V8-style:
  * ```scala
  *   <head>
  *     at <fn> (<url>:<line>:<col>)
  * ```
  * Empty `functionName` becomes `<anonymous>`; missing `url` becomes `<eval>` (typical for inline `Runtime.evaluate` expressions).
  */
private[kyo] object ExceptionDetailsFormat:

    def format(ex: ExceptionDetails): String =
        val text    = ex.text.getOrElse("")
        val desc    = ex.exception.flatMap(_.descriptionOpt).getOrElse("")
        val head    = Seq(text, desc).filter(_.nonEmpty).mkString(": ")
        val headMsg = if head.isEmpty then "Unknown script error" else head
        val frames  = ex.stackTrace.fold("")(formatStackFrames)
        if frames.isEmpty then headMsg else s"$headMsg\n$frames"
    end format

    private def formatStackFrames(stackTrace: CdpStackTrace): String =
        stackTrace.callFrames.map { frame =>
            val fn  = if frame.functionName.nonEmpty then frame.functionName else "<anonymous>"
            val url = if frame.url.nonEmpty then frame.url else "<eval>"
            s"  at $fn ($url:${frame.lineNumber}:${frame.columnNumber})"
        }.mkString("\n")

end ExceptionDetailsFormat

/** Eval-result post-processing helpers shared by `BrowserEval.evalJs`, `BrowserEval.evalJsChecked`, `Browser.tryAcceptCookies`,
  * `NavigationWatcher.waitForLoad`, and the inlined `evalJsOn` call sites in `BrowserSnapshot` / `BrowserTabSetup`.
  *
  * Pure projections over the typed CDP `Runtime.evaluate` [[EvalResult]]; no `Browser` effect coupling.
  */
private[kyo] object CdpEvalDecoder:

    /** Extracts the evaluated value from a typed `Runtime.evaluate` envelope.
      *
      * Per-variant projection (typed match over [[RemoteObject]]):
      *   - `string` / `bigint`: the inner `value` string verbatim.
      *   - `number`: the inner `value` Double, stringified preserving the int-vs-decimal source distinction (integer-valued doubles render
      *     as `Long.toString`; non-integer as `Double.toString`).
      *   - `boolean`: `"true"` or `"false"`.
      *   - `object` / `function` / `symbol`: the variant's `description` if CDP supplied one (e.g. `"Promise"`), otherwise `""`. CDP returns
      *     non-serialisable objects without a value, and `Browser.eval` callers expect a non-Abort result for that case.
      *   - `undefined`: `""`.
      */
    private[kyo] def extractEvalValue(env: EvalResult)(using Frame): String < Abort[BrowserReadException] =
        extractRemoteObjectValue(env.result)

    /** Projects a typed `Runtime.evaluate` [[EvalResult]] to its string value, raising
      * [[BrowserProtocolErrorException]] when the eval reported `exceptionDetails`. The engine decodes the
      * reply to a typed [[EvalResult]] via its schema, so there is no JSON re-parse.
      */
    private[kyo] def extractValueOrFail(env: EvalResult)(using Frame): String < Abort[BrowserReadException] =
        env.exceptionDetails match
            case Present(ex) =>
                Abort.fail(BrowserProtocolErrorException.internalEvalFailed(ExceptionDetailsFormat.format(ex)))
            case Absent => extractEvalValue(env)

    private def extractRemoteObjectValue(ro: RemoteObject): String =
        ro match
            case s: RemoteObject.`string` => s.value
            case n: RemoteObject.`number` =>
                // CDP sends integer literals as JSON numbers (`20`); kyo-schema decodes them as Double (`20.0`). Round-trip through Long
                // when the Double is integer-valued so callers see "20" rather than "20.0". (Loses precision only for integers > 2^53,
                // which are not present in CDP's `Runtime.evaluate` payloads.)
                val d      = n.value
                val asLong = d.toLong
                if !d.isInfinite && !d.isNaN && d == asLong.toDouble then asLong.toString else d.toString
            case b: RemoteObject.`boolean` => b.value.toString
            case o: RemoteObject.`object`  =>
                // CDP encodes JS `null` as a `type=object` RemoteObject with `subtype="null"`. Surface the literal
                // string `"null"` so callers (e.g. `localStorage.getItem(missing)`) can distinguish null from empty.
                o.subtype match
                    case Present("null") => "null"
                    case _               => o.description.getOrElse("")
            case f: RemoteObject.`function`  => f.description.getOrElse("")
            case s: RemoteObject.`symbol`    => s.description.getOrElse("")
            case b: RemoteObject.`bigint`    => b.value
            case _: RemoteObject.`undefined` => ""

    /** Decodes the JSON array reply emitted by `Browser.textAll` / `Browser.attributeAll` JS probes (`JSON.stringify([...])`). Non-empty
      * input that fails to decode as `Seq[String]` raises `BrowserProtocolErrorException.decodeFailure(label, …)`. Empty input is treated
      * as the "no match" sentinel and returns `Chunk.empty`.
      */
    private[kyo] def decodeStringListReply(label: String, json: String)(using Frame): Chunk[String] < (Sync & Abort[BrowserReadException]) =
        if json.isEmpty then Chunk.empty
        else
            Json.decode[Seq[String]](json) match
                case Result.Success(list) => Chunk.from(list)
                case other =>
                    Log.warn(s"$label: unexpected wire shape decoding Seq[String]: $other; raw=$json")
                        .andThen(Abort.fail(BrowserProtocolErrorException.decodeFailure(label, s"$other; raw=$json")))

end CdpEvalDecoder
