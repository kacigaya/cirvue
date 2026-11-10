package app.cirvue.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Holds the one image the user chose to share. It stays in memory: writing it to disk would
 * break the no-storage rule.
 */
object SharedCrop {
    const val MIME = "image/jpeg"

    private class Entry(val uri: Uri, val jpeg: ByteArray)

    @Volatile private var entry: Entry? = null

    /**
     * Makes [jpeg] the shared image and returns its URI. Each call gives a new random path, so
     * an app that got an earlier image cannot read this one.
     */
    fun put(context: Context, jpeg: ByteArray): Uri {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(context.packageName + ".share")
            .appendPath("${UUID.randomUUID()}.jpg")
            .build()
        entry = Entry(uri, jpeg)
        return uri
    }

    fun clear() {
        entry = null
    }

    internal fun jpeg(uri: Uri): ByteArray? = entry?.takeIf { it.uri == uri }?.jpeg
}

/**
 * Serves [SharedCrop] to the app the user picked in the share sheet. The provider is not
 * exported. Another app can read only the URI that the share intent grants. Data goes through
 * a pipe, so the file is not seekable.
 */
class CropProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = SharedCrop.jpeg(uri)?.let { SharedCrop.MIME }

    /** Share targets ask for the name and size before they read the image. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val jpeg = SharedCrop.jpeg(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                OpenableColumns.SIZE -> jpeg.size.toLong()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val jpeg = SharedCrop.jpeg(uri)
        if (jpeg == null || mode != "r") throw FileNotFoundException(uri.toString())
        return openPipeHelper(uri, SharedCrop.MIME, null, jpeg) { output, _, _, _, data ->
            try {
                // The helper closes the pipe after this returns.
                FileOutputStream(output.fileDescriptor).write(data)
            } catch (_: IOException) {
                // The reader closed the pipe early. There is nothing to clean up.
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
