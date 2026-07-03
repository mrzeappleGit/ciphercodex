package tech.mrzeapple.ciphercodex.ui.library

import tech.mrzeapple.ciphercodex.data.ImportResult

/** Maps a completed import batch to the status line shown above the grid.
 *  A single-file batch keeps the specific per-file wording; larger batches
 *  get counted parts joined with " · ", and only read as an error when
 *  nothing landed in the library. */
fun summarizeImports(results: List<ImportResult>): ImportUiState {
    results.singleOrNull()?.let { result ->
        return when (result) {
            is ImportResult.Imported -> ImportUiState.Done("IMPORTED")
            is ImportResult.Duplicate -> ImportUiState.Done("ALREADY IN LIBRARY")
            is ImportResult.Failed -> ImportUiState.Error(result.message)
        }
    }
    val imported = results.count { it is ImportResult.Imported }
    val duplicates = results.count { it is ImportResult.Duplicate }
    val failed = results.count { it is ImportResult.Failed }
    val message = buildList {
        if (imported > 0) add("IMPORTED $imported")
        if (duplicates > 0) add("$duplicates ALREADY IN LIBRARY")
        if (failed > 0) add("$failed FAILED")
    }.joinToString(" · ")
    return if (imported == 0 && duplicates == 0) {
        ImportUiState.Error(message)
    } else {
        ImportUiState.Done(message)
    }
}
