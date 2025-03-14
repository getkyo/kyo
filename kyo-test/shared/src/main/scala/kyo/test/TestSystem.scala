package kyo.test

import kyo.*

trait TestSystem extends System with Restorable:
    def putEnv(name: String, value: String)(implicit trace: Trace): Unit < IO
    def putProperty(name: String, value: String)(implicit trace: Trace): Unit < IO
    def setLineSeparator(lineSep: String)(implicit trace: Trace): Unit < IO
    def clearEnv(variable: String)(implicit trace: Trace): Unit < IO
    def clearProperty(prop: String)(implicit trace: Trace): Unit < IO
end TestSystem

object TestSystem extends Serializable:

    final case class Test(systemState: AtomicRef[TestSystem.Data]) extends TestSystem:

        def env(variable: => String)(implicit trace: Trace): Option[String] < (Env[Any] & Abort[SecurityException]) =
            unsafe.env(variable)(Unsafe.unsafe)

        def envOrElse(variable: => String, alt: => String)(implicit trace: Trace): String < (Env[Any] & Abort[SecurityException]) =
            unsafe.envOrElse(variable, alt)(Unsafe.unsafe)

        def envOrOption(variable: => String, alt: => Option[String])(implicit
            trace: Frame
        ): Option[String] < (Env[Any] & Abort[SecurityException]) =
            unsafe.envOrOption(variable, alt)(Unsafe.unsafe)

        def envs(implicit trace: Trace): Map[String, String] < (Env[Any] & Abort[SecurityException]) =
            unsafe.envs()(Unsafe.unsafe)

        def lineSeparator(implicit trace: Trace): String =
            unsafe.lineSeparator()(Unsafe.unsafe)

        def properties(implicit trace: Trace): Map[String, String] < (Env[Any] & Abort[Throwable]) =
            unsafe.properties()(Unsafe.unsafe)

        def property(prop: => String)(implicit trace: Trace): Option[String] < (Env[Any] & Abort[Throwable]) =
            unsafe.property(prop)(Unsafe.unsafe)

        def propertyOrElse(prop: => String, alt: => String)(implicit trace: Trace): String < (Env[Any] & Abort[Throwable]) =
            unsafe.propertyOrElse(prop, alt)(Unsafe.unsafe)

        def propertyOrOption(prop: => String, alt: => Option[String])(implicit
            trace: Frame
        ): Option[String] < (Env[Any] & Abort[Throwable]) =
            unsafe.propertyOrOption(prop, alt)(Unsafe.unsafe)

        def putEnv(name: String, value: String)(implicit trace: Trace): Unit < IO =
            systemState.update(data => data.copy(envs = data.envs.updated(name, value)))

        def putProperty(name: String, value: String)(implicit trace: Trace): Unit < IO =
            systemState.update(data => data.copy(properties = data.properties.updated(name, value)))

        def setLineSeparator(lineSep: String)(implicit trace: Trace): Unit < IO =
            systemState.update(_.copy(lineSeparator = lineSep))

        def clearEnv(variable: String)(implicit trace: Trace): Unit < IO =
            systemState.update(data => data.copy(envs = data.envs - variable))

        def clearProperty(prop: String)(implicit trace: Trace): Unit < IO =
            systemState.update(data => data.copy(properties = data.properties - prop))

        def save(implicit trace: Trace): Unit < Any =
            for
                systemData <- systemState.get
            yield systemState.set(systemData)

        override val unsafe: UnsafeAPI =
            new UnsafeAPI:
                override def env(variable: String)(implicit unsafe: Unsafe): Option[String] =
                    systemState.unsafe.get.envs.get(variable)

                override def envOrElse(variable: String, alt: => String)(implicit unsafe: Unsafe): String =
                    System.envOrElseWith(variable, alt)(env)

                override def envOrOption(variable: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String] =
                    System.envOrOptionWith(variable, alt)(env)

                override def envs()(implicit unsafe: Unsafe): Map[String, String] =
                    systemState.unsafe.get.envs

                override def lineSeparator()(implicit unsafe: Unsafe): String =
                    systemState.unsafe.get.lineSeparator

                override def properties()(implicit unsafe: Unsafe): Map[String, String] =
                    systemState.unsafe.get.properties

                override def property(prop: String)(implicit unsafe: Unsafe): Option[String] =
                    systemState.unsafe.get.properties.get(prop)

                override def propertyOrElse(prop: String, alt: => String)(implicit unsafe: Unsafe): String =
                    System.propertyOrElseWith(prop, alt)(property)

                override def propertyOrOption(prop: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String] =
                    System.propertyOrOptionWith(prop, alt)(property)
    end Test

    val DefaultData: Data = Data(Map(), Map(), "\n")

    def live(data: Data): Layer[Nothing, TestSystem] =
        given Trace = Tracer.newTrace
        Layer.scoped {
            for
                ref <- Var.unsafe.make(data)(Unsafe.unsafe)
                test = Test(ref)
                _ <- withSystemScoped(test)
            yield test
        }
    end live

    val any: Layer[TestSystem, Nothing] =
        Layer.environment[TestSystem](Tracer.newTrace)

    val default: Layer[Nothing, TestSystem] =
        live(DefaultData)

    def putEnv(name: => String, value: => String)(implicit trace: Trace): Unit < IO =
        testSystemWith(_.putEnv(name, value))

    def putProperty(name: => String, value: => String)(implicit trace: Trace): Unit < IO =
        testSystemWith(_.putProperty(name, value))

    def save(implicit trace: Trace): UIO[Unit < Any] =
        testSystemWith(_.save)

    def setLineSeparator(lineSep: => String)(implicit trace: Trace): Unit < IO =
        testSystemWith(_.setLineSeparator(lineSep))

    def clearEnv(variable: => String)(implicit trace: Trace): Unit < IO =
        testSystemWith(_.clearEnv(variable))

    def clearProperty(prop: => String)(implicit trace: Trace): Unit < IO =
        testSystemWith(_.clearProperty(prop))

    final case class Data(
        properties: Map[String, String] = Map.empty,
        envs: Map[String, String] = Map.empty,
        lineSeparator: String = "\n"
    )
end TestSystem
