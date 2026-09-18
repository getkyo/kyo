package kyo.db

import kyo.*

class BackendJvmTest extends Test:

    "public backend registry methods have resolvable JVM signatures" in {
        val methods = Seq("kyo.db.Backend$Registry", "kyo.db.Backend$Registry$").flatMap { name =>
            try Class.forName(name, false, getClass.getClassLoader).getDeclaredMethods.toSeq
            catch case error: LinkageError => fail(s"$name method signatures reference an unavailable class: $error")
        }
        val schemes = methods.filter(_.getName == "schemes")
        assert(schemes.map(_.getReturnType.getName) == Seq("kyo.Chunk"))
    }

end BackendJvmTest
