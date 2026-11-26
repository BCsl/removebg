package dev.eren.removebg

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.eren.removebg.AutoRemover.MlKitStatus
import dev.eren.removebg.ui.theme.RemovebgTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemovebgTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RemoveBackground()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveBackground() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inputImage by remember { mutableStateOf<Bitmap?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                inputImage =
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            } else {
                Log.d("PhotoPicker", "No media selected")
            }
        })

    var preferMlKit by remember { mutableStateOf(true) }
    val image = inputImage
    var outputImage by remember(image, preferMlKit) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(image, preferMlKit) { mutableStateOf(image != null) }
    var showOriginal by remember(image, preferMlKit) { mutableStateOf(false) }
    val remover = remember(context) { AutoRemover(context) }
    var mlKitStatus by remember(remover) { mutableStateOf<MlKitStatus?>(null) }

    DisposableEffect(remover) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch { remover.close() }
        }
    }

    LaunchedEffect(lifecycle, remover) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val status = remover.getMlKitStatus()
                mlKitStatus = status
                if (status == MlKitStatus.READY || status == MlKitStatus.PLAY_SERVICES_UNAVAILABLE) break
                delay(5_000)
            }
        }
    }

    LaunchedEffect(image, preferMlKit, remover) {
        if (image == null) return@LaunchedEffect
        try {
            outputImage = remover.clearBackground(image, preferMlKit).first()
        } finally {
            loading = false
        }
    }

    Scaffold { paddingValues ->
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxWidth()
            ) {
                Button(onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Text(text = "Open Gallery")
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    ModelToggleButton("Auto", selected = preferMlKit) { preferMlKit = true }
                    ModelToggleButton("U2NetP", selected = !preferMlKit) { preferMlKit = false }
                }
                MlKitStatusText(mlKitStatus)
                Spacer(modifier = Modifier.height(16.dp))
                val output = outputImage
                if (loading) {
                    CircularProgressIndicator()
                } else if (image != null && output == null) {
                    Text(text = "抠图失败", color = MaterialTheme.colorScheme.error)
                } else if (image != null && output != null) {
                    Image(
                        bitmap = (if (showOriginal) image else output).asImageBitmap(),
                        contentDescription = "",
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showOriginal = !showOriginal
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun MlKitStatusText(status: MlKitStatus?) {
    val label = when (status) {
        null -> R.string.mlkit_status_checking
        MlKitStatus.READY -> R.string.mlkit_status_ready
        MlKitStatus.MODEL_NOT_READY -> R.string.mlkit_status_model_not_ready
        MlKitStatus.PLAY_SERVICES_UNAVAILABLE -> R.string.mlkit_status_play_services_unavailable
        MlKitStatus.CHECK_FAILED -> R.string.mlkit_status_check_failed
    }
    Text(
        text = stringResource(label),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ModelToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(text = label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text = label)
        }
    }
}
