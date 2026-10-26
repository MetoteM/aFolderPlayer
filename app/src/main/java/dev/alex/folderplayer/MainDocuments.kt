package dev.alex.folderplayer

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

internal enum class DocumentRequest {
    MUSIC_FOLDER,
    IMPORT_LYRICS,
    EXPORT_LYRICS,
    IMPORT_MODEL,
    IMPORT_ENGINE,
    DOCUMENT_TRANSLATION,
    EXTENSION_FOLDER,
}

/** Stable registry keys preserve pending results across recreation and process restoration. */
internal class MainDocuments(
    host: ComponentActivity,
    complete: (DocumentRequest, ActivityResult) -> Unit,
    registry: ActivityResultRegistry = host.activityResultRegistry,
) {
    private val launchers =
        DocumentRequest.entries.associateWith { request ->
            registry.register(
                "afp.documents.${request.name}",
                host,
                ActivityResultContracts.StartActivityForResult(),
            ) {
                complete(request, it)
            }
        }

    fun launch(intent: Intent, request: DocumentRequest) {
        launchers.getValue(request).launch(intent)
    }
}
