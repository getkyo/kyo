package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.internal.SystemPlatformSpecific

/** POSIX / io_uring struct types bound through kyo-ffi.
  *
  * The structs split by shape:
  *   - flat, naturally-aligned structs ([[KEvent]], [[Timespec]], [[IoUringCqe]]) are codegen case classes: the plugin derives their C
  *     layout from the field types and the generated impl self-checks the total byte size against the target ABI at class init via
  *     `StructAbiCheck`.
  *   - [[EpollEvent]] is a value type with a MANUAL `Buffer[Byte]` layout in its companion, because `struct epoll_event` is not the same
  *     shape on every Linux target: it is `__attribute__((packed))` on x86_64 (12 bytes, `data` at offset 4) but naturally aligned on aarch64
  *     (16 bytes, `data` at offset 8). No single fixed codegen layout is correct on both, so the offsets and size are computed per host arch
  *     and the event is encoded/decoded by hand, exactly as the `sockaddr_*` families are. `epoll_ctl` / `epoll_wait` therefore take
  *     `Buffer[Byte]` rather than a struct.
  *   - the `sockaddr_*` families are MANUAL `Buffer[Byte]` layouts ([[SockAddr]]): they need network byte order on the port/address, the
  *     `sun_path` is a fixed `char[108]` array (no struct-field array support), and the generic `struct sockaddr*` punning that
  *     `bind`/`connect` require cannot be modeled by a single case class. This mirrors the kyo-ffi-it precedent.
  *   - `io_uring_sqe` is an opaque 64-byte region owned by the ring, never read field-by-field; it is filled only through the
  *     `io_uring_prep_*` helpers, so no case class is declared for it.
  */
private[net] object PosixStructs

// --- struct types: EpollEvent is a manual arch-aware layout; KEvent/Timespec/IoUringCqe are codegen (StructAbiCheck-verified) ---

/** `struct epoll_event` (Linux): the interest mask `events` and the user key `data` (only the `__u64` member of the `data` union is used).
  *
  * This is a plain value type, NOT a codegen struct: `struct epoll_event` is `__attribute__((packed))` on x86_64 (12 bytes, `data` at
  * offset 4) but naturally aligned on aarch64 (16 bytes, `data` at offset 8), so no fixed codegen layout is correct on both architectures.
  * The arch-aware byte layout lives in the [[EpollEvent$]] companion, which encodes into and decodes out of a `Buffer[Byte]`; the
  * `epoll_ctl` / `epoll_wait` bindings marshal that buffer as the raw `struct epoll_event*` pointer the kernel expects.
  */
private[net] case class EpollEvent(events: Int, data: Long) derives CanEqual

/** Arch-aware byte layout for `struct epoll_event`, shared identically across JVM, JS, and Native.
  *
  * The kernel's `struct epoll_event` differs by CPU architecture:
  *   - x86_64: `__attribute__((packed))`, 12 bytes, `events` (`uint32_t`) at offset 0 and `data` (`__u64`) at offset 4 with no padding.
  *   - aarch64 (and other naturally-aligned ABIs): 16 bytes, `events` at offset 0 then 4 bytes of padding and `data` at offset 8.
  *
  * Host architecture is detected once via [[kyo.internal.SystemPlatformSpecific.osArch]] (the same accessor `kyo.System` uses): it returns a
  * Java-style token on every backend (`os.arch` on JVM/Native, normalised `process.arch` on JS). x86_64 (`x86_64` / `amd64` / `x64`) selects
  * the packed 12-byte layout; every other arch uses the naturally-aligned 16-byte layout. Encoding is little-endian, matching all current
  * Linux epoll targets and the byte-addressed [[Buffer]] primitives.
  */
private[net] object EpollEvent:

    /** True when the host CPU is x86_64, where `struct epoll_event` is packed to 12 bytes with `data` at offset 4. */
    val isX86_64: Boolean =
        // Unsafe: this is module-init arch detection running outside any effect, so there is no AllowUnsafe in scope to thread through.
        import AllowUnsafe.embrace.danger
        SystemPlatformSpecific.osArch().toLowerCase match
            case "x86_64" | "amd64" | "x64" => true
            case _                          => false
    end isX86_64

    /** Byte offset of the 8-byte `data` field: 4 on packed x86_64, 8 on naturally-aligned arches. */
    val dataOffset: Int = if isX86_64 then 4 else 8

    /** Total byte size of `struct epoll_event`: 12 on packed x86_64, 16 on naturally-aligned arches. */
    val size: Int = if isX86_64 then 12 else 16

    /** Write `event` into `buf` at `offset` using the host layout (little-endian: `events` at `offset`, `data` at `offset + dataOffset`). */
    def encode(buf: Buffer[Byte], offset: Int, event: EpollEvent): Unit =
        putIntLe(buf, offset, event.events)
        putLongLe(buf, offset + dataOffset, event.data)

    /** Read the `struct epoll_event` at `offset` from `buf` using the host layout. */
    def decode(buf: Buffer[Byte], offset: Int): EpollEvent =
        EpollEvent(getIntLe(buf, offset), getLongLe(buf, offset + dataOffset))

    private def putIntLe(buf: Buffer[Byte], offset: Int, value: Int): Unit =
        var i = 0
        while i < 4 do
            buf.set(offset + i, ((value >> (i * 8)) & 0xff).toByte)
            i += 1
    end putIntLe

    private def putLongLe(buf: Buffer[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf.set(offset + i, ((value >> (i * 8)) & 0xff).toByte)
            i += 1
    end putLongLe

    private def getIntLe(buf: Buffer[Byte], offset: Int): Int =
        var v = 0
        var i = 0
        while i < 4 do
            v |= (buf.get(offset + i) & 0xff) << (i * 8)
            i += 1
        v
    end getIntLe

    private def getLongLe(buf: Buffer[Byte], offset: Int): Long =
        var v = 0L
        var i = 0
        while i < 8 do
            v |= (buf.get(offset + i).toLong & 0xff) << (i * 8)
            i += 1
        v
    end getLongLe

end EpollEvent

/** `struct kevent` (macOS/BSD). Flat, naturally aligned, 32 bytes:
  *   - `ident` (`uintptr_t`, the watched fd) at offset 0
  *   - `filter` (`int16_t`, e.g. `EVFILT_READ`) at offset 8
  *   - `flags` (`uint16_t`, e.g. `EV_ADD`) at offset 10
  *   - `fflags` (`uint32_t`) at offset 12
  *   - `data` (`intptr_t`, bytes available) at offset 16
  *   - `udata` (`void*`, user pointer) at offset 24
  *
  * Value type with a MANUAL `Buffer[Byte]` codec in the [[KEvent$]] companion, mirroring [[EpollEvent]]. The kqueue poller marshals a
  * `Buffer[Byte]` (not a `Buffer[KEvent]`) through the `kevent` binding (the binding only reads the buffer as a raw pointer, never field by
  * field), so the changelist is encoded and the eventlist decoded with the companion's primitive accessors. This is allocation-free on the
  * poll hot path: a generic `Buffer[KEvent]` round-trip via `StructLayout` boxes every `Long` field (`ident`/`data`/`udata`) on both the
  * `productElement`-based write and the `Any`-returning read, which a JFR alloc profile of the poller pinpointed; the manual codec reads and
  * writes the primitives directly with no boxing. The case class is retained for value equality in tests and the ABI size check; production
  * code uses [[KEvent.encodeChange]] and the field readers, never `Buffer[KEvent]`.
  */
private[net] case class KEvent(ident: Long, filter: Short, flags: Short, fflags: Int, data: Long, udata: Long) derives CanEqual

/** Manual `Buffer[Byte]` codec for `struct kevent`, shared identically across JVM and Native (the only kqueue targets).
  *
  * The C `struct kevent` is flat and naturally aligned at 32 bytes on every macOS/BSD target this codebase supports, all of which are
  * little-endian, so the fields are read and written little-endian at their fixed offsets (`ident` 0, `filter` 8, `flags` 10, `fflags` 12,
  * `data` 16, `udata` 24). The poll hot path never needs a whole `KEvent`: it reads only `ident` (the fd), `filter` (the direction), and
  * `flags` (the error/EOF bits), so it calls the per-field readers and allocates nothing. [[encodeChange]] writes a one-element register /
  * deregister changelist in place. [[decode]] (whole-struct) exists for the binding integration test's round-trip assertion.
  */
private[net] object KEvent:

    /** Total byte size of `struct kevent` on the supported (64-bit, naturally aligned) kqueue ABIs. */
    val size: Int = 32

    /** Write the changelist entry for `fd` at element 0 of `buf`: `ident`/`udata` = fd, the given `filter`/`flags`, and `fflags`/`data` = 0.
      * Used by the kqueue arm path (register / deregister) in place of `Buffer[KEvent].set(0, KEvent(...))`, which would box the Long fields.
      */
    def encodeChange(buf: Buffer[Byte], fd: Int, filter: Short, flags: Short): Unit =
        putLongLe(buf, 0, fd.toLong)  // ident
        putShortLe(buf, 8, filter)    // filter
        putShortLe(buf, 10, flags)    // flags
        putIntLe(buf, 12, 0)          // fflags
        putLongLe(buf, 16, 0L)        // data
        putLongLe(buf, 24, fd.toLong) // udata
    end encodeChange

    /** Write the changelist entry for `fd` at element `slot` of `buf` (slot is a 0-based index; byte offset = slot * size). Identical to the
      * single-element overload but writes at the given slot for changelist batching: `drainChanges` encodes multiple changes at
      * consecutive slots in `KqueuePollData.changelistBuf` before passing the batch to `kevent` alongside the poll wait.
      */
    def encodeChange(buf: Buffer[Byte], slot: Int, fd: Int, filter: Short, flags: Short): Unit =
        val base = slot * size
        putLongLe(buf, base + 0, fd.toLong)  // ident
        putShortLe(buf, base + 8, filter)    // filter
        putShortLe(buf, base + 10, flags)    // flags
        putIntLe(buf, base + 12, 0)          // fflags
        putLongLe(buf, base + 16, 0L)        // data
        putLongLe(buf, base + 24, fd.toLong) // udata
    end encodeChange

    /** Write a one-element `EVFILT_USER` changelist at element 0 of `buf` with explicit `fflags`. Used by the kqueue poll-loop wakeup: the
      * register call passes `flags = EV_ADD | EV_CLEAR` and `fflags = 0`; the trigger call passes `flags = 0` and `fflags = NOTE_TRIGGER`. The
      * `ident` is a fixed wakeup key (not an fd), distinct from any socket fd so its delivered event is recognized and consumed by the poll loop
      * rather than dispatched to a connection. Identical layout to [[encodeChange]] except `filter` is `EVFILT_USER` and `fflags` is settable.
      */
    def encodeUser(buf: Buffer[Byte], ident: Long, flags: Short, fflags: Int): Unit =
        putLongLe(buf, 0, ident)                       // ident (fixed wakeup key)
        putShortLe(buf, 8, PosixConstants.EVFILT_USER) // filter
        putShortLe(buf, 10, flags)                     // flags
        putIntLe(buf, 12, fflags)                      // fflags (NOTE_TRIGGER on the trigger call)
        putLongLe(buf, 16, 0L)                         // data
        putLongLe(buf, 24, ident)                      // udata
    end encodeUser

    /** Read the `ident` (watched fd as `uintptr_t`) of the event at element `i`. */
    def ident(buf: Buffer[Byte], i: Int): Long = getLongLe(buf, i * size + 0)

    /** Read the `filter` (e.g. `EVFILT_READ` / `EVFILT_WRITE`) of the event at element `i`. */
    def filter(buf: Buffer[Byte], i: Int): Short = getShortLe(buf, i * size + 8)

    /** Read the `flags` (e.g. `EV_ERROR` / `EV_EOF`) of the event at element `i`. */
    def flags(buf: Buffer[Byte], i: Int): Short = getShortLe(buf, i * size + 10)

    /** Read the `data` (bytes available / errno) of the event at element `i`. */
    def data(buf: Buffer[Byte], i: Int): Long = getLongLe(buf, i * size + 16)

    /** Decode the whole `struct kevent` at element `i`. Used by the binding integration test's round-trip assertion; the poll hot path uses
      * the per-field readers instead so it never allocates a `KEvent`.
      */
    def decode(buf: Buffer[Byte], i: Int): KEvent =
        val base = i * size
        KEvent(
            ident = getLongLe(buf, base + 0),
            filter = getShortLe(buf, base + 8),
            flags = getShortLe(buf, base + 10),
            fflags = getIntLe(buf, base + 12),
            data = getLongLe(buf, base + 16),
            udata = getLongLe(buf, base + 24)
        )
    end decode

    private def putShortLe(buf: Buffer[Byte], offset: Int, value: Short): Unit =
        buf.set(offset, (value & 0xff).toByte)
        buf.set(offset + 1, ((value >> 8) & 0xff).toByte)

    private def putIntLe(buf: Buffer[Byte], offset: Int, value: Int): Unit =
        var i = 0
        while i < 4 do
            buf.set(offset + i, ((value >> (i * 8)) & 0xff).toByte)
            i += 1
    end putIntLe

    private def putLongLe(buf: Buffer[Byte], offset: Int, value: Long): Unit =
        var i = 0
        while i < 8 do
            buf.set(offset + i, ((value >> (i * 8)) & 0xff).toByte)
            i += 1
    end putLongLe

    private def getShortLe(buf: Buffer[Byte], offset: Int): Short =
        ((buf.get(offset) & 0xff) | ((buf.get(offset + 1) & 0xff) << 8)).toShort

    private def getIntLe(buf: Buffer[Byte], offset: Int): Int =
        var v = 0
        var i = 0
        while i < 4 do
            v |= (buf.get(offset + i) & 0xff) << (i * 8)
            i += 1
        v
    end getIntLe

    private def getLongLe(buf: Buffer[Byte], offset: Int): Long =
        var v = 0L
        var i = 0
        while i < 8 do
            v |= (buf.get(offset + i).toLong & 0xff) << (i * 8)
            i += 1
        v
    end getLongLe

end KEvent

/** `struct timespec` / `__kernel_timespec`. Two 8-byte fields on 64-bit ABIs: `tv_sec` then `tv_nsec`, 16 bytes total. Used as the kqueue
  * poll timeout and (later) the io_uring bounded-wait timeout.
  */
private[net] case class Timespec(tvSec: Long, tvNsec: Long) derives CanEqual

/** `struct io_uring_cqe` (the completion-queue entry). Flat, 16 bytes: `userData` (the key set on the SQE) at offset 0, `res` (>=0 result
  * or `-errno`) at offset 8, `flags` at offset 12. Used by the io_uring driver to map a completion back to its promise.
  */
private[net] case class IoUringCqe(userData: Long, res: Int, flags: Int) derives CanEqual

/** Manual `Buffer[Byte]` encoders for the `sockaddr_*` families.
  *
  * The kernel reads these through a `struct sockaddr*` cast, so the family/port/address bytes are laid out by hand in the exact on-wire
  * order. Ports and IPv4/IPv6 addresses are written in network byte order (big-endian) as the socket API requires. Each encoder returns the
  * allocated buffer paired with the `socklen_t` length to pass to `bind`/`connect`.
  *
  * The caller owns the returned buffer and must `close()` it once the syscall that reads it has returned.
  */
private[net] object SockAddr:

    /** Byte size of `struct sockaddr_in` (AF_INET). */
    val inet4Size: Int = 16

    /** Byte size of `struct sockaddr_in6` (AF_INET6). */
    val inet6Size: Int = 28

    /** Byte size of `struct sockaddr_un` (AF_UNIX): 2-byte family + 108-byte `sun_path`. */
    val unixSize: Int = 110

    /** Length of the `sun_path` array inside `struct sockaddr_un`. */
    val sunPathMax: Int = 108

    /** Resolve the well-known loopback host NAMES to their loopback IP literal, or pass any other `host` through unchanged.
      *
      * The transport has no general DNS resolver (no `getaddrinfo` binding exists yet, see the follow-up note in [[encodeInet]]'s caller), so a
      * non-numeric host normally cannot be encoded. The loopback names are the one exception worth handling without DNS: they have a fixed,
      * RFC-mandated answer that every host's resolver returns identically, so mapping them to a literal here matches what `getaddrinfo` (and the
      * old NIO `InetSocketAddress`) would have produced, and unblocks the common loopback-server case the kyo-http migration relies on. The
      * mapping is exactly the `/etc/hosts` loopback block every POSIX system ships:
      *   - `localhost` -> `127.0.0.1` (the IPv4 loopback; chosen over `::1` so a v4-only listener, the common test/server default, is reached)
      *   - `ip6-localhost` / `ip6-loopback` -> `::1` (the Debian/Ubuntu names for the IPv6 loopback)
      *
      * Any host that is neither a loopback name nor already a literal is returned unchanged and will fail to encode (Absent), which the caller
      * surfaces as an `unresolvable address` `Closed`: arbitrary-hostname DNS is the documented follow-up, not silently mis-resolved here. The
      * match is case-insensitive because hostnames are.
      */
    def resolveLoopbackName(host: String): String =
        host.toLowerCase match
            case "localhost"                      => "127.0.0.1"
            case "ip6-localhost" | "ip6-loopback" => "::1"
            case _                                => host

    /** Encode an IPv4 `sockaddr_in` for `host:port`.
      *
      * Layout (16 bytes): `sin_family` (2 bytes, host order), `sin_port` (2 bytes, network order), `sin_addr` (4 bytes, network order),
      * 8 bytes of zero padding. `host` must be dotted-quad ("127.0.0.1"); name resolution is the caller's job. Returns the buffer and its
      * length, or `Absent` if `host` is not a valid IPv4 literal.
      */
    def encodeInet4(family: Int, host: String, port: Int)(using AllowUnsafe): Maybe[(Buffer[Byte], Int)] =
        parseIpv4(host).flatMap(addr => encodeInet4Raw(family, addr, port))

    /** Encode an IPv4 `sockaddr_in` from raw 4-byte network-order address bytes (the form a resolver returns), bypassing the literal parse.
      * Same layout as [[encodeInet4]]. Returns `Absent` if `addr` is not exactly 4 bytes.
      */
    def encodeInet4Raw(family: Int, addr: Array[Byte], port: Int)(using AllowUnsafe): Maybe[(Buffer[Byte], Int)] =
        if addr.length != 4 then Absent
        else
            val buf = Buffer.alloc[Byte](inet4Size)
            zero(buf, inet4Size)
            putFamily(buf, family)
            putPortNetOrder(buf, 2, port)
            var i = 0
            while i < 4 do
                buf.set(4 + i, addr(i))
                i += 1
            Maybe((buf, inet4Size))
        end if
    end encodeInet4Raw

    /** Encode an IPv6 `sockaddr_in6` for `host:port`.
      *
      * Layout (28 bytes): `sin6_family` (2 bytes, host order), `sin6_port` (2 bytes, network order), `sin6_flowinfo` (4 bytes, zero),
      * `sin6_addr` (16 bytes, network order), `sin6_scope_id` (4 bytes, zero). `host` must be an IPv6 literal ("::1"). Returns `Absent` if
      * `host` is not a valid IPv6 literal.
      */
    def encodeInet6(family: Int, host: String, port: Int)(using AllowUnsafe): Maybe[(Buffer[Byte], Int)] =
        parseIpv6(host).flatMap(addr => encodeInet6Raw(family, addr, port))

    /** Encode an IPv6 `sockaddr_in6` from raw 16-byte network-order address bytes (the form a resolver returns), bypassing the literal parse.
      * Same layout as [[encodeInet6]]. Returns `Absent` if `addr` is not exactly 16 bytes.
      */
    def encodeInet6Raw(family: Int, addr: Array[Byte], port: Int)(using AllowUnsafe): Maybe[(Buffer[Byte], Int)] =
        if addr.length != 16 then Absent
        else
            val buf = Buffer.alloc[Byte](inet6Size)
            zero(buf, inet6Size)
            putFamily(buf, family)
            putPortNetOrder(buf, 2, port)
            var i = 0
            while i < 16 do
                buf.set(8 + i, addr(i))
                i += 1
            Maybe((buf, inet6Size))
        end if
    end encodeInet6Raw

    /** Encode a Unix-domain `sockaddr_un` for `path`.
      *
      * Layout (110 bytes): `sun_family` (2 bytes, host order) followed by `sun_path` (`char[108]`, NUL-terminated). Returns `Absent` when
      * the UTF-8 encoding of `path` does not fit in the 108-byte `sun_path` (leaving room for the NUL terminator).
      */
    def encodeUnix(family: Int, path: String)(using AllowUnsafe): Maybe[(Buffer[Byte], Int)] =
        val bytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        if bytes.length >= sunPathMax then Absent
        else
            val buf = Buffer.alloc[Byte](unixSize)
            zero(buf, unixSize)
            putFamily(buf, family)
            var i = 0
            while i < bytes.length do
                buf.set(2 + i, bytes(i))
                i += 1
            Maybe((buf, unixSize))
        end if
    end encodeUnix

    private def zero(buf: Buffer[Byte], n: Int): Unit =
        var i = 0
        while i < n do
            buf.set(i, 0.toByte)
            i += 1
    end zero

    /** Write the address family into the first 2 bytes in host byte order. The socket API treats `sa_family_t` as host-order, unlike the
      * port and address which are network-order.
      */
    private def putFamily(buf: Buffer[Byte], family: Int): Unit =
        buf.set(0, (family & 0xff).toByte)
        buf.set(1, ((family >> 8) & 0xff).toByte)

    /** Write a 16-bit port in network byte order (big-endian) at `offset`. */
    private def putPortNetOrder(buf: Buffer[Byte], offset: Int, port: Int): Unit =
        buf.set(offset, ((port >> 8) & 0xff).toByte)
        buf.set(offset + 1, (port & 0xff).toByte)

    private def parseIpv4(host: String): Maybe[Array[Byte]] =
        val parts = host.split('.')
        if parts.length != 4 then Absent
        else
            val out = new Array[Byte](4)
            var i   = 0
            var ok  = true
            while i < 4 && ok do
                val v = parts(i).toIntOption
                v match
                    case Some(n) if n >= 0 && n <= 255 => out(i) = n.toByte
                    case _                             => ok = false
                i += 1
            end while
            if ok then Maybe(out) else Absent
        end if
    end parseIpv4

    /** Parse an IPv6 literal into its 16 network-order bytes.
      *
      * Pure Scala (no `java.net.InetAddress`, which is unavailable on Native/JS), supporting the full `x:x:x:x:x:x:x:x` form and `::`
      * zero-compression (the most common literals like "::1"). An embedded IPv4 tail (e.g. "::ffff:127.0.0.1") and scope-id suffixes are not
      * accepted; those are rejected with `Absent` rather than mis-parsed. Returns `Absent` on any malformed input.
      */
    private def parseIpv6(host: String): Maybe[Array[Byte]] =
        if host.contains('.') || host.contains('%') then Absent
        else
            val doubleColonAt = host.indexOf("::")
            val hasCompress   = doubleColonAt >= 0
            // A "::" may appear at most once.
            if hasCompress && host.indexOf("::", doubleColonAt + 1) >= 0 then Absent
            else
                def parseGroups(s: String): Maybe[Array[Int]] =
                    if s.isEmpty then Maybe(Array.empty[Int])
                    else
                        val parts = s.split(':')
                        val out   = new Array[Int](parts.length)
                        var i     = 0
                        var ok    = true
                        while i < parts.length && ok do
                            val p = parts(i)
                            if p.isEmpty || p.length > 4 then ok = false
                            else
                                try
                                    val v = java.lang.Integer.parseInt(p, 16)
                                    if v < 0 || v > 0xffff then ok = false else out(i) = v
                                catch case _: NumberFormatException => ok = false
                            end if
                            i += 1
                        end while
                        if ok then Maybe(out) else Absent
                    end if
                end parseGroups

                val groupsMaybe: Maybe[Array[Int]] =
                    if !hasCompress then
                        parseGroups(host).flatMap(g => if g.length == 8 then Maybe(g) else Absent)
                    else
                        val head = host.substring(0, doubleColonAt)
                        val tail = host.substring(doubleColonAt + 2)
                        parseGroups(head).flatMap { h =>
                            parseGroups(tail).flatMap { t =>
                                val fill = 8 - h.length - t.length
                                if fill < 0 then Absent
                                else Maybe(h ++ Array.fill(fill)(0) ++ t)
                            }
                        }

                groupsMaybe.map { groups =>
                    val out = new Array[Byte](16)
                    var i   = 0
                    while i < 8 do
                        out(i * 2) = ((groups(i) >> 8) & 0xff).toByte
                        out(i * 2 + 1) = (groups(i) & 0xff).toByte
                        i += 1
                    end while
                    out
                }
            end if
        end if
    end parseIpv6

end SockAddr

/** Manual reader for the one `struct stat` field the stdio pollability probe needs: `st_mode`.
  *
  * The full `struct stat` layout differs by OS AND by Linux architecture and is large, but only `st_mode` (the file-type bits the probe masks
  * with `S_IFMT`) is read, so a single host-aware offset suffices instead of a full struct binding. The field's offset and width diverge
  * across the supported targets:
  *   - macOS/BSD (the 64-bit-inode `struct stat`): `st_mode` is a `__uint16_t` at offset 4, after the 4-byte `st_dev`.
  *   - Linux x86_64 (the x86 `struct stat`): `st_mode` is a 4-byte `mode_t` at offset 24, after `st_dev` (8), `st_ino` (8), `st_nlink` (8).
  *   - Linux aarch64 (the asm-generic `struct stat`, shared by riscv64 and the other newer arches): `st_mode` comes BEFORE `st_nlink`, so it
  *     is a 4-byte `mode_t` at offset 16, after `st_dev` (8) and `st_ino` (8). This is NOT the x86_64 layout: reading offset 24 on aarch64
  *     lands on `st_uid`, whose value never masks to a file-type, so the probe would misclassify every fd.
  *
  * All supported targets are little-endian, so the field is decoded LE and masked to its low bits. The probe over-allocates the buffer
  * ([[PosixConstants.statSize]]) so the offset is always in range whatever the exact struct size is.
  */
private[net] object PosixStat:

    /** Byte offset of `st_mode` within `struct stat`: 4 on macOS/BSD, 24 on Linux x86_64, 16 on Linux aarch64 (asm-generic). The x86_64 vs
      * aarch64 split reuses the same host-arch detection [[EpollEvent.isX86_64]] uses for `struct epoll_event`.
      */
    val modeOffset: Int =
        if PosixConstants.isMacOrBsd then 4
        else if EpollEvent.isX86_64 then 24
        else 16

    /** Read `st_mode` (the file-type and permission bits) from a `fstat`-filled buffer using the host layout. macOS stores it in 2 bytes,
      * Linux in 4; both are read little-endian and the file-type bits the probe needs live in the low 16 bits either way.
      */
    def stMode(buf: Buffer[Byte]): Int =
        if PosixConstants.isMacOrBsd then
            (buf.get(modeOffset) & 0xff) | ((buf.get(modeOffset + 1) & 0xff) << 8)
        else
            (buf.get(modeOffset) & 0xff) |
                ((buf.get(modeOffset + 1) & 0xff) << 8) |
                ((buf.get(modeOffset + 2) & 0xff) << 16) |
                ((buf.get(modeOffset + 3) & 0xff) << 24)
    end stMode

end PosixStat
