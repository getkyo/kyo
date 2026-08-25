package kyo

/** Drives the device and Windows conditions the Native secure source has to survive.
  *
  * A host missing `/dev/urandom` is not reachable from this build, so the two POSIX failure edges are driven through an explicit device list
  * instead: a path that cannot be opened, and a path that reaches end-of-file before the request is satisfied. `/dev/null` and `/dev/zero`
  * stand in for the exhausted and the unlimited source, which needs no fixture and no temporary file.
  *
  * The Windows arm is driven through the `WindowsFill` seam, because this build runs on POSIX: the live `BCryptGenRandom` path is only
  * reachable on a Windows runner, while the NTSTATUS mapping is pinned here.
  *
  * The platform-neutral properties of `SecureRandom` live in `kyo.SecureRandomTest` under `shared`, which runs this source through the
  * public capability.
  */
class SecureRandomPlatformSpecificTest extends kyo.test.Test[Any]:

    private val missing     = "/nonexistent/kyo-entropy-probe"
    private val alsoMissing = "/nonexistent/kyo-entropy-probe-2"

    "readFully" - {

        "is false for a device that cannot be opened" in {
            Sync.defer {
                assert(!SecureRandom.readFully(missing, new Array[Byte](8)))
            }
        }

        "is false when the source is exhausted before the request is satisfied" in {
            // /dev/null reads end-of-file immediately, which is the shape of a source yielding fewer bytes than asked. The read loop has to
            // stop and report failure rather than spin on a zero-byte read.
            Sync.defer {
                assert(!SecureRandom.readFully("/dev/null", new Array[Byte](8)))
            }
        }

        "writes every position of the buffer" in {
            // /dev/zero yields an unlimited run of zero bytes, so poisoning the buffer first makes a partly-written result visible: any
            // position the read did not cover keeps its poison value.
            Sync.defer {
                val out = Array.fill(64)(0xff.toByte)
                assert(SecureRandom.readFully("/dev/zero", out))
                assert(out.toSeq == Seq.fill(64)(0.toByte))
            }
        }

        "writes every position of a request spanning many stdio buffers" in {
            Sync.defer {
                val out = Array.fill(70000)(0xff.toByte)
                assert(SecureRandom.readFully("/dev/zero", out))
                assert(out.toSeq == Seq.fill(70000)(0.toByte))
            }
        }

        "reads entropy from /dev/urandom, and two reads differ" in {
            Sync.defer {
                val first  = new Array[Byte](32)
                val second = new Array[Byte](32)
                assert(SecureRandom.readFully("/dev/urandom", first))
                assert(SecureRandom.readFully("/dev/urandom", second))
                assert(first.exists(_ != 0.toByte))
                assert(first.toSeq != second.toSeq)
            }
        }
    }

    "fillBytesFrom" - {

        "falls through to the next candidate when the first cannot be opened" in {
            Sync.defer {
                val out = new Array[Byte](32)
                SecureRandom.fillBytesFrom(Seq(missing, "/dev/urandom"), out)
                assert(out.exists(_ != 0.toByte))
            }
        }

        "falls through past a candidate that is exhausted before the request is satisfied" in {
            Sync.defer {
                val out = new Array[Byte](32)
                SecureRandom.fillBytesFrom(Seq("/dev/null", "/dev/urandom"), out)
                assert(out.exists(_ != 0.toByte))
            }
        }

        "fails with SecureRandom.EntropyUnavailable when no candidate can be read" in {
            Abort.run[Throwable](Sync.defer {
                SecureRandom.fillBytesFrom(Seq(missing, alsoMissing), new Array[Byte](32))
            }).map {
                case Result.Failure(e: SecureRandom.EntropyUnavailable) =>
                    assert(e.getMessage().contains(missing))
                case other =>
                    fail(s"expected a typed SecureRandom.EntropyUnavailable failure, got $other")
            }
        }

        "leaves a zero-length request alone rather than opening a device" in {
            Sync.defer {
                val out = new Array[Byte](0)
                SecureRandom.fillBytesFrom(Seq(missing, alsoMissing), out)
                assert(out.length == 0)
            }
        }
    }

    "fillBytesWindows" - {

        // The Windows arm is driven through the fill seam: this build runs on POSIX, so the live BCrypt path is only reachable on a
        // windows-x64 runner, while the status mapping is pinned here.
        final class RecordingFill(payload: Array[Byte], status: Int = 0) extends SecureRandom.WindowsFill:
            var calls = 0
            def fill(target: Array[Byte]): Int =
                calls += 1
                if status == 0 then java.lang.System.arraycopy(payload, 0, target, 0, target.length)
                status
            end fill
        end RecordingFill

        "a zero status fills every position" in {
            Sync.defer {
                val payload = Array.tabulate[Byte](32)(i => (i * 7 + 1).toByte)
                val fill    = new RecordingFill(payload)
                val out     = new Array[Byte](32)
                SecureRandom.fillBytesWindows(fill, out)
                assert(out.toSeq == payload.toSeq)
                assert(fill.calls == 1)
            }
        }

        "a non-zero NTSTATUS fails with SecureRandom.EntropyUnavailable naming the code in hex" in {
            Abort.run[Throwable](Sync.defer {
                SecureRandom.fillBytesWindows(new RecordingFill(Array.emptyByteArray, status = 0xc0000001), new Array[Byte](8))
            }).map {
                case Result.Failure(e: SecureRandom.EntropyUnavailable) =>
                    assert(e.getMessage().contains("c0000001"))
                case other =>
                    fail(s"expected a typed SecureRandom.EntropyUnavailable failure, got $other")
            }
        }

        "leaves a zero-length request alone rather than calling the source" in {
            Sync.defer {
                val fill = new RecordingFill(Array.emptyByteArray)
                SecureRandom.fillBytesWindows(fill, new Array[Byte](0))
                assert(fill.calls == 0)
            }
        }
    }

    "fillBytes" - {

        "reads from the host's own devices" in {
            Sync.defer {
                val out = new Array[Byte](32)
                SecureRandom.fillBytes(out)
                assert(out.exists(_ != 0.toByte))
            }
        }
    }

end SecureRandomPlatformSpecificTest
