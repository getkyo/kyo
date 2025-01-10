package kyo.scheduler

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AgentTest extends AnyFreeSpec with Matchers {
    "The agent" - {
        "should intercept local class" in {
            val agentJar = new File("target/scala-3.6.2/kyo-scheduler-agent.jar")
            agentJar should exist

            val classpath = System.getProperty("java.class.path")

            val pb = new ProcessBuilder(
                "java",
                s"-javaagent:${agentJar.getAbsolutePath}",
                "-cp",
                classpath,
                "kyo.scheduler.TestApp"
            )

            pb.redirectError(Redirect.INHERIT)
            val process = pb.start()

            val output = scala.io.Source.fromInputStream(process.getInputStream).getLines().mkString("\n")

            println(output)

            process.waitFor(5, TimeUnit.SECONDS) shouldBe true

            output should include("Method intercepted!")
            output should include("intercepted message")
        }
    }
}
