// What the generator can't write. What Jolt needs set up before anything else: RegisterDefaultAllocator is declared
// in a header that isn't API, and Factory::sInstance is a static field. And the calls whose arguments Jolt keeps.
#ifndef JPH_MANUAL_JPHJOLT_H
#define JPH_MANUAL_JPHJOLT_H

#include "../generated/Types.h"

#ifdef __cplusplus
extern "C" {
#endif

// Call first: everything else allocates.
void Jph_RegisterDefaultAllocator(void);

// Jph_RegisterTypes needs the instance set; unset (NULL) and destroy it after Jph_UnregisterTypes.
JphFactory* JphFactory_GetInstance(void);
void JphFactory_SetInstance(JphFactory* factory);

// In place of the generated forwarders, to their signatures: Jolt keeps pointers into ioBodies from Prepare until
// Finalize or Abort, and a caller's array (a binding's scratch copy) may not live that long. Prepare keeps a copy of
// its own, which the returned state holds and Finalize or Abort frees; ioBodies gets the order Jolt sorted it into.
void* JphBodyInterface_AddBodiesPrepare(JphBodyInterface* self, uint32_t* ioBodies, int32_t inNumber);
void JphBodyInterface_AddBodiesFinalize(JphBodyInterface* self, uint32_t* ioBodies, int32_t inNumber, void* inAddState, JphEActivation inActivationMode);
void JphBodyInterface_AddBodiesAbort(JphBodyInterface* self, uint32_t* ioBodies, int32_t inNumber, void* inAddState);

#ifdef __cplusplus
}
#endif

#endif // JPH_MANUAL_JPHJOLT_H
