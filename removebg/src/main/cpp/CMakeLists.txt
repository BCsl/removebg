cmake_minimum_required(VERSION 3.22.1)

project("ncnn_removebg")

# NCNN SDK 路径
set(NCNN_DIR ${CMAKE_SOURCE_DIR}/ncnn)

# 添加 NCNN 库
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_DIR}/lib/${ANDROID_ABI}/libncnn.a
)

# 创建 JNI 库
add_library(ncnn_removebg SHARED ncnn_jni.cpp)

# 包含头文件
target_include_directories(ncnn_removebg PRIVATE
    ${NCNN_DIR}/include
)

# 链接库
target_link_libraries(ncnn_removebg
    ncnn
    android
    jnigraphics
    log
    z
)
