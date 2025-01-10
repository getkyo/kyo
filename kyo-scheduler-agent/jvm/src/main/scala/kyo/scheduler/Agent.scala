package kyo.scheduler

import java.io.File
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.security.ProtectionDomain
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.`type`.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.loading.ClassInjector
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.matcher.ElementMatchers.*
import net.bytebuddy.utility.JavaModule
import scala.jdk.CollectionConverters.*

object Agent {
    def premain(agentArgs: String, inst: Instrumentation): Unit = {
        println("Agent starting...")

        new AgentBuilder.Default()
            .ignore(none())
            .`type`(named("kyo.scheduler.SimpleTarget"))
            .transform(new AgentBuilder.Transformer {
                override def transform(
                    builder: DynamicType.Builder[?],
                    typeDescription: TypeDescription,
                    classLoader: ClassLoader,
                    module: JavaModule,
                    protectionDomain: ProtectionDomain
                ): DynamicType.Builder[?] = {
                    println(s"Found target class")
                    builder.method(named("getMessage"))
                        .intercept(MethodDelegation.to(classOf[SimpleInterceptor]))
                }
            })
            .installOn(inst)
        println("Agent done")
    }
}

class SimpleTarget {
    def getMessage(): String = "original message"
}

class SimpleInterceptor {
    @RuntimeType
    def intercept(): String = {
        println("Method intercepted!")
        "intercepted message"
    }
}

object TestApp {
    def main(args: Array[String]): Unit = {
        val target = new SimpleTarget()
        println(target.getMessage())
    }
}
