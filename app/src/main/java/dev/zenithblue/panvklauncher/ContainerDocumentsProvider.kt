package dev.zenithblue.panvklauncher

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Exposes filesDir/container (Wine prefix etc.) to the system Files app via SAF. Root doc id = "root". */
class ContainerDocumentsProvider : DocumentsProvider() {
    private val base: File by lazy { File(context!!.filesDir, "container").also { it.mkdirs() }.canonicalFile }

    private fun file(id: String): File {
        val f = if (id == "root") base else File(base, id).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw SecurityException("outside container: $id")
        if (!f.exists() && f != base) throw FileNotFoundException(id)
        return f
    }

    private fun id(f: File) = if (f == base) "root" else f.path.removePrefix(base.path + File.separator)

    private fun row(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (dir) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE else flags = flags or Document.FLAG_SUPPORTS_WRITE
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, id(f))
            .add(Document.COLUMN_DISPLAY_NAME, if (f == base) "PanPlay Wine container" else f.name)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mime(f))
    }

    private fun mime(f: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "container").add(Root.COLUMN_TITLE, "PanPlay Wine container")
                .add(Root.COLUMN_DOCUMENT_ID, "root")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).also { row(it, file(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).also { c ->
            file(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { row(c, it) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(file(parentDocumentId), displayName.substringAfterLast('/'))
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        return id(f.canonicalFile)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (f == base) throw SecurityException("root")
        f.deleteRecursively()
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId)
        if (f == base) throw SecurityException("root")
        val n = File(f.parentFile, displayName.substringAfterLast('/'))
        if (!f.renameTo(n)) throw FileNotFoundException(displayName)
        return id(n.canonicalFile)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        runCatching { file(documentId).path.startsWith(file(parentDocumentId).path) }.getOrDefault(false)

    private companion object {
        val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE
        )
    }
}
