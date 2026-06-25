package com.locallayer.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        downloadJob = viewModelScope.launch {
            val state = _uiState.value
            val source = state.sourceLanguage.code
            val target = state.targetLanguage.code

            try {
                val downloadedModels = kotlinx.coroutines.suspendCancellableCoroutine<Set<TranslateRemoteModel>> { cont ->
                    modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it, null) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(emptySet(), null) }
                }
                val downloadedLangs = downloadedModels.mapNotNull { it.language }.toSet()

                val langsNeeded = listOfNotNull(source, target)
                    .filterNot { it in downloadedLangs }
                    .distinct()

                if (langsNeeded.isEmpty()) {
                    _uiState.update { it.copy(modelsReady = true, error = null) }
                    return@launch
                }

                _uiState.update { it.copy(isModelDownloading = true) }
                val conditions = DownloadConditions.Builder().build()

                for (lang in langsNeeded) {
                    val model = TranslateRemoteModel.Builder(lang).build()
                    _uiState.update {
                        it.copy(
                            downloadProgress = if (langsNeeded.size > 1)
                                "Downloading $lang model (${langsNeeded.indexOf(lang) + 1}/${langsNeeded.size})..."
                            else
                                "Downloading $lang model...",
                            downloadProgressValue = 0f,
                            downloadLanguage = lang
                        )
                    }

                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        modelManager.download(model, conditions)
                            .addOnSuccessListener {
                                if (continuation.isActive) continuation.resume(Unit, null)
                            }
                            .addOnFailureListener { e ->
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(e))
                                }
                            }
                    }
                }

                refreshDownloadedModels()
                _uiState.update {
                    it.copy(
                        modelsReady = true,
                        isModelDownloading = false,
                        downloadProgress = "",
                        downloadProgressValue = 0f,
                        downloadLanguage = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isModelDownloading = false,
                        downloadProgressValue = 0f,
                        error = e.localizedMessage ?: "Model download failed"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
    }
}
