package kyo.internal

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Minimal C bindings for libcurl and POSIX pipe/poll.
  *
  * Variadic curl functions (curl_easy_setopt, curl_easy_getinfo, curl_multi_setopt) are called through fixed-signature C wrappers
  * (curl_wrappers.c) to work around ARM64 variadic calling convention differences. See https://github.com/softwaremill/sttp/pull/2082
  */
@link("curl")
private[kyo] object CurlBindings:

    // ── Types ──────────────────────────────────────────────────────────

    type CURL  = Ptr[Byte]
    type CURLM = Ptr[Byte]

    type CURLcode  = CInt
    type CURLMcode = CInt
    type CURLMSG   = CInt

    // ── curl_easy (non-variadic) ─────────────────────────────────────

    @extern def curl_easy_init(): CURL                                 = extern
    @extern def curl_easy_cleanup(handle: CURL): Unit                  = extern
    @extern def curl_easy_reset(handle: CURL): Unit                    = extern
    @extern def curl_easy_pause(handle: CURL, bitmask: CInt): CURLcode = extern

    // ── curl_easy_setopt (variadic → wrappers) ───────────────────────

    @extern @name("kyo_curl_easy_setopt_long")
    def curl_easy_setopt(handle: CURL, option: CInt, param: Long): CURLcode = extern

    @extern @name("kyo_curl_easy_setopt_ptr")
    def curl_easy_setopt(handle: CURL, option: CInt, param: Ptr[Byte]): CURLcode = extern

    @extern @name("kyo_curl_easy_setopt_ptr")
    def curl_easy_setopt(handle: CURL, option: CInt, param: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize]): CURLcode = extern

    // ── curl_easy_getinfo (variadic → wrapper) ───────────────────────

    @extern @name("kyo_curl_easy_getinfo_ptr")
    def curl_easy_getinfo(handle: CURL, info: CInt, out: Ptr[Ptr[Byte]]): CURLcode = extern

    // ── curl_multi (non-variadic) ────────────────────────────────────

    @extern def curl_multi_init(): CURLM                                                                                      = extern
    @extern def curl_multi_cleanup(multi: CURLM): CURLMcode                                                                   = extern
    @extern def curl_multi_add_handle(multi: CURLM, easy: CURL): CURLMcode                                                    = extern
    @extern def curl_multi_remove_handle(multi: CURLM, easy: CURL): CURLMcode                                                 = extern
    @extern def curl_multi_socket_action(multi: CURLM, sockfd: CInt, ev_bitmask: CInt, running_handles: Ptr[CInt]): CURLMcode = extern
    @extern def curl_multi_info_read(multi: CURLM, msgs_in_queue: Ptr[CInt]): Ptr[CURLMsg]                                    = extern

    // ── curl_multi_setopt (variadic → wrapper) ───────────────────────

    @extern @name("kyo_curl_multi_setopt_ptr")
    def curl_multi_setopt(multi: CURLM, option: CInt, param: CFuncPtr5[CURL, CInt, CInt, Ptr[Byte], Ptr[Byte], CInt]): CURLMcode =
        extern

    @extern @name("kyo_curl_multi_setopt_ptr")
    def curl_multi_setopt(multi: CURLM, option: CInt, param: CFuncPtr3[CURLM, Long, Ptr[Byte], CInt]): CURLMcode = extern

    @extern @name("kyo_curl_multi_setopt_ptr")
    def curl_multi_setopt(multi: CURLM, option: CInt, param: Ptr[Byte]): CURLMcode = extern

    // ── curl_slist (non-variadic) ────────────────────────────────────

    @extern def curl_slist_append(list: Ptr[Byte], str: CString): Ptr[Byte] = extern
    @extern def curl_slist_free_all(list: Ptr[Byte]): Unit                  = extern

    // ── curl_global (non-variadic) ───────────────────────────────────

    @extern def curl_global_init(flags: Long): CURLcode = extern
    @extern def curl_global_cleanup(): Unit             = extern

    // ── CURLMsg struct ───────────────────────────────────────────────
    // struct CURLMsg { CURLMSG msg; CURL *easy_handle; union { void *whatever; CURLcode result; } data; }

    type CURLMsg = CStruct3[CInt, CURL, CInt]

    // ── POSIX pipe/poll (non-variadic) ───────────────────────────────

    type PollFd = CStruct3[CInt, CShort, CShort] // fd, events, revents

    @extern @name("pipe") def posix_pipe(pipefd: Ptr[CInt]): CInt                                      = extern
    @extern @blocking @name("poll") def posix_poll(fds: Ptr[PollFd], nfds: CSize, timeout: CInt): CInt = extern
    @extern @blocking @name("read") def posix_read(fd: CInt, buf: Ptr[Byte], count: CSize): CSSize     = extern
    @extern @name("write") def posix_write(fd: CInt, buf: Ptr[Byte], count: CSize): CSSize             = extern
    @extern @name("close") def posix_close(fd: CInt): CInt                                             = extern

    // ── Constants ────────────────────────────────────────────────────

    // curl_easy_setopt options
    inline val CURLOPT_URL                 = 10002
    inline val CURLOPT_WRITEFUNCTION       = 20011
    inline val CURLOPT_WRITEDATA           = 10001
    inline val CURLOPT_HEADERFUNCTION      = 20079
    inline val CURLOPT_HEADERDATA          = 10029
    inline val CURLOPT_PRIVATE             = 10103
    inline val CURLOPT_HTTPHEADER          = 10023
    inline val CURLOPT_CUSTOMREQUEST       = 10036
    inline val CURLOPT_COPYPOSTFIELDS      = 10165
    inline val CURLOPT_POSTFIELDSIZE_LARGE = 30120
    inline val CURLOPT_CONNECTTIMEOUT_MS   = 156
    inline val CURLOPT_NOBODY              = 44
    inline val CURLOPT_NOSIGNAL            = 99
    inline val CURLOPT_FOLLOWLOCATION      = 52

    // curl_multi_setopt options
    inline val CURLMOPT_SOCKETFUNCTION = 20001
    inline val CURLMOPT_SOCKETDATA     = 10002
    inline val CURLMOPT_TIMERFUNCTION  = 20004
    inline val CURLMOPT_TIMERDATA      = 10005

    // curl_easy_getinfo
    inline val CURLINFO_RESPONSE_CODE = 0x200002
    inline val CURLINFO_PRIVATE       = 0x100015

    // Socket action events
    inline val CURL_POLL_IN     = 1
    inline val CURL_POLL_OUT    = 2
    inline val CURL_POLL_INOUT  = 3
    inline val CURL_POLL_REMOVE = 4

    // Read callback (streaming request body)
    inline val CURLOPT_READFUNCTION = 20012
    inline val CURLOPT_READDATA     = 10009
    inline val CURLOPT_UPLOAD       = 46
    val CURL_READFUNC_PAUSE: CSize  = 0x10000001L.toCSize

    // Pause/unpause
    val CURL_WRITEFUNC_PAUSE: CSize = 0x10000001L.toCSize
    inline val CURLPAUSE_CONT       = 0

    // curl_multi_info_read
    inline val CURLMSG_DONE = 1

    // curl_global_init flags
    inline val CURL_GLOBAL_DEFAULT = 3L

    // curl_multi_socket_action special fd
    inline val CURL_SOCKET_TIMEOUT = -1

    // POSIX poll events
    val POLLIN: CShort  = 0x0001.toShort
    val POLLOUT: CShort = 0x0004.toShort

end CurlBindings
