package java.lang.ref

case class WeakReference[T](value: T) {
    def get(): T = value
}
