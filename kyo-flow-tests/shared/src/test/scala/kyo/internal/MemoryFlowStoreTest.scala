package kyo.internal

import kyo.*

class MemoryFlowStoreTest extends FlowStoreTest:
    def makeStore(using Frame): FlowStore < (Async & Scope) = FlowStore.initMemory
