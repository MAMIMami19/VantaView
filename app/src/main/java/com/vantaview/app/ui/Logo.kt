// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vantaview.app.R

/** The VantaView logo (V with play triangle). */
@Composable
fun VantaLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Image(painter = painterResource(R.drawable.vv_logo), contentDescription = "VantaView", modifier = modifier.size(size))
}
