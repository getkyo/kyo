package kyo.ffi.internal

/** Child-JVM main used by [[NativeLoaderForkStressTest]] to stress `extractBytesToTemp` under concurrent peer processes.
  *
  * Args: `<extractDir> <libId> <hex-payload>`
  *
  * The child writes `<hex-payload>` bytes into the configured dir under the normal F11 extraction path, then prints the resulting absolute
  * path on stdout and exits 0 on success / 1 on failure. Parent asserts all children exit 0 and that each reported path's content matches.
  */
object NativeLoaderForkMain:
    def main(args: Array[String]): Unit =
        try
            require(args.length == 3, "usage: <extractDir> <libId> <hex-payload>")
            java.lang.System.setProperty("kyo.ffi.extractDir", args(0)): Unit
            val libId   = args(1)
            val payload = hexDecode(args(2))
            val out     = NativeLoader.extractBytesToTemp(payload, "lib", libId, "so")
            java.lang.System.out.println(out.toAbsolutePath.nn.toString)
            java.lang.System.exit(0)
        catch
            case t: Throwable =>
                t.printStackTrace()
                java.lang.System.exit(1)
        end try
    end main

    private def hexDecode(s: String): Array[Byte] =
        val out = new Array[Byte](s.length / 2)
        var i   = 0
        while i < out.length do
            out(i) = java.lang.Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
            i += 1
        end while
        out
    end hexDecode
end NativeLoaderForkMain
