package kyo

import kyo.internal.tasty.classfile.ModuleInfoReader

/** JVM-only tests for ModuleInfoReader that require the jrt:/ filesystem (JDK 9+). */
class ModuleInfoJvmTest extends kyo.test.Test[Any]:

    "classpath.findModule(java.base) on JVM classpath returns Present(desc) with name == java.base" in {
        val jrtBytes = loadJavaBaseModuleInfo()
        ModuleInfoReader.read(jrtBytes).map { desc =>
            assert(desc.name == "java.base", s"Expected name 'java.base', got '${desc.name}'")
            assert(
                desc.requires.nonEmpty || desc.exports.nonEmpty,
                "java.base should have non-empty requires or exports"
            )
        }
    }

    /** Load the module-info.class for java.base from the JDK jrt:/ filesystem.
      *
      * The jrt:/ filesystem is available on JDK 9+ and provides access to the platform modules. The path for java.base's module descriptor
      * is jrt:/modules/java.base/module-info.class.
      */
    private def loadJavaBaseModuleInfo(): Array[Byte] =
        val uri = java.net.URI.create("jrt:/")
        val fs  = java.nio.file.FileSystems.getFileSystem(uri)
        val p   = fs.getPath("/modules/java.base/module-info.class")
        java.nio.file.Files.readAllBytes(p)
    end loadJavaBaseModuleInfo

end ModuleInfoJvmTest
