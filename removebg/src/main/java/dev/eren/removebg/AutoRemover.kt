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
            val foreground = attempt("ML Kit") { removeWithSubject(image) }
            if (foreground != null) {
                Log.d(TAG, "Processed image with ML Kit")
                emit(foreground)
                return@flow
            }
        }

        currentCoroutineContext().ensureActive()
        val fallback = attempt("NCNN") {
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

    private suspend fun removeWithSubject(image: Bitmap): Bitmap? {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) return null

        val segmenter = getSubject() ?: return null
        val modules = ModuleInstall.getClient(appContext)
        val modelReady = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
            modules.areModulesAvailable(segmenter).await().areModulesAvailable()
        } ?: return null
        if (!modelReady) {
            if (installRequested.compareAndSet(false, true)) {
                modules.deferredInstall(segmenter).addOnCompleteListener { task ->
                    if (!task.isSuccessful) installRequested.set(false)
                }
            }
            return null
        }

        val bitmap = segmenter.process(InputImage.fromBitmap(image, 0)).await().foregroundBitmap
        return bitmap?.takeIf { it.hasVisiblePixels() }
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

    private suspend fun <T> attempt(engine: String, remove: suspend () -> T): T? = try {
        remove()
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "$engine background removal failed", exception)
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
