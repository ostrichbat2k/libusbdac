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
