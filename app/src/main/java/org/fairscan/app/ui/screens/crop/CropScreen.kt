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
package org.fairscan.app.ui.screens.crop

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fairscan.app.R
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.components.MainActionButton
import org.fairscan.app.ui.components.MyScaffold
import org.fairscan.app.ui.components.isLandscape
import org.fairscan.app.ui.dummyNavigation
import org.fairscan.app.ui.theme.FairScanTheme
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    pageId: String,
    initState: CropInitState,
    navigation: Navigation,
    onUpdatePageQuad: (Quad) -> Unit,
    onAutoDetectQuad: (suspend (Bitmap) -> Quad?)? = null,
) {
    val quadHandler = remember { QuadEditingHandler() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var isAutoDetecting by remember { mutableStateOf(false) }

    val (bitmap, initialQuad) = if (initState is CropInitState.Ready && initState.pageId == pageId) {
        Pair(initState.bitmap, initState.quad)
    } else {
        Pair(null, null)
    }

    val editableQuad = rememberSaveable(pageId, saver = QuadSaver) {
        mutableStateOf(initialQuad)
    }
    val state = remember(pageId) {
        CropScreenState(editableQuad)
    }

    LaunchedEffect(pageId, initialQuad) {
        if (state.editableQuad == null && initialQuad != null) {
            state.updateQuad(initialQuad)
        }
    }

    BackHandler { navigation.back() }

    val isLandscape = isLandscape(LocalConfiguration.current)
    val density = LocalDensity.current
    val bottomInsetForSystemGestures = with(density) {
        WindowInsets.mandatorySystemGestures.getBottom(density).toDp()
    }

    MyScaffold(
        navigation = navigation,
        bottomBar = {},
    ) { modifier ->

        Box(modifier = modifier.fillMaxSize()) {
            bitmap?.let { bmp ->
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Image to edit",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomInsetForSystemGestures)
                            .onGloballyPositioned { coordinates ->
                                state.containerSize = coordinates.size
                            },
                        contentScale = ContentScale.Fit,
                    )

                    DragQuadOverlay(state, quadHandler, bmp)
                }
            }

            DragMagnifyingGlass(state, bitmap)

            ActionButtons(
                isLandscape = isLandscape,
                isAutoDetecting = isAutoDetecting,
                canAutoDetect = bitmap != null && onAutoDetectQuad != null,
                modifier = Modifier
                    .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(16.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                onAutoDetect = {
                    val bmp = bitmap ?: return@ActionButtons
                    val detector = onAutoDetectQuad ?: return@ActionButtons
                    coroutineScope.launch {
                        isAutoDetecting = true
                        try {
                            val detected = detector(bmp)
                            if (detected != null) {
                                state.updateQuad(detected)
                                Toast.makeText(context, "Document edges detected", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No distinct border found — adjust corners manually", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isAutoDetecting = false
                        }
                    }
                },
                onFullPage = {
                    state.updateQuad(
                        Quad(
                            Point(0.0, 0.0),
                            Point(1.0, 0.0),
                            Point(1.0, 1.0),
                            Point(0.0, 1.0),
                        )
                    )
                },
                onConfirm = {
                    state.editableQuad?.let { onUpdatePageQuad(it) }
                    navigation.back()
                }
            )
        }
    }
}

@Composable
private fun ActionButtons(
    isLandscape: Boolean,
    isAutoDetecting: Boolean,
    canAutoDetect: Boolean,
    modifier: Modifier,
    onAutoDetect: () -> Unit,
    onFullPage: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        if (isLandscape) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (canAutoDetect) {
                    FilledTonalButton(
                        onClick = onAutoDetect,
                        enabled = !isAutoDetecting,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("crop_auto_detect_btn")
                    ) {
                        if (isAutoDetecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Auto", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = onFullPage,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("crop_full_page_btn")
                ) {
                    Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Full", style = MaterialTheme.typography.labelMedium)
                }
                MainActionButton(
                    onClick = onConfirm,
                    text = stringResource(R.string.apply),
                    icon = Icons.Filled.Check,
                    iconDescription = stringResource(R.string.apply),
                    modifier = Modifier.testTag("crop_apply_btn")
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canAutoDetect) {
                    FilledTonalButton(
                        onClick = onAutoDetect,
                        enabled = !isAutoDetecting,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("crop_auto_detect_btn")
                    ) {
                        if (isAutoDetecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Auto-Detect", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = onFullPage,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("crop_full_page_btn")
                ) {
                    Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Full", style = MaterialTheme.typography.labelMedium)
                }
                MainActionButton(
                    onClick = onConfirm,
                    text = stringResource(R.string.apply),
                    icon = Icons.Filled.Check,
                    iconDescription = stringResource(R.string.apply),
                    modifier = Modifier.testTag("crop_apply_btn")
                )
            }
        }
    }
}


@Composable
private fun DragQuadOverlay(
    state: CropScreenState,
    quadHandler: QuadEditingHandler,
    bmp: Bitmap
) {
    if (state.editableQuad == null || state.containerSize == null) return

    val containerSize = state.containerSize!!
    val displaySize = QuadCoordinateUtils.calculateDisplaySize(bmp.width, bmp.height, containerSize)
    val liftWiggleThresholdPx = with(LocalDensity.current) {
        CropScreenState.LIFT_WIGGLE_MAX_DISTANCE.toPx()
    }

    QuadOverlay(
        quad = state.editableQuad!!,
        containerSize = containerSize,
        displaySize = displaySize,
        modifier = Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startPos ->
                        val quad = state.editableQuad ?: return@detectDragGestures
                        state.dragPosition = startPos

                        // Prefer the index stored at raw touch-down (exact touch position,
                        // before slop). Fall back to re-detecting at the slop position only
                        // when the raw-touch handler missed the down event.
                        val cornerIndex = if (state.touchDownCornerIndex >= 0) {
                            state.touchDownCornerIndex
                        } else {
                            quadHandler.findTouchedCorner(startPos, quad, containerSize, displaySize)
                        }

                        if (cornerIndex >= 0) {
                            state.startCornerDrag(cornerIndex)
                        }
                    },
                    onDragEnd = {
                        state.rollbackLastDragStepIfLikelyLiftWiggle(liftWiggleThresholdPx)
                        state.endDrag()
                        state.onTouchUp()
                    },
                    onDragCancel = {
                        state.rollbackLastDragStepIfLikelyLiftWiggle(liftWiggleThresholdPx)
                        state.endDrag()
                        state.onTouchUp()
                    },
                    onDrag = { change, dragAmount ->
                        // change.consume() is intentionally omitted: detectDragGestures
                        // already calls it.consume() internally after this callback returns.
                        state.dragPosition = change.position
                        val quad = state.editableQuad ?: return@detectDragGestures
                        state.recordDragStep(quad, dragAmount)
                        val normalizedDelta = QuadCoordinateUtils.screenDeltaToNormalized(
                            dragAmount, displaySize
                        )

                        when {
                            state.draggedCornerIndex >= 0 -> {
                                state.updateQuad(
                                    quadHandler.updateQuadCorner(
                                        quad, state.draggedCornerIndex, normalizedDelta
                                    )
                                )
                            }
                        }
                    }
                )
            }
            // Second pointer-input: fires immediately on press (before touch slop)
            // so the loupe appears as soon as the finger touches a handle.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val quad = state.editableQuad
                    if (quad != null) {
                        val cIdx = quadHandler.findTouchedCorner(down.position, quad, containerSize, displaySize)
                        if (cIdx >= 0) {
                            state.onTouchDown(down.position, cIdx)
                        }
                    }
                    // For a tap (no drag): waitForUpOrCancellation() sees the UP event and
                    // returns it, so we call onTouchUp() here.
                    // For a drag: detectDragGestures consumes move events, causing
                    // waitForUpOrCancellation() to return null. We do NOT call onTouchUp()
                    // here; onDragEnd / onDragCancel above handle that instead.
                    if (waitForUpOrCancellation() != null) {
                        state.onTouchUp()
                    }
                }
            }
    )
}

@Composable
private fun DragMagnifyingGlass(state: CropScreenState, bitmap: Bitmap?) {
    // showLoupe becomes true immediately on touch-down and stays true for
    // one additional second after the finger is lifted.
    val showLoupe = remember { mutableStateOf(false) }
    // Remember the last valid focus position so the loupe keeps rendering
    // correctly during the 1-second fade-out (when dragged indices are reset).
    val lastKnownFocusPosition = remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(state.isTouching) {
        if (state.isTouching) {
            showLoupe.value = true
        } else {
            delay(1_000)
            showLoupe.value = false
        }
    }

    if (!showLoupe.value || state.dragPosition == null || state.containerSize == null) return

    val bmp = bitmap ?: return
    val containerSize = state.containerSize!!
    val displaySize = QuadCoordinateUtils.calculateDisplaySize(
        bmp.width, bmp.height, containerSize
    )
    val quad = state.editableQuad

    // Resolve which corner index to focus on.
    // Priority: active drag > pre-drag touch-down > nothing (fade-out phase).
    val activeCornerIndex = state.draggedCornerIndex.takeIf { it >= 0 }
        ?: state.touchDownCornerIndex.takeIf { it >= 0 }

    val focusPosition = if (quad != null) {
        when {
            activeCornerIndex != null -> {
                val corner = when (activeCornerIndex) {
                    0 -> quad.topLeft
                    1 -> quad.topRight
                    2 -> quad.bottomRight
                    3 -> quad.bottomLeft
                    else -> null
                }
                corner?.let {
                    QuadCoordinateUtils.normalizedToScreen(it, containerSize, displaySize)
                }
            }
            else -> null
        }
    } else null

    // Keep the last known focus position so it's still valid after endDrag() resets the indices.
    if (focusPosition != null) lastKnownFocusPosition.value = focusPosition
    // On the very first touch the drag indices are not set yet and lastKnownFocusPosition
    // has never been populated, so fall back to dragPosition (the finger is on the handle).
    val effectiveFocusPosition = focusPosition ?: lastKnownFocusPosition.value ?: state.dragPosition ?: return

    MagnifyingGlass(
        bitmap = bmp,
        fingerPosition = state.dragPosition!!,
        focusPosition = effectiveFocusPosition,
        containerSize = containerSize,
        displaySize = displaySize,
        quad = state.editableQuad,
    )
}

val QuadSaver: Saver<MutableState<Quad?>, *> = listSaver(
    save = { state ->
        state.value?.let {
            listOf(
                it.topLeft.x, it.topLeft.y,
                it.topRight.x, it.topRight.y,
                it.bottomRight.x, it.bottomRight.y,
                it.bottomLeft.x, it.bottomLeft.y,
            )
        } ?: listOf()
    },
    restore = { list ->
        val quad = if (list.size == 8) {
            Quad(
                topLeft = Point(list[0], list[1]),
                topRight = Point(list[2], list[3]),
                bottomRight = Point(list[4], list[5]),
                bottomLeft = Point(list[6], list[7]),
            )
        } else null
        mutableStateOf(quad)
    }
)

@Composable
@Preview(showSystemUi = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, showSystemUi = true)
@Preview(name = "Landscape", showBackground = true, widthDp = 640, heightDp = 320)
@Preview(name = "RTL", locale = "ar", showSystemUi = true)
fun EditPageScreenPreview() {
    FairScanTheme {
        val dummyImage = LocalContext.current.assets.open("gallica.bnf.fr-bpt6k5530456s-1.jpg").use { input ->
            BitmapFactory.decodeStream(input)
        }
        val quad = Quad(Point(.1, .1), Point(.9, .1), Point(.9, .9), Point(.1, .9))
        CropScreen(
            pageId = "123",
            initState = CropInitState.Ready("123",dummyImage, quad),
            navigation = dummyNavigation(),
            onUpdatePageQuad = { _ -> },
        )
    }
}
