package io.github.erkko68.kolt

import kotlin.js.Promise

/** kolt.js's factory (`-sEXPORT_NAME`). */
private external fun createKoltModule(): Promise<JsAny>

/**
 * Loads kolt.wasm, next to the kolt.js the page includes; [Jolt.init] and everything else come after it resolves.
 * Jolt runs on the calling thread here: a job system's threads are ignored.
 */
fun loadJolt(): Promise<JsAny> = createKoltModule()
