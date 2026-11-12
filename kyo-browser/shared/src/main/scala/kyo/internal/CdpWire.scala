package kyo.internal

import kyo.*

/** Base64 decoders for CDP wire payloads.
  *
  * Both helpers translate a malformed Base64 input (which the cross-platform [[kyo.Base64]] decoder reports as [[Result.Failure]] carrying
  * a [[kyo.Base64.Failure]]) into a typed [[BrowserDecodingException]] keyed by the originating CDP `method`, so the malformed-wire path
  * stays inside the typed-error channel rather than escaping as a thrown exception.
  *
  * The helpers live in `kyo.internal` next to the rest of the wire-translation utilities ([[CdpEvalDecoder]]). Exposed as `private[kyo]`
  * so unit tests in `kyo` and `kyo.internal` can exercise the translation without round-tripping through a live CDP connection.
  */
private[kyo] object CdpBase64Decode:

    /** Decodes a CDP wire Base64 payload to raw bytes, translating malformed input to a typed [[BrowserDecodingException]]. */
    def decodeWireBase64(method: String, data: String)(using Frame): Span[Byte] < Abort[BrowserDecodingException] =
        Base64.decode(data) match
            case Result.Success(bytes) => bytes
            case Result.Failure(err)   => Abort.fail(BrowserDecodingException(method, err.message))
            case Result.Panic(t)       => Abort.panic(t)

    /** Decodes a CDP screenshot/data Base64 payload into an [[Image]], translating malformed input to a typed [[BrowserDecodingException]]
      * (mirrors [[decodeWireBase64]]).
      */
    def decodeScreenshotImage(method: String, data: String)(using Frame): Image < Abort[BrowserDecodingException] =
        Image.fromBase64(data) match
            case Result.Success(img) => img
            case Result.Failure(err) => Abort.fail(BrowserDecodingException(method, err.message))
            case Result.Panic(t)     => Abort.panic(t)

end CdpBase64Decode

/** Renders a CDP `ExceptionDetails` into a human-readable diagnostic.
  *
  * CDP's `text` is a short prefix like "Uncaught"; the exception holds a primitive value or an object's description. Frames are rendered V8-style:
  * ```scala
  *   <head>
  *     at <fn> (<url>:<line>:<col>)
  * ```
  * Empty `functionName` becomes `<anonymous>`; missing `url` becomes `<eval>` (typical for inline `Runtime.evaluate` expressions).
  */
private[kyo] object ExceptionDetailsFormat:

    def format(ex: ExceptionDetails): String =
        val text    = ex.text.getOrElse("")
        val desc    = ex.exception.map(_.text).getOrElse("")
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
      *   - `number`: integer-valued doubles inside Long's range render without a decimal suffix; other values use `Double.toString`.
      *     Negative zero retains its sign.
      *   - `boolean`: `"true"` or `"false"`.
      *   - `object` / `function` / `symbol`: the variant's `description` if CDP supplied one (e.g. `"Promise"`), otherwise `""`. CDP returns
      *     non-serialisable objects without a value, and `Browser.eval` callers expect a non-Abort result for that case.
      *   - `undefined`: `""`.
      */
    private[kyo] def extractEvalValue(env: EvalResult)(using Frame): String < Abort[BrowserReadException] =
        env.result match
            case _: RemoteObject.`undefined` => ""
            case value                       => value.text

    /** Projects a typed `Runtime.evaluate` [[EvalResult]] to its string value, raising
      * [[BrowserProtocolErrorException]] when the eval reported `exceptionDetails`. The engine decodes the
      * reply to a typed [[EvalResult]] via its schema, so there is no JSON re-parse.
      */
    private[kyo] def extractValueOrFail(env: EvalResult)(using Frame): String < Abort[BrowserReadException] =
        env.exceptionDetails match
            case Present(ex) =>
                Abort.fail(BrowserProtocolErrorException.internalEvalFailed(ExceptionDetailsFormat.format(ex)))
            case Absent => extractEvalValue(env)

    /** Decodes the JSON array reply emitted by `Browser.textAll` / `Browser.attributeAll` JS probes (`JSON.stringify([...])`). Non-empty
      * input that fails to decode as `Seq[String]` raises `BrowserProtocolErrorException.decodeFailure(label, …)`. Empty input is treated
      * as the "no match" sentinel and returns `Chunk.empty`.
      */
    private[kyo] def decodeStringListReply(label: String, json: String)(using Frame): Chunk[String] < (Sync & Abort[BrowserReadException]) =
        if json.isEmpty then Chunk.empty
        else
            Json.decode[Seq[String]](json) match
                case Result.Success(list) => Chunk.from(list)
                case other                =>
                    Log.warn(s"$label: unexpected wire shape decoding Seq[String]: $other; raw=$json")
                        .andThen(Abort.fail(BrowserProtocolErrorException.decodeFailure(label, s"$other; raw=$json")))

end CdpEvalDecoder
