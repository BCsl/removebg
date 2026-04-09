package dev.eren.removebg

import android.content.Context

/**
 * 背景移除器工厂类
 * 用于创建不同实现方式的 Remover 实例
 *
 * 使用示例：
 * ```kotlin
 * // 使用 ONNX Runtime（推荐，支持 16KB page size）
 * val remover = RemoveBgFactory.create(context, RemoveBgFactory.Engine.ONNX)
 *
 * // 使用 PyTorch（不支持 16KB page size）
 * val remover = RemoveBgFactory.create(context, RemoveBgFactory.Engine.PYTORCH)
 * ```
 */
object RemoveBgFactory {

    /**
     * 推理引擎类型
     */
    enum class Engine {
        /**
         * ONNX Runtime 引擎
         * - 支持 16KB page size（Android 15+）
         * - Gradle 依赖引入，集成简单
         * - 性能优秀
         */
        ONNX,

        /**
         * PyTorch Lite 引擎
         * - 不支持 16KB page size
         * - PyTorch Mobile 已被官方弃用
         * - 仅用于兼容旧设备
         */
        PYTORCH,

        /**
         * NCNN 引擎
         * - 支持 16KB page size（需要正确编译）
         * - 需要手动集成 SDK
         * - 高性能，支持 Vulkan GPU 加速
         */
        NCNN
    }

    /**
     * 创建背景移除器实例
     *
     * @param context Android Context
     * @param engine 推理引擎类型，默认 ONNX
     * @return Remover 实例
     */
    fun create(context: Context, engine: Engine = Engine.ONNX): Remover<android.graphics.Bitmap> {
        return when (engine) {
            Engine.ONNX -> OnnxRemover(context)
            Engine.PYTORCH -> RemoveBg(context)
            Engine.NCNN -> NcnnRemover(context)
        }
    }

    /**
     * 检查指定引擎是否可用
     *
     * @param engine 推理引擎类型
     * @return 是否可用
     */
    fun isEngineAvailable(engine: Engine): Boolean {
        return try {
            when (engine) {
                Engine.ONNX -> true  // ONNX Runtime 通过 Gradle 引入，始终可用
                Engine.PYTORCH -> true  // PyTorch 通过 Gradle 引入，始终可用
                Engine.NCNN -> {
                    // NCNN 需要手动集成，检查 native 库是否加载
                    try {
                        System.loadLibrary("ncnn_removebg")
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取推荐的引擎类型
     *
     * @return 推荐的引擎（优先选择支持 16KB page size 的引擎）
     */
    fun getRecommendedEngine(): Engine {
        return when {
            isEngineAvailable(Engine.ONNX) -> Engine.ONNX
            isEngineAvailable(Engine.NCNN) -> Engine.NCNN
            else -> Engine.PYTORCH
        }
    }
}
