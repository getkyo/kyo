package java.util

class ServiceLoader[A]:
    def iterator(): Iterator[A] =
        (new ArrayList[A](0)).iterator()

object ServiceLoader:
    def load[A](cls: Class[A]): ServiceLoader[A] =
        new ServiceLoader[A]
