package kyo.internal

import kyo.*

/** Minimal byte-level read/write interface for TLS implementations. NioTlsStream and NativeTlsStream wrap SSLEngine/OpenSSL which naturally
  * operate on byte buffers. TransportStream's pull-based Stream API is adapted via bridge classes.
  */
private[kyo] trait RawStream:
    def read(buf: Array[Byte])(using Frame): Int < Async
    def write(data: Span[Byte])(using Frame): Unit < Async
