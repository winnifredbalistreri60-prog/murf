package com.uzairansar.hermex.agent

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.uzairansar.hermex.ui.createExportDirectory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Шаринг, экспорт, Google Диск, Загрузки — без OAuth, через системные интенты. */
object AgentShare {
    const val DRIVE_PACKAGE = "com.google.android.apps.docs"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun safeFileName(name: String, fallback: String = "agent"): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(96).ifBlank { fallback }

    fun toast(context: Context, text: String) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MURF", text))
    }

    fun writeExport(context: Context, name: String, bytes: ByteArray): File {
        val dir = context.createExportDirectory("share")
        return File(dir, safeFileName(name)).apply { writeBytes(bytes) }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareText(context: Context, text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        context.startActivity(Intent.createChooser(intent, tr("Поделиться", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(context: Context, file: File, mime: String, title: String = tr("Поделиться файлом", "Share file")) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isDriveInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(DRIVE_PACKAGE, 0); true
    }.getOrDefault(false)

    /** «Сохранить в Google Диск»: системный диалог загрузки приложения Диск (без OAuth). */
    fun saveFileToDrive(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isDriveInstalled(context)) {
            try {
                context.startActivity(Intent(intent).setPackage(DRIVE_PACKAGE))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        toast(context, tr("Приложение Google Диск не найдено — выберите, куда сохранить", "Google Drive app not found — choose where to save"))
        context.startActivity(Intent.createChooser(intent, tr("Сохранить", "Save")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveTextToDrive(context: Context, text: String, baseName: String) {
        val f = writeExport(context, "${safeFileName(baseName)}.md", text.toByteArray())
        saveFileToDrive(context, f, "text/markdown")
    }

    /** Сохранить в общую папку «Загрузки/Agent». Возвращает человекочитаемый путь. */
    fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): String {
        val fileName = safeFileName(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Agent")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error(tr("Не удалось создать файл в Загрузках", "Could not create file in Downloads"))
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tr("Загрузки/Agent/$fileName", "Downloads/Agent/$fileName")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Agent")
            val target = runCatching { dir.mkdirs(); File(dir, fileName).apply { writeBytes(bytes) } }
                .getOrElse {
                    val alt = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    alt.writeBytes(bytes); alt
                }
            target.absolutePath
        }
    }

    /** Простой PDF из текста (A4, перенос строк). */
    fun textToPdf(context: Context, title: String, text: String): File {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 40
        val paint = TextPaint().apply { color = Color.BLACK; textSize = 10.5f; isAntiAlias = true }
        val titlePaint = TextPaint(paint).apply { textSize = 15f; isFakeBoldText = true }
        val width = pageW - margin * 2
        val body = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        val head = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, width).build()
        var line = 0; var pageNo = 1
        while (line < body.lineCount || pageNo == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val c = page.canvas
            var y = margin.toFloat()
            if (pageNo == 1) {
                c.save(); c.translate(margin.toFloat(), y); head.draw(c); c.restore(); y += head.height + 12
            }
            val avail = pageH - margin - y
            val startTop = if (line < body.lineCount) body.getLineTop(line) else 0
            var end = line
            while (end < body.lineCount && body.getLineBottom(end) - startTop <= avail) end++
            if (end == line && line < body.lineCount) end = line + 1
            if (line < body.lineCount) {
                c.save(); c.translate(margin.toFloat(), y - startTop)
                c.clipRect(0f, startTop.toFloat(), width.toFloat(), body.getLineBottom(end - 1).toFloat())
                body.draw(c); c.restore()
            }
            doc.finishPage(page)
            line = end; pageNo++
            if (body.lineCount == 0) break
        }
        val f = File(context.createExportDirectory("pdf"), "${safeFileName(title)}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Достаёт код из ```блоков``` (или весь текст, если блоков нет). */
    fun extractCode(text: String): String? {
        val blocks = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```").findAll(text).map { it.groupValues[1].trimEnd() }.toList()
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

/** Подтверждение перед запуском кода из сообщения в Termux (видимая сессия). */
fun confirmRunInTermux(context: android.content.Context, code: String) {
    val activity = context as? android.app.Activity
    val installed = Termux.isInstalled(context)
    val permitted = Termux.hasPermission(context)
    if (activity == null) { AgentShare.copy(context, code); return }
    val b = android.app.AlertDialog.Builder(activity)
        .setTitle(tr("Выполнить в Termux?", "Run in Termux?"))
        .setMessage(code.take(1500) + if (code.length > 1500) "\n…" else "")
        .setNegativeButton(tr("Отмена", "Cancel"), null)
        .setNeutralButton(tr("Копировать", "Copy")) { _, _ -> AgentShare.copy(context, code); AgentShare.toast(context, tr("Скопировано", "Copied")) }
    if (installed && permitted) {
        b.setPositiveButton(tr("Выполнить", "Run")) { _, _ -> Termux.runVisible(context, code + "\necho; read -p 'Готово. Enter — закрыть' _") }
    } else {
        b.setPositiveButton(if (installed) tr("Скопировать и открыть Termux", "Copy and open Termux") else tr("Нужен Termux", "Termux required")) { _, _ ->
            AgentShare.copy(context, code)
            context.packageManager.getLaunchIntentForPackage(Termux.PACKAGE)?.let { context.startActivity(it) }
                ?: AgentShare.toast(context, tr("Установите Termux: раздел «Телефон и Termux»", "Install Termux: see “Phone & Termux”"))
        }
    }
    b.show()
}
