package app.aaps.ui.food.recognition

enum class LocalRecognitionFailure {
    IMAGE_UNREADABLE,
    MODEL_UNAVAILABLE,
    OCR_UNAVAILABLE,
    OCR_FAILED,
    OCR_TIMED_OUT
}

data class LocalFoodRecognitionResult(
    val barcode: String?,
    val rawText: String,
    val nutrition: NutritionLabelResult,
    val failures: Set<LocalRecognitionFailure> = emptySet()
)
