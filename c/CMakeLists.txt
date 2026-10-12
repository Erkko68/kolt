cmake_minimum_required(VERSION 3.22)

# The Jph* C API over Jolt, built by :kotlin:kolt's cmakeBuild: libkolt-c.a and the libJolt.a it calls, both into
# KOLT_OUTPUT_DIR for Kotlin/Native to link.
project(kolt-c C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${KOLT_OUTPUT_DIR}")

# Jolt as generateCApi read its headers: no debug renderer or profiler. No LTO: Kotlin/Native's linker takes objects.
# No GPU compute: it isn't API, and needs Metal or Vulkan.
set(DEBUG_RENDERER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL "")
set(PROFILER_IN_DEBUG_AND_RELEASE OFF CACHE BOOL "")
set(INTERPROCEDURAL_OPTIMIZATION OFF CACHE BOOL "")
set(JPH_USE_MTL OFF CACHE BOOL "")
set(JPH_USE_VK OFF CACHE BOOL "")
set(ENABLE_INSTALL OFF CACHE BOOL "")
add_subdirectory(../jolt/Build jolt)

# Each module is its generated API (<module>/generated) plus the hand-written rest (<module>/manual).
file(GLOB sources CONFIGURE_DEPENDS kolt/generated/*.cpp kolt/manual/*.cpp)
add_library(kolt-c STATIC ${sources})
# Jolt's defines and include dir come with the target.
target_link_libraries(kolt-c PUBLIC Jolt)
