package kyo.test
// Converted from Kyo to Kyo test system
// import zio.stacktracer.TracingImplicits.disableAutoTrace

private[test] trait PrettyPrintVersionSpecific:
    def labels(product: Product): Iterator[String] = product.productElementNames
