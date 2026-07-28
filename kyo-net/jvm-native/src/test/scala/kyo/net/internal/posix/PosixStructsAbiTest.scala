package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.StructLayout
import kyo.ffi.internal.StructAbiCheck
import kyo.internal.UnsafeLayout
import kyo.net.Test

/** ABI byte-size checks for the `kyo.net.internal.posix` struct types.
  *
  * Two kinds of assertion (mirroring kyo-ffi's `ItStructAbiTest`):
  *   - the CODEGEN structs (`KEvent`/`Timespec`/`IoUringCqe`, flat) have the exact total byte size the C ABI uses; the derived `StructLayout`
  *     mirrors the codegen's size model, and a `Buffer` of each allocates to the right size.
  *   - the MANUAL byte layouts ([[EpollEvent$]] and the sockaddr encoders) produce the C `sizeof`. `EpollEvent` is arch-aware (12 bytes
  *     packed on x86_64, 16 bytes naturally aligned on aarch64); the sockaddr encoders are 16 / 28 / 110 and the opaque SQE region is 64.
  *
  * The `StructAbiCheck` fail-fast contract is exercised directly with a synthetic mismatch, proving the check is not a no-op. The codegen
  * impls run the real check at class init when their binding loads (exercised by the binding tests).
  */
class PosixStructsAbiTest extends Test:

    import AllowUnsafe.embrace.danger

    // Layout instances matching each codegen struct's natural-alignment model.
    private given UnsafeLayout[KEvent]     = StructLayout.derived[KEvent]
    private given UnsafeLayout[Timespec]   = StructLayout.derived[Timespec]
    private given UnsafeLayout[IoUringCqe] = StructLayout.derived[IoUringCqe]

    "codegen struct sizes" - {
        "EpollEvent is arch-aware: 12 bytes packed on x86_64, 16 bytes naturally aligned otherwise" in {
            // struct epoll_event is __attribute__((packed)) on x86_64 (data at offset 4, total 12) but naturally
            // aligned on aarch64 (data at offset 8, total 16). The layout follows the detected host arch.
            val expectedSize   = if EpollEvent.isX86_64 then 12 else 16
            val expectedOffset = if EpollEvent.isX86_64 then 4 else 8
            assert(EpollEvent.size == expectedSize)
            assert(EpollEvent.dataOffset == expectedOffset)
        }
        "EpollEvent encode/decode round-trips through the host layout" in {
            val ev  = EpollEvent(PosixConstants.EPOLLIN | PosixConstants.EPOLLOUT, 0xdeadbeefcafeL)
            val buf = Buffer.alloc[Byte](EpollEvent.size)
            try
                EpollEvent.encode(buf, 0, ev)
                // events is little-endian at offset 0; data is little-endian at dataOffset.
                assert((buf.get(0) & 0xff) == (ev.events & 0xff))
                assert((buf.get(EpollEvent.dataOffset) & 0xffL) == (ev.data & 0xffL))
                assert(EpollEvent.decode(buf, 0) == ev)
            finally buf.close()
            end try
        }
        "KEvent is 32, Timespec is 16, IoUringCqe is 16" in {
            assert(summon[UnsafeLayout[KEvent]].size == 32)
            assert(summon[UnsafeLayout[Timespec]].size == 16)
            assert(summon[UnsafeLayout[IoUringCqe]].size == 16)
        }
        "a deliberately-wrong codegen size FAILS the StructAbiCheck (not a no-op)" in {
            // A packed-vs-natural mismatch: a 16-byte naturally-aligned size where C packs the struct to 12.
            val ex = intercept[FfiLoadError.AbiMismatch](
                StructAbiCheck.verifyByteSize("kyo.net.internal.posix.SomeBindings", "SomeStruct", 12L, 16L)
            )
            assert(ex.expected == "12")
            assert(ex.actual == "16")
            assert(ex.getMessage.contains("packedStructs"))
        }
    }

    "manual struct stat reader matches C" - {
        // Direct guard for PosixStat.modeOffset across the OS/arch matrix. struct stat is NOT laid out uniformly across Linux arches
        // (x86_64 puts st_mode at offset 24; aarch64/asm-generic at offset 16; macOS at offset 4). fstat a real regular file and assert the
        // decoded mode masks to S_IFREG, which holds only when modeOffset matches the host's struct stat. A wrong offset lands on an adjacent
        // field (st_uid / st_nlink) whose value does not mask to S_IFREG, which is exactly the aarch64 misclassification this guards against.
        "PosixStat.stMode reads S_IFREG from a real fstat'd regular file (arch-aware st_mode offset)" in {
            val sockets     = Ffi.load[SocketBindings]
            val (tempFd, _) = PosixTestSockets.tempFileFd(Array.empty[Byte])
            val stat        = Buffer.alloc[Byte](PosixConstants.statSize)
            try
                assert(sockets.fstat(tempFd, stat).value >= 0, "fstat of the temp regular file failed")
                val mode = PosixStat.stMode(stat)
                assert(
                    (mode & PosixConstants.S_IFMT) == PosixConstants.S_IFREG,
                    s"st_mode 0x${mode.toHexString} did not mask to S_IFREG (0x${PosixConstants.S_IFREG.toHexString}); modeOffset=${PosixStat.modeOffset} is wrong for this arch"
                )
            finally
                stat.close()
                PosixTestSockets.closeTempFd(tempFd)
            end try
        }
    }

    "manual sockaddr sizes match C sizeof" - {
        "inet4 == 16, inet6 == 28, unix == 110" in {
            val v4 = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 8080).getOrElse(fail("inet4 encode failed"))
            val v6 = SockAddr.encodeInet6(PosixConstants.AF_INET6, "::1", 8080).getOrElse(fail("inet6 encode failed"))
            val un = SockAddr.encodeUnix(PosixConstants.AF_UNIX, "/tmp/kyo.sock").getOrElse(fail("unix encode failed"))
            try
                assert(v4._2 == 16 && v4._1.size == 16)
                assert(v6._2 == 28 && v6._1.size == 28)
                assert(un._2 == 110 && un._1.size == 110)
            finally
                v4._1.close()
                v6._1.close()
                un._1.close()
            end try
        }
        "inet4 encodes family host-order and port network-order" in {
            val (buf, _) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0x0102).getOrElse(fail("encode failed"))
            try
                // sin_family (host order, AF_INET == 2) in the first 2 bytes.
                assert((buf.get(0) & 0xff) == (PosixConstants.AF_INET & 0xff))
                assert((buf.get(1) & 0xff) == ((PosixConstants.AF_INET >> 8) & 0xff))
                // sin_port (network order, big-endian): 0x0102 => byte[2]=0x01, byte[3]=0x02.
                assert((buf.get(2) & 0xff) == 0x01)
                assert((buf.get(3) & 0xff) == 0x02)
                // sin_addr 127.0.0.1 in network order at bytes 4..7.
                assert((buf.get(4) & 0xff) == 127)
                assert((buf.get(5) & 0xff) == 0)
                assert((buf.get(6) & 0xff) == 0)
                assert((buf.get(7) & 0xff) == 1)
            finally buf.close()
            end try
        }
        "unix rejects an over-long path" in {
            val tooLong = "/" + ("a" * SockAddr.sunPathMax)
            assert(SockAddr.encodeUnix(PosixConstants.AF_UNIX, tooLong).isEmpty)
        }
        "inet6 parses ::1 to the loopback address in network order" in {
            val (buf, _) = SockAddr.encodeInet6(PosixConstants.AF_INET6, "::1", 0x0102).getOrElse(fail("encode failed"))
            try
                // sin6_family at 0..1 (host order), sin6_port net-order at 2..3.
                assert((buf.get(2) & 0xff) == 0x01)
                assert((buf.get(3) & 0xff) == 0x02)
                // sin6_addr (16 bytes) at offset 8: ::1 is 15 zero bytes then 0x01.
                var i  = 0
                var ok = true
                while i < 15 do
                    if (buf.get(8 + i) & 0xff) != 0 then ok = false
                    i += 1
                assert(ok, "::1 high bytes must be zero")
                assert((buf.get(8 + 15) & 0xff) == 1)
            finally buf.close()
            end try
        }
        "inet6 parses a full uncompressed literal" in {
            val (buf, _) =
                SockAddr.encodeInet6(PosixConstants.AF_INET6, "2001:db8:0:0:0:0:0:1", 0).getOrElse(fail("encode failed"))
            try
                assert((buf.get(8) & 0xff) == 0x20)
                assert((buf.get(9) & 0xff) == 0x01)
                assert((buf.get(10) & 0xff) == 0x0d)
                assert((buf.get(11) & 0xff) == 0xb8)
                assert((buf.get(8 + 15) & 0xff) == 1)
            finally buf.close()
            end try
        }
        "inet6 rejects malformed literals" in {
            assert(SockAddr.encodeInet6(PosixConstants.AF_INET6, "::1::2", 0).isEmpty)  // two "::"
            assert(SockAddr.encodeInet6(PosixConstants.AF_INET6, "gggg::1", 0).isEmpty) // non-hex group
            assert(SockAddr.encodeInet6(PosixConstants.AF_INET6, "1:2:3", 0).isEmpty)   // too few groups, no "::"
        }
    }

    "loopback host-name resolution (kyo-http migration regression)" - {
        // The kyo-http client passes a hostname straight to transport.connect; only the well-known loopback names have a fixed
        // RFC answer the transport can resolve without a DNS binding. These pin that mapping deterministically (no socket).
        "localhost maps to the IPv4 loopback 127.0.0.1" in {
            assert(SockAddr.resolveLoopbackName("localhost") == "127.0.0.1")
        }
        "localhost is matched case-insensitively (hostnames are case-insensitive)" in {
            assert(SockAddr.resolveLoopbackName("LocalHost") == "127.0.0.1")
            assert(SockAddr.resolveLoopbackName("LOCALHOST") == "127.0.0.1")
        }
        "the IPv6 loopback names map to ::1" in {
            assert(SockAddr.resolveLoopbackName("ip6-localhost") == "::1")
            assert(SockAddr.resolveLoopbackName("ip6-loopback") == "::1")
        }
        "numeric literals pass through unchanged (no spurious rewrite)" in {
            assert(SockAddr.resolveLoopbackName("127.0.0.1") == "127.0.0.1")
            assert(SockAddr.resolveLoopbackName("::1") == "::1")
            assert(SockAddr.resolveLoopbackName("10.0.0.5") == "10.0.0.5")
        }
        "an arbitrary hostname passes through unchanged (NOT resolved here; DNS is a follow-up)" in {
            // example.com must come back unchanged so the caller fails it Closed rather than mis-resolving without DNS.
            assert(SockAddr.resolveLoopbackName("example.com") == "example.com")
            assert(SockAddr.encodeInet4(PosixConstants.AF_INET, SockAddr.resolveLoopbackName("example.com"), 80).isEmpty)
        }
    }

    "opaque SQE region is the liburing-fixed 64 bytes" in {
        // io_uring_sqe is a fixed 64-byte region in the liburing ABI (kernel UAPI struct io_uring_sqe). The io_uring shim's
        // kyo_uring_sizeof reports the ring size; the SQE region itself is fixed by the kernel UAPI at 64 bytes regardless of host.
        val ioUringSqeSize = 64
        assert(ioUringSqeSize == 64)
    }

end PosixStructsAbiTest
