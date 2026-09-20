package kyo.ffi.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sbt._

/** Coverage for reading `META-INF/services` back out of a jar. The reading half is file-shaped, so each test builds a
  * real jar: a test that agreed with the code about a path neither had ever read from a jar would prove nothing.
  */
class ServiceProvidersTest extends AnyFunSuite with Matchers {

    private def withJar(entries: Seq[(String, String)])(check: File => Unit): Unit = {
        IO.withTemporaryDirectory { dir =>
            val files = entries.map { case (path, content) =>
                val f = dir / "content" / path.replace('/', '_')
                IO.write(f, content)
                f -> path
            }
            val jar = dir / "artifact.jar"
            IO.zip(files, jar, None)
            check(jar)
        }
    }

    test("parse: one class per line, comments and blanks dropped") {
        val text = "# the backend\nkyo.internal.mysql.MysqlBackendFactory\n\n  kyo.Other  # trailing\n"
        ServiceProviders.parse(text) shouldBe Seq("kyo.internal.mysql.MysqlBackendFactory", "kyo.Other")
    }

    test("parse: a file that is only comments declares nothing") {
        ServiceProviders.parse("# nothing here\n\n   \n") shouldBe Nil
    }

    test("parse: the same class twice is one provider") {
        ServiceProviders.parse("kyo.A\nkyo.A\n") shouldBe Seq("kyo.A")
    }

    test("a jar's service files become interface to implementations") {
        withJar(Seq(
            "META-INF/services/kyo.db.Backend"                   -> "kyo.internal.mysql.MysqlBackendFactory\n",
            "META-INF/services/kyo.stats.internal.ExporterFactory" -> "kyo.stats.machine.MachineStatFactory\n"
        )) { jar =>
            ServiceProviders.readJars(Seq(jar)) shouldBe Map(
                "kyo.db.Backend"                    -> Seq("kyo.internal.mysql.MysqlBackendFactory"),
                "kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.machine.MachineStatFactory")
            )
        }
    }

    test("two jars declaring one interface contribute both implementations, sorted") {
        withJar(Seq("META-INF/services/kyo.db.Backend" -> "kyo.internal.sqlite.SqliteBackendFactory\n")) { first =>
            withJar(Seq("META-INF/services/kyo.db.Backend" -> "kyo.internal.mysql.MysqlBackendFactory\n")) { second =>
                ServiceProviders.readJars(Seq(first, second)) shouldBe Map(
                    "kyo.db.Backend" -> Seq("kyo.internal.mysql.MysqlBackendFactory", "kyo.internal.sqlite.SqliteBackendFactory")
                )
            }
        }
    }

    test("a nested path under the services directory is some other tool's resource, not a provider") {
        withJar(Seq("META-INF/services/vendor/thing.json" -> "{}")) { jar =>
            ServiceProviders.readJars(Seq(jar)) shouldBe Map.empty[String, Seq[String]]
        }
    }

    test("a classpath entry that is a directory rather than a jar is skipped") {
        IO.withTemporaryDirectory { dir =>
            ServiceProviders.readJars(Seq(dir)) shouldBe Map.empty[String, Seq[String]]
        }
    }

    test("merge keeps what the config already had, because a build may have named its own") {
        val existing = Map[String, Iterable[String]]("kyo.db.Backend" -> Seq("app.CustomBackend"))
        val declared = Map("kyo.db.Backend" -> Seq("kyo.internal.mysql.MysqlBackendFactory"))
        ServiceProviders.merge(existing, declared) shouldBe Map(
            "kyo.db.Backend" -> Seq("app.CustomBackend", "kyo.internal.mysql.MysqlBackendFactory")
        )
    }

    test("merge does not duplicate an implementation the config already names") {
        val existing = Map[String, Iterable[String]]("kyo.db.Backend" -> Seq("kyo.A"))
        ServiceProviders.merge(existing, Map("kyo.db.Backend" -> Seq("kyo.A"))) shouldBe
            Map("kyo.db.Backend" -> Seq("kyo.A"))
    }
}
