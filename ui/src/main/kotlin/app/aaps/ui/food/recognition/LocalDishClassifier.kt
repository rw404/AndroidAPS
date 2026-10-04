package app.aaps.ui.food.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** A possible Food-101 class. [score] is a softmax model score, not calibrated confidence. */
data class DishCandidate(val id: String, val displayName: String, val score: Float)

/**
 * CPU-only, offline suggestions from the bundled MobileNet V2 Food-101 checkpoint.
 * These broad dish names never imply ingredients, portion mass, carbohydrates or insulin.
 * Food-101 has limited coverage; even a high score can be wrong for an unfamiliar dish.
 */
class LocalDishClassifier @Inject constructor(context: Context) {

    private val appContext = context.applicationContext
    private val inferenceMutex = Mutex()

    /** Decodes local picker/camera images with EXIF orientation and bounded pixel memory. */
    suspend fun classify(uri: Uri): List<DishCandidate> = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" || uri.scheme == "file") { "A local image is required" }
        currentCoroutineContext().ensureActive()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val ratio = min(1.0, MAX_DECODE_EDGE.toDouble() / maxOf(size.width, size.height))
            decoder.setTargetSize(maxOf(1, (size.width * ratio).roundToInt()), maxOf(1, (size.height * ratio).roundToInt()))
        }
        try {
            classify(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Keeps the caller's bitmap intact; one inference uses only a 224×224 pixel buffer. */
    suspend fun classify(bitmap: Bitmap): List<DishCandidate> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            currentCoroutineContext().ensureActive()
            require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "The image is unavailable" }
            val candidates = infer(bitmap)
            currentCoroutineContext().ensureActive()
            candidates
        }
    }

    private fun infer(bitmap: Bitmap): List<DishCandidate> {
        val labels = appContext.assets.open("$ASSET_DIRECTORY/labels.txt").bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() }
        }
        require(labels.size == CLASS_COUNT && labels == labels.sorted() && labels.distinct().size == CLASS_COUNT) { "Invalid Food-101 labels" }
        val model = readModel()
        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(model, options).use { session ->
                val input = session.inputInfo[INPUT_NAME]?.info as? TensorInfo
                val output = session.outputInfo[OUTPUT_NAME]?.info as? TensorInfo
                require(input != null && input.type == OnnxJavaType.FLOAT && input.shape.contentEquals(INPUT_SHAPE)) { "Unexpected classifier input" }
                require(output != null && output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1, CLASS_COUNT.toLong()))) { "Unexpected classifier output" }
                OnnxTensor.createTensor(environment, preprocess(bitmap), INPUT_SHAPE).use { tensor ->
                    session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                        val outputTensor = result[0] as? OnnxTensor ?: error("No classifier output")
                        val logitsBuffer = outputTensor.floatBuffer
                        require(logitsBuffer.remaining() == CLASS_COUNT) { "Unexpected class count" }
                        val logits = FloatArray(CLASS_COUNT)
                        logitsBuffer.get(logits)
                        require(logits.all { it.isFinite() }) { "Non-finite classifier output" }
                        val maximum = logits.maxOrNull() ?: error("No classifier scores")
                        val probabilities = logits.map { exp((it - maximum).toDouble()) }
                        val sum = probabilities.sum()
                        require(sum.isFinite() && sum > 0.0) { "Invalid classifier scores" }
                        return logits.indices.sortedByDescending { logits[it] }.take(3).map { index ->
                            val id = labels[index]
                            DishCandidate(id, displayName(id), (probabilities[index] / sum).toFloat())
                        }
                    }
                }
            }
        }
    }

    private fun readModel(): ByteArray {
        val bytes = appContext.assets.open("$ASSET_DIRECTORY/$MODEL_FILE").use { input ->
            val output = ByteArrayOutputStream(MODEL_BYTES)
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                require(total <= MODEL_BYTES) { "Oversized classifier model" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size == MODEL_BYTES) { "Incomplete classifier model" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(checksum == MODEL_SHA256) { "Classifier model checksum mismatch" }
        return bytes
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val resized = Bitmap.createBitmap(INPUT_EDGE, INPUT_EDGE, Bitmap.Config.ARGB_8888)
        try {
            // Geometry equivalent to validation Resize(short edge=256), CenterCrop(224).
            val side = min(bitmap.width, bitmap.height) * (INPUT_EDGE / 256f)
            val left = (bitmap.width - side) / 2f
            val top = (bitmap.height - side) / 2f
            val transformation = Matrix().apply {
                setRectToRect(
                    RectF(left, top, left + side, top + side),
                    RectF(0f, 0f, INPUT_EDGE.toFloat(), INPUT_EDGE.toFloat()),
                    Matrix.ScaleToFit.FILL
                )
            }
            Canvas(resized).drawBitmap(bitmap, transformation, Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(INPUT_EDGE * INPUT_EDGE)
            resized.getPixels(pixels, 0, INPUT_EDGE, 0, 0, INPUT_EDGE, INPUT_EDGE)
            val values = FloatArray(pixels.size * 3)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                values[index] = (((pixel shr 16) and 255) / 255f - 0.485f) / 0.229f
                values[index + pixels.size] = (((pixel shr 8) and 255) / 255f - 0.456f) / 0.224f
                values[index + pixels.size * 2] = ((pixel and 255) / 255f - 0.406f) / 0.225f
            }
            return FloatBuffer.wrap(values)
        } finally {
            resized.recycle()
        }
    }

    private fun displayName(id: String): String {
        val russian = appContext.resources.configuration.locales[0].language == "ru"
        return if (russian) RUSSIAN_NAMES[id] ?: id.replace('_', ' ') else id.replace('_', ' ')
    }

    companion object {
        private const val ASSET_DIRECTORY = "food/classifier"
        private const val MODEL_FILE = "mobilenet_v2_food101.onnx"
        private const val MODEL_BYTES = 9_385_783
        private const val MODEL_SHA256 = "ed386d53e69bb71f637dc9794429a5332e1c7dde4481ad177934ca0cc8aaa444"
        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "output1"
        private const val INPUT_EDGE = 224
        private const val MAX_DECODE_EDGE = 1024
        private const val CLASS_COUNT = 101
        private val INPUT_SHAPE = longArrayOf(1, 3, INPUT_EDGE.toLong(), INPUT_EDGE.toLong())
        private val RUSSIAN_NAMES = mapOf(
            "apple_pie" to "Яблочный пирог", "baklava" to "Пахлава", "beet_salad" to "Свекольный салат",
            "caesar_salad" to "Салат Цезарь", "carrot_cake" to "Морковный торт", "cheesecake" to "Чизкейк",
            "cheese_plate" to "Сырная тарелка", "chicken_curry" to "Куриное карри", "chicken_wings" to "Куриные крылышки",
            "chocolate_cake" to "Шоколадный торт", "chocolate_mousse" to "Шоколадный мусс", "churros" to "Чуррос",
            "creme_brulee" to "Крем-брюле", "donuts" to "Пончики", "edamame" to "Эдамаме", "falafel" to "Фалафель",
            "french_fries" to "Картофель фри", "french_onion_soup" to "Французский луковый суп", "fried_rice" to "Жареный рис",
            "garlic_bread" to "Чесночный хлеб", "gnocchi" to "Ньокки", "greek_salad" to "Греческий салат",
            "grilled_salmon" to "Лосось на гриле", "guacamole" to "Гуакамоле", "gyoza" to "Гёдза", "hamburger" to "Гамбургер",
            "hot_dog" to "Хот-дог", "hummus" to "Хумус", "ice_cream" to "Мороженое", "lasagna" to "Лазанья",
            "macaroni_and_cheese" to "Макароны с сыром", "macarons" to "Макаронс", "miso_soup" to "Мисо-суп",
            "mussels" to "Мидии", "nachos" to "Начос", "omelette" to "Омлет", "onion_rings" to "Луковые кольца",
            "oysters" to "Устрицы", "pad_thai" to "Пад-тай", "paella" to "Паэлья", "panna_cotta" to "Панна-котта",
            "peking_duck" to "Утка по-пекински", "pho" to "Фо", "pizza" to "Пицца", "ramen" to "Рамен", "ravioli" to "Равиоли",
            "red_velvet_cake" to "Торт Красный бархат", "risotto" to "Ризотто", "samosa" to "Самоса", "sashimi" to "Сашими",
            "scallops" to "Морские гребешки", "spaghetti_bolognese" to "Спагетти болоньезе", "spaghetti_carbonara" to "Спагетти карбонара",
            "spring_rolls" to "Спринг-роллы", "steak" to "Стейк", "sushi" to "Суши", "tacos" to "Тако", "takoyaki" to "Такояки",
            "tiramisu" to "Тирамису", "tuna_tartare" to "Тартар из тунца", "waffles" to "Вафли"
        )
    }
}
