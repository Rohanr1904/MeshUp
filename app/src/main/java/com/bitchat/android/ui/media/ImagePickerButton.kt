package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize
import java.io.File

/** Entries shown in the camera button menu. Photo only, never video. */
enum class PhotoMenuItem { TAKE_PHOTO, CHOOSE_PHOTO }

/** "Take photo" is hidden on devices without any camera. */
internal fun photoMenuItems(hasCamera: Boolean): List<PhotoMenuItem> =
    if (hasCamera) listOf(PhotoMenuItem.TAKE_PHOTO, PhotoMenuItem.CHOOSE_PHOTO)
    else listOf(PhotoMenuItem.CHOOSE_PHOTO)

/** True when the camera permission was denied and the system will no longer prompt. */
internal fun isCameraPermissionBlocked(shouldShowRationale: Boolean): Boolean = !shouldShowRationale

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit
) {
    val context = LocalContext.current
    var capturedImagePath by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) onImageReady(outPath)
        }
    }
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            // Downscale + correct orientation, then send; delete original
            val outPath = com.bitchat.android.features.media.ImageUtils.downscalePathAndSaveToAppFiles(context, path)
            if (!outPath.isNullOrBlank()) {
                onImageReady(outPath)
            }
            runCatching { File(path).delete() }
        } else {
            // Cleanup on cancel/failure
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ImagePickerButton", "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        } else {
            val blocked = isCameraPermissionBlocked(
                context.findActivity()?.let {
                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                } ?: false
            )
            Toast.makeText(
                context,
                if (blocked) com.bitchat.android.R.string.meshup_camera_permission_blocked
                else com.bitchat.android.R.string.meshup_camera_permission_needed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var menuOpen by remember { mutableStateOf(false) }

    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Shares the composer's button treatment so camera, microphone and send read as one set.
    Box {
        ComposerActionSurface(
            isActive = false,
            isPressed = isPressed,
            modifier = modifier.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { menuOpen = true },
                onLongClick = { if (hasCamera) takePhoto() else menuOpen = true }
            )
        ) { tint ->
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(com.bitchat.android.R.string.pick_image),
                tint = tint,
                modifier = Modifier.size(ComposerIconSize)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            photoMenuItems(hasCamera).forEach { item ->
                when (item) {
                    PhotoMenuItem.TAKE_PHOTO -> DropdownMenuItem(
                        text = { Text(stringResource(com.bitchat.android.R.string.meshup_take_photo)) },
                        onClick = { menuOpen = false; takePhoto() }
                    )
                    PhotoMenuItem.CHOOSE_PHOTO -> DropdownMenuItem(
                        text = { Text(stringResource(com.bitchat.android.R.string.meshup_choose_photo)) },
                        onClick = {
                            menuOpen = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }

    // No custom preview: native camera UI handles confirmation
}
