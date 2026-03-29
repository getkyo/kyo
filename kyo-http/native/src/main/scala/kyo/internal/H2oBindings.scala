package kyo.internal

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Bindings for the kyo h2o C bridge (h2o_wrappers.c).
  *
  * h2o runs a single-threaded event loop. Scala registers callbacks for request handling, response draining, and streaming generator
  * events. All h2o API calls happen on the event loop thread.
  */
@link("h2o-evloop")
private[kyo] object H2oBindings:

    // Opaque pointer types for clarity
    type H2oServer    = Ptr[Byte]
    type H2oReq       = Ptr[Byte]
    type H2oGenerator = Ptr[Byte]

    // ── Server lifecycle ────────────────────────────────────────────────

    @extern @name("kyo_h2o_start")
    def start(host: CString, port: CInt, maxBodySize: CInt, backlog: CInt): H2oServer = extern

    @extern @name("kyo_h2o_evloop_run_once")
    @blocking
    def evloopRunOnce(server: H2oServer): CInt = extern

    @extern @name("kyo_h2o_stop")
    def stop(server: H2oServer): Unit = extern

    @extern @name("kyo_h2o_destroy")
    def destroy(server: H2oServer): Unit = extern

    @extern @name("kyo_h2o_port")
    def port(server: H2oServer): CInt = extern

    @extern @name("kyo_h2o_response_fd")
    def responseFd(server: H2oServer): CInt = extern

    // ── Callback registration ───────────────────────────────────────────

    @extern @name("kyo_h2o_set_handler")
    def setHandler(server: H2oServer, fn: CFuncPtr2[H2oServer, H2oReq, CInt]): Unit = extern

    @extern @name("kyo_h2o_set_drain")
    def setDrain(server: H2oServer, fn: CFuncPtr1[H2oServer, Unit]): Unit = extern

    @extern @name("kyo_h2o_set_proceed")
    def setProceed(server: H2oServer, fn: CFuncPtr1[CInt, Unit]): Unit = extern

    @extern @name("kyo_h2o_set_stop")
    def setStop(server: H2oServer, fn: CFuncPtr1[CInt, Unit]): Unit = extern

    // ── Request accessors ───────────────────────────────────────────────

    @extern @name("kyo_h2o_req_method")
    def reqMethod(req: H2oReq): CString = extern

    @extern @name("kyo_h2o_req_method_len")
    def reqMethodLen(req: H2oReq): CInt = extern

    @extern @name("kyo_h2o_req_path")
    def reqPath(req: H2oReq): CString = extern

    @extern @name("kyo_h2o_req_path_len")
    def reqPathLen(req: H2oReq): CInt = extern

    @extern @name("kyo_h2o_req_query_at")
    def reqQueryAt(req: H2oReq): CInt = extern

    @extern @name("kyo_h2o_req_header_count")
    def reqHeaderCount(req: H2oReq): CInt = extern

    @extern @name("kyo_h2o_req_header_name")
    def reqHeaderName(req: H2oReq, index: CInt): CString = extern

    @extern @name("kyo_h2o_req_header_name_len")
    def reqHeaderNameLen(req: H2oReq, index: CInt): CInt = extern

    @extern @name("kyo_h2o_req_header_value")
    def reqHeaderValue(req: H2oReq, index: CInt): CString = extern

    @extern @name("kyo_h2o_req_header_value_len")
    def reqHeaderValueLen(req: H2oReq, index: CInt): CInt = extern

    @extern @name("kyo_h2o_req_body")
    def reqBody(req: H2oReq): Ptr[Byte] = extern

    @extern @name("kyo_h2o_req_body_len")
    def reqBodyLen(req: H2oReq): CInt = extern

    // ── Buffered response ───────────────────────────────────────────────

    @extern @name("kyo_h2o_send_buffered")
    def sendBuffered(
        req: H2oReq,
        status: CInt,
        headerNames: Ptr[CString],
        headerNameLens: Ptr[CInt],
        headerValues: Ptr[CString],
        headerValueLens: Ptr[CInt],
        headerCount: CInt,
        body: Ptr[Byte],
        bodyLen: CInt
    ): Unit = extern

    // ── Error response ──────────────────────────────────────────────────

    @extern @name("kyo_h2o_send_error")
    def sendError(
        req: H2oReq,
        status: CInt,
        headerNames: Ptr[CString],
        headerNameLens: Ptr[CInt],
        headerValues: Ptr[CString],
        headerValueLens: Ptr[CInt],
        headerCount: CInt
    ): Unit = extern

    // ── Streaming response ──────────────────────────────────────────────

    @extern @name("kyo_h2o_start_streaming")
    def startStreaming(
        server: H2oServer,
        req: H2oReq,
        status: CInt,
        headerNames: Ptr[CString],
        headerNameLens: Ptr[CInt],
        headerValues: Ptr[CString],
        headerValueLens: Ptr[CInt],
        headerCount: CInt,
        streamId: CInt
    ): H2oGenerator = extern

    @extern @name("kyo_h2o_send_chunk")
    def sendChunk(
        req: H2oReq,
        generator: H2oGenerator,
        data: Ptr[Byte],
        len: CInt,
        isFinal: CInt
    ): Unit = extern

    // ── Wake event loop ─────────────────────────────────────────────────

    @extern @name("kyo_h2o_accept_start")
    def acceptStart(server: H2oServer): Unit = extern

    @extern @name("kyo_h2o_wake")
    def wake(server: H2oServer): Unit = extern

    // ── WebSocket ────────────────────────────────────────────────────────

    type H2oWsConn = Ptr[Byte]

    // Callback types for WebSocket events
    type WsMsgFn   = CFuncPtr4[Ptr[Byte], CInt, Ptr[Byte], CInt, Unit]
    type WsCloseFn = CFuncPtr1[Ptr[Byte], Unit]

    /** Check if a request is a WebSocket upgrade. Returns non-zero if yes. */
    @extern @name("kyo_h2o_is_websocket")
    def isWebSocket(req: H2oReq): CInt = extern

    /** Upgrade a request to WebSocket. Returns the connection pointer. */
    @extern @name("kyo_h2o_upgrade_websocket")
    def upgradeWebSocket(
        req: H2oReq,
        userData: Ptr[Byte],
        msgFn: WsMsgFn,
        closeFn: WsCloseFn
    ): H2oWsConn = extern

    /** Send a WebSocket frame. opcode: 1=text, 2=binary. */
    @extern @name("kyo_h2o_ws_send")
    def wsSend(conn: H2oWsConn, opcode: CInt, data: Ptr[Byte], len: CInt): Unit = extern

    /** Close a WebSocket connection. */
    @extern @name("kyo_h2o_ws_close")
    def wsClose(conn: H2oWsConn): Unit = extern

end H2oBindings
