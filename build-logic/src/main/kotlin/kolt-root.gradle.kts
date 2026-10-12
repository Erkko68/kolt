import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.CBindings
import buildlogic.apigen.CppSources
import buildlogic.apigen.Instantiations
import buildlogic.apigen.KotlinBindings
import buildlogic.apigen.Mirror
import buildlogic.apigen.Naming
import buildlogic.apigen.RefCounting
import buildlogic.apigen.Scalar
import buildlogic.apigen.TypeTables
import buildlogic.apigen.gradle.registerApiGenTasks
import buildlogic.emsdk.SetupEmsdkTask

// Everything the generator is told about Jolt; buildlogic.apigen itself knows no library.
registerApiGenTasks(
    ApiGenConfig(
        name = "Jolt",
        sources = CppSources(
            // Jolt's sources: its headers include each other as `<Jolt/…>`.
            includeDir = "jolt",
            astFilters = listOf("JPH::"),
            // As a shared library, so JPH_EXPORT marks the exported classes.
            clangArgs = listOf("-DJPH_SHARED_LIBRARY"),
            // Jolt's headers expect Jolt.h first.
            prelude = listOf("Jolt/Jolt.h"),
        ),
        c = CBindings(
            prefix = "Jph",
            helpers = "kolt",
            droppedNamespaces = setOf("JPH"),
            groupRoot = "Jolt",
            bridgeHeader = "../manual/JphBridge.hpp",
        ),
        naming = Naming(
            memberPrefix = "^m(?=[A-Z])",
            parameterPrefix = "^(in|out|io)(?=[A-Z])",
            outParameter = "^out(?=[A-Z])",
            staticPrefix = "^s(?=[A-Z])",
        ),
        types = TypeTables(
            // IDs and layers are their value.
            scalars = mapOf(
                "JPH::BodyID" to Scalar("uint32_t", "JPH::BodyID({})", "({}).GetIndexAndSequenceNumber()", layout = true),
                "JPH::SubShapeID" to Scalar("uint32_t", "kolt::subShapeID({})", "({}).GetValue()", layout = true),
                "JPH::BroadPhaseLayer" to Scalar("uint32_t", "JPH::BroadPhaseLayer(JPH::BroadPhaseLayer::Type({}))", "uint32_t(({}).GetValue())"),
            ),
            // A Vec3 or DVec3 is four lanes wide; the forwarders set the last (JphBridge.hpp), so C needn't.
            mirrors = mapOf(
                "JPH::Vec3" to Mirror("float", 4, aligned = true), "JPH::Vec4" to Mirror("float", 4, aligned = true),
                "JPH::Quat" to Mirror("float", 4, aligned = true), "JPH::UVec4" to Mirror("uint32_t", 4, aligned = true),
                "JPH::Mat44" to Mirror("float", 16, aligned = true), "JPH::DVec3" to Mirror("double", 4, aligned = true),
                "JPH::Float2" to Mirror("float", 2), "JPH::Float3" to Mirror("float", 3), "JPH::Float4" to Mirror("float", 4),
                "JPH::Double3" to Mirror("double", 3),
            ),
            flagEnums = setOf("JPH::EAllowedDOFs"),
            sequences = setOf("JPH::Array", "JPH::StaticArray"),
            // JPH::String is a std::basic_string with Jolt's allocator.
            strings = mapOf("std::basic_string" to "JPH::String"),
            refs = RefCounting(
                pointers = mapOf("JPH::Ref" to false, "JPH::RefConst" to true),
                targets = setOf("JPH::RefTarget", "JPH::RefTargetVirtual"),
                get = "GetPtr", addRef = "AddRef", release = "Release",
            ),
            results = setOf("JPH::Result"),
        ),
        templates = Instantiations(
            // The collectors of a query's hits: Jolt names each by an alias of CollisionCollector.
            aliases = mapOf(
                "JPH::CastRayCollector" to mapOf("ResultTypeArg" to "JPH::RayCastResult", "TraitsType" to "JPH::CollisionCollectorTraitsCastRay"),
                "JPH::CastShapeCollector" to mapOf("ResultTypeArg" to "JPH::ShapeCastResult", "TraitsType" to "JPH::CollisionCollectorTraitsCastShape"),
                "JPH::CollidePointCollector" to mapOf("ResultTypeArg" to "JPH::CollidePointResult", "TraitsType" to "JPH::CollisionCollectorTraitsCollidePoint"),
                "JPH::CollideShapeCollector" to mapOf("ResultTypeArg" to "JPH::CollideShapeResult", "TraitsType" to "JPH::CollisionCollectorTraitsCollideShape"),
                "JPH::TransformedShapeCollector" to mapOf("ResultTypeArg" to "JPH::TransformedShape", "TraitsType" to "JPH::CollisionCollectorTraitsCollideShape"),
                "JPH::RayCastBodyCollector" to mapOf("ResultTypeArg" to "JPH::BroadPhaseCastResult", "TraitsType" to "JPH::CollisionCollectorTraitsCastRay"),
                "JPH::CastShapeBodyCollector" to mapOf("ResultTypeArg" to "JPH::BroadPhaseCastResult", "TraitsType" to "JPH::CollisionCollectorTraitsCastShape"),
                "JPH::CollideShapeBodyCollector" to mapOf("ResultTypeArg" to "JPH::BodyID", "TraitsType" to "JPH::CollisionCollectorTraitsCollideShape"),
            ),
        ),
        kotlin = KotlinBindings(
            packages = mapOf("kolt" to "io.github.erkko68.kolt.capi"),
            interop = "io.github.erkko68.kolt.interop",
            wasmRuntime = "kolt",
            wasmPackage = "io.github.erkko68.kolt.wasm",
        ),
    ),
)

tasks.register<SetupEmsdkTask>("setupEmsdk") {
    group = "build setup"
    description = "Installs the pinned Emscripten SDK (emsdkVersion in gradle.properties) into .emsdk/."
    emsdkVersion.set(providers.gradleProperty("emsdkVersion"))
    installDir.set(layout.projectDirectory.dir(".emsdk"))
}
