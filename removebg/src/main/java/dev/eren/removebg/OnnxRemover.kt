package dev.eren.removebg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.eren.removebg.common.ModelTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.FloatBuffer

/**
 * ONNX Runtime-based background remover
 * Supports 16KB page size devices (Android 15+)
 */
class OnnxRemover(context: Context) : Remover<Bitmap> {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val maskPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targetSize = 320

    init {
        maskPaint.isAntiAlias = true
        maskPaint.style = Paint.Style.FILL
        maskPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))

        loadModel(context)
    }

    private fun loadModel(context: Context) {
        try {
            val modelBytes = context.assets.open(ModelTypes.U2NETP.onnxFileName).use {
                it.readBytes()
            }

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4)
            sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)

            ortSession = ortEnv.createSession(modelBytes, sessionOptions)

            android.util.Log.d(TAG, "ONNX model loaded successfully")
            android.util.Log.d(TAG, "Input names: ${ortSession?.inputNames}")
            android.util.Log.d(TAG, "Output names: ${ortSession?.outputNames}")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading ONNX model: ${e.message}")
        }
    }

    override fun clearBackground(image: Bitmap): Flow<Bitmap?> = flow {
        val mutableImage = image.copy(Bitmap.Config.ARGB_8888, true)
        emit(removeBackground(mutableImage))
    }.flowOn(Dispatchers.IO)

    override fun getMaskedImage(input: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val mCanvas = Canvas(result)

        mCanvas.drawBitmap(input, 0f, 0f, null)
        mCanvas.drawBitmap(mask, 0f, 0f, maskPaint)
        return result
    }

    private fun removeBackground(input: Bitmap): Bitmap? {
        val session = ortSession ?: run {
            android.util.Log.e(TAG, "ONNX session not initialized")
            return null
        }

        val width = input.width
        val height = input.height

        // 准备输入张量
        val inputTensor = preprocessBitmap(input)

        // 运行推理
        val inputName = session.inputNames.iterator().next()
        val output = session.run(mapOf(inputName to inputTensor))

        // 获取输出
        val outputTensor = output[0] as OnnxTensor
        val outputArray = outputTensor.floatBuffer

        // 后处理生成 mask
        val maskBitmap = postprocessOutput(outputArray, targetSize, targetSize)

        // 缩放 mask 到原图尺寸
        val scaledMask = Bitmap.createScaledBitmap(maskBitmap, width, height, true)

        // 释放资源
        inputTensor.close()
        output.close()

        return getMaskedImage(input, scaledMask)
    }

    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        // 缩放到目标尺寸
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)

        // 转换为 float 数组并归一化
        val floatBuffer = FloatBuffer.allocate(1 * 3 * targetSize * targetSize)
        val pixels = IntArray(targetSize * targetSize)
        scaledBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        // ImageNet 归一化参数
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        // 按照 CHW 格式填充 (Channel, Height, Width)
        for (c in 0 until 3) {
            for (i in 0 until targetSize) {
                for (j in 0 until targetSize) {
                    val pixel = pixels[i * targetSize + j]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f  // R
                        1 -> (pixel shr 8 and 0xFF) / 255.0f   // G
                        else -> (pixel and 0xFF) / 255.0f      // B
                    }
                    val normalized = (channelValue - mean[c]) / std[c]
                    floatBuffer.put(normalized)
                }
            }
        }

        floatBuffer.rewind()

        // 创建 ONNX 张量
        val shape = longArrayOf(1, 3, targetSize.toLong(), targetSize.toLong())
        return OnnxTensor.createTensor(ortEnv, floatBuffer, shape)
    }

    private fun postprocessOutput(buffer: FloatBuffer, width: Int, height: Int): Bitmap {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 找到最大最小值用于归一化
        buffer.rewind()
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE

        for (i in 0 until width * height) {
            val v = buffer.get(i)
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = if (maxVal - minVal > 0.0001f) maxVal - minVal else 1.0f

        // 转换为 Bitmap
        for (i in 0 until width) {
            for (j in 0 until height) {
                val value = buffer.get(i * height + j)
                val normalized = ((value - minVal) / range * 255f).coerceIn(0f, 255f).toInt()
                // 存储为 alpha 通道
                maskBitmap.setPixel(j, i, normalized shl 24)
            }
        }

        return maskBitmap
    }

    /**
     * 检查模型是否加载成功
     */
    fun isModelLoaded(): Boolean = ortSession != null

    /**
     * 释放资源
     */
    fun destroy() {
        ortSession?.close()
        ortSession = null
    }

    companion object {
        private const val TAG = "OnnxRemover"
    }
}
