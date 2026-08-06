# OmniLLM native packaging — libomnillm_llama.so
# Authority: ANDROID-NATIVE, ENGINE-LLAMACPP
#
# Builds production JNI surface linking pinned ggml-org/llama.cpp when present
# under third_party/llama.cpp. Always includes EXPERIMENTAL_FIXTURE C++ path.
# See engines/llama-cpp/NATIVE.md and third_party/README.md.
#
# NDK r28+ defaults to 16 KB max page size; ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES
# is also passed from Gradle.

cmake_minimum_required(VERSION 3.22.1)
project(omnillm_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 16 KB page-size safety (ANDROID-NATIVE §2).
if(ANDROID)
  set(OMNILLM_PAGE_FLAGS "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()

# ---------------------------------------------------------------------------
# Optional vendored llama.cpp (pinned via engines/llama-cpp/UPSTREAM.lock)
# ---------------------------------------------------------------------------
set(OMNILLM_LLAMA_VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp")
option(OMNILLM_LLAMA_WITH_UPSTREAM
    "Link against vendored llama.cpp when third_party tree is present" ON)

set(OMNILLM_HAS_UPSTREAM OFF)
if(OMNILLM_LLAMA_WITH_UPSTREAM AND EXISTS "${OMNILLM_LLAMA_VENDOR_DIR}/CMakeLists.txt")
  message(STATUS "OmniLLM: vendored llama.cpp found at ${OMNILLM_LLAMA_VENDOR_DIR}")

  # Library-only consumer build (no tools/server/tests/UI).
  set(LLAMA_BUILD_COMMON OFF CACHE BOOL "omnillm: no common utils" FORCE)
  set(LLAMA_BUILD_TESTS OFF CACHE BOOL "omnillm: no tests" FORCE)
  set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "omnillm: no tools" FORCE)
  set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "omnillm: no examples" FORCE)
  set(LLAMA_BUILD_SERVER OFF CACHE BOOL "omnillm: no server" FORCE)
  set(LLAMA_BUILD_APP OFF CACHE BOOL "omnillm: no app" FORCE)
  set(LLAMA_BUILD_UI OFF CACHE BOOL "omnillm: no web ui" FORCE)
  set(LLAMA_OPENSSL OFF CACHE BOOL "omnillm: no openssl" FORCE)
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "omnillm: static llama/ggml into omnillm_llama" FORCE)
  set(GGML_NATIVE OFF CACHE BOOL "omnillm: no host-native march" FORCE)
  set(GGML_OPENMP OFF CACHE BOOL "omnillm: OpenMP off for Android portability" FORCE)
  set(GGML_BACKEND_DL OFF CACHE BOOL "omnillm: no dynamic backends" FORCE)
  set(GGML_CCACHE OFF CACHE BOOL "omnillm: avoid ccache surprises in AGP" FORCE)

  # Keep ggml CPU-only; accelerator backends are UNQUALIFIED by default.
  set(GGML_CUDA OFF CACHE BOOL "" FORCE)
  set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
  set(GGML_METAL OFF CACHE BOOL "" FORCE)
  set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
  set(GGML_SYCL OFF CACHE BOOL "" FORCE)
  set(GGML_RPC OFF CACHE BOOL "" FORCE)

  add_subdirectory("${OMNILLM_LLAMA_VENDOR_DIR}" llama_cpp_build EXCLUDE_FROM_ALL)
  set(OMNILLM_HAS_UPSTREAM ON)
else()
  message(STATUS
      "OmniLLM: vendored llama.cpp not present — building EXPERIMENTAL_FIXTURE only. "
      "See android/native/src/main/cpp/third_party/README.md")
endif()

# ---------------------------------------------------------------------------
# libomnillm_llama
# ---------------------------------------------------------------------------
set(OMNILLM_LLAMA_SOURCES
    omnillm_llama.cpp
    omnillm_fixture_backend.cpp
    omnillm_upstream_backend.cpp
    jni_bridge.cpp
)

add_library(omnillm_llama SHARED ${OMNILLM_LLAMA_SOURCES})

target_include_directories(omnillm_llama PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(omnillm_llama PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
)

if(OMNILLM_HAS_UPSTREAM)
  target_compile_definitions(omnillm_llama PRIVATE OMNILLM_HAS_LLAMA_CPP=1)
  # llama / ggml targets from vendored tree
  if(TARGET llama)
    target_link_libraries(omnillm_llama PRIVATE llama)
  else()
    message(WARNING "OmniLLM: llama target missing after add_subdirectory — fixture only")
    target_compile_definitions(omnillm_llama PRIVATE OMNILLM_HAS_LLAMA_CPP=0)
    set(OMNILLM_HAS_UPSTREAM OFF)
  endif()
endif()

if(OMNILLM_PAGE_FLAGS)
  target_link_options(omnillm_llama PRIVATE ${OMNILLM_PAGE_FLAGS})
endif()

find_library(log-lib log)
if(log-lib)
  target_link_libraries(omnillm_llama PRIVATE ${log-lib})
endif()

# Android needs libdl for some ggml paths; harmless if unused.
find_library(dl-lib dl)
if(dl-lib)
  target_link_libraries(omnillm_llama PRIVATE ${dl-lib})
endif()

if(CMAKE_BUILD_TYPE STREQUAL "Release")
  target_link_options(omnillm_llama PRIVATE -s)
endif()

message(STATUS "OmniLLM: OMNILLM_HAS_UPSTREAM=${OMNILLM_HAS_UPSTREAM}")
