package kyo

import kyo.*
import scala.annotation.nowarn

/** Base class for all JSON-RPC errors, organized into four sealed subcategories by operational stage.
  *
  * The four subcategories map to distinct pipeline stages where errors arise:
  *   - [[kyo.JsonRpcParseFailure]]: errors surfaced during JSON-text parsing or envelope shape validation
  *   - [[kyo.JsonRpcDispatchFailure]]: errors surfaced during method routing or parameter validation
  *   - [[kyo.JsonRpcExecutionFailure]]: errors surfaced during handler execution, transport, lifecycle, or configuration
  *   - [[kyo.JsonRpcApplicationFailure]]: user-domain errors from handler bodies
  *
  * Every leaf carries typed fields; the human-readable `message` is constructed from those fields
  * inside the case-class body. No free-form `detail: String` parameters appear on any leaf.
  *
  * Handler operations fail with `Abort[JsonRpcError]` as their error channel. Match on the specific
  * subtype or subcategory trait to distinguish recoverable transport failures from unrecoverable
  * protocol errors.
  *
  * @see
  *   [[kyo.JsonRpcParseFailure]] Parse-stage failures
  * @see
  *   [[kyo.JsonRpcDispatchFailure]] Dispatch-stage failures
  * @see
  *   [[kyo.JsonRpcExecutionFailure]] Execution-stage failures
  * @see
  *   [[kyo.JsonRpcApplicationFailure]] Application-domain failures
  */
sealed abstract class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Maybe[Structure.Value],
    cause: String | Throwable = ""
)(using Frame)
    extends KyoException(message, cause)

object JsonRpcError:
    /** Wire decoder: maps known error codes back to specific leaf types; unknown codes become [[JsonRpcCustomError]].
      *
      * Used by the codec when deserializing an `error` field from a JSON-RPC response envelope. Codes in the
      * implementation-defined range (-32099 to -32000) are mapped to [[JsonRpcImplementationError]]; all other
      * unknown codes become [[JsonRpcCustomError]].
      */
    def fromWire(code: Int, message: String, data: Maybe[Structure.Value])(using Frame): JsonRpcError =
        code match
            case -32700 => JsonRpcParseError("[wire-received]", 0, JsonRpcParseError.Reason.TrailingContent)
            case -32600 => JsonRpcInvalidRequestError(data.getOrElse(Structure.Value.Null), Chunk.empty)
            case -32601 => JsonRpcMethodNotFoundError("[wire-received]", Chunk.empty)
            case -32602 => JsonRpcInvalidParamsError("[wire-received]", data, Chunk.empty)
            case -32603 => JsonRpcInternalError(JsonRpcInternalError.Operation.Other, new RuntimeException(message))
            case c if c >= -32099 && c <= -32000 => JsonRpcImplementationError(c, message, data)
            case _                               => JsonRpcCustomError(code, message, data)

    /** Hand-rolled [[Schema]] projecting any [[JsonRpcError]] to its wire triple `(code, message, data)`.
      *
      * On encode: writes the `code`, `message`, and `data` fields from the error.
      * On decode (from Structure.Value): uses [[fromWire]] to reconstruct the appropriate leaf type.
      *
      * The wire encoding matches the JSON-RPC 2.0 §5.1 error object shape:
      * `{"code": Int, "message": String, "data": Maybe[Structure.Value]}`.
      */
    @nowarn("msg=anonymous")
    given Schema[JsonRpcError] = new Schema[JsonRpcError](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(err: JsonRpcError, writer: Codec.Writer): Unit =
            writer.objectStart("JsonRpcError", 3)
            writer.field("code", 1)
            writer.int(err.code)
            writer.field("message", 2)
            writer.string(err.message)
            writer.field("data", 3)
            err.data match
                case Absent     => writer.nil()
                case Present(v) => Schema.writeStructureValue(writer, v)
            writer.objectEnd()
        end serializeWrite
        @publicInBinary private[kyo] def serializeRead(reader: Codec.Reader): JsonRpcError =
            // Binary deserialization path (JSON/Protobuf). For Structure.Value round-trips
            // the fromStructureValue override below is used instead.
            var code: Int       = -32603
            var message: String = ""
            val n               = reader.objectStart()
            var i               = 0
            while i < n do
                reader.fieldParse()
                if reader.matchField("code".getBytes("UTF-8")) then
                    code = reader.int()
                else if reader.matchField("message".getBytes("UTF-8")) then
                    message = reader.string()
                else
                    reader.skip()
                end if
                i += 1
            end while
            reader.objectEnd()
            fromWire(code, message, Absent)(using Frame.internal)
        end serializeRead
        @publicInBinary private[kyo] def getter(value: JsonRpcError): Maybe[Any] = Maybe(value)
        @publicInBinary private[kyo] def setter(value: JsonRpcError, next: Any): JsonRpcError =
            next match
                case e: JsonRpcError => e
                case _               => value
        // Open: see JsonRpcId for rationale.
        private lazy val _structure: Structure.Type =
            Structure.Type.Open(Tag[JsonRpcError].asInstanceOf[Tag[Any]])
        override def structure: Structure.Type = _structure
        override private[kyo] def fromStructureValue(sv: Structure.Value)(using Frame): Result[DecodeException, JsonRpcError] =
            sv match
                case Structure.Value.Record(fields) =>
                    val m       = fields.iterator.toMap
                    val code    = m.get("code").collect { case Structure.Value.Integer(n) => n.toInt }.getOrElse(-32603)
                    val message = m.get("message").collect { case Structure.Value.Str(s) => s }.getOrElse("")
                    val data: Maybe[Structure.Value] = m.get("data") match
                        case Some(Structure.Value.Null) | None => Absent
                        case Some(v)                           => Present(v)
                    Result.Success(fromWire(code, message, data))
                case _ =>
                    Result.Failure(TypeMismatchException(Seq.empty, "Record", sv.toString))
end JsonRpcError

// --- Parse-failure category ---

/** Marks errors surfaced during JSON-text parsing or envelope-shape validation. */
sealed trait JsonRpcParseFailure extends JsonRpcError

// --- Dispatch-failure category ---

/** Marks errors surfaced during method routing or parameter validation. */
sealed trait JsonRpcDispatchFailure extends JsonRpcError

// --- Execution-failure category ---

/** Marks errors surfaced during handler-body execution or supporting operations (transport, lifecycle, configuration). */
sealed trait JsonRpcExecutionFailure extends JsonRpcError

// --- Application-failure category ---

/** Marks user-domain errors from handler bodies. Non-sealed: callers extend [[JsonRpcApplicationError]] with typed subclasses. */
sealed trait JsonRpcApplicationFailure extends JsonRpcError

// =============================================================================
// Parse-failure leaves
// =============================================================================

/** JSON parse failure (JSON-RPC 2.0 code -32700).
  *
  * Raised when inbound bytes cannot be parsed as well-formed JSON, or when the engine
  * constructs an outbound response to a peer's parse failure. The `input` excerpt and
  * `offset` carry enough context for the consumer to diagnose and log the problem without
  * re-parsing the raw bytes.
  *
  * @param input   the raw input string that failed to parse (truncated to 80 chars in the message)
  * @param offset  the byte offset at which parsing failed
  * @param reason  typed reason from [[JsonRpcParseError.Reason]]
  */
case class JsonRpcParseError(input: String, offset: Int, reason: JsonRpcParseError.Reason)(using Frame)
    extends JsonRpcError(
        code = -32700,
        message =
            s"""Parse error at offset $offset: ${reason.describe}.

  Input excerpt: ${input.take(80)}${if input.length > 80 then "..." else ""}""",
        data = Absent
    ) with JsonRpcParseFailure

object JsonRpcParseError:
    /** Typed reason vocabulary for [[JsonRpcParseError]]. Each case carries only the fields relevant to that reason. */
    enum Reason derives CanEqual:
        case UnexpectedEof
        case UnexpectedChar(c: Char, expected: String)
        case InvalidEscape(seq: String)
        case NumberOutOfRange(value: String)
        case TrailingContent
        def describe: String = this match
            case UnexpectedEof               => "unexpected end of input"
            case UnexpectedChar(c, expected) => s"unexpected '$c', expected $expected"
            case InvalidEscape(seq)          => s"invalid escape sequence \\$seq"
            case NumberOutOfRange(value)     => s"number $value out of range"
            case TrailingContent             => "trailing content after JSON value"
    end Reason
end JsonRpcParseError

/** Invalid JSON-RPC envelope shape (JSON-RPC 2.0 code -32600).
  *
  * Raised when a received message is valid JSON but fails to conform to the JSON-RPC 2.0
  * envelope schema (e.g., missing required `jsonrpc`, `method`, or `id` fields), or when
  * the codec falls back from an unrecognizable wire error object.
  *
  * @param received      the raw [[Structure.Value]] that failed envelope validation
  * @param missingFields fields absent from the envelope that are required by the spec
  */
case class JsonRpcInvalidRequestError(received: Structure.Value, missingFields: Chunk[String])(using Frame)
    extends JsonRpcError(
        code = -32600,
        message =
            s"""Invalid Request.

  Missing required fields: ${if missingFields.isEmpty then "(none)" else missingFields.mkString(", ")}""",
        data = Present(received)
    ) with JsonRpcParseFailure

// =============================================================================
// Dispatch-failure leaves
// =============================================================================

/** Unknown method name (JSON-RPC 2.0 code -32601).
  *
  * Raised by the engine dispatcher when an inbound request names a method that has no
  * registered handler. The `available` list lets the caller discover valid method names
  * without a separate negotiation round-trip.
  *
  * @param method     the method name that was not found
  * @param available  the methods currently registered on this endpoint
  */
case class JsonRpcMethodNotFoundError(method: String, available: Chunk[String])(using Frame)
    extends JsonRpcError(
        code = -32601,
        message =
            s"""Method not found: '$method'.

  Available methods: ${if available.isEmpty then "(none)" else available.mkString(", ")}""",
        data = Absent
    ) with JsonRpcDispatchFailure

/** Parameter validation failure (JSON-RPC 2.0 code -32602).
  *
  * Raised when the engine successfully parses and routes an inbound request but the
  * parameter payload fails schema validation before the handler is invoked.
  *
  * @param method    the method name whose params failed validation
  * @param received  the raw params value as received (for diagnostic context)
  * @param errors    one [[JsonRpcInvalidParamsError.ParamError]] per failing field
  */
case class JsonRpcInvalidParamsError(
    method: String,
    received: Maybe[Structure.Value],
    errors: Chunk[JsonRpcInvalidParamsError.ParamError]
)(using Frame)
    extends JsonRpcError(
        code = -32602,
        message =
            s"""Invalid params for '$method'.

  Errors: ${if errors.isEmpty then "(none)" else errors.map(_.describe).mkString("; ")}""",
        data = received
    ) with JsonRpcDispatchFailure

object JsonRpcInvalidParamsError:
    /** Associates a field path with the specific [[Problem]] that occurred on it. */
    case class ParamError(field: String, problem: Problem):
        def describe: String = s"'$field': ${problem.describe}"

    /** Typed problem vocabulary for a single parameter field. */
    enum Problem derives CanEqual:
        case Missing
        case TypeMismatch(expected: String, received: String)
        case ConstraintViolation(constraint: String)
        def describe: String = this match
            case Missing                          => "missing required field"
            case TypeMismatch(expected, received) => s"expected $expected, got $received"
            case ConstraintViolation(constraint)  => s"constraint violated: $constraint"
    end Problem
end JsonRpcInvalidParamsError

// =============================================================================
// Execution-failure leaves
// =============================================================================

/** Endpoint misconfiguration detected at runtime (JSON-RPC 2.0 code -32603).
  *
  * Raised when the engine detects that a required configuration setting is absent or invalid
  * before it can process a request. This is a caller-API-misuse error, not an internal panic.
  *
  * @param setting  the name of the misconfigured setting (e.g. `"progressPolicy"`)
  * @param reason   a brief explanation of what is wrong with the setting
  */
case class JsonRpcConfigurationError(setting: String, reason: String)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Configuration error: '$setting'; $reason",
        data = Absent
    ) with JsonRpcExecutionFailure

/** Endpoint lifecycle transition error (JSON-RPC 2.0 code -32603).
  *
  * Raised when an operation is attempted on an endpoint that is in an incompatible lifecycle
  * stage (e.g., calling after the endpoint has been closed). Mirrors [[kyo.HttpBindException]]
  * in shape: a typed stage field replaces the string-prefix match in consumer code.
  *
  * @param stage  the lifecycle stage in which the error occurred
  */
case class JsonRpcLifecycleError(stage: JsonRpcLifecycleError.Stage)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Lifecycle error: ${stage.describe}",
        data = Absent
    ) with JsonRpcExecutionFailure

object JsonRpcLifecycleError:
    /** Named lifecycle stages at which a [[JsonRpcLifecycleError]] can be raised. */
    enum Stage derives CanEqual:
        case Init, Bind, Connect, Drain, Close
        def describe: String = this.toString.toLowerCase
    end Stage
end JsonRpcLifecycleError

/** Wire transport failure (JSON-RPC 2.0 code -32603).
  *
  * Raised when the underlying wire transport fails: the connection is closed unexpectedly,
  * a send callback returns an error, or the receive stream terminates with a non-EOF failure.
  * Mirrors [[kyo.HttpConnectException]] in shape: a detail string + a causal Throwable.
  *
  * @param detail  brief description of what went wrong (e.g. the closed exception's message)
  * @param cause   the underlying throwable that triggered the transport failure
  */
case class JsonRpcTransportError(detail: String, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Transport error: $detail (${cause.getMessage})",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure

/** Unhandled panic from a route handler (JSON-RPC 2.0 code -32603).
  *
  * Raised when a handler body throws an uncaught exception (i.e., a `Result.Panic`). The
  * `method` field records which handler panicked; information that the current flat
  * `internalError` string loses. Mirrors [[kyo.HttpHandlerException]] in shape.
  *
  * @param method  the registered method name whose handler produced the panic
  * @param cause   the throwable that caused the panic
  */
case class JsonRpcHandlerPanicError(method: String, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Handler '$method' panicked: ${cause.getMessage}",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure

/** Residual catchall for internal engine errors not covered by a specialized leaf (JSON-RPC 2.0 code -32603).
  *
  * Used for programmer-error paths such as response-decode failures and response-encode failures that
  * are local to the engine and not caused by the peer or the handler. The [[JsonRpcInternalError.Operation]]
  * field makes the operational context typed so consumers can discriminate without string-prefix matching.
  *
  * @param operation  which internal engine operation failed
  * @param cause      the underlying throwable
  */
case class JsonRpcInternalError(operation: JsonRpcInternalError.Operation, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Internal error during ${operation.describe}: ${cause.getMessage}",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure

object JsonRpcInternalError:
    /** Named operations at which a [[JsonRpcInternalError]] can be raised. */
    enum Operation derives CanEqual:
        case DecodeResult, EncodeResponse, Other
        def describe: String = this match
            case DecodeResult   => "result decode"
            case EncodeResponse => "response encode"
            case Other          => "internal operation"
    end Operation
end JsonRpcInternalError

/** Implementation-defined server error in the JSON-RPC 2.0 reserved range -32099 to -32000.
  *
  * The constructor is private; use the [[JsonRpcImplementationError.apply]] factory which
  * validates that the code falls in the reserved server-error range.
  *
  * {{{
  * val err = JsonRpcImplementationError(-32050, "Rate limited")
  * val errWithData = JsonRpcImplementationError(-32050, "Rate limited", Present(Structure.Value.Integer(60)))
  * }}}
  *
  * @param code   error code in [-32099, -32000]
  * @param label  short human-readable label for this server error
  * @param data   optional structured data attached to the wire error object
  */
case class JsonRpcImplementationError private (
    override val code: Int,
    label: String,
    override val data: Maybe[Structure.Value]
)(using Frame)
    extends JsonRpcError(
        code = code,
        message = s"Server error ($code): $label",
        data = data
    ) with JsonRpcExecutionFailure

object JsonRpcImplementationError:
    /** Smart constructor that validates the code is in the reserved [-32099, -32000] range. */
    def apply(code: Int, label: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcImplementationError =
        require(
            code >= -32099 && code <= -32000,
            s"JsonRpcImplementationError code must be in [-32099, -32000], got $code"
        )
        new JsonRpcImplementationError(code, label, data)
    end apply
end JsonRpcImplementationError

// =============================================================================
// Application-failure leaves
// =============================================================================

/** Base class for user-domain errors with caller-defined codes outside the JSON-RPC reserved range.
  *
  * Non-sealed: callers extend this to define their own typed application errors:
  *
  * {{{
  * case class Unauthorized()(using Frame) extends JsonRpcApplicationError(401, "Unauthorized")
  * case class NotFound(resource: String)(using Frame) extends JsonRpcApplicationError(404, s"Not found: $resource")
  * }}}
  *
  * The `code` and `message` are fully caller-defined. The optional `data` slot carries
  * structured diagnostic context. The optional `cause` chains an underlying throwable.
  *
  * @param code    caller-defined integer error code
  * @param message caller-defined human-readable message
  * @param data    optional structured data for the wire error object
  * @param cause   optional underlying throwable or string cause
  */
abstract class JsonRpcApplicationError(
    code: Int,
    message: String,
    data: Maybe[Structure.Value] = Absent,
    cause: String | Throwable = ""
)(using Frame)
    extends JsonRpcError(code, message, data, cause) with JsonRpcApplicationFailure

/** Catchall application error for callers that do not want a typed subclass.
  *
  * Also used by [[JsonRpcError.fromWire]] for unknown codes received from the peer.
  *
  * @param code   caller-defined or peer-sent integer error code
  * @param label  brief description of the error
  * @param data   optional structured data
  */
case class JsonRpcCustomError(
    override val code: Int,
    label: String,
    override val data: Maybe[Structure.Value] = Absent
)(using Frame)
    extends JsonRpcApplicationError(code, s"Application error ($code): $label", data)
