// What Jolt needs set up before anything else, which the generator can't reach: RegisterDefaultAllocator is declared
// in a header that isn't API, and Factory::sInstance is a static field.
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

#ifdef __cplusplus
}
#endif

#endif // JPH_MANUAL_JPHJOLT_H
