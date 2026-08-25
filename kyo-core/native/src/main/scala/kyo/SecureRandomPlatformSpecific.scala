package kyo

import scala.annotation.tailrec
import scala.scalanative.libc.stdio.fclose
import scala.scalanative.libc.stdio.fopen
import scala.scalanative.libc.stdio.fread
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Cryptographically secure entropy for the Scala Native platform.
  *
  * On POSIX systems entropy comes from a character device rather than a library call, so two things a single `fopen`/`fread` pair leaves
  * unchecked are handled here. A host may not expose the device the code names, so the devices are probed in turn and a host exposing none
  * fails with [[SecureRandom.EntropyUnavailable]]. And a single `fread` may satisfy fewer bytes than asked when a signal interrupts the
  * underlying read, so the request resumes from where it stopped instead of being reported as a failure. On Windows, where no such device
  * exists, entropy comes from `BCryptGenRandom` through the `kyo_bcrypt_gen_random` C shim, and a non-zero NTSTATUS fails the same typed way.
  *
  * The `java.security.SecureRandom` shim this platform ships (for `java.util.UUID.randomUUID` linkage) delegates to [[SecureRandom.live]]
  * rather than reaching this logic directly, so these members can stay `private[kyo]`.
  */
private[kyo] trait SecureRandomPlatformSpecific:

    private[kyo] def liveUnsafe: SecureRandom.Unsafe =
        new SecureRandom.Unsafe:
            def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                val arr = new Array[Byte](length)
                fillBytes(arr)(using Frame.internal)
                Span.fromUnsafe(arr)
            end nextBytes

    /** Entropy devices probed in order. `/dev/urandom` comes first because it never blocks and, on the systems Scala Native targets, draws
      * from the same seeded pool as `/dev/random`. `/dev/random` is there for a host that exposes only it.
      */
    private[kyo] val devices: Seq[String] = Seq("/dev/urandom", "/dev/random")

    /** Writes cryptographically secure bytes over every position of `bytes`, and leaves a zero-length array alone. Reads a character device
      * on POSIX, `BCryptGenRandom` on Windows.
      *
      * @throws SecureRandom.EntropyUnavailable
      *   when no platform source can produce the requested length
      */
    private[kyo] def fillBytes(bytes: Array[Byte])(using Frame): Unit =
        if kyo.internal.Platform.isWindows then fillBytesWindows(BCryptFill, bytes)
        else fillBytesFrom(devices, bytes)

    /** Same as [[fillBytes]] against an explicit device list.
      *
      * Exposed so the two failure edges can be driven without a host that lacks `/dev/urandom`: a path that cannot be opened, and a path that
      * reaches end-of-file before the request is satisfied.
      *
      * @throws SecureRandom.EntropyUnavailable
      *   when no candidate can be opened and read to the requested length
      */
    private[kyo] def fillBytesFrom(candidates: Seq[String], bytes: Array[Byte])(using Frame): Unit =
        if bytes.length > 0 && !candidates.exists(readFully(_, bytes)) then
            throw new SecureRandom.EntropyUnavailable(s"none of ${candidates.mkString(", ")} could be read")

    /** Reads exactly `into.length` bytes from `device`, resuming across short reads.
      *
      * False when the device cannot be opened, or is exhausted or errors before the request is satisfied. On a false result `into` holds
      * whatever was read up to that point, so a caller moves to the next candidate rather than using it.
      *
      * The device is opened in binary mode. Text mode is the same on the systems targeted here, but the bytes being read are not text, and
      * nothing on this path wants a host's newline or end-of-file translation applied to them.
      */
    private[kyo] def readFully(device: String, into: Array[Byte]): Boolean =
        Zone {
            val fp = fopen(toCString(device), c"rb")
            if fp == null then false
            else
                try
                    val len = into.length
                    val buf = alloc[Byte](len)

                    @tailrec def readLoop(filled: Int): Boolean =
                        if filled == len then true
                        else
                            val got = fread(buf + filled, 1.toCSize, (len - filled).toCSize, fp).toInt
                            // A zero read means exhausted or errored, and there is nothing left to resume from. Anything else is progress,
                            // including a short count, which is what an interrupted read looks like from here.
                            if got <= 0 then false
                            else readLoop(filled + got)

                    if !readLoop(0) then false
                    else
                        @tailrec def copyLoop(i: Int): Unit =
                            if i < len then
                                into(i) = buf(i)
                                copyLoop(i + 1)
                        copyLoop(0)
                        true
                    end if
                finally
                    val _ = fclose(fp)
                end try
            end if
        }

    /** How the Windows arm fills a buffer: answers an NTSTATUS, zero for success. A seam rather than a direct call so the status mapping is
      * drivable from a POSIX test host; [[BCryptFill]] is the live implementation.
      */
    private[kyo] trait WindowsFill:
        def fill(target: Array[Byte]): Int
    end WindowsFill

    private[kyo] object BCryptFill extends WindowsFill:
        def fill(target: Array[Byte]): Int =
            // Unsafe: the array remains strongly reachable and is not mutated by Scala while the synchronous native call fills it.
            EntropyWindows.kyo_bcrypt_gen_random(
                target.asInstanceOf[ByteArray].at(0),
                target.length
            )
    end BCryptFill

    /** Same as [[fillBytes]] against an explicit Windows fill, leaving a zero-length array alone.
      *
      * @throws SecureRandom.EntropyUnavailable
      *   when the fill answers a non-zero NTSTATUS
      */
    private[kyo] def fillBytesWindows(fill: WindowsFill, bytes: Array[Byte])(using Frame): Unit =
        if bytes.length > 0 then
            val status = fill.fill(bytes)
            if status != 0 then
                throw new SecureRandom.EntropyUnavailable(
                    s"BCryptGenRandom failed with NTSTATUS 0x${java.lang.Integer.toHexString(status)}"
                )
            end if
        end if
    end fillBytesWindows
end SecureRandomPlatformSpecific

@extern
private object EntropyWindows:
    def kyo_bcrypt_gen_random(target: Ptr[Byte], length: CInt): CInt = extern
end EntropyWindows
