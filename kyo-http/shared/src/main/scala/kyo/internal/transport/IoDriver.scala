package kyo.internal.transport

import kyo.*

/** Completion-based I/O driver. The driver performs reads internally and delivers results via promise completion. This model unifies
  * poll-based platforms (Native epoll/kqueue, JVM NIO Selector) with callback-based platforms (JS Node.js).
  *
  * ## Completion model
  *
  * Unlike readiness-based APIs (epoll, kqueue) where the caller is notified "fd is readable" and then calls read(), this driver does the
  * read internally and completes the caller's promise with the actual bytes. This matches how libuv, Netty, and Node.js work.
  *
  * ## Promise passing
  *
  * Callers pass a `Promise.Unsafe` to `awaitRead`/`awaitWritable`. The driver stores the promise and completes it when the operation
  * finishes. This avoids allocation per operation when the caller reuses a promise (e.g., pumps extend IOPromise and pass themselves).
  *
  * ## Platform implementations
  *
  *   - Native (NativeIoDriver): epoll/kqueue poll loop does the read, completes promise
  *   - JVM (NioIoDriver): NIO Selector poll loop does the read, completes promise
  *   - JS (JsIoDriver): Node.js "data" event delivers bytes, completes promise
  *
  * @tparam Handle
  *   Platform's connection identifier: `Int` (fd) for Native, `SocketChannel` for JVM, `JsHandle` for JS
  */
abstract private[kyo] class IoDriver[Handle]:

    /** Start the driver's event loop. Returns a fiber representing the event loop lifetime.
      *
      * Poll-based drivers spawn a fiber that loops on the poll mechanism. JS returns a sentinel fiber that completes on close().
      */
    def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

    /** Request a read. The driver performs the read and completes the promise with bytes.
      *
      *   - Success with bytes: data read
      *   - Success with empty span: EOF
      *   - Failure(Closed): connection error
      *   - Panic: unexpected error
      *
      * Only one read request per handle at a time. Calling again before completion is undefined.
      */
    def awaitRead(handle: Handle, promise: Promise.Unsafe[Span[Byte], Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Request writable notification. The driver completes the promise when the handle becomes writable.
      *
      * Used after `write` returns `Partial` to know when to retry.
      */
    def awaitWritable(handle: Handle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Request connect completion notification. The driver completes the promise when the non-blocking connect finishes.
      *
      * On JVM this uses OP_CONNECT which fires reliably when the TCP handshake completes, eliminating the race condition that exists with
      * OP_WRITE-based connect detection. On Native, delegates to awaitWritable (epoll/kqueue signal connect via write-readiness). On JS,
      * completes immediately (Node.js handles connect via callback).
      */
    def awaitConnect(handle: Handle, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit

    /** Synchronous write attempt.
      *
      *   - Done: all bytes written
      *   - Partial(remaining): socket buffer full, await writable and retry with remaining
      *   - Error: connection failed
      */
    def write(handle: Handle, data: Span[Byte])(using AllowUnsafe): WriteResult

    /** Cancel pending read/write requests for handle. Completes pending promises with Closed. */
    def cancel(handle: Handle)(using AllowUnsafe, Frame): Unit

    /** Close the handle (fd, socket, channel). Idempotent. Cleans up any pending operations. */
    def closeHandle(handle: Handle)(using AllowUnsafe, Frame): Unit

    /** Shutdown the driver. Closes the poll mechanism and completes the event loop fiber. Idempotent. */
    def close()(using AllowUnsafe, Frame): Unit

    /** Driver label for logging (e.g., "NativeIoDriver"). */
    def label: String

    /** Handle label for logging (e.g., "fd=42"). */
    def handleLabel(handle: Handle): String

end IoDriver
