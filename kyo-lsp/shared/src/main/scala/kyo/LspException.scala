package kyo

/** Base class for all LSP exceptions, organized by the operation that can raise them.
  *
  * The kyo-lsp boundary is flat: both engines re-encode every server-side `JsonRpcError` into one
  * [[LspRemoteException]] and never discriminate per method, so all request operations share the
  * identical producible-failure set. Two sealed per-operation traits name those sets:
  *   - [[LspRequestFailure]]: every client request and every server reverse-request can raise a
  *     remote application error or observe a closed connection.
  *   - [[LspInitFailure]]: the eager client `initialize` / `initialized` handshake.
  *
  * A trait names exactly the leaves that operation can produce, so a caller matches one and the
  * compiler narrows the row to prove what remains. Concrete leaves mix in every trait they belong
  * to, so [[LspConnectionClosedException]] (observable by every operation) lists both traits while a
  * notification, whose only failure is a closed transport, carries [[LspConnectionClosedException]]
  * directly with no trait.
  *
  * Extends [[JsonRpcApplicationError]], the cross-module extension point for JSON-RPC errors.
  * [[JsonRpcApplicationError]] itself extends [[JsonRpcError]], so every `LspException` is a valid
  * [[JsonRpcError]] and travels through `Abort[JsonRpcError | ...]` rows transparently. The inherited
  * `Schema[JsonRpcError]` encodes any `LspException` as the wire triple `(code, message, data)`. No
  * separate `Schema[LspException]` is needed.
  *
  * User-domain errors are registered per-handler via `handler.error[E2](code, message)` rather than
  * by extending this hierarchy directly; the hierarchy is sealed so callers exhaust pattern matches
  * on a given operation's trait without an open extension surface.
  *
  * @see [[LspRequestFailure]]
  * @see [[LspInitFailure]]
  * @see [[LspConnectionClosedException]]
  * @see [[LspRemoteException]]
  */
sealed abstract class LspException(
    code: Int,
    message: String,
    // Structure carve-out: data forwarded to JsonRpcApplicationError
    data: Maybe[Structure.Value] = Absent,
    cause: String | Throwable = ""
)(using Frame)
    extends JsonRpcApplicationError(
        code,
        message,
        data,
        cause
    )

// =============================================================================
// Per-operation failure traits
// =============================================================================
//
// One sealed trait per producible-failure shape; the trait IS the operation's `Abort` row. A
// concrete leaf mixes in every trait whose operation can produce it (see the leaf definitions
// below). LSP needs only two traits because the engine funnels all remote variation through one
// re-encoded leaf and never discriminates per method.

/** Failure row for every client request (`LspClient.completion`, `hover`, ...) and every server
  * reverse-request (`LspServer.showMessageRequest`, `applyEdit`, ...).
  */
sealed trait LspRequestFailure extends LspException

/** Failure row for the eager client `initialize` / `initialized` handshake (`LspClient.init`). */
sealed trait LspInitFailure extends LspException

// =============================================================================
// Connection / configuration leaves
// =============================================================================

/** The transport or peer closed the connection (-32603).
  *
  * The typed boundary leaf for a closed transport: every request, notification, and init operation
  * maps the underlying `Closed` signal from the JSON-RPC transport into this leaf, so no raw `Closed`
  * reaches a public row. Mixes into both operation-traits because any operation can observe a closed
  * connection; a notification carries it directly as the whole row.
  */
final case class LspConnectionClosedException()(using Frame)
    extends LspException(code = -32603, message = "Connection closed.")
    with LspRequestFailure
    with LspInitFailure

/** Invalid LSP configuration detected at initialization (-32603).
  *
  * Thrown as a panic by `LspConfig.require` when a configuration field is rejected (for example an
  * empty `positionEncodings` list). These are construction-time programmer errors, not recoverable
  * runtime failures, so they panic rather than appear on a tracked `Abort` row, matching the sibling
  * `JsonRpcHandler.Config.require`. The `setting` field names the offending configuration slot and
  * `reason` describes the violation.
  *
  * @param setting the configuration setting that is invalid
  * @param reason  a brief description of why the setting is invalid
  */
final case class LspConfigurationError(setting: String, reason: String)(using Frame)
    extends LspException(code = -32603, message = s"Invalid LSP configuration for '$setting': $reason")

// =============================================================================
// Remote-forward leaf (the universal client-observable failure)
// =============================================================================

/** Remote application error received from the peer.
  *
  * The universal remote-forward leaf: both engines re-encode every non-recovered wire `JsonRpcError`
  * into this leaf, so it is the only `LspException` leaf a request or init row carries besides
  * [[LspConnectionClosedException]]. The wire `code`, `message`, and `data` triple are preserved
  * verbatim; the caller pattern-matches on `remoteCode` to discriminate across the error families the
  * peer registered via `handler.error[E2](code, message)` (and the engine-internal gate rejections,
  * which arrive flattened into this leaf).
  *
  * @param remoteCode    the JSON-RPC error code from the wire response
  * @param remoteMessage the JSON-RPC error message from the wire response
  * @param remoteData    the Schema-encoded `data` payload (Absent when the peer did not include one)
  */
// Structure carve-out: passes wire data through to LspException
final case class LspRemoteException(
    remoteCode: Int,
    remoteMessage: String,
    remoteData: Maybe[Structure.Value] = Absent
)(using Frame)
    extends LspException(code = remoteCode, message = remoteMessage, data = remoteData)
    with LspRequestFailure
    with LspInitFailure

// =============================================================================
// Engine-internal gate leaves (raised server-side; reach the client as LspRemoteException)
// =============================================================================
//
// These are the only LspException leaves the server gate constructs. Each becomes a wire
// JsonRpcResponse.failure; the client's engine re-encodes it to LspRemoteException. They mix no
// operation-trait because they never appear on a client-facing row as themselves.

/** A method was called before the `initialize` handshake completed (-32002).
  *
  * Raised server-side when the gate rejects any request other than `initialize` before the handshake
  * is done. The `attemptedMethod` field names the premature request.
  *
  * @param attemptedMethod the method name that was called before initialization
  */
final case class LspNotInitializedException(attemptedMethod: String)(using Frame)
    extends LspException(code = -32002, message = s"Method '$attemptedMethod' called before initialize.")

/** A method was rejected because shutdown is in progress (-32600).
  *
  * Raised server-side when the gate rejects a request after `shutdown` was received.
  *
  * @param attemptedMethod the method name that was rejected
  */
final case class LspShutdownInProgressException(attemptedMethod: String)(using Frame)
    extends LspException(code = -32600, message = s"Shutdown in progress; '$attemptedMethod' rejected.")

/** A required server or client capability was not advertised (-32601).
  *
  * Raised server-side when the capability gate rejects a method whose required capability was not
  * declared during the handshake.
  *
  * @param name the capability name that was not advertised
  */
final case class LspCapabilityNotAdvertisedException(name: LspCapabilities.Name)(using Frame)
    extends LspException(code = -32601, message = s"Required capability '$name' was not advertised.")

// =============================================================================
// Construction-time programmer-error leaves (THROWN inside init, off every tracked row)
// =============================================================================
//
// Constructed via `throw` in LspCatalog while building the handler set synchronously inside
// LspServer.init / LspClient.init. They are construction-time programmer errors, not recoverable
// runtime failures, so they panic rather than appear on a tracked Abort row. They mix no
// operation-trait.

/** A handler was registered for a method that does not match its expected direction (-32603).
  *
  * @param handlerKind  the handler kind that was registered
  * @param registeredOn the direction it was registered on
  */
final case class LspWrongDirectionException(handlerKind: LspHandler.Kind, registeredOn: LspHandler.Direction)(using Frame)
    extends LspException(code = -32603, message = s"Handler for '$handlerKind' cannot be registered on $registeredOn.")

/** A handler attempted to register for an engine-reserved method (-32603).
  *
  * @param method the reserved method name
  */
final case class LspReservedMethodException(method: String)(using Frame)
    extends LspException(code = -32603, message = s"Method '$method' is engine-reserved and cannot be user-registered.")

/** A duplicate handler was registered for a method kind (-32603).
  *
  * @param kind the handler kind that was registered more than once
  */
final case class LspDuplicateHandlerException(kind: LspHandler.Kind)(using Frame)
    extends LspException(code = -32603, message = s"Duplicate handler registration for kind '$kind'.")

// =============================================================================
// Handler-body decode leaves (own narrow Lsp.* rows; mix no operation-trait)
// =============================================================================

/** A field in the wire payload could not be decoded to the expected type (-32602).
  *
  * Raised by the typed open-payload readers (`Lsp.initializationOptions`, the `*As` projections, the
  * `LspHandler.*Result.dataAs` readers) when a value is present but does not conform to the requested
  * type. Carries its own narrow row on those helpers (`Abort[LspDecodeException]`), so it mixes into
  * no operation-trait.
  *
  * @param target the slot being decoded
  * @param reason the decode failure detail
  * @param cause  the underlying decode throwable or string cause
  */
final case class LspDecodeException(target: String, reason: String, cause: String | Throwable = "")(using Frame)
    extends LspException(code = -32602, message = s"Decode error for '$target': $reason", cause = cause)

/** A required parameter field was invalid (-32602).
  *
  * Raised by `Lsp.extras` when the request's extra parameters do not decode. Carries its own narrow
  * row (`Abort[LspInvalidParamsException]`), so it mixes into no operation-trait.
  *
  * @param method the method whose parameter failed validation
  * @param reason a brief description of the validation failure
  * @param cause  the underlying throwable or string cause
  */
final case class LspInvalidParamsException(method: String, reason: String, cause: String | Throwable = "")(using Frame)
    extends LspException(code = -32602, message = s"Invalid params for '$method': $reason", cause = cause)
