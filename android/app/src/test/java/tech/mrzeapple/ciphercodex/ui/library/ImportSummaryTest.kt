package tech.mrzeapple.ciphercodex.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mrzeapple.ciphercodex.data.ImportResult

class ImportSummaryTest {

    @Test
    fun singleSuccessKeepsOriginalMessage() {
        assertEquals(
            ImportUiState.Done("IMPORTED"),
            summarizeImports(listOf(ImportResult.Imported(1L))),
        )
    }

    @Test
    fun singleDuplicateKeepsOriginalMessage() {
        assertEquals(
            ImportUiState.Done("ALREADY IN LIBRARY"),
            summarizeImports(listOf(ImportResult.Duplicate(1L))),
        )
    }

    @Test
    fun singleFailureKeepsSpecificMessage() {
        assertEquals(
            ImportUiState.Error("Not a readable EPUB: bad zip"),
            summarizeImports(listOf(ImportResult.Failed("Not a readable EPUB: bad zip"))),
        )
    }

    @Test
    fun multiAllSuccess() {
        assertEquals(
            ImportUiState.Done("IMPORTED 3"),
            summarizeImports(
                listOf(
                    ImportResult.Imported(1L),
                    ImportResult.Imported(2L),
                    ImportResult.Imported(3L),
                ),
            ),
        )
    }

    @Test
    fun mixedBatchJoinsPartsAndIsDone() {
        assertEquals(
            ImportUiState.Done("IMPORTED 2 · 1 ALREADY IN LIBRARY · 1 FAILED"),
            summarizeImports(
                listOf(
                    ImportResult.Imported(1L),
                    ImportResult.Failed("Could not open the selected file."),
                    ImportResult.Duplicate(2L),
                    ImportResult.Imported(3L),
                ),
            ),
        )
    }

    @Test
    fun allFailedIsError() {
        assertEquals(
            ImportUiState.Error("2 FAILED"),
            summarizeImports(
                listOf(
                    ImportResult.Failed("Could not open the selected file."),
                    ImportResult.Failed("Not a readable EPUB: bad zip"),
                ),
            ),
        )
    }

    @Test
    fun allDuplicatesIsDone() {
        assertEquals(
            ImportUiState.Done("2 ALREADY IN LIBRARY"),
            summarizeImports(
                listOf(
                    ImportResult.Duplicate(1L),
                    ImportResult.Duplicate(2L),
                ),
            ),
        )
    }
}
