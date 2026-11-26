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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.eren.removebg.ui.theme.RemovebgTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    DisposableEffect(remover) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch { remover.close() }
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
        Box(modifier = Modifier.background(Color.White)) {
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
