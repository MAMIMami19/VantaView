// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

/** Movies or TV Series page: a row per add-on catalogue, plus search. */
@Composable
fun CatalogScreen(
    title: String,
    onOpen: (MetaPreview) -> Unit,
    onAddAddon: () -> Unit,
    vm: CatalogViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Vanta.Bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        if (ui.loaded && ui.addonCount == 0 && ui.kids) {
            Message("Ask a grown-up to add add-ons to this profile.")
            return@Column
        }
        if (ui.loaded && ui.addonCount == 0) {
            Empty(
                "No add-ons yet. Install Stremio or Nuvio add-ons on the Sources page and their " +
                    (if (vm.type == "series") "series" else "films") + " appear here.",
                "Add an add-on", onAddAddon,
            )
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            label = { Text("Search ${title.lowercase()}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        val results = ui.results
        when {
            query.isNotBlank() && (ui.searching || results == null) -> Message("Searching…")
            query.isNotBlank() && results != null -> {
                if (results.isEmpty()) Message("Nothing found for \"$query\". Search only covers add-ons that support it.")
                else LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }, modifier = Modifier) }
                }
            }
            ui.loaded && ui.rows.isEmpty() && ui.kids -> Message(
                "None of this profile's add-ons has a family or kids catalogue for ${if (vm.type == "series") "series" else "films"}."
            )
            ui.loaded && ui.rows.isEmpty() -> Message(
                "Your add-ons have no ${if (vm.type == "series") "series" else "film"} catalogues to browse. " +
                    "You can still search above, or add a catalogue add-on such as Cinemeta on the Sources page."
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ui.rows, key = { it.key }) { row ->
                    Column {
                        Text(row.title, color = Vanta.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        when (val st = ui.rowStates[row.key]) {
                            is RowState.Ready ->
                                if (st.items.isEmpty()) Text("Empty", color = Vanta.TextDim)
                                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(st.items, key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }) }
                                }
                            RowState.Failed -> Text("Couldn't load this row", color = Vanta.Error)
                            else -> Text("Loading…", color = Vanta.TextDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Vanta.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    }
}

@Composable
fun Empty(text: String, button: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, color = Vanta.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
            GradientButton(button, onClick = onClick)
        }
    }
}
