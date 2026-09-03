#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <string>

namespace {

void throwIllegalArgument(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

int clampByte(int value) {
    return std::clamp(value, 0, 255);
}

bool planeFits(
    jlong capacity,
    int offset,
    int stride,
    int row_count,
    int row_width
) {
    if (capacity < 0 || offset < 0 || stride < row_width || row_count <= 0 || row_width <= 0) {
        return false;
    }
    const jlong required = static_cast<jlong>(offset) +
        static_cast<jlong>(row_count - 1) * stride + row_width;
    return required <= capacity;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_glasses_webrtc_NativeI420BitmapConverter_nativeConvert(
    JNIEnv* env,
    jobject,
    jobject y_buffer,
    jint y_offset,
    jint y_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_stride,
    jint width,
    jint height,
    jint rotation,
    jobject bitmap
) {
    if (width <= 0 || height <= 0 ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
        throwIllegalArgument(env, "Invalid I420 dimensions or rotation");
        return;
    }

    const auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buffer));
    const auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buffer));
    const auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buffer));
    if (y == nullptr || u == nullptr || v == nullptr) {
        throwIllegalArgument(env, "I420 planes must use direct ByteBuffers");
        return;
    }

    const int chroma_width = (width + 1) / 2;
    const int chroma_height = (height + 1) / 2;
    if (!planeFits(env->GetDirectBufferCapacity(y_buffer), y_offset, y_stride, height, width) ||
        !planeFits(env->GetDirectBufferCapacity(u_buffer), u_offset, u_stride, chroma_height, chroma_width) ||
        !planeFits(env->GetDirectBufferCapacity(v_buffer), v_offset, v_stride, chroma_height, chroma_width)) {
        throwIllegalArgument(env, "I420 plane is smaller than its declared dimensions");
        return;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwIllegalArgument(env, "Destination must be an RGBA_8888 Bitmap");
        return;
    }

    const int output_width = rotation == 90 || rotation == 270 ? height : width;
    const int output_height = rotation == 90 || rotation == 270 ? width : height;
    if (info.width != static_cast<uint32_t>(output_width) ||
        info.height != static_cast<uint32_t>(output_height)) {
        throwIllegalArgument(env, "Destination Bitmap dimensions do not match rotation");
        return;
    }

    void* raw_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &raw_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        raw_pixels == nullptr) {
        throwIllegalArgument(env, "Could not lock destination Bitmap pixels");
        return;
    }

    auto* destination = static_cast<uint8_t*>(raw_pixels);
    y += y_offset;
    u += u_offset;
    v += v_offset;

    for (int source_y = 0; source_y < height; ++source_y) {
        const uint8_t* y_row = y + source_y * y_stride;
        const uint8_t* u_row = u + (source_y / 2) * u_stride;
        const uint8_t* v_row = v + (source_y / 2) * v_stride;
        for (int source_x = 0; source_x < width; ++source_x) {
            const int y_value = y_row[source_x];
            const int u_value = u_row[source_x / 2];
            const int v_value = v_row[source_x / 2];
            const int c = std::max(0, y_value - 16);
            const int d = u_value - 128;
            const int e = v_value - 128;

            int destination_x = source_x;
            int destination_y = source_y;
            switch (rotation) {
                case 90:
                    destination_x = height - 1 - source_y;
                    destination_y = source_x;
                    break;
                case 180:
                    destination_x = width - 1 - source_x;
                    destination_y = height - 1 - source_y;
                    break;
                case 270:
                    destination_x = source_y;
                    destination_y = width - 1 - source_x;
                    break;
                default:
                    break;
            }

            uint8_t* pixel = destination + destination_y * info.stride + destination_x * 4;
            pixel[0] = static_cast<uint8_t>(clampByte((298 * c + 409 * e + 128) >> 8));
            pixel[1] = static_cast<uint8_t>(clampByte((298 * c - 100 * d - 208 * e + 128) >> 8));
            pixel[2] = static_cast<uint8_t>(clampByte((298 * c + 516 * d + 128) >> 8));
            pixel[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}
