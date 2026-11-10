package ma.odema.rain.ui.odema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ma.odema.core.ops.Photo
import ma.odema.rain.R
import ma.odema.rain.ui.MainViewModel
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos stay small (≈100 KB) so they sync over a weak 3G connection like any other record. */
object PhotoCodec {
    private const val MAX_SIDE = 1280

    fun encode(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 62, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun decode(photo: Photo): ImageBitmap? = runCatching {
        val bytes = Base64.decode(photo.jpegBase64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** Photos of a work or breakdown, with buttons to take one or pick from the gallery. */
@Composable
fun PhotoStrip(vm: MainViewModel, ownerId: String) {
    val context = LocalContext.current
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val photos = ops.data.photosOf(ownerId)
    var pending by remember { mutableStateOf<Uri?>(null) }
    var open by remember { mutableStateOf<Photo?>(null) }
    fun add(uri: Uri) { PhotoCodec.encode(context, uri)?.let { b64 -> vm.ops { addPhoto(ownerId, b64) } } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending?.let(::add) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::add) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleSmall)
        if (photos.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { p ->
                val img = remember(p.id) { PhotoCodec.decode(p) }
                if (img != null) Image(img, null, Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).clickable { open = p }, contentScale = ContentScale.Crop)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                pending = uri
                runCatching { camera.launch(uri) }
            }) { Text("📷 " + stringResource(R.string.photos_take)) }
            OutlinedButton(onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("🖼️ " + stringResource(R.string.photos_pick)) }
        }
    }
    open?.let { p ->
        val img = remember(p.id) { PhotoCodec.decode(p) }
        AlertDialog(
            onDismissRequest = { open = null },
            text = { img?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = {
                TextButton(onClick = { vm.ops { deletePhoto(p.id) }; open = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
