package it.objectfinder.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Larghezza massima di schede, impostazioni e moduli: oltre, righe e pulsanti diventano illeggibili. */
val ReadableMaxWidth: Dp = 640.dp

/**
 * Occupa tutta la larghezza fino a [max], poi resta centrato. Su telefono non cambia nulla;
 * su tablet, pieghevoli e finestre larghe evita pulsanti e riquadri lunghi un metro.
 */
fun Modifier.readableWidth(max: Dp = ReadableMaxWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
