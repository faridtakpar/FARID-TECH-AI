
cmake_minimum_required(VERSION 3.22.1)

project(FaridTechAI LANGUAGES C CXX)

include(FetchContent)

# Build static dependencies with position-independent code.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android ARM64 configuration.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

# Disable unnecessary components.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

# Use a fixed llama.cpp revision.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b12144
    GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(llama_cpp)

# Native JNI library.
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# Android logging library.
find_library(
    log-lib
    log
)

# Link llama.cpp and its dependencies.
target_link_libraries(
    native-lib
    PRIVATE
    llama
    ${log-lib}
)
