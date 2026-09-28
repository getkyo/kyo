package com.example.flowstore

import kyo.*

/** The suite extended from outside package `kyo`, as a store written elsewhere extends it: no package-private access, and frames derived
  * at this call site.
  */
class FlowStoreExternalPackageTest extends FlowStoreConformanceTest:
    def makeStore(using Frame): FlowStore < (Async & Scope) = FlowStore.initMemory
