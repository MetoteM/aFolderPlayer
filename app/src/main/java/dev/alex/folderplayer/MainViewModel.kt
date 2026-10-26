package dev.alex.folderplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LibraryUiState(
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Application-owned work survives recreation; the screen only observes state. */
internal class MainViewModel
@JvmOverloads
constructor(
    application: Application,
    private val readTracks: () -> List<Track> = { Library(application).tracks() },
) : AndroidViewModel(application) {
    val translation = RetainedOperation<SongTranslationResult>()
    val lyrics = RetainedOperation<List<LyricsSearch.Result>>()
    val scan = RetainedOperation<Library.ScanResult>()
    var online: OnlineSection.Snapshot? = null
    private val state = MutableStateFlow(LibraryUiState())
    val libraryState = state.asStateFlow()
    private var loading: Job? = null

    init {
        reload()
    }

    fun reload() {
        loading?.cancel()
        state.value = state.value.copy(loading = true, error = null)
        loading =
            viewModelScope.launch {
                try {
                    val tracks = withContext(Dispatchers.IO) { readTracks() }
                    state.value = LibraryUiState(tracks, false)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    state.value =
                        state.value.copy(
                            loading = false,
                            error = "Не удалось прочитать библиотеку. Повтори загрузку.",
                        )
                }
            }
    }

    fun scanned(tracks: List<Track>) {
        loading?.cancel()
        state.value = LibraryUiState(tracks, false)
    }

    override fun onCleared() {
        translation.close()
        lyrics.close()
        scan.close()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application) = viewModelFactory {
            initializer { MainViewModel(application) }
        }
    }
}
