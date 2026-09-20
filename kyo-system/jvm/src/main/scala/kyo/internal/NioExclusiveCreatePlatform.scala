package kyo.internal

import java.nio.file.StandardOpenOption

private[kyo] object NioExclusiveCreatePlatform:

    /** Claims `jpath` exclusively and returns the options the follow-up `FileChannel.open` must use.
      *
      * On the JVM the claim is the open: `CREATE_NEW` reaches a single `open(2)` carrying `O_EXCL` (`CreateFileW` with `CREATE_NEW` on
      * Windows), so exclusion is the kernel's and nothing is claimed ahead of time.
      */
    private[kyo] def claimExclusively(jpath: java.nio.file.Path): Array[StandardOpenOption] =
        Array(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)

end NioExclusiveCreatePlatform
