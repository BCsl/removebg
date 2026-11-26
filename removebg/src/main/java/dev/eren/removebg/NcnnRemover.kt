package dev.eren.removebg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.eren.removebg.common.ModelTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.lang.ref.WeakReference
import kotlin.math.pow

/**
 * NCNN-based background remover
 * Supports 16KB page size devices (Android 15+)
 */
class NcnnRemover(
    context: Context,
    maskStrength: Float = DEFAULT_MASK_STRENGTH
) : Remover<Bitmap> {

    private val appContext = context.applicationContext
    private val assetManager = appContext.assets
    private var isLoaded = false
    private var maskStrength = maskStrength.coerceIn(MIN_MASK_STRENGTH, MAX_MASK_STRENGTH)

    init {
        synchronized(nativeModelLock) { loadModel() }
    }

    private fun isNativeOwner(): Boolean = nativeOwner?.get() === this

    private fun loadModel() {
        try {
            val modelType = resolveNcnnModel(appContext)
            val paramPath = modelType.paramFileName
            val binPath = modelType.binFileName

            nativeOwner = null
            isLoaded = loadModelNative(assetManager, paramPath, binPath)

            if (isLoaded) {
                nativeOwner = WeakReference(this)
                android.util.Log.d(TAG, "NCNN model loaded successfully: ${modelType.name}")
            } else {
                android.util.Log.e(TAG, "NCNN model load failed: ${modelType.name}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading model: ${e.message}")
            isLoaded = false
        }
    }

    private fun resolveNcnnModel(context: Context): ModelTypes {
        val candidates = listOf(ModelTypes.U2NETP, ModelTypes.U2NET)
        return candidates.firstOrNull { model ->
            assetExists(context, model.paramFileName) && assetExists(context, model.binFileName)
        } ?: throw IllegalStateException(
            "No NCNN model assets found. Expected one of: ${
                candidates.joinToString { "${it.paramFileName} + ${it.binFileName}" }
            }"
        )
    }

    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                input.read()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun clearBackground(image: Bitmap): Flow<Bitmap?> = flow {
        val mutableImage = image.copy(Bitmap.Config.ARGB_8888, true)
        emit(removeBackground(mutableImage))
    }.flowOn(Dispatchers.IO)

    override fun getMaskedImage(input: Bitmap, mask: Bitmap): Bitmap = applyBitmapMask(input, mask)

    private fun removeBackground(input: Bitmap): Bitmap? = synchronized(nativeModelLock) {
        // The JNI bridge owns one network across all remover instances.
        if (isLoaded && !isNativeOwner()) loadModel()
        if (!isLoaded) {
            android.util.Log.e(TAG, "Model not loaded")
            return null
        }

        val width = input.width
        val height = input.height

        // 调用 JNI 进行推理
        val maskBitmap = inference(input) ?: run {
            android.util.Log.e(TAG, "Inference failed")
            return null
        }

        val adjustedMask = adjustMaskStrength(maskBitmap)

        // 缩放 mask 到原图尺寸
        val scaledMask = Bitmap.createScaledBitmap(adjustedMask, width, height, true)

        return getMaskedImage(input, scaledMask)
    }

    private fun adjustMaskStrength(mask: Bitmap): Bitmap {
        val adjustedMask = if (mask.isMutable) {
            mask
        } else {
            mask.copy(Bitmap.Config.ARGB_8888, true)
        }

        val width = adjustedMask.width
        val height = adjustedMask.height
        val pixels = IntArray(width * height)
        adjustedMask.getPixels(pixels, 0, width, 0, 0, width, height)

        var minAlpha = 255
        var maxAlpha = 0
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            if (alpha < minAlpha) minAlpha = alpha
            if (alpha > maxAlpha) maxAlpha = alpha
        }

        val alphaRange = (maxAlpha - minAlpha).coerceAtLeast(1)
        val normalizedStrength = ((maskStrength - MIN_MASK_STRENGTH) /
            (MAX_MASK_STRENGTH - MIN_MASK_STRENGTH)).coerceIn(0f, 1f)
        val suppressionThreshold = 0.08f + normalizedStrength * 0.52f
        val foregroundGamma = 1.0f + normalizedStrength * 1.2f

        for (index in pixels.indices) {
            val alpha = pixels[index] ushr 24
            val normalized = (alpha - minAlpha) / alphaRange.toFloat()
            val lifted = ((normalized - suppressionThreshold) /
                (1f - suppressionThreshold)).coerceIn(0f, 1f)
            val adjusted = lifted.pow(foregroundGamma)
            val adjustedAlpha = (adjusted * 255f).toInt().coerceIn(0, 255)
            pixels[index] = adjustedAlpha shl 24
        }

        adjustedMask.setPixels(pixels, 0, width, 0, 0, width, height)
        return adjustedMask
    }

    /**
     * 检查模型是否加载成功
     */
    fun isModelLoaded(): Boolean = synchronized(nativeModelLock) { isLoaded }

    fun getMaskStrength(): Float = maskStrength

    fun setMaskStrength(strength: Float) {
        maskStrength = strength.coerceIn(MIN_MASK_STRENGTH, MAX_MASK_STRENGTH)
    }

    /**
     * 释放模型资源
     */
    fun destroy() {
        synchronized(nativeModelLock) {
            if (isNativeOwner()) {
                release()
                nativeOwner = null
            }
            isLoaded = false
        }
    }

    // JNI 原生方法
    private external fun loadModelNative(
        assetManager: android.content.res.AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    private external fun inference(bitmap: Bitmap): Bitmap?

    private external fun release()

    companion object {
        private const val TAG = "NcnnRemover"
        private val nativeModelLock = Any()
        private var nativeOwner: WeakReference<NcnnRemover>? = null
        const val DEFAULT_MASK_STRENGTH = 1.35f
        const val MIN_MASK_STRENGTH = 0.5f
        const val MAX_MASK_STRENGTH = 3.0f

        init {
            System.loadLibrary("ncnn_removebg")
        }
    }
}

internal fun applyBitmapMask(input: Bitmap, mask: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    canvas.drawBitmap(input, 0f, 0f, null)
    canvas.drawBitmap(mask, 0f, 0f, maskPaint)
    return result
}
