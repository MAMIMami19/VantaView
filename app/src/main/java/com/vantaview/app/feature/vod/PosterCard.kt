// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

@Composable
fun PosterCard(item: MetaPreview, onClick: () -> Unit, modifier: Modifier = Modifier.width(118.dp)) {
    Column(modifier) {
        FocusCard(onClick = onClick, padding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().height(177.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Vanta.SurfaceHigh), contentAlignment = Alignment.Center) {
                Text(
                    item.name, color = Vanta.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                if (item.poster != null) {
                    AsyncImage(
                        model = item.poster, contentDescription = item.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            item.name, color = Vanta.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val sub = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString("  ")
        if (sub.isNotEmpty()) Text(sub, color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}
