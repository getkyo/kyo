package kyo.internal

import scala.scalajs.js

/** The name SQLite registers its default VFS under here, read from Node rather than from the Scala platform: the
  * answer is the operating system's, and a Node process runs on all of them. See the jvm-native copy for why a
  * suite naming a VFS cannot hard-code one.
  */
private[kyo] object SqliteVfsDefault:

    val name: String =
        val platform =
            try js.Dynamic.global.selectDynamic("process").selectDynamic("platform").asInstanceOf[String]
            catch case _: Throwable => ""
        if platform == "win32" then "win32" else "unix"
    end name

end SqliteVfsDefault
