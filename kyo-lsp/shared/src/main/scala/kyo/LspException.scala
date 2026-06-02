package kyo

/** Base class for all LSP exceptions organized into four sealed subcategories.
  *
  * The four subcategories map to distinct pipeline stages:
  *   - [[LspException.Handshake]]: errors during the LSP initialize / initialized lifecycle
  *   - [[LspException.Dispatch]]: errors during method routing and capability gating
  *   - [[LspException.Execution]]: errors during handler execution and payload validation
  *   - [[LspException.Application]]: typed user-domain errors from handler bodies
  *
  * Extends [[JsonRpcApplicationError]]; every `LspException` travels through
  * `Abort[JsonRpcError | ...]` rows transparently. The inherited `Schema[JsonRpcError]` encodes
  * any `LspException` as the wire triple `(code, message, data)`. No separate `Schema[LspException]`
  * is needed.
  *
  * User-domain errors are registered per-handler via `handler.error[E2](code, message)` rather
  * than by extending this hierarchy directly. The hierarchy is sealed; callers exhaust pattern
  * matches on the four subcategories without an open extension surface.
  *
  * JSON-RPC error code mapping:
  *   - `-32002` : NotInitialized / AlreadyInitialized
  *   - `-32600` : ShutdownInProgress / InvalidRequest / UnsupportedDocumentSync
  *   - `-32601` : MethodNotFound / CapabilityNotAdvertised
  *   - `-32602` : InvalidParams / UnknownDocument / Decode
  *   - `-32603` : WrongDirection / ReservedMethod / DuplicateHandler / ExecutionPanic / ProgressTokenAlreadyInUse
  *   - `-32800` : RequestCancelled (LSP §3.16)
  *   - `-32801` : ContentModified (LSP §3.16)
  *   - `-32802` : ServerCancelled (LSP §3.16)
  *
  * @see [[LspException.Handshake]]
  * @see [[LspException.Dispatch]]
  * @see [[LspException.Execution]]
  * @see [[LspException.Application]]
  */
sealed abstract class LspException private[kyo] (
    code: Int,
    message: Text,
    // Structure carve-out: data forwarded to JsonRpcApplicationError
    data: Maybe[Structure.Value] = Absent,
    cause: Text | Throwable = ""
)(using Frame)
    extends JsonRpcApplicationError(
        code,
        message.show,
        data,
        LspException.toRpcCause(cause)
    )

object LspException:

    private[kyo] def toRpcCause(cause: Text | Throwable): String | Throwable =
        cause match
            case t: Throwable => t
            case s: String    => s
            case other        => other.asInstanceOf[Text].show

    // =========================================================================
    // Stage bases
    // =========================================================================

    /** Errors arising during the LSP initialize / initialized lifecycle. */
    sealed abstract class Handshake private[kyo] (code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
        extends LspException(code, message, Absent, cause)

    /** Errors arising during method dispatch, capability gating, and direction filtering. */
    sealed abstract class Dispatch private[kyo] (code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
        extends LspException(code, message, Absent, cause)

    /** Errors arising during handler execution or structured-payload validation. */
    sealed abstract class Execution private[kyo] (code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
        extends LspException(code, message, Absent, cause)

    /** Marks user-domain application errors from handler bodies. */
    sealed abstract class Application private[kyo] (
        code: Int,
        message: Text,
        cause: Text | Throwable = "",
        // Structure carve-out: data forwarded to LspException
        data: Maybe[Structure.Value] = Absent
    )(using Frame)
        extends LspException(code, message, data, cause)

    // =========================================================================
    // Handshake leaves
    // =========================================================================

    /** Leaves nested under the [[Handshake]] stage base. */
    object Handshake:

        /** A method was called before `initialize` completed. JSON-RPC code -32002. */
        final case class NotInitialized(attemptedMethod: String)(using Frame)
            extends Handshake(code = -32002, message = s"Method '$attemptedMethod' called before initialize.")

        /** A second `initialize` was attempted after the handshake completed. JSON-RPC code -32002. */
        final case class AlreadyInitialized(attemptedMethod: String)(using Frame)
            extends Handshake(code = -32002, message = s"Method '$attemptedMethod' attempted after initialize completed.")

        /** A method was rejected because shutdown is in progress. JSON-RPC code -32600. */
        final case class ShutdownInProgress(attemptedMethod: String)(using Frame)
            extends Handshake(code = -32600, message = s"Shutdown in progress; '$attemptedMethod' rejected.")

    end Handshake

    // =========================================================================
    // Dispatch leaves
    // =========================================================================

    /** Leaves nested under the [[Dispatch]] stage base. */
    object Dispatch:

        /** No handler registered for the requested method. JSON-RPC code -32601. */
        final case class MethodNotFound(method: String)(using Frame)
            extends Dispatch(code = -32601, message = s"Method not found: $method")

        /** A required parameter field was invalid. JSON-RPC code -32602. */
        final case class InvalidParams(method: String, reason: String, cause: Throwable | String = "")(using Frame)
            extends Dispatch(code = -32602, message = s"Invalid params for '$method': $reason", cause = cause)

        /** The request itself was malformed. JSON-RPC code -32600. */
        final case class InvalidRequest(reason: String)(using Frame)
            extends Dispatch(code = -32600, message = s"Invalid request: $reason")

        /** A document URI referenced in the request is not open in the registry. JSON-RPC code -32602. */
        final case class UnknownDocument(uri: LspHandler.LspDocument.Uri, attemptedMethod: String)(using Frame)
            extends Dispatch(code = -32602, message = s"Unknown document '${uri.asString}' for method '$attemptedMethod'.")

        /** A handler was registered for a method that does not match its expected direction. JSON-RPC code -32603. */
        final case class WrongDirection(handlerKind: LspHandler.Kind, registeredOn: LspHandler.Direction)(using Frame)
            extends Dispatch(code = -32603, message = s"Handler for '$handlerKind' cannot be registered on $registeredOn.")

        /** A handler attempted to register for an engine-reserved method. JSON-RPC code -32603. */
        final case class ReservedMethod(method: String)(using Frame)
            extends Dispatch(code = -32603, message = s"Method '$method' is engine-reserved and cannot be user-registered.")

        /** A required server or client capability was not advertised. JSON-RPC code -32601. */
        final case class CapabilityNotAdvertised(name: LspCapabilities.Name)(using Frame)
            extends Dispatch(code = -32601, message = s"Required capability '${name}' was not advertised.")

        /** A duplicate handler was registered for a method kind. JSON-RPC code -32603. */
        final case class DuplicateHandler(kind: LspHandler.Kind)(using Frame)
            extends Dispatch(code = -32603, message = s"Duplicate handler registration for kind '$kind'.")

    end Dispatch

    // =========================================================================
    // Execution leaves
    // =========================================================================

    /** Leaves nested under the [[Execution]] stage base. */
    object Execution:

        /** A request was cancelled by the peer. JSON-RPC code -32800 (LSP §3.16). */
        final case class RequestCancelled(id: JsonRpcId)(using Frame)
            extends Execution(code = -32800, message = s"Request cancelled.")

        /** The document content changed while the request was in flight. JSON-RPC code -32801 (LSP §3.16). */
        final case class ContentModified(uri: LspHandler.LspDocument.Uri)(using Frame)
            extends Execution(code = -32801, message = s"Content modified for '${uri.asString}'; server state is inconsistent.")

        /** The server cancelled a request it previously accepted. JSON-RPC code -32802 (LSP §3.16). */
        final case class ServerCancelled(reason: String)(using Frame)
            extends Execution(code = -32802, message = s"Server cancelled the request: $reason")

        /** An unhandled exception escaped a handler body. JSON-RPC code -32603. */
        final case class ExecutionPanic(method: String, cause: Throwable | Text = "")(using Frame)
            extends Execution(code = -32603, message = s"Execution panic in '$method'.", cause = cause)

        /** A `$/progress` create token was already in use. JSON-RPC code -32603. */
        final case class ProgressTokenAlreadyInUse(token: LspHandler.ProgressToken)(using Frame)
            extends Execution(code = -32603, message = s"Progress token already in use.")

        /** The document sync kind requested is not supported. JSON-RPC code -32600. */
        final case class UnsupportedDocumentSync(
            received: LspHandler.TextDocumentSyncKind,
            configured: LspHandler.TextDocumentSyncKind
        )(using Frame)
            extends Execution(code = -32600, message = s"Unsupported document sync: received $received but configured $configured.")

        /** A field in the wire payload could not be decoded to the expected type. JSON-RPC code -32602. */
        final case class Decode(target: String, reason: String, cause: Throwable | String = "")(using Frame)
            extends Execution(code = -32602, message = s"Decode error for '$target': $reason", cause = cause)

    end Execution

    // =========================================================================
    // Application (no concrete library leaves; user errors via .error[E2])
    // =========================================================================

    /** Container for user-domain application errors.
      * Register user errors per-handler via `handler.error[E2](code, message)`. The wire
      * triple of any such error is surfaced on the receiving peer as [[Application.Remote]].
      */
    object Application:

        /** Remote application error received from the peer.
          *
          * Surfaces user-defined error types that the remote registered via
          * `handler.error[E2](code, message)` on a server-side or client-side handler. The
          * wire `code`, `message`, and `data` triple are preserved verbatim; the caller
          * pattern-matches on `code` to discriminate across user-defined error families.
          *
          * @param code    the JSON-RPC error code from the wire response
          * @param message the JSON-RPC error message from the wire response
          * @param data    the Schema-encoded `data` payload (Absent when the peer did not include one)
          */
        // Structure carve-out: passes wire data through to LspException
        final case class Remote(
            remoteCode: Int,
            remoteMessage: String,
            remoteData: Maybe[Structure.Value] = Absent
        )(using Frame)
            extends Application(code = remoteCode, message = remoteMessage, cause = "", data = remoteData)

    end Application

end LspException
