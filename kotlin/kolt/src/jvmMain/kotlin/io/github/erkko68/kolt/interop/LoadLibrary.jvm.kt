package io.github.erkko68.kolt.interop

import io.github.erkko68.kolt.desktop.KoltLoader

internal actual fun loadLibrary() = KoltLoader.load()
