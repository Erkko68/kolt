#include "../generated/Includes.hpp"
#include "JphJoltManual.h"

#include <Jolt/Core/Memory.h>
#include <Jolt/Physics/Body/BodyInterface.h>

#include <algorithm>

namespace {

// What AddBodiesPrepare hands out: Jolt's own state, and the IDs it points into.
struct AddState {
    JPH::Array<JPH::BodyID> bodies;
    JPH::BodyInterface::AddState state;
};

} // namespace

extern "C" {

void Jph_RegisterDefaultAllocator(void) {
    JPH::RegisterDefaultAllocator();
}

JphFactory* JphFactory_GetInstance(void) {
    return kolt::c(JPH::Factory::sInstance);
}

void JphFactory_SetInstance(JphFactory* factory) {
    JPH::Factory::sInstance = kolt::cpp(factory);
}

void* JphBodyInterface_AddBodiesPrepare(JphBodyInterface* self, uint32_t* ioBodies, int32_t inNumber) {
    if (inNumber <= 0) return nullptr;
    const JPH::BodyID* ids = reinterpret_cast<const JPH::BodyID*>(ioBodies);
    AddState* add = new AddState{JPH::Array<JPH::BodyID>(ids, ids + inNumber), nullptr};
    add->state = kolt::cpp(self)->AddBodiesPrepare(add->bodies.data(), inNumber);
    std::copy(add->bodies.begin(), add->bodies.end(), reinterpret_cast<JPH::BodyID*>(ioBodies));
    return add;
}

void JphBodyInterface_AddBodiesFinalize(JphBodyInterface* self, uint32_t*, int32_t inNumber, void* inAddState, JphEActivation inActivationMode) {
    AddState* add = static_cast<AddState*>(inAddState);
    if (!add) return;
    kolt::cpp(self)->AddBodiesFinalize(add->bodies.data(), inNumber, add->state, static_cast<JPH::EActivation>(inActivationMode));
    delete add;
}

void JphBodyInterface_AddBodiesAbort(JphBodyInterface* self, uint32_t*, int32_t inNumber, void* inAddState) {
    AddState* add = static_cast<AddState*>(inAddState);
    if (!add) return;
    kolt::cpp(self)->AddBodiesAbort(add->bodies.data(), inNumber, add->state);
    delete add;
}

} // extern "C"
