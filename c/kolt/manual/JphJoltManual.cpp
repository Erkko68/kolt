#include "../generated/Includes.hpp"
#include "JphJoltManual.h"

#include <Jolt/Core/Memory.h>

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

} // extern "C"
