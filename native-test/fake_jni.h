// A minimal fake JNIEnv for host-testing usb_native.cpp's submitIso(), which is the only
// function in that file that calls back into JNIEnv (GetArrayLength/GetByteArrayRegion/
// GetIntArrayElements/ReleaseIntArrayElements). There is no real JVM in this build -- JNIEnv is
// just a pointer to a function-pointer table (JNINativeInterface_) per the JNI spec, so a real
// JNIEnv* can be faked by building that table ourselves and pointing a handful of its named
// slots (zero-initializing the rest) at the functions below. jbyteArray/jintArray are likewise
// just opaque object pointers in the JNI type system -- here they're reinterpret_cast<>'d
// FakeJavaArray* instances we own, never real JVM objects.
#pragma once

#include <jni.h>
#include <cstring>
#include <vector>

struct FakeJavaArray {
    std::vector<jbyte> bytes;
    std::vector<jint> ints;
    bool isByteArray;

    static FakeJavaArray OfBytes(std::vector<jbyte> b) {
        FakeJavaArray a;
        a.bytes = std::move(b);
        a.isByteArray = true;
        return a;
    }
    static FakeJavaArray OfInts(std::vector<jint> i) {
        FakeJavaArray a;
        a.ints = std::move(i);
        a.isByteArray = false;
        return a;
    }
};

inline FakeJavaArray* AsFake(jarray a) { return reinterpret_cast<FakeJavaArray*>(a); }

inline jsize Fake_GetArrayLength(JNIEnv*, jarray array) {
    FakeJavaArray* a = AsFake(array);
    return (jsize) (a->isByteArray ? a->bytes.size() : a->ints.size());
}

inline void Fake_GetByteArrayRegion(JNIEnv*, jbyteArray array, jsize start, jsize len, jbyte* buf) {
    FakeJavaArray* a = AsFake(array);
    memcpy(buf, a->bytes.data() + start, (size_t) len);
}

inline jint* Fake_GetIntArrayElements(JNIEnv*, jintArray array, jboolean* isCopy) {
    FakeJavaArray* a = AsFake(array);
    if (isCopy) *isCopy = JNI_FALSE;
    return a->ints.data();
}

inline void Fake_ReleaseIntArrayElements(JNIEnv*, jintArray, jint*, jint) {
    // No-op: Fake_GetIntArrayElements handed back the vector's own backing storage directly
    // (isCopy = JNI_FALSE), so there's nothing to copy back or free here.
}

// Builds a JNIEnv* backed by a function table with only the entries usb_native.cpp actually
// calls populated -- every other slot stays null-initialized, since nothing here ever invokes
// them. Must outlive any call made through the returned pointer.
class FakeJni {
public:
    FakeJni() {
        memset(&functions_, 0, sizeof(functions_));
        functions_.GetArrayLength = Fake_GetArrayLength;
        functions_.GetByteArrayRegion = Fake_GetByteArrayRegion;
        functions_.GetIntArrayElements = Fake_GetIntArrayElements;
        functions_.ReleaseIntArrayElements = Fake_ReleaseIntArrayElements;
        env_.functions = &functions_;
    }

    JNIEnv* env() { return &env_; }

private:
    JNINativeInterface_ functions_{};
    JNIEnv_ env_{&functions_};
};
