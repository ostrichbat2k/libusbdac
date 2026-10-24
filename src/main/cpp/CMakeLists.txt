# Tiny native shim: one raw USBDEVFS_IOCTL/USBDEVFS_DISCONNECT call that Android's public
# UsbDeviceConnection API doesn't expose on its own (see usb_native.cpp for why it's needed).
cmake_minimum_required(VERSION 3.22.1)

project("usbnative") # becomes "libusbnative.so"

add_library(${CMAKE_PROJECT_NAME} SHARED
        usb_native.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        log
)

# Arm Memory Tagging Extension (MTE), debug-only diagnostic instrumentation for catching heap/
# stack memory-safety bugs (use-after-free, buffer overflow) in this file's raw USBDEVFS ioctl/
# URB handling on a real MTE-capable device (e.g. Pixel 8+). Sync mode trades speed for a precise
# fault report (exact address + alloc/dealloc stacks) -- see
# https://developer.android.com/ndk/guides/arm-mte. AArch64-only flag, and only meaningful for a
# Debug build (AGP sets CMAKE_BUILD_TYPE to match the Android build variant automatically) -- a
# release build, or any other ABI, is left untouched.
if(CMAKE_BUILD_TYPE STREQUAL "Debug" AND ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
            -fsanitize=memtag -fno-omit-frame-pointer -march=armv8-a+memtag)
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
            -fsanitize=memtag -fsanitize-memtag-mode=sync -march=armv8-a+memtag)
endif()
