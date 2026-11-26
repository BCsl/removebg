package dev.eren.removebg

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Tries an installed ML Kit subject model, falling back to the bundled NCNN model. */
class AutoRemover(context: Context) : Remover<Bitmap>, Closeable {

    enum class MlKitStatus {
        READY,
        MODEL_NOT_READY,
        PLAY_SERVICES_UNAVAILABLE,
        CHECK_FAILED
    }

    private val appContext = context.applicationContext
    private val ncnn = lazy { NcnnRemover(appContext) }
    private val installRequested = AtomicBoolean(false)
    private var closed = false

    private val subject = lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
    }

    override fun clearBackground(image: Bitmap): Flow<Bitmap?> = clearBackground(image, preferMlKit = true)

    /** Set [preferMlKit] to false for offline NCNN-only processing. */
    fun clearBackground(image: Bitmap, preferMlKit: Boolean): Flow<Bitmap?> = flow {
        if (preferMlKit) {
            val foreground = attempt("ML Kit background removal") { removeWithSubject(image) }
            if (foreground != null) {
                Log.d(TAG, "Processed image with ML Kit")
                emit(foreground)
                return@flow
            }
        }

        currentCoroutineContext().ensureActive()
        val fallback = attempt("NCNN background removal") {
            val remover = withContext(Dispatchers.IO) { getNcnn() }
            remover?.clearBackground(image)?.first()
        }
        if (fallback != null) {
            Log.d(TAG, if (preferMlKit) "Processed image with NCNN fallback" else "Processed image with NCNN")
        }
        emit(fallback)
    }.flowOn(Dispatchers.Default)

    override fun getMaskedImage(input: Bitmap, mask: Bitmap): Bitmap =
        applyBitmapMask(input, mask)

    /** Checks whether ML Kit can be used without requesting a model download. */
    suspend fun getMlKitStatus(): MlKitStatus = withContext(Dispatchers.Default) {
        attempt("ML Kit readiness check") { checkMlKitStatus(requestInstall = false) }
            ?: MlKitStatus.CHECK_FAILED
    }

    private suspend fun removeWithSubject(image: Bitmap): Bitmap? {
        if (checkMlKitStatus(requestInstall = true) != MlKitStatus.READY) return null

        val segmenter = getSubject() ?: return null
        val mask = segmenter.process(InputImage.fromBitmap(image, 0)).await().foregroundConfidenceMask
        return mask?.let { image.applySoftForeground(it) }
    }

    private suspend fun checkMlKitStatus(requestInstall: Boolean): MlKitStatus {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) return MlKitStatus.PLAY_SERVICES_UNAVAILABLE

        val segmenter = getSubject() ?: return MlKitStatus.CHECK_FAILED
        val modules = ModuleInstall.getClient(appContext)
        val modelReady = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
            modules.areModulesAvailable(segmenter).await().areModulesAvailable()
        } ?: return MlKitStatus.CHECK_FAILED
        if (modelReady) return MlKitStatus.READY

        if (requestInstall && installRequested.compareAndSet(false, true)) {
            modules.deferredInstall(segmenter).addOnCompleteListener { task ->
                if (!task.isSuccessful) installRequested.set(false)
            }
        }
        return MlKitStatus.MODEL_NOT_READY
    }

    @Synchronized
    private fun getSubject(): SubjectSegmenter? = if (closed) null else subject.value

    @Synchronized
    private fun getNcnn(): NcnnRemover? = if (closed) null else ncnn.value

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (subject.isInitialized()) subject.value.close()
        } finally {
            if (ncnn.isInitialized()) ncnn.value.destroy()
        }
    }

    private suspend fun <T> attempt(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "$operation failed", exception)
        null
    }

    private suspend fun Bitmap.applySoftForeground(mask: FloatBuffer): Bitmap? {
        require(mask.limit().toLong() == width.toLong() * height) { "Unexpected ML Kit mask size" }
        val coroutineContext = currentCoroutineContext()
        val output = copy(Bitmap.Config.ARGB_8888, true) ?: return null
        var keepOutput = false
        try {
            output.setHasAlpha(true)
            val row = IntArray(width)
            var hasForeground = false
            for (y in 0 until height) {
                coroutineContext.ensureActive()
                val offset = y * width
                output.getPixels(row, 0, width, 0, y, width, 1)
                for (x in row.indices) {
                    val pixel = row[x]
                    val position = ((mask.get(offset + x) - EDGE_LOW_CONFIDENCE) /
                        (EDGE_HIGH_CONFIDENCE - EDGE_LOW_CONFIDENCE)).coerceIn(0f, 1f)
                    val opacity = position * position * (3f - 2f * position)
                    val alpha = ((pixel ushr 24) * opacity).roundToInt()
                    row[x] = (pixel and 0x00FFFFFF) or (alpha shl 24)
                    if (alpha != 0) hasForeground = true
                }
                output.setPixels(row, 0, width, 0, y, width, 1)
            }
            if (!hasForeground) return null
            keepOutput = true
            return output
        } finally {
            if (!keepOutput) output.recycle()
        }
    }

    private companion object {
        const val TAG = "AutoRemover"
        const val MODEL_CHECK_TIMEOUT_MS = 1_000L
        const val EDGE_LOW_CONFIDENCE = 0.35f
        const val EDGE_HIGH_CONFIDENCE = 0.65f
    }
}
