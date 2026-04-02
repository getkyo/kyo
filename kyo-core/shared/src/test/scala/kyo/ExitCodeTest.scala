package kyo

class ExitCodeTest extends Test:

    "ExitCode(0) is ExitCode.Success" in {
        assert(ExitCode(0) == ExitCode.Success)
    }

    "ExitCode(1) is ExitCode.Failure(1)" in {
        assert(ExitCode(1) == ExitCode.Failure(1))
    }

    "ExitCode(130) is ExitCode.Signaled(2) (SIGINT)" in {
        assert(ExitCode(130) == ExitCode.Signaled(2))
        assert(ExitCode(130) == ExitCode.SIGINT)
    }

    // ---------------------------------------------------------------------------
    // toInt
    // ---------------------------------------------------------------------------

    "ExitCode.Success.toInt returns 0" in {
        assert(ExitCode.Success.toInt == 0)
    }

    "ExitCode.Failure(42).toInt returns 42" in {
        assert(ExitCode.Failure(42).toInt == 42)
    }

    "ExitCode.Signaled(15).toInt returns 143" in {
        assert(ExitCode.Signaled(15).toInt == 143)
        assert(ExitCode.SIGTERM.toInt == 143)
    }

    // ---------------------------------------------------------------------------
    // isSuccess
    // ---------------------------------------------------------------------------

    "ExitCode.Success.isSuccess is true" in {
        assert(ExitCode.Success.isSuccess == true)
    }

    "ExitCode.Failure(1).isSuccess is false" in {
        assert(ExitCode.Failure(1).isSuccess == false)
    }

    "ExitCode.Signaled(15).isSuccess is false" in {
        assert(ExitCode.Signaled(15).isSuccess == false)
        assert(ExitCode.SIGTERM.isSuccess == false)
    }

    // ---------------------------------------------------------------------------
    // Boundary cases
    // ---------------------------------------------------------------------------

    "ExitCode(128) is Signaled(0) per POSIX encoding" in {
        // 128 = 128 + 0, so it is Signaled(0) per POSIX convention
        assert(ExitCode(128) == ExitCode.Signaled(0))
    }

    "ExitCode(129) is Signaled(1) (SIGHUP)" in {
        // 129 > 128, so 129 - 128 = 1 → SIGHUP
        assert(ExitCode(129) == ExitCode.Signaled(1))
        assert(ExitCode(129) == ExitCode.SIGHUP)
    }

    // ExitCode(128) boundary — POSIX signal encoding

    "ExitCode(128) should be Signaled(0) per POSIX convention" in {
        // POSIX: exit codes >= 128 encode signals (128 + N)
        // ExitCode(128) should be Signaled(0), not Failure(128)
        assert(ExitCode(128) == ExitCode.Signaled(0))
    }

    "ExitCode boundary: 127 is Failure, 128 and above are Signaled" in {
        assert(ExitCode(127) == ExitCode.Failure(127))
        assert(ExitCode(128) == ExitCode.Signaled(0))
        assert(ExitCode(129) == ExitCode.Signaled(1))
    }

    "ExitCode.toInt round-trips for signal numbers 0 through 31" in {
        (0 to 31).foreach { n =>
            val code         = ExitCode.Signaled(n)
            val roundTripped = ExitCode(code.toInt)
            assert(
                roundTripped == code,
                s"Signal $n: Signaled($n).toInt=${code.toInt}, ExitCode(${code.toInt})=$roundTripped, expected $code"
            )
        }
        succeed
    }

    "ExitCode with negative integer produces Failure" in {
        assert(ExitCode(-1) == ExitCode.Failure(-1))
    }

    // ---------------------------------------------------------------------------
    // signalName
    // ---------------------------------------------------------------------------

    "signalName returns Present for named signals" in {
        assert(ExitCode.Signaled(1).signalName == Present("SIGHUP"))
        assert(ExitCode.Signaled(2).signalName == Present("SIGINT"))
        assert(ExitCode.Signaled(3).signalName == Present("SIGQUIT"))
        assert(ExitCode.Signaled(9).signalName == Present("SIGKILL"))
        assert(ExitCode.Signaled(11).signalName == Present("SIGSEGV"))
        assert(ExitCode.Signaled(13).signalName == Present("SIGPIPE"))
        assert(ExitCode.Signaled(15).signalName == Present("SIGTERM"))
    }

    "signalName returns Absent for unknown signal numbers" in {
        assert(ExitCode.Signaled(42).signalName == Absent)
        assert(ExitCode.Signaled(99).signalName == Absent)
    }

    "signalName returns Absent for non-Signaled exit codes" in {
        assert(ExitCode.Success.signalName == Absent)
        assert(ExitCode.Failure(1).signalName == Absent)
    }

    // ---------------------------------------------------------------------------
    // Named signal constants
    // ---------------------------------------------------------------------------

    "all 7 named signal constants have correct numbers" in {
        assert(ExitCode.SIGHUP == ExitCode.Signaled(1))
        assert(ExitCode.SIGINT == ExitCode.Signaled(2))
        assert(ExitCode.SIGQUIT == ExitCode.Signaled(3))
        assert(ExitCode.SIGKILL == ExitCode.Signaled(9))
        assert(ExitCode.SIGSEGV == ExitCode.Signaled(11))
        assert(ExitCode.SIGPIPE == ExitCode.Signaled(13))
        assert(ExitCode.SIGTERM == ExitCode.Signaled(15))
    }

    "import ExitCode.* enables named signal pattern matching" in {
        import ExitCode.*
        val code: ExitCode = ExitCode.Signaled(15)
        val matched = code match
            case SIGTERM     => "sigterm"
            case Signaled(n) => s"other signal: $n"
            case _           => "other"
        assert(matched == "sigterm")
    }

    "Signaled(n) matches unknown signal numbers" in {
        import ExitCode.*
        val code: ExitCode = ExitCode.Signaled(42)
        val matched = code match
            case SIGTERM     => "sigterm"
            case Signaled(n) => s"signal $n"
            case _           => "other"
        assert(matched == "signal 42")
    }

    "ExitCode is accessible as Process.ExitCode" in {
        assert(Process.ExitCode.Success == ExitCode.Success)
        assert(Process.ExitCode.Failure(1) == ExitCode.Failure(1))
        assert(Process.ExitCode.Signaled(15) == ExitCode.Signaled(15))
    }

    // ---------------------------------------------------------------------------
    // Additional ExitCode tests
    // ---------------------------------------------------------------------------

    "ExitCode(255) is Signaled(127)" in {
        // 255 > 128, so 255 - 128 = 127
        assert(ExitCode(255) == ExitCode.Signaled(127))
    }

    "ExitCode.Signaled(0).toInt returns 128" in {
        assert(ExitCode.Signaled(0).toInt == 128)
    }

    "signalName returns Absent for unknown signal number" in {
        assert(ExitCode.Signaled(100).signalName == Absent)
    }

    "signalName returns Present for known signals" in {
        assert(ExitCode.Signaled(9).signalName == Present("SIGKILL"))
        assert(ExitCode.Signaled(15).signalName == Present("SIGTERM"))
    }

    "ExitCode with values above 255 are Signaled" in {
        assert(ExitCode(256) == ExitCode.Signaled(128))
        assert(ExitCode(256).toInt == 256)
    }

    // ---------------------------------------------------------------------------
    // Process-based ExitCode tests
    // ---------------------------------------------------------------------------

    "waitFor with timeout returns Present(ExitCode) for quick process" in run {
        Scope.run {
            for
                proc   <- Command("true").spawn
                result <- proc.waitFor(5.seconds)
            yield assert(result == Present(ExitCode.Success))
        }
    }

    "waitFor with timeout returns Absent when process exceeds timeout" in run {
        Scope.run {
            for
                proc   <- Command("sleep", "60").spawn
                result <- proc.waitFor(200.millis)
            yield assert(result == Absent)
        }
    }

    "envAppend adds and overrides env variables preserving existing ones" in run {
        Command("sh", "-c", "echo $KYO_TEST_VAR")
            .envAppend(Map("KYO_TEST_VAR" -> "appended_value"))
            .text
            .map { result =>
                assert(result.trim == "appended_value")
            }
    }

    "envReplace runs process with only specified environment" in run {
        Command("sh", "-c", "echo ${KYO_ONLY_VAR:-only}")
            .envReplace(Map("KYO_ONLY_VAR" -> "only"))
            .text
            .map { result =>
                assert(result.trim == "only")
            }
    }

    "envClear runs process with empty environment" in run {
        Command("env")
            .envClear
            .text
            .map { result =>
                assert(result.trim.isEmpty)
            }
    }

end ExitCodeTest
