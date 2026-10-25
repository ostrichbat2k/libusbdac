# Standalone host-side test build for usb_native.cpp -- deliberately NOT wired into the
# NDK/AGP build (../src/main/cpp/CMakeLists.txt), since it targets the host compiler/libc, not
# Android. Needs: a plain host cmake/g++ (NOT the Android toolchain file), GoogleTest (via
# pkg-config, e.g. the distro's gtest-dev package), and a JDK (for a real jni.h -- Android's own
# jni.h isn't shipped standalone off-device).
#
# Build/run:
#   cmake -S native-test -B native-test/build -DJNI_INCLUDE_DIR=$JAVA_HOME/include
#   cmake --build native-test/build
#   ctest --test-dir native-test/build --output-on-failure
cmake_minimum_required(VERSION 3.22.1)
project(usb_native_host_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT JNI_INCLUDE_DIR)
    message(FATAL_ERROR "Pass -DJNI_INCLUDE_DIR=<path to a JDK's include/ dir containing jni.h "
            "and jni_md.h/linux>, e.g. $JAVA_HOME/include")
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GTEST REQUIRED gtest_main)

add_executable(usb_native_test
        ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp/usb_native.cpp
        usb_native_test.cpp
)

target_include_directories(usb_native_test PRIVATE
        # android_log_stub comes first so `#include <android/log.h>` resolves to the stub
        # instead of failing outright (that header doesn't exist off-device).
        ${CMAKE_CURRENT_SOURCE_DIR}/android_log_stub
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${JNI_INCLUDE_DIR}
        ${JNI_INCLUDE_DIR}/linux
)

target_link_libraries(usb_native_test PRIVATE ${GTEST_LIBRARIES} pthread)
target_compile_options(usb_native_test PRIVATE ${GTEST_CFLAGS_OTHER} -Wall -Wextra)

# Host equivalent of the on-device arm64 MTE flag in ../src/main/cpp/CMakeLists.txt -- same
# intent (catch heap use-after-free/buffer-overflow in this file's manual malloc/free and raw
# URB buffer handling), no special hardware needed on host.
option(ENABLE_SANITIZERS "Build with AddressSanitizer/UndefinedBehaviorSanitizer" ON)
if(ENABLE_SANITIZERS)
    target_compile_options(usb_native_test PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(usb_native_test PRIVATE -fsanitize=address,undefined)
endif()

enable_testing()
add_test(NAME usb_native_test COMMAND usb_native_test)
