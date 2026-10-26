cmake_minimum_required(VERSION 3.22)
project(lumen_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if (NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp 서브모듈이 비어 있습니다. 저장소 루트에서 'git submodule update --init'을 실행하세요")
endif ()

# whisper.cpp를 정적 라이브러리로 묶어 JNI 라이브러리 하나로 만든다
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# NDK의 OpenMP는 libomp.so를 따로 실어야 해서 끄고 ggml 자체 스레드 풀을 쓴다
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # fp16 연산으로 2배 가까이 빨라진다. 지원하지 않는 CPU는 앱이 미리 확인해 막는다(WhisperNative.isCpuSupported)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
endif ()

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(lumen_whisper SHARED whisper_jni.cpp)
target_link_libraries(lumen_whisper PRIVATE whisper log)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(lumen_whisper PRIVATE -O3)
endif ()
# 디버그 빌드에서도 ggml은 최적화해야 실기기에서 쓸 만한 속도가 나온다
target_compile_options(ggml-cpu PRIVATE -O3)
target_compile_options(ggml-base PRIVATE -O3)
target_compile_options(whisper PRIVATE -O3)
