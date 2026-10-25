project(Octo4a)
cmake_minimum_required(VERSION 3.4.1)

# NDK r21 stores libatomic outside the API-specific sysroot directories.
# Add the ABI-specific archive directory so the toolchain's -latomic resolves.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(ANDROID_ATOMIC_LIB_DIR "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/aarch64-linux-android/lib64")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(ANDROID_ATOMIC_LIB_DIR "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/arm-linux-androideabi/lib/armv7-a")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    set(ANDROID_ATOMIC_LIB_DIR "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/x86_64-linux-android/lib64")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
    set(ANDROID_ATOMIC_LIB_DIR "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/i686-linux-android/lib")
endif()
if(ANDROID_ATOMIC_LIB_DIR)
    file(TO_CMAKE_PATH "${ANDROID_ATOMIC_LIB_DIR}" ANDROID_ATOMIC_LIB_DIR)
    set(CMAKE_C_STANDARD_LIBRARIES "${ANDROID_ATOMIC_LIB_DIR}/libatomic.a -lm")
    set(CMAKE_CXX_STANDARD_LIBRARIES "${ANDROID_ATOMIC_LIB_DIR}/libatomic.a -lm")
endif()

add_library(vsp-pty SHARED
            vsp-pty.cpp openpty.c)

target_link_libraries(vsp-pty
                      android
                      log)

add_library(yuv2rgb SHARED
            yuv2rgb.cpp)

target_link_libraries(yuv2rgb
                      android)

add_library(ioctl-hook SHARED
            ioctl-hook.c)

target_link_libraries(ioctl-hook
                      android)
