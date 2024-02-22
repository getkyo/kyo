package java.util

import java.util.ArrayList
import java.util.Iterator

class ServiceLoader[T]:
    def iterator(): Iterator[T] =
        (new ArrayList[T](0)).iterator()

object ServiceLoader:
    def load[T](cls: Class[T]): ServiceLoader[T] =
        new ServiceLoader[T]
