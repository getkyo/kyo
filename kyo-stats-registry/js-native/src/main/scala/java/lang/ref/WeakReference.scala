package java.lang.ref

case class WeakReference[A](value: A) {
    def get(): A = value
}
