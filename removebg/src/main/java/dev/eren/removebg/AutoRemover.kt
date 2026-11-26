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
import java.util.concurrent.atomic.AtomicBoolean

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
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
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
        val bitmap = segmenter.process(InputImage.fromBitmap(image, 0)).await().foregroundBitmap
        return bitmap?.takeIf { it.hasVisiblePixels() }
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

    private fun Bitmap.hasVisiblePixels(): Boolean {
        if (!hasAlpha()) return true
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            if (row.any { it ushr 24 != 0 }) return true
        }
        return false
    }

    private companion object {
        const val TAG = "AutoRemover"
        const val MODEL_CHECK_TIMEOUT_MS = 1_000L
    }
}
