// The helpers only Jolt can write, on top of the generated Bridge.hpp; kolt's generated Includes.hpp includes this.
#pragma once

// Jolt's headers expect Jolt.h first.
#include <Jolt/Jolt.h>

#include <Jolt/Core/Array.h>
#include <Jolt/Core/Reference.h>
#include <Jolt/Core/Result.h>
#include <Jolt/Core/StaticArray.h>
#include <Jolt/Physics/Collision/Shape/SubShapeID.h>

#include "../generated/Bridge.hpp"

namespace kolt {

// A Vec3 or DVec3 gets the fourth lane Jolt expects (a copy of Z), so C only has to fill three.
template<typename T> requires requires(T t) { T(T::sFixW(t.mValue)); }
struct Mirrored<T> {
    static void fix(T& t) { t = T(T::sFixW(t.mValue)); }
};

template<typename T, JPH::uint N>
constexpr uint32_t capacity<JPH::StaticArray<T, N>> = N;

// The pointer a Ref or RefConst holds, with a reference added for C to release; a new object's first reference.
template<typename T>
T* retain(T* p) {
    if (p) p->AddRef();
    return p;
}

template<typename T>
T* retain(const JPH::Ref<T>& ref) { return retain(ref.GetPtr()); }

template<typename T>
const T* retain(const JPH::RefConst<T>& ref) { return retain(ref.GetPtr()); }

// A Result's value as convert makes it; or, when it holds an error, a null one (NULL, false) after copying the
// message into C's buffer, NUL-terminated and cut to its capacity.
template<typename T, typename F>
auto result(const JPH::Result<T>& r, char* error, uint32_t capacity, F convert) -> decltype(convert(r.Get())) {
    if (r.IsValid()) return convert(r.Get());
    if (capacity > 0) std::snprintf(error, capacity, "%s", r.HasError() ? r.GetError().c_str() : "empty result");
    return {};
}

inline JPH::SubShapeID subShapeID(uint32_t value) {
    JPH::SubShapeID id;
    id.SetValue(value);
    return id;
}

} // namespace kolt
