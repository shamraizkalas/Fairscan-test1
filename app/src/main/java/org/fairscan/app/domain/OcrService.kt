/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.domain

import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fairscan.app.data.OcrLanguageRepository
import org.fairscan.imageprocessing.ImageRect
import org.fairscan.imageprocessing.OcrTextBox

class OcrService(
    private val ocrLanguageRepository: OcrLanguageRepository,
    private val scope: CoroutineScope,
) {
    private var tess: TessBaseAPI? = null

    private val mutex = Mutex()

    private var languageString = ""
    fun languageString() = languageString
    fun isConfigured(): Boolean = languageString.isNotEmpty()

    fun initialize() {
        scope.launch {
            ocrLanguageRepository.enabledLanguages.collect { _ -> reinitialize() }
        }
    }

    private suspend fun reinitialize() {
        mutex.withLock {
            tess?.recycle()
            tess = null

            languageString = ocrLanguageRepository.buildTesseractLanguageString()
            if (languageString.isEmpty()) return

            val dataPath = ocrLanguageRepository.tessdataDir.parent!!
            val newTess = TessBaseAPI()
            if (!newTess.init(dataPath, languageString)) {
                newTess.recycle()
                return
            }
            tess = newTess
        }
    }

    suspend fun runOcr(bitmap: Bitmap): List<OcrTextBox> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = this@OcrService.tess ?: return@withContext listOf()
            val textBoxes = mutableListOf<OcrTextBox>()
            tess.setImage(bitmap)
            val text = tess.getUTF8Text() // Trigger text recognition
            val iterator = tess.resultIterator ?: return@withContext listOf()
            if (!text.isNullOrEmpty()) {
                iterator.begin()
                do {
                    val word = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                    val wordBox = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                    val lineBox = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)
                    if (confidence > 50) {
                        val rect = wordBox.toImageRect()
                        textBoxes.add(OcrTextBox(word, rect, lineBox.height(), lineBox.bottom))
                    }
                } while (iterator.next(PageIteratorLevel.RIL_WORD))
            }
            iterator.delete()
            return@withContext textBoxes
        }
    }

    suspend fun extractFullText(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val tess = this@OcrService.tess ?: return@withContext ""
            tess.setImage(bitmap)
            tess.getUTF8Text()?.trim().orEmpty()
        }
    }

    suspend fun extractTextAndBoxes(bitmap: Bitmap): Pair<String, List<org.fairscan.imageprocessing.OcrTextBox>> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val tess = this@OcrService.tess ?: return@withContext ("" to emptyList())
                tess.setImage(bitmap)
                val fullText = tess.getUTF8Text()?.trim().orEmpty()
                val textBoxes = mutableListOf<OcrTextBox>()
                val iterator = tess.resultIterator
                if (iterator != null) {
                    if (fullText.isNotEmpty()) {
                        iterator.begin()
                        do {
                            val word = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                            val wordBox = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                            val lineBox = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                            val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)
                            if (confidence > 50) {
                                textBoxes.add(
                                    OcrTextBox(
                                        word,
                                        wordBox.toImageRect(),
                                        lineBox.height(),
                                        lineBox.bottom
                                    )
                                )
                            }
                        } while (iterator.next(PageIteratorLevel.RIL_WORD))
                    }
                    iterator.delete()
                }
                fullText to textBoxes
            }
        }

    private fun Rect.toImageRect(): ImageRect = ImageRect(left, top, right, bottom)
}
