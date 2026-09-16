package kyo.internal

import kyo.*

/** The system temporary-directory root, for [[SqlTestContainers]]'s co-owner registry.
  *
  * The registry is a cross-process rendezvous: every test process on the machine must resolve the SAME directory, so this is the platform's
  * temp root itself rather than `Path.tempDir`, which mints a fresh private directory per call. Scala.js does not surface `java.io.tmpdir`
  * as a system property, so the root comes from Node's `os.tmpdir()` instead, reached through [[TestNodeBuiltins]] at the call rather than
  * through an import at load. `Absent` means the platform has no temp root, which disables the registry: that is a page, which has no
  * container to register in either.
  */
private[kyo] object TestTempRoot:

    def get(using Frame): Maybe[String] < Sync =
        Sync.defer {
            TestNodeBuiltins.get("node:os").flatMap { os =>
                Maybe(os.applyDynamic("tmpdir")().asInstanceOf[String]).filter(_.nonEmpty)
            }
        }

end TestTempRoot
