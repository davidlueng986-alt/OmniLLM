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

# ---------------------------------------------------------------------------
# D1b: byte-reproducible builds — neutralize absolute checkout paths.
#
# Assert/fatal strings embed __FILE__ (absolute source path) into .rodata, so
# .so bytes depended on the checkout directory (two clones of the same commit
# produced different digests). Map source + build dirs to fixed canonical
# prefixes; the D2 digest gate then matches across checkouts.
# ---------------------------------------------------------------------------
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  string(REPLACE "\\" "/" _omnillm_src_dir "${CMAKE_CURRENT_SOURCE_DIR}")
  string(REPLACE "\\" "/" _omnillm_bin_dir "${CMAKE_CURRENT_BINARY_DIR}")
  add_compile_options(
    "-ffile-prefix-map=${_omnillm_src_dir}=omnillm/src"
    "-ffile-prefix-map=${_omnillm_bin_dir}=omnillm/build"
  )
endif()

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

  # -------------------------------------------------------------------------
  # D1 build-repro pin: embed the UPSTREAM commit regardless of outer repo.
  #
  # The vendored tree is committed WITHOUT .git, so llama.cpp's
  # cmake/build-info.cmake and ggml/CMakeLists.txt run `git rev-parse` with
  # WORKING_DIRECTORY = the vendored tree. git then walks UP to the outer
  # OmniLLM repo and embeds the WRONG commit (e.g. OmniLLM HEAD) instead of
  # the upstream llama.cpp pin — breaking byte reproducibility of the .so.
  #
  # Pin from HERE (our own CMakeLists; no upstream file change for the pin):
  #   - LLAMA_BUILD_COMMIT is honored by llama.cpp's
  #     `if (NOT DEFINED LLAMA_BUILD_COMMIT)` guard (CMakeLists.txt:130) and
  #     forwarded to GGML_BUILD_COMMIT before add_subdirectory(ggml).
  #   - The vendored ggml/CMakeLists.txt carries a MINIMAL one-line guard
  #     (`if(GIT_EXE AND NOT DEFINED GGML_BUILD_COMMIT)`) so the ggml subtree
  #     keeps the pinned value instead of re-deriving it from the outer repo.
  #     See that file for the full rationale.
  #
  # Keep in sync with engines/llama-cpp/UPSTREAM.lock upstream.commit.
  # -------------------------------------------------------------------------
  set(LLAMA_BUILD_COMMIT "47c786924ad1ab7e91da2cdc72fcdb563780c2bd"
      CACHE STRING "omnillm D1: pinned upstream llama.cpp commit (b9999; see UPSTREAM.lock)" FORCE)

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
