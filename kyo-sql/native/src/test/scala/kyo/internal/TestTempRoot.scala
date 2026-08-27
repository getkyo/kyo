package kyo.internal

import kyo.*

/** The system temporary-directory root, for [[SqlTestContainers]]'s co-owner registry.
  *
  * The registry is a cross-process rendezvous: every test process on the machine must resolve the SAME directory, so this is the platform's
  * temp root itself rather than `Path.tempDir`, which mints a fresh private directory per call. `Absent` means the platform has no temp
  * root, which disables the registry.
  */
private[kyo] object TestTempRoot:

    def get(using Frame): Maybe[String] < Sync =
        System.property[String]("java.io.tmpdir").map(_.filter(_.nonEmpty))

end TestTempRoot
