#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include "net.h"

#define TAG "NCNN_RemoveBG"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net* g_net = nullptr;
static int g_target_size = 320;

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_eren_removebg_NcnnRemover_loadModel(
    JNIEnv* env, jobject thiz,
    jobject assetManager, jstring paramPath, jstring binPath) {

    // 释放旧模型
    if (g_net != nullptr) {
        delete g_net;
        g_net = nullptr;
    }

    g_net = new ncnn::Net();

    // 设置选项
    g_net->opt.use_vulkan_compute = false;
    g_net->opt.lightmode = true;

    // 获取 AssetManager
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("Failed to get AssetManager");
        return JNI_FALSE;
    }

    // 加载模型
    const char* param = env->GetStringUTFChars(paramPath, 0);
    const char* bin = env->GetStringUTFChars(binPath, 0);

    int ret_param = g_net->load_param(mgr, param);
    int ret_bin = g_net->load_model(mgr, bin);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);

    if (ret_param != 0 || ret_bin != 0) {
        LOGE("Model load failed: param=%d, bin=%d", ret_param, ret_bin);
        delete g_net;
        g_net = nullptr;
        return JNI_FALSE;
    }

    LOGD("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_eren_removebg_NcnnRemover_inference(
    JNIEnv* env, jobject thiz, jobject bitmap) {

    if (g_net == nullptr) {
        LOGE("Model not loaded");
        return nullptr;
    }

    // 获取 Bitmap 信息
    AndroidBitmapInfo info;
    int ret = AndroidBitmap_getInfo(env, bitmap, &info);
    if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed: %d", ret);
        return nullptr;
    }

    // 检查格式
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format is not RGBA_8888");
        return nullptr;
    }

    int src_width = info.width;
    int src_height = info.height;

    // 锁像素
    void* pixels;
    ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed: %d", ret);
        return nullptr;
    }

    // 转换 Bitmap 到 ncnn::Mat
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        (const unsigned char*)pixels,
        ncnn::Mat::PIXEL_RGBA2RGB,
        src_width,
        src_height,
        g_target_size,
        g_target_size
    );

    AndroidBitmap_unlockPixels(env, bitmap);

    // 归一化 (ImageNet mean/std)
    const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm_vals[3] = {1.0f / 0.229f / 255.f, 1.0f / 0.224f / 255.f, 1.0f / 0.225f / 255.f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    // 创建 extractor
    ncnn::Extractor ex = g_net->create_extractor();
    ex.set_light_mode(true);
    ex.set_num_threads(4);

    // 设置输入 (input name depends on model)
    ex.input("input", in);

    // 推理
    ncnn::Mat out;
    int ret_extract = ex.extract("output", out);
    if (ret_extract != 0) {
        LOGE("Extract failed: %d", ret_extract);
        return nullptr;
    }

    // 创建输出 Bitmap（mask）
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (bitmapClass == nullptr) {
        LOGE("Failed to find Bitmap class");
        return nullptr;
    }

    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (createBitmapMethod == nullptr) {
        LOGE("Failed to find createBitmap method");
        return nullptr;
    }

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (configClass == nullptr) {
        LOGE("Failed to find Bitmap.Config class");
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (argb8888Field == nullptr) {
        LOGE("Failed to find ARGB_8888 field");
        return nullptr;
    }

    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888Field);

    jobject maskBitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmapMethod,
        g_target_size, g_target_size, argb8888Config
    );
    if (maskBitmap == nullptr) {
        LOGE("Failed to create mask bitmap");
        return nullptr;
    }

    // 锁定 mask bitmap 像素
    void* maskPixels;
    ret = AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels);
    if (ret != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels for mask failed: %d", ret);
        return nullptr;
    }

    // 将输出转换为灰度 Bitmap
    float* outData = (float*)out.data;
    unsigned char* rgbaData = (unsigned char*)maskPixels;

    // 找到输出数据的范围进行归一化
    float minVal = outData[0];
    float maxVal = outData[0];
    for (int i = 1; i < g_target_size * g_target_size; i++) {
        if (outData[i] < minVal) minVal = outData[i];
        if (outData[i] > maxVal) maxVal = outData[i];
    }

    float range = maxVal - minVal;
    if (range < 0.0001f) range = 1.0f;

    for (int i = 0; i < g_target_size * g_target_size; i++) {
        float val = outData[i];
        // 归一化到 0-255
        val = (val - minVal) / range * 255.f;
        if (val < 0) val = 0;
        if (val > 255) val = 255;

        unsigned char gray = (unsigned char)val;
        rgbaData[i * 4] = gray;      // R
        rgbaData[i * 4 + 1] = gray;  // G
        rgbaData[i * 4 + 2] = gray;  // B
        rgbaData[i * 4 + 3] = 255;   // A
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);

    return maskBitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_eren_removebg_NcnnRemover_release(JNIEnv* env, jobject thiz) {
    if (g_net != nullptr) {
        delete g_net;
        g_net = nullptr;
    }
    LOGD("Model released");
}
