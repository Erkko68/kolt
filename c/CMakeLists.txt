cmake_minimum_required(VERSION 3.22)

# The Jph* C API over Jolt, built by :kotlin:kolt's cmakeBuild: libkolt-c.a and the libJolt.a it calls, both into
# KOLT_OUTPUT_DIR for Kotlin/Native to link; and with KOLT_BINDINGS_DIR, libkolt, the same as a JNI library.
project(kolt-c C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Android's output dir holds only what an app packages: libkolt.so.
if (NOT ANDROID)
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${KOLT_OUTPUT_DIR}")
endif()

# Jolt as generateCApi read its headers: no debug renderer or profiler. No LTO: Kotlin/Native's linker takes objects.
# No GPU compute: it isn't API, and needs Metal or Vulkan.
set(DEBUG_RENDERER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL "")
set(PROFILER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL "")
set(INTERPROCEDURAL_OPTIMIZATION OFF CACHE BOOL "")
set(JPH_USE_MTL OFF CACHE BOOL "")
set(JPH_USE_VK OFF CACHE BOOL "")
set(ENABLE_INSTALL OFF CACHE BOOL "")
# Android's x86_64 promises SSE4.2, nothing newer.
if (ANDROID)
    foreach(feature USE_AVX USE_AVX2 USE_AVX512 USE_LZCNT USE_TZCNT USE_F16C USE_FMADD)
        set(${feature} OFF CACHE BOOL "")
    endforeach()
endif()
add_subdirectory(../jolt/Build jolt)

# The generated API (kolt/generated, grouped as Jolt's dirs) plus the hand-written rest (kolt/manual).
file(GLOB_RECURSE sources CONFIGURE_DEPENDS kolt/generated/*.cpp kolt/manual/*.cpp)
add_library(kolt-c STATIC ${sources})
# Jolt's defines and include dir come with the target.
target_link_libraries(kolt-c PUBLIC Jolt)
# As Jolt is built: a class deriving from one of its (the generated _Upcalls) would need its type info otherwise.
if (NOT MSVC)
    target_compile_options(kolt-c PRIVATE -fno-rtti)
endif()

# The JVM's image: the C API, the JNI runtime (kolt/jni) and the forwarders generateBindings writes, over one Jolt.
if (KOLT_BINDINGS_DIR)
    file(GLOB forwarders CONFIGURE_DEPENDS "${KOLT_BINDINGS_DIR}/jni/*.c")
    add_library(kolt-jni SHARED kolt/jni/KoltJni.cpp ${forwarders})
    set_target_properties(kolt-jni PROPERTIES OUTPUT_NAME kolt LIBRARY_OUTPUT_DIRECTORY "${KOLT_OUTPUT_DIR}")
    target_link_libraries(kolt-jni PRIVATE kolt-c)
    # The forwarders include the C headers as `kolt/generated/…`.
    target_include_directories(kolt-jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    # Only the JNI entry points are exported: Jolt's C++ stays local, and the linker drops what they never reach.
    set(seal "${CMAKE_CURRENT_SOURCE_DIR}/kolt/jni/kolt-jni.map")
    if (ANDROID)
        # jni.h is the NDK's. 16 KB pages are required from Android 15.
        target_link_options(kolt-jni PRIVATE "LINKER:--version-script=${seal}" "LINKER:-z,max-page-size=16384" "LINKER:--gc-sections" "LINKER:--strip-all")
        set_target_properties(kolt-jni PROPERTIES LINK_DEPENDS "${seal}")
    else()
        # jni.h is the JDK's running the build.
        if (APPLE)
            set(jni_os darwin)
        elseif (WIN32)
            set(jni_os win32)
        else()
            set(jni_os linux)
        endif()
        target_include_directories(kolt-jni PRIVATE "${JNI_HOME}/include" "${JNI_HOME}/include/${jni_os}")
        if (APPLE)
            target_link_options(kolt-jni PRIVATE "LINKER:-exported_symbol,_Java_*" "LINKER:-exported_symbol,_JNI_OnLoad" "LINKER:-dead_strip")
        endif()
    endif()
endif()
