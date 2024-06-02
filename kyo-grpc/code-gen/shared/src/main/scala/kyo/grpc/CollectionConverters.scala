package kyo.grpc

import scala.collection.convert.AsJavaExtensions
import scala.collection.convert.AsScalaExtensions

// A version without deprecation warnings that works for Scala 2.12, 2.13 and 3.
object CollectionConverters extends AsJavaExtensions with AsScalaExtensions
