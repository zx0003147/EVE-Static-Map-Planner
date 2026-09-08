package dev.evestaticmapplanner.webpack

import java.nio.file.Path
import javax.swing.JFileChooser

sealed interface WebPackExportUiState {
    data object Idle : WebPackExportUiState
    data object Exporting : WebPackExportUiState
    data class Success(val report: WebPackExportReport) : WebPackExportUiState
    data class Failure(val message: String) : WebPackExportUiState
}

internal fun chooseWebPackExportParentDirectory(): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select parent directory for ${WebPackSchema.EXPORT_DIRECTORY_NAME}"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        approveButtonText = "Export Here"
    }
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
