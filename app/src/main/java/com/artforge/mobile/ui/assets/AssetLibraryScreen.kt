package com.artforge.mobile.ui.assets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artforge.mobile.data.asset.AssetCategory
import com.artforge.mobile.data.db.AssetEntity
import com.artforge.mobile.ui.common.GestureIconButton
import com.artforge.mobile.ui.common.Hairline
import com.artforge.mobile.ui.common.PillButton
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Offline asset library: import, browse, search and preview textures, patterns,
 * backgrounds, stamps, references and templates. Assets are plain files on disk.
 */
@Composable
fun AssetLibraryScreen(onBack: () -> Unit, vm: AssetLibraryViewModel = viewModel()) {
    val assets by vm.assets.collectAsStateWithLifecycle()
    var category by remember { mutableStateOf(AssetCategory.Texture) }
    var query by remember { mutableStateOf("") }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAsset(it, category, query.ifBlank { "Imported" }) }
    }

    Column(Modifier.fillMaxSize().background(ForgeColors.Charcoal)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GestureIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 42)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Asset library", style = MaterialTheme.typography.titleLarge, color = ForgeColors.TextPrimary)
                Text(
                    "${assets.size} offline assets",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                )
            }
            GestureIconButton(
                Icons.Filled.FileUpload,
                "Import asset",
                { importLauncher.launch(arrayOf("image/*", "application/octet-stream")) },
                size = 42,
            )
        }
        Hairline()

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.search(it)
            },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = ForgeColors.TextMuted) },
            placeholder = { Text("Search assets") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )

        LazyRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AssetCategory.entries.toList()) { cat ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (category == cat) ForgeColors.Accent else ForgeColors.GraphiteRaised)
                        .clickable { category = cat }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(
                        cat.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (category == cat) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
                    )
                }
            }
        }

        val filtered = assets.filter { it.category == category.key }
        if (query.isNotBlank()) {
            val results = remember(query, assets) { vm.searchResults }
            LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(results) { asset -> AssetTile(asset) }
            }
        } else if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No ${category.label.lowercase()} yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use the import button to add files from this device. Built-in textures are generated " +
                        "automatically the first time you open a texture slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                PillButton(
                    "Generate built-in textures",
                    { vm.generateBuiltInTextures() },
                    selected = true,
                    icon = Icons.Filled.AutoAwesome,
                )
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(filtered) { asset -> AssetTile(asset) }
            }
        }
    }
}

@Composable
private fun AssetTile(asset: AssetEntity) {
    val bitmap = remember(asset.previewPath) {
        asset.previewPath?.let { path -> runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.Graphite)
            .border(1.dp, ForgeColors.Hairline, RoundedCornerShape(14.dp))
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ForgeColors.GraphiteElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = asset.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(asset.name, style = MaterialTheme.typography.titleSmall, color = ForgeColors.TextPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(
            AssetCategory.fromKey(asset.category).label,
            style = MaterialTheme.typography.labelSmall,
            color = ForgeColors.TextMuted,
        )
    }
}
