package app.cirvue.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cirvue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max

/** Long side of a captured or picked image. Enough for OCR; keeps memory use low. */
private const val MAX_SIDE = 2048

@Composable
fun CameraScreen(onCaptured: (Bitmap) -> Unit, onExternal: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var unavailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { decode(context, uri) } }.onSuccess(onCaptured)
            busy = false
        }
    }
    val pick = {
        onExternal()
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !granted -> Message(
                title = stringResource(R.string.camera_permission_title),
                body = stringResource(R.string.camera_permission_body),
                action = stringResource(R.string.action_allow),
                onAction = {
                    onExternal()
                    permission.launch(Manifest.permission.CAMERA)
                },
                onPick = pick,
            )
            unavailable -> Message(
                title = null,
                body = stringResource(R.string.camera_unavailable),
                action = null,
                onAction = {},
                onPick = pick,
            )
            else -> Viewfinder(
                busy = busy,
                onUnavailable = { unavailable = true },
                onShutter = { capture ->
                    scope.launch {
                        busy = true
                        runCatching { capture() }.onSuccess(onCaptured)
                        busy = false
                    }
                },
                onPick = pick,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close), tint = Color.White)
        }
    }
}

@Composable
private fun Viewfinder(
    busy: Boolean,
    onUnavailable: () -> Unit,
    onShutter: (suspend () -> Bitmap) -> Unit,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            onUnavailable()
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            kotlinx.coroutines.awaitCancellation()
        } catch (e: IllegalArgumentException) {
            onUnavailable()
        } finally {
            provider.unbind(preview, capture)
        }
    }

    Box(Modifier.fillMaxSize()) {
        request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onPick, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_photo_library), stringResource(R.string.action_pick_photo))
            }
            LargeFloatingActionButton(
                onClick = { if (!busy) onShutter { capture.takeBitmap(context) } },
                shape = CircleShape,
                containerColor = Color.White,
                contentColor = Color.Black,
                modifier = Modifier.border(4.dp, Color.White.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_capture), Modifier.size(36.dp))
            }
            // Keeps the shutter centered.
            Spacer(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun Message(title: String?, body: String, action: String?, onAction: () -> Unit, onPick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp), Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_photo_camera),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            if (title != null) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
            }
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPick) { Text(stringResource(R.string.action_pick_photo)) }
                if (action != null) Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Captures into memory only. CameraX never writes a file with this callback. */
private suspend fun ImageCapture.takeBitmap(context: Context): Bitmap = suspendCoroutine { cont ->
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = image.use { it.toUprightBitmap() }
                cont.resume(result)
            }

            override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
        },
    )
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val source = toBitmap()
    val scale = (MAX_SIDE.toFloat() / max(source.width, source.height)).coerceAtMost(1f)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun decode(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        // Software memory: the selection crops and reads pixels.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = (MAX_SIDE.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
    }
}
