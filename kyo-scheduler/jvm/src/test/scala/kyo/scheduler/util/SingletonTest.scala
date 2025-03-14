package kyo.scheduler.util

import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class TestInstance(val value: Int)
class TestSingleton extends Singleton[TestInstance] {
    def init() = new TestInstance(42)
}

class SingletonTest extends AnyFreeSpec with NonImplicitAssertions {

    "basic functionality" - {
        "should return same instance across multiple calls" in {
            val singleton = new TestSingleton
            val instance1 = singleton.get
            val instance2 = singleton.get

            assert(instance1 eq instance2)
            assert(instance1.value == 42)
        }

        "should initialize only once" in {
            var initCount = 0
            val singleton =
                new Singleton[String] {
                    def init(): String = {
                        initCount += 1
                        s"instance-$initCount"
                    }
                }

            singleton.get
            singleton.get

            assert(initCount == 1)
        }
    }

    "should maintain single instance across different classloaders" in {
        val cl1 = new URLClassLoader(Array.empty[URL], getClass.getClassLoader)
        val cl2 = new URLClassLoader(Array.empty[URL], getClass.getClassLoader)

        val singleton1 =
            cl1.loadClass("kyo.scheduler.util.TestSingleton").getDeclaredConstructor()
                .newInstance().asInstanceOf[Singleton[TestInstance]]
        val singleton2 =
            cl2.loadClass("kyo.scheduler.util.TestSingleton").getDeclaredConstructor()
                .newInstance().asInstanceOf[Singleton[TestInstance]]

        val instance1 = singleton1.get
        val instance2 = singleton2.get

        assert(instance1 eq instance2)
        assert(instance1.value == 42)
    }

    "concurrency" - {
        "should handle concurrent initialization safely" in {
            class TestInstance(val id: String)
            class TestSingleton extends Singleton[TestInstance] {
                def init() = new TestInstance("test")
            }

            val threadCount = 32
            val latch       = new CountDownLatch(1)
            val singleton   = new TestSingleton
            val executor    = Executors.newFixedThreadPool(threadCount)
            val instances   = ConcurrentHashMap.newKeySet[TestInstance]()

            try {
                val futures = (1 to threadCount).map { _ =>
                    executor.submit(new Runnable {
                        def run(): Unit = {
                            latch.await()
                            val instance = singleton.get
                            instances.add(instance)
                            ()
                        }
                    })
                }

                latch.countDown()

                futures.foreach(_.get(5, TimeUnit.SECONDS))

                val finalInstance = singleton.get
                assert(finalInstance.id == "test")
                assert(instances.size == 1)
            } finally {
                executor.shutdown()
            }
        }

        "should handle concurrent initialization across multiple classloaders" in {
            val classLoaderCount = 5
            val threadsPerLoader = 3
            val totalThreads     = classLoaderCount * threadsPerLoader
            val instances        = ConcurrentHashMap.newKeySet[TestInstance]()

            val classLoaders = (1 to classLoaderCount).map { _ =>
                new URLClassLoader(Array.empty[URL], getClass.getClassLoader)
            }

            val startLatch = new CountDownLatch(1)
            val executor   = Executors.newFixedThreadPool(totalThreads)

            try {
                val futures = for {
                    classLoader <- classLoaders
                    _           <- 1 to threadsPerLoader
                } yield {
                    executor.submit(new Runnable {
                        def run(): Unit = {
                            startLatch.await()

                            val singletonClass = classLoader
                                .loadClass("kyo.scheduler.util.TestSingleton")
                                .getDeclaredConstructor()
                                .newInstance()
                                .asInstanceOf[Singleton[TestInstance]]

                            val instance = singletonClass.get
                            instances.add(instance)
                            ()
                        }
                    })
                }

                startLatch.countDown()

                futures.foreach(_.get(5, TimeUnit.SECONDS))

                val finalLoader = new URLClassLoader(Array.empty[URL], getClass.getClassLoader)
                val finalSingleton = finalLoader
                    .loadClass("kyo.scheduler.util.TestSingleton")
                    .getDeclaredConstructor()
                    .newInstance()
                    .asInstanceOf[Singleton[TestInstance]]

                val finalInstance = finalSingleton.get
                assert(finalInstance.value == 42)
                assert(instances.size == 1)

            } finally {
                executor.shutdown()
            }
        }
    }
}
