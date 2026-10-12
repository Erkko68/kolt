package io.github.erkko68.kolt

import io.github.erkko68.kolt.capi.JphFactory_SetInstance
import io.github.erkko68.kolt.capi.Jph_RegisterDefaultAllocator
import io.github.erkko68.kolt.core.Factory
import io.github.erkko68.kolt.interop.NullPointer

/** What Jolt needs set up before anything else: its allocator, the factory, and the types registered with it. */
object Jolt {
    private var factory: Factory? = null

    /** Call once, before creating anything. */
    fun init() {
        check(factory == null) { "Jolt is already initialized" }
        Jph_RegisterDefaultAllocator()
        factory = Factory().also { JphFactory_SetInstance(it.factoryHandle) }
        registerTypes()
    }

    /** Call once everything created since [init] is closed. */
    fun shutdown() {
        val factory = checkNotNull(factory) { "Jolt isn't initialized" }
        unregisterTypes()
        JphFactory_SetInstance(NullPointer)
        factory.close()
        this.factory = null
    }
}
