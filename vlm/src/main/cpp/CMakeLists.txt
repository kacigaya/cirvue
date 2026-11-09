cmake_minimum_required(VERSION 3.22)
project(cirvue_vlm LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# AGP selects Debug for debug APKs. Inference still needs optimized kernels.
set(CMAKE_C_FLAGS_DEBUG "-O3 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g -DNDEBUG")
foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_EXAMPLES
        LLAMA_BUILD_SERVER LLAMA_BUILD_TOOLS LLAMA_CURL LLAMA_OPENSSL
        LLAMA_SUBPROCESS MTMD_VIDEO GGML_NATIVE GGML_VULKAN GGML_OPENCL
        GGML_OPENMP GGML_CPU_KLEIDIAI)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp)
add_library(cirvue_vlm SHARED cirvue_vlm.cpp)
target_link_libraries(cirvue_vlm PRIVATE mtmd llama android jnigraphics log dl)
# Optional device harness. It uses the exact inference implementation below.
option(CIRVUE_VLM_BENCHMARK "Build the standalone device benchmark" OFF)
if(CIRVUE_VLM_BENCHMARK)
    add_executable(cirvue_vlm_benchmark cirvue_vlm.cpp)
    target_compile_definitions(cirvue_vlm_benchmark PRIVATE CIRVUE_VLM_BENCHMARK)
    target_link_libraries(cirvue_vlm_benchmark PRIVATE mtmd llama log dl)
endif()
