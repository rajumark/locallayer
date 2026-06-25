package com.locallayer.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SetupStep {
    SELECT_TARGET,
    DONE
}

data class TranslateLanguageOption(
    val code: String,
    val displayName: String
)

data class TranslateUiState(
    val sourceLanguage: TranslateLanguageOption = TranslateLanguageOption("en", "English"),
    val targetLanguage: TranslateLanguageOption = TranslateLanguageOption("hi", "Hindi"),
    val modelsReady: Boolean = false,
    val isModelDownloading: Boolean = false,
    val downloadProgress: String = "",
    val downloadProgressValue: Float = 0f,
    val downloadLanguage: String = "",
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    private var downloadGeneration = 0

    private val _setupStep = MutableStateFlow(SetupStep.SELECT_TARGET)
    val setupStep: StateFlow<SetupStep> = _setupStep.asStateFlow()

    private val modelManager = RemoteModelManager.getInstance()
    private var downloadJob: Job? = null
    private val prefs = application.getSharedPreferences("locallayer_prefs", Context.MODE_PRIVATE)

    val languages = listOf(
        TranslateLanguageOption("ar", "Arabic"),
        TranslateLanguageOption("zh", "Chinese (Simplified)"),
        TranslateLanguageOption("zh-TW", "Chinese (Traditional)"),
        TranslateLanguageOption("cs", "Czech"),
        TranslateLanguageOption("da", "Danish"),
        TranslateLanguageOption("nl", "Dutch"),
        TranslateLanguageOption("fi", "Finnish"),
        TranslateLanguageOption("fr", "French"),
        TranslateLanguageOption("de", "German"),
        TranslateLanguageOption("el", "Greek"),
        TranslateLanguageOption("he", "Hebrew"),
        TranslateLanguageOption("hi", "Hindi"),
        TranslateLanguageOption("hu", "Hungarian"),
        TranslateLanguageOption("id", "Indonesian"),
        TranslateLanguageOption("it", "Italian"),
        TranslateLanguageOption("ja", "Japanese"),
        TranslateLanguageOption("ko", "Korean"),
        TranslateLanguageOption("ms", "Malay"),
        TranslateLanguageOption("nb", "Norwegian"),
        TranslateLanguageOption("pl", "Polish"),
        TranslateLanguageOption("pt", "Portuguese"),
        TranslateLanguageOption("ro", "Romanian"),
        TranslateLanguageOption("ru", "Russian"),
        TranslateLanguageOption("es", "Spanish"),
        TranslateLanguageOption("sv", "Swedish"),
        TranslateLanguageOption("th", "Thai"),
        TranslateLanguageOption("tr", "Turkish"),
        TranslateLanguageOption("uk", "Ukrainian"),
        TranslateLanguageOption("vi", "Vietnamese")
    )

    init {
        val savedTarget = prefs.getString("target_language", null)
        if (savedTarget != null) {
            val sourceCode = prefs.getString("source_language", "en") ?: "en"
            val sourceLang = languages.find { it.code == sourceCode } ?: languages.first()
            val targetLang = languages.find { it.code == savedTarget } ?: languages.first()
            _uiState.update { it.copy(sourceLanguage = sourceLang, targetLanguage = targetLang) }
            _setupStep.value = SetupStep.DONE
            ensureModelsDownloaded()
        }
        refreshDownloadedModels()
    }

    fun refreshDownloadedModels() {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                _downloadedLanguages.value = models.mapNotNull { it.language }.toSet()
            }
    }

    fun setSourceLanguage(language: TranslateLanguageOption) {
        _uiState.update { it.copy(sourceLanguage = language, modelsReady = false, error = null) }
        prefs.edit().putString("source_language", language.code).apply()
        LayerAccessibilityService.translationCache.clear()
        ensureModelsDownloaded()
    }

    fun setTargetLanguage(language: TranslateLanguageOption) {
        _uiState.update { it.copy(targetLanguage = language, modelsReady = false, error = null) }
        prefs.edit().putString("target_language", language.code).apply()
        LayerAccessibilityService.translationCache.clear()
        if (_setupStep.value == SetupStep.SELECT_TARGET) {
            _setupStep.value = SetupStep.DONE
        }
        ensureModelsDownloaded()
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage,
                modelsReady = false,
                error = null
            )
        }
        prefs.edit()
            .putString("source_language", _uiState.value.targetLanguage.code)
            .putString("target_language", _uiState.value.sourceLanguage.code)
            .apply()
        LayerAccessibilityService.translationCache.clear()
        ensureModelsDownloaded()
    }

    private fun ensureModelsDownloaded() {
        downloadJob?.cancel()
        downloadGeneration++
        val gen = downloadGeneration
        Log.d("TranslateVM", "ensureModelsDownloaded gen=$gen")
        _uiState.update { it.copy(isModelDownloading = true, error = null) }

        val state = _uiState.value
        val source = state.sourceLanguage.code
        val target = state.targetLanguage.code
        Log.d("TranslateVM", "source=$source target=$target")

        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                Log.d("TranslateVM", "getDownloadedModels success gen=$gen count=${models.size}")
                if (gen != downloadGeneration) {
                    Log.d("TranslateVM", "stale gen=$gen current=$downloadGeneration")
                    return@addOnSuccessListener
                }
                val downloadedLangs = models.mapNotNull { it.language }.toSet()
                val langsNeeded = listOfNotNull(source, target)
                    .filterNot { it in downloadedLangs }
                    .distinct()
                Log.d("TranslateVM", "downloadedLangs=$downloadedLangs langsNeeded=$langsNeeded")

                if (langsNeeded.isEmpty()) {
                    Log.d("TranslateVM", "no langs needed, models ready")
                    _uiState.update {
                        it.copy(modelsReady = true, isModelDownloading = false)
                    }
                    return@addOnSuccessListener
                }

                downloadModels(langsNeeded, gen)
            }
            .addOnFailureListener { e ->
                Log.d("TranslateVM", "getDownloadedModels failed gen=$gen: $e")
                if (gen != downloadGeneration) {
                    Log.d("TranslateVM", "stale gen=$gen current=$downloadGeneration")
                    return@addOnFailureListener
                }
                downloadModels(listOfNotNull(source, target).distinct(), gen)
            }
    }

    private fun downloadModels(langsNeeded: List<String>, gen: Int) {
        Log.d("TranslateVM", "downloadModels gen=$gen langs=$langsNeeded")
        val conditions = DownloadConditions.Builder().build()

        fun downloadNext(index: Int) {
            Log.d("TranslateVM", "downloadNext gen=$gen index=$index total=${langsNeeded.size}")
            if (gen != downloadGeneration) {
                Log.d("TranslateVM", "stale gen=$gen current=$downloadGeneration")
                return
            }
            if (index >= langsNeeded.size) {
                Log.d("TranslateVM", "all downloads complete gen=$gen")
                refreshDownloadedModels()
                _uiState.update {
                    it.copy(
                        modelsReady = true,
                        isModelDownloading = false,
                        downloadProgress = "",
                        downloadLanguage = ""
                    )
                }
                return
            }

            val lang = langsNeeded[index]
            val total = langsNeeded.size
            _uiState.update {
                it.copy(
                    downloadProgress = if (total > 1) "Downloading $lang model (${index + 1}/$total)..."
                        else "Downloading $lang model...",
                    downloadLanguage = lang
                )
            }

            val model = TranslateRemoteModel.Builder(lang).build()
            Log.d("TranslateVM", "starting download for $lang gen=$gen")
            modelManager.download(model, conditions)
                .addOnSuccessListener {
                    Log.d("TranslateVM", "download success $lang gen=$gen")
                    if (gen == downloadGeneration) downloadNext(index + 1)
                }
                .addOnFailureListener { e ->
                    Log.d("TranslateVM", "download failed $lang gen=$gen: $e")
                    if (gen == downloadGeneration) {
                        _uiState.update {
                            it.copy(
                                isModelDownloading = false,
                                error = "Failed to download $lang: ${e.localizedMessage ?: "unknown error"}"
                            )
                        }
                    }
                }
        }

        downloadNext(0)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
    }
}
