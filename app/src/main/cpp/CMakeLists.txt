# AI subtitles: whisper.cpp (MIT licence), fetched at build time and built for each ABI.
cmake_minimum_required(VERSION 3.22.1)
project(vvwhisper C CXX)

# Speech recognition is far too slow unoptimised, so always build it in Release.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggerganov/whisper.cpp/archive/refs/tags/v1.7.4.tar.gz
)
FetchContent_MakeAvailable(whisper)

add_library(vvwhisper SHARED vvwhisper.cpp)
target_compile_options(vvwhisper PRIVATE -O3)
target_link_libraries(vvwhisper PRIVATE whisper log)
