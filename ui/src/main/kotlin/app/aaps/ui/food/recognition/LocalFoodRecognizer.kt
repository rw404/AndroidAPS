package app.aaps.ui.food.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.UPCEReader
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device package-label OCR (Russian and English) and GTIN barcode decoding.
 * Images remain in memory; only bundled traineddata are copied to noBackupFilesDir.
 * This class does not recognise meals, call a remote service, or deliver treatment.
 */
@Singleton
class LocalFoodRecognizer @Inject constructor(context: Context) {

    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "AapsFoodOcr").apply { isDaemon = true } }

    suspend fun recognize(uri: Uri): LocalFoodRecognitionResult = recognizeImage { FoodRecognitionImageLoader.decode(context, uri) }

    /** The caller retains ownership of the supplied bitmap. */
    suspend fun recognize(bitmap: Bitmap): LocalFoodRecognitionResult = recognizeImage { FoodRecognitionImageLoader.copy(bitmap) }

    private suspend fun recognizeImage(load: () -> Bitmap): LocalFoodRecognitionResult {
        val request = Request()
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { request.cancel() }
                val future = worker.submit {
                    if (request.cancelled.get()) return@submit
                    var bitmap: Bitmap? = null
                    val result = try {
                        val loaded = load()
                        bitmap = loaded
                        if (request.cancelled.get()) return@submit
                        recognizeLoaded(loaded, request)
                    } catch (_: Exception) {
                        result(request.barcode.get(), "", LocalRecognitionFailure.IMAGE_UNREADABLE)
                    } finally {
                        bitmap?.recycle()
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                request.future.set(future)
                if (request.cancelled.get()) future.cancel(false)
            }
        } ?: result(request.barcode.get(), "", LocalRecognitionFailure.OCR_TIMED_OUT)
    }

    private fun recognizeLoaded(bitmap: Bitmap, request: Request): LocalFoodRecognitionResult {
        request.barcode.set(decodeBarcode(bitmap, request))
        if (request.cancelled.get()) return result(request.barcode.get(), "")
        val dataPath = try {
            installModels()
        } catch (_: Exception) {
            return result(request.barcode.get(), "", LocalRecognitionFailure.MODEL_UNAVAILABLE)
        }
        if (request.cancelled.get()) return result(request.barcode.get(), "")
        val api = try {
            TessBaseAPI()
        } catch (_: LinkageError) {
            return result(request.barcode.get(), "", LocalRecognitionFailure.OCR_UNAVAILABLE)
        }
        return try {
            if (!api.init(dataPath.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                return result(request.barcode.get(), "", LocalRecognitionFailure.MODEL_UNAVAILABLE)
            }
            synchronized(request.nativeLock) { request.api = api }
            if (request.cancelled.get()) return result(request.barcode.get(), "")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(bitmap)
            // getUTF8Text alone cannot be stopped in this library. HOCR runs the
            // interruptible recognition first; the UTF8 getter then reads its cache.
            api.getHOCRText(0)
            if (request.cancelled.get()) return result(request.barcode.get(), "")
            val rawText = api.getUTF8Text().orEmpty().trim()
            result(request.barcode.get(), rawText)
        } catch (_: LinkageError) {
            result(request.barcode.get(), "", LocalRecognitionFailure.OCR_UNAVAILABLE)
        } catch (_: Exception) {
            result(request.barcode.get(), "", LocalRecognitionFailure.OCR_FAILED)
        } finally {
            synchronized(request.nativeLock) {
                request.api = null
                api.recycle()
            }
        }
    }

    private fun decodeBarcode(bitmap: Bitmap, request: Request): String? {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E)
        )
        for (degrees in listOf(0, 90, 180, 270)) {
            if (request.cancelled.get()) return null
            val rotated = if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            try {
                val pixels = IntArray(rotated.width * rotated.height)
                rotated.getPixels(pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
                val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(rotated.width, rotated.height, pixels)))
                val reader = MultiFormatReader()
                try {
                    val barcode = reader.decode(image, hints)
                    return if (barcode.barcodeFormat == BarcodeFormat.UPC_E) UPCEReader.convertUPCEtoUPCA(barcode.text) else barcode.text
                } catch (_: NotFoundException) {
                    // A barcode is optional. Keep looking locally, then continue OCR.
                } finally {
                    reader.reset()
                }
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        }
        return null
    }

    private fun installModels(): File {
        val root = File(context.noBackupFilesDir, "food/ocr")
        val tessdata = File(root, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs())
        models.forEach { model ->
            val target = File(tessdata, model.name)
            if (target.isFile && target.length() == model.bytes && sha256(target) == model.sha256) return@forEach
            val pending = File(tessdata, model.name + ".pending")
            try {
                context.assets.open("food/ocr/" + model.name).use { source -> pending.outputStream().use { source.copyTo(it) } }
                check(pending.length() == model.bytes && sha256(pending) == model.sha256) { "OCR model integrity check failed" }
                check(!target.exists() || target.delete())
                check(pending.renameTo(target))
            } finally {
                pending.delete()
            }
        }
        return root
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun result(barcode: String?, rawText: String, failure: LocalRecognitionFailure? = null) =
        LocalFoodRecognitionResult(barcode, rawText.take(NutritionLabelParser.MAX_TEXT_LENGTH), NutritionLabelParser.parse(rawText), failure?.let(::setOf) ?: emptySet())

    private class Request {
        val cancelled = AtomicBoolean(false)
        val future = AtomicReference<Future<*>?>()
        val barcode = AtomicReference<String?>()
        val nativeLock = Any()
        var api: TessBaseAPI? = null

        fun cancel() {
            cancelled.set(true)
            future.get()?.cancel(false)
            synchronized(nativeLock) { api?.stop() }
        }
    }

    private data class Model(val name: String, val bytes: Long, val sha256: String)

    private companion object {
        val models = listOf(
            Model("eng.traineddata", 4_113_088L, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
            Model("rus.traineddata", 3_861_738L, "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225")
        )
    }
}
