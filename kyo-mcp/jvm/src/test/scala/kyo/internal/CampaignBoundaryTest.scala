package kyo.internal

import kyo.*

/** Pins INV-011: no kyo-jsonrpc file was modified during the kyo-mcp campaign. */
class CampaignBoundaryTest extends Test:

    "INV-011: no kyo-jsonrpc file modified in the working tree (JVM-only)" in run {
        if Platform.isJVM then
            // AllowUnsafe: Runtime.exec is an OS-level side effect; intentional for boundary lint.
            val (exitCode, output) = Sync.Unsafe.evalOrThrow(
                Sync.defer {
                    val proc = Runtime.getRuntime.exec(
                        Array("git", "diff", "--name-only", "HEAD", "--", "kyo-jsonrpc/")
                    )
                    proc.waitFor()
                    val out = scala.io.Source.fromInputStream(proc.getInputStream).mkString.trim
                    (proc.exitValue(), out)
                }
            )(using summon[Frame], AllowUnsafe.embrace.danger)
            assert(exitCode == 0, s"git exited with code $exitCode")
            assert(output.isEmpty, s"kyo-jsonrpc files modified since HEAD: $output")
        else
            succeed
        end if
    }

end CampaignBoundaryTest
