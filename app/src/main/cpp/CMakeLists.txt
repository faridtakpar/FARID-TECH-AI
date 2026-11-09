
cmake_minimum_required(VERSION 3.22.1)

project(FaridTechAI LANGUAGES C CXX)

include(FetchContent)

# Build static libraries and include them in native-lib
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Android ARM64 CPU configuration
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Disable unnecessary components
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

# Fetch a fixed llama.cpp version
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b12143
    GIT_SHALLOW TRUE
)

FetchContent_MakeAvailable(llama_cpp)

# Native JNI library
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

# Connect llama.cpp to our JNI library
target_link_libraries(
    native-lib
    PRIVATE
    llama
    ${log-lib}
)
