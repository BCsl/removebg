cmake_minimum_required(VERSION 3.22.1)

project("ncnn_removebg")

# NCNN SDK 路径
set(NCNN_DIR ${CMAKE_SOURCE_DIR}/ncnn)

# 添加 NCNN 库
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_DIR}/lib/${ANDROID_ABI}/libncnn.a
)

# 某些预编译 ncnn 静态库是用 OpenMP 构建的，会额外依赖 NDK 自带的 libomp。
# 如果不补上这个运行时，链接阶段会出现 __kmpc_global_thread_num 等未定义符号。
set(NCNN_OPENMP_ARCH "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NCNN_OPENMP_ARCH "aarch64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(NCNN_OPENMP_ARCH "arm")
elseif(ANDROID_ABI STREQUAL "x86")
    set(NCNN_OPENMP_ARCH "i386")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(NCNN_OPENMP_ARCH "x86_64")
endif()

if(NCNN_OPENMP_ARCH AND DEFINED CMAKE_ANDROID_NDK)
    file(GLOB NCNN_CLANG_RUNTIME_DIRS
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux"
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib64/clang/*/lib/linux"
    )
    if(NCNN_CLANG_RUNTIME_DIRS)
        list(GET NCNN_CLANG_RUNTIME_DIRS 0 NCNN_CLANG_RUNTIME_DIR)
        set(NCNN_LIBOMP_PATH
            ${NCNN_CLANG_RUNTIME_DIR}/${NCNN_OPENMP_ARCH}/libomp.a
        )

        if(EXISTS ${NCNN_LIBOMP_PATH})
            add_library(ncnn_openmp STATIC IMPORTED)
            set_target_properties(ncnn_openmp PROPERTIES
                IMPORTED_LOCATION ${NCNN_LIBOMP_PATH}
            )
        endif()
    endif()
endif()

# 创建 JNI 库
add_library(ncnn_removebg SHARED ncnn_jni.cpp)

# 包含头文件
target_include_directories(ncnn_removebg PRIVATE
    ${NCNN_DIR}/include
)

# 链接库 - 优先静态链接；如果 ncnn 是 OpenMP 版本则补上 libomp
target_link_libraries(ncnn_removebg
    ncnn
    $<$<TARGET_EXISTS:ncnn_openmp>:ncnn_openmp>
    c++_static
    android
    jnigraphics
    log
    z
)
