/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.compose.about

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.axion.compose.R
import com.android.axion.compose.preferences.SettingsType
import com.android.axion.compose.preferences.rememberSettingString
import com.android.axion.compose.scaffold.AxionScaffold
import com.android.axion.deviceinfo.DeviceInfo
import com.android.axion.deviceinfo.DeviceInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable
private class StableImageBitmap(val bitmap: ImageBitmap?)

@Composable
fun AxionAboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onEditDeviceName: (String) -> Unit,
    onMaintainerClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val density = LocalDensity.current
    var deviceInfo by remember(context) { mutableStateOf<DeviceInfo?>(null) }
    var loadFailed by remember(context) { mutableStateOf(false) }
    val deviceName by rememberSettingString(
        key = Settings.Global.DEVICE_NAME,
        type = SettingsType.GLOBAL,
        default = Build.MODEL,
    )
    var showEditDialog by remember { mutableStateOf(false) }
    var wallpaperHolder by remember { mutableStateOf(StableImageBitmap(null)) }

    // Camera and storage queries must not block the Compose/UI thread.
    LaunchedEffect(context) {
        deviceInfo = withContext(Dispatchers.IO) {
            runCatching { DeviceInfoProvider.getDeviceInfo(context) }.getOrNull()
        }
        loadFailed = deviceInfo == null
    }

    LaunchedEffect(context, isTablet, density) {
        val result = withContext(Dispatchers.IO) {
            try {
                val original = (WallpaperManager.getInstance(context).drawable as? BitmapDrawable)
                    ?.bitmap ?: return@withContext null
                val targetHeight = with(density) { 240.dp.roundToPx() }.coerceAtLeast(1)
                val targetWidth = (targetHeight * if (isTablet) 1.4f else 0.48f)
                    .toInt().coerceAtLeast(1)
                // Crop before scaling; don't allocate a huge bitmap for a large wallpaper.
                val aspect = targetWidth.toFloat() / targetHeight
                val cropWidth = minOf(original.width, (original.height * aspect).toInt())
                    .coerceAtLeast(1)
                val cropHeight = minOf(original.height, (original.width / aspect).toInt())
                    .coerceAtLeast(1)
                val crop = Bitmap.createBitmap(
                    original,
                    (original.width - cropWidth) / 2,
                    (original.height - cropHeight) / 2,
                    cropWidth,
                    cropHeight,
                )
                val scaled = Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true)
                if (scaled !== crop && crop !== original) crop.recycle()
                scaled.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
        wallpaperHolder = StableImageBitmap(result)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        AxionScaffold(
            title = stringResource(R.string.ax_about_title),
            onBackClick = onNavigateBack,
            collapsedByDefault = false,
        ) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val info = deviceInfo
                if (info == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(
                                if (loadFailed) R.string.ax_about_unavailable
                                else R.string.ax_about_loading
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    BannerSection(
                        version = info.axionVersion,
                        androidVersion = info.androidVersion,
                        buildType = info.axionBuildType,
                        maintainer = info.maintainer,
                        deviceName = deviceName,
                        wallpaperHolder = wallpaperHolder,
                        isTablet = isTablet,
                        onDeviceNameClick = { showEditDialog = true },
                        onMaintainerClick = onMaintainerClick,
                    )
                    SpecsGrid(
                        processor = info.processor,
                        rearCamera = stringResource(R.string.ax_about_rear, info.rearCamera),
                        frontCamera = stringResource(R.string.ax_about_front, info.frontCamera),
                        ram = info.totalRam,
                        storage = if (info.storageUsed == "—" || info.storageTotal == "—") {
                            info.storageTotal
                        } else "${info.storageUsed} / ${info.storageTotal}",
                        battery = info.batteryCapacity,
                        screen = info.screenSize.ifBlank { info.screenResolution },
                        isTablet = isTablet,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showEditDialog) {
        DeviceNameDialog(
            currentName = deviceName,
            onConfirm = { newName ->
                DeviceInfoProvider.setDeviceName(context, newName)
                onEditDeviceName(newName)
            },
            onDismiss = { showEditDialog = false },
        )
    }
}

@Composable
private fun BannerSection(
    version: String,
    androidVersion: String,
    buildType: String,
    maintainer: String,
    deviceName: String,
    wallpaperHolder: StableImageBitmap,
    isTablet: Boolean,
    onDeviceNameClick: () -> Unit,
    onMaintainerClick: () -> Unit,
) {
    val cardRadius = RoundedCornerShape(24.dp)
    val surfaceColor = MaterialTheme.colorScheme.surfaceBright
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The AxionOS logo belongs to the artwork. The build, maintainer and
        // Android release are live text, not baked-in data that goes stale.
        Surface(
            modifier = Modifier.fillMaxWidth().height(if (isTablet) 320.dp else 252.dp),
            shape = cardRadius,
            color = surfaceColor,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(R.drawable.axion_about_banner),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BannerPill(version)
                        BannerPill(
                            maintainer,
                            modifier = Modifier.clickable(
                                enabled = maintainer != "—",
                                onClick = onMaintainerClick,
                            ),
                        )
                    }
                    BannerPill("Android $androidVersion")
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDeviceNameClick),
            shape = cardRadius,
            color = surfaceColor,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    text = stringResource(R.string.ax_about_device_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BannerPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.33f),
        shape = RoundedCornerShape(50.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DeviceIllustration(
    wallpaperHolder: StableImageBitmap,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val animation = rememberInfiniteTransition(label = "about_device_float")
    val floatY by animation.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "device_float",
    )
    val frameRadius = if (isTablet) 18.dp else 20.dp
    val bezel = if (isTablet) 2.dp else 3.dp
    Surface(
        modifier = modifier.offset(y = floatY.dp)
            .aspectRatio(if (isTablet) 1.4f else 0.48f),
        shape = RoundedCornerShape(frameRadius),
        color = Color(0xFF1A1A1A),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(bezel),
            shape = RoundedCornerShape(frameRadius - bezel),
            color = Color(0xFF1A1A2E),
        ) {
            wallpaperHolder.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SpecsGrid(
    processor: String,
    rearCamera: String,
    frontCamera: String,
    ram: String,
    storage: String,
    battery: String,
    screen: String,
    isTablet: Boolean,
) {
    val gap = 4.dp
    val large = 24.dp
    val small = 6.dp
    val color = MaterialTheme.colorScheme.surfaceBright
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        if (isTablet) {
            SpecRowCard(color, RoundedCornerShape(topStart = large, topEnd = large, bottomStart = small, bottomEnd = small)) {
                SpecItem(Modifier.weight(1f), Icons.Outlined.Memory, stringResource(R.string.ax_about_processor), processor)
                SpecItem(Modifier.weight(1f), Icons.Outlined.CameraAlt, stringResource(R.string.ax_about_camera), "$rearCamera\n$frontCamera")
                SpecItem(Modifier.weight(1f), Icons.Outlined.DeveloperBoard, stringResource(R.string.ax_about_ram), ram)
            }
            SpecRowCard(color, RoundedCornerShape(topStart = small, topEnd = small, bottomStart = large, bottomEnd = large)) {
                SpecItem(Modifier.weight(1f), Icons.Outlined.GridView, stringResource(R.string.ax_about_storage), storage)
                SpecItem(Modifier.weight(1f), Icons.Outlined.BatteryStd, stringResource(R.string.ax_about_battery), battery)
                SpecItem(Modifier.weight(1f), Icons.Outlined.Smartphone, stringResource(R.string.ax_about_screen), screen)
            }
        } else {
            SpecRowCard(color, RoundedCornerShape(topStart = large, topEnd = large, bottomStart = small, bottomEnd = small)) {
                SpecItem(Modifier.weight(1f), Icons.Outlined.Memory, stringResource(R.string.ax_about_processor), processor)
                SpecItem(Modifier.weight(1f), Icons.Outlined.CameraAlt, stringResource(R.string.ax_about_camera), "$rearCamera\n$frontCamera")
            }
            SpecRowCard(color, RoundedCornerShape(small)) {
                SpecItem(Modifier.weight(1f), Icons.Outlined.DeveloperBoard, stringResource(R.string.ax_about_ram), ram)
                SpecItem(Modifier.weight(1f), Icons.Outlined.GridView, stringResource(R.string.ax_about_storage), storage)
            }
            SpecRowCard(color, RoundedCornerShape(topStart = small, topEnd = small, bottomStart = large, bottomEnd = large)) {
                SpecItem(Modifier.weight(1f), Icons.Outlined.BatteryStd, stringResource(R.string.ax_about_battery), battery)
                SpecItem(Modifier.weight(1f), Icons.Outlined.Smartphone, stringResource(R.string.ax_about_screen), screen)
            }
        }
    }
}

@Composable
private fun SpecRowCard(
    color: Color,
    shape: RoundedCornerShape,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = color) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SpecItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
) {
    Column(modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.height(12.dp))
        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceDetailsSection(
    androidVersion: String,
    securityPatch: String,
    buildDate: String,
    kernelVersion: String,
    onSeeAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        Text(
            text = stringResource(R.string.ax_about_device_details),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        DetailLine(stringResource(R.string.ax_about_android_version), androidVersion)
        DetailLine(stringResource(R.string.ax_about_security_patch), securityPatch)
        DetailLine(stringResource(R.string.ax_about_build_date), buildDate)
        DetailLine(stringResource(R.string.ax_about_kernel), kernelVersion)
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onSeeAll)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ax_about_see_all),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailLine(title: String, value: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusChip(
    buildType: String,
    maintainer: String,
    onMaintainerClick: () -> Unit,
) {
    val official = buildType.equals("OFFICIAL", ignoreCase = true)
    val tint = if (official) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
    val bg = if (official) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (official) {
                Icon(Icons.Outlined.Verified, null, modifier = Modifier.size(13.dp), tint = tint)
            }
            Text(
                text = buildType,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Surface(
        modifier = Modifier.clickable(enabled = maintainer != "—", onClick = onMaintainerClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = maintainer,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp).basicMarquee(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DeviceNameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ax_about_edit_device_name)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.ax_about_device_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onConfirm(text.trim())
                onDismiss()
            }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.ax_about_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ax_about_cancel))
            }
        },
    )
}
