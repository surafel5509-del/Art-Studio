@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.artforge.mobile.ui.home


import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.core.model.ProjectSummary
import com.artforge.mobile.data.asset.AssetLibrary
import com.artforge.mobile.data.asset.CanvasTemplate
import com.artforge.mobile.ui.theme.ForgeColors

/**
 * Home screen: create actions, recent projects, templates, recovery and settings.
 * All project data comes from the local Room metadata store.
 */
@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onNewProject: (ProjectKind, String, Int, Int, Int, Int, Boolean, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenRecovery: () -> Unit,
    onImportProject: (android.net.Uri) -> Unit,
    recents: List<ProjectSummary>,
    onDeleteProject: (String) -> Unit,
    onDuplicateProject: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showNewDialog by remember { mutableStateOf(false) }
    var newKind by remember { mutableStateOf(ProjectKind.Illustration) }
    var showOnboarding by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportProject)
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("artforge_ui", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_seen", false)) {
            showOnboarding = true
            prefs.edit().putBoolean("onboarding_seen", true).apply()
        }
    }

    Box(Modifier.fillMaxSize().background(ForgeColors.Charcoal)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(28.dp))
                Text(
                    "ARTFORGE",
                    style = MaterialTheme.typography.displaySmall,
                    color = ForgeColors.TextPrimary,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Professional art & animation studio",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeColors.TextMuted,
                )
                Spacer(Modifier.height(20.dp))
            }

            item {
                CreateActionsGrid(
                    onNew = { kind ->
                        newKind = kind
                        showNewDialog = true
                    },
                    onOpen = { importLauncher.launch(arrayOf("*/*")) },
                    onAssets = onOpenAssets,
                    onSettings = onOpenSettings,
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                SectionTitle("Templates")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(AssetLibrary.builtInTemplates()) { template ->
                        TemplateCard(template) {
                            onNewProject(
                                when (template.category) {
                                    "Animation" -> ProjectKind.Animation
                                    "Pixel" -> ProjectKind.PixelArt
                                    "Comic" -> ProjectKind.Comic
                                    else -> ProjectKind.Illustration
                                },
                                template.name,
                                template.width,
                                template.height,
                                24,
                                24,
                                false,
                                0xFFFFFFFF.toInt(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("Recent projects")
                    TextButton(onClick = onOpenRecovery) { Text("Recovery", style = MaterialTheme.typography.labelSmall) }
                }
            }

            if (recents.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ForgeColors.Graphite),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No projects yet", style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextSecondary)
                            Text(
                                "Create a canvas to get started",
                                style = MaterialTheme.typography.bodySmall,
                                color = ForgeColors.TextMuted,
                            )
                        }
                    }
                }
            } else {
                items(recents, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { onDeleteProject(project.id) },
                        onDuplicate = { onDuplicateProject(project.id) },
                        onToggleFavorite = { onToggleFavorite(project.id, it) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallAction("Settings", Icons.Filled.Settings, onOpenSettings)
                    SmallAction("Assets", Icons.Filled.Collections, onOpenAssets)
                    SmallAction("Help", Icons.Filled.Help, onOpenHelp)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            initialKind = newKind,
            onDismiss = { showNewDialog = false },
            onCreate = { kind, title, width, height, fps, frames, transparent, bg ->
                showNewDialog = false
                onNewProject(kind, title, width, height, fps, frames, transparent, bg)
            },
        )
    }

    if (showOnboarding) {
        OnboardingDialog(onDismiss = { showOnboarding = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = ForgeColors.TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun CreateActionsGrid(
    onNew: (ProjectKind) -> Unit,
    onOpen: () -> Unit,
    onAssets: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CreateCard("New drawing", "Canvas & layers", Icons.Filled.Brush, ForgeColors.Accent, Modifier.weight(1f)) {
                onNew(ProjectKind.Illustration)
            }
            CreateCard("New animation", "Frame-by-frame", Icons.Filled.Animation, ForgeColors.AccentWarm, Modifier.weight(1f)) {
                onNew(ProjectKind.Animation)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CreateCard("New comic", "Panels & bubbles", Icons.Filled.MenuBook, Color(0xFF4CD9A0), Modifier.weight(1f)) {
                onNew(ProjectKind.Comic)
            }
            CreateCard("New pixel art", "Sprites & tiles", Icons.Filled.GridOn, Color(0xFF8A4FFF), Modifier.weight(1f)) {
                onNew(ProjectKind.PixelArt)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CreateCard("Open project", "Import .artforge", Icons.Filled.Image, ForgeColors.AccentSoft, Modifier.weight(1f), onOpen)
            CreateCard("Asset library", "Brushes & textures", Icons.Filled.Collections, ForgeColors.AccentSoft, Modifier.weight(1f), onAssets)
        }
    }
}

@Composable
private fun CreateCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(96.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = ForgeColors.Graphite),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, title, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextMuted)
        }
    }
}

@Composable
private fun TemplateCard(template: CanvasTemplate, onClick: () -> Unit) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(template.accent)) }.getOrDefault(ForgeColors.Accent)
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.Graphite)
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${template.width}×${template.height}",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(template.name, style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(template.category, style = MaterialTheme.typography.labelSmall, color = ForgeColors.TextMuted)
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleFavorite: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val thumb = remember(project.thumbnailPath, project.modifiedAt) {
        project.thumbnailPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ForgeColors.Graphite)
            .clickable { onOpen() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ForgeColors.GraphiteElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    thumb.asImageBitmap(),
                    contentDescription = project.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    when (project.kind) {
                        ProjectKind.Animation -> Icons.Filled.Animation
                        ProjectKind.Comic -> Icons.Filled.MenuBook
                        ProjectKind.PixelArt -> Icons.Filled.GridOn
                        else -> Icons.Filled.Palette
                    },
                    contentDescription = null,
                    tint = ForgeColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.title, style = MaterialTheme.typography.titleMedium, color = ForgeColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${project.kind.name} · ${project.width}×${project.height}" +
                    if (project.kind == ProjectKind.Animation) " · ${project.frameCount} frames" else "",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeColors.TextMuted,
            )
            Text(
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(project.modifiedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = ForgeColors.TextMuted,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, "More", tint = ForgeColors.TextMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("\"${project.title}\" and all of its artwork will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = ForgeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ForgeColors.Graphite)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ForgeColors.TextSecondary)
    }
}

/** New project sheet with size presets and animation settings. */
@Composable
fun NewProjectDialog(
    initialKind: ProjectKind,
    onDismiss: () -> Unit,
    onCreate: (ProjectKind, String, Int, Int, Int, Int, Boolean, Int) -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var title by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("2048") }
    var height by remember { mutableStateOf("2048") }
    var fps by remember { mutableStateOf("24") }
    var frames by remember { mutableStateOf("24") }
    var transparent by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(0xFFFFFFFF.toInt()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectKind.entries.forEach { k ->
                        MiniChip(k.name, kind == k) { kind = k }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit) },
                        label = { Text("Width") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit) },
                        label = { Text("Height") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (kind == ProjectKind.Animation) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fps,
                            onValueChange = { fps = it.filter(Char::isDigit) },
                            label = { Text("FPS") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = frames,
                            onValueChange = { frames = it.filter(Char::isDigit) },
                            label = { Text("Frames") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniChip("Transparent", transparent) { transparent = !transparent }
                    MiniChip("White background", !transparent && background == 0xFFFFFFFF.toInt()) {
                        transparent = false
                        background = 0xFFFFFFFF.toInt()
                    }
                    MiniChip("Dark canvas", !transparent && background != 0xFFFFFFFF.toInt()) {
                        transparent = false
                        background = 0xFF121316.toInt()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    kind,
                    title.ifBlank { "${kind.name} ${System.currentTimeMillis() % 1000}" },
                    width.toIntOrNull()?.coerceIn(16, 8192) ?: 2048,
                    height.toIntOrNull()?.coerceIn(16, 8192) ?: 2048,
                    fps.toIntOrNull()?.coerceIn(1, 60) ?: 24,
                    frames.toIntOrNull()?.coerceIn(1, 2000) ?: 24,
                    transparent,
                    background,
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MiniChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ForgeColors.Accent else ForgeColors.GraphiteElevated)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color(0xFF0B0C0F) else ForgeColors.TextSecondary,
        )
    }
}

/** Short, honest onboarding: what the app does and where things live. */
@Composable
fun OnboardingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to ArtForge") },
        text = {
            Column {
                Text(
                    "A complete offline art studio: layered painting, vector shapes, pixel art, " +
                        "comic pages and frame-by-frame 2D animation with onion skin and audio sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeColors.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                listOf(
                    "Two fingers pan and pinch-zoom · twist to rotate",
                    "Double tap the canvas to fit it on screen",
                    "Stylus pressure, tilt and rotation are supported",
                    "Ctrl+Z undo · Ctrl+S save · B brush · E eraser · Space pan",
                    "Everything is stored locally — no account, no internet needed",
                ).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Start creating") } },
    )
}
