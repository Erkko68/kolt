import io.github.erkko68.filament.compose.FilamentApp
import io.github.erkko68.kolt.loadJolt

// Both runtimes instantiate their wasm asynchronously: kolt's first, then FilamentApp loads Filament's.
fun main() {
    loadJolt().then {
        FilamentApp { CubesScene() }
        null
    }
}
