package com.uzairansar.hermex.agent

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Запуск сканера QR (ZXing embedded, Apache-2.0). Возвращает функцию «открыть камеру». */
@Composable
fun rememberMurfQrScanner(onResult: (String) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { latest.value(it) }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(tr("Наведите камеру на QR из «murf pair» или из MURF на другом устройстве", "Point the camera at the QR from `murf pair` or from MURF on another device"))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}

/** QR-код как картинка (для «Показать QR для другого устройства»). */
fun qrImageBitmap(text: String, sizePx: Int = 720): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) pixels[row + x] = if (matrix[x, y]) -0x1000000 else -0x1
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
