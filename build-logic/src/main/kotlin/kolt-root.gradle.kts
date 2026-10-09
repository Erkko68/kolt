import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.Mirror
import buildlogic.apigen.RefCounting
import buildlogic.apigen.Scalar
import buildlogic.apigen.registerApiGenTasks

// Everything the generator is told about Jolt; buildlogic.apigen itself knows no library.
registerApiGenTasks(
    ApiGenConfig(
        name = "Jolt",
        // Jolt's sources: its headers include each other as `<Jolt/…>`.
        includeDir = "jolt",
        // As a shared library, so JPH_EXPORT marks the exported classes.
        clangArgs = listOf("-DJPH_SHARED_LIBRARY"),
        // Jolt's headers expect Jolt.h first.
        prelude = listOf("Jolt/Jolt.h"),
        astFilters = listOf("JPH::"),
        prefix = "Jph",
        droppedNamespaces = setOf("JPH"),
        memberPrefix = "^m(?=[A-Z])",
        helpers = "kolt",
        bridgeHeader = "../manual/JphBridge.hpp",
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
)
