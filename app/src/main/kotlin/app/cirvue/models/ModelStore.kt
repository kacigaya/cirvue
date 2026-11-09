package app.cirvue.models

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface PackState {
    data object Missing : PackState
    /** [progress] is 0 to 1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : PackState
    data object Verifying : PackState
    data object Installed : PackState
    data class Failed(val reason: FailReason) : PackState
}

enum class FailReason { Network, Storage, Checksum }

/**
 * Downloads, verifies, and deletes model packs. The system `DownloadManager` does the transfer,
 * so it survives app restarts and shows its own notification. Finished files are hashed while
 * they move into internal storage; a file with the wrong SHA-256 is deleted, never used.
 */
class ModelStore(private val context: Context) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val ids = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val root = File(context.filesDir, "models")

    private val _states = MutableStateFlow(ModelPack.entries.associateWith(::installedOrMissing))
    val states: StateFlow<Map<ModelPack, PackState>> = _states.asStateFlow()

    fun file(pack: ModelPack, name: String) = File(File(root, pack.dir), name)

    fun isInstalled(pack: ModelPack): Boolean = pack.files.all { installed(pack, it) }

    val anyActive: Boolean
        get() = _states.value.values.any { it is PackState.Downloading || it is PackState.Verifying }

    suspend fun download(pack: ModelPack) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val missing = pack.files.filter { !installed(pack, it) && idOf(pack, it) == null }
            val staging = context.getExternalFilesDir(null)
            // All staged files, plus one installed copy while it is hashed.
            val needed = missing.sumOf { it.size } + (missing.maxOfOrNull { it.size } ?: 0)
            if (staging == null || !hasSpaceFor(needed)) {
                set(pack, PackState.Failed(FailReason.Storage))
                return@withContext
            }
            for (f in missing) {
                staging(pack, f)?.delete()
                val request = DownloadManager.Request(f.url.toUri())
                    .setTitle(f.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, null, stagingPath(pack, f))
                ids.edit { putLong(key(pack, f), dm.enqueue(request)) }
            }
            set(pack, PackState.Downloading(null))
        }
        refresh()
    }

    /** Cancels a running download, or deletes an installed pack. */
    suspend fun remove(pack: ModelPack) = mutex.withLock {
        withContext(Dispatchers.IO) {
            clearDownloads(pack)
            File(root, pack.dir).deleteRecursively()
            set(pack, PackState.Missing)
        }
    }

    /** Reads download progress and installs finished files. Call it while the UI is visible. */
    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { ModelPack.entries.forEach { refresh(it) } }
        } finally {
            mutex.unlock()
        }
    }

    private fun refresh(pack: ModelPack) {
        if (isInstalled(pack)) return set(pack, PackState.Installed)
        val pending = pack.files.filter { !installed(pack, it) }
        val downloads = pending.mapNotNull { f -> idOf(pack, f)?.let { f to query(it) } }
        if (downloads.isEmpty()) {
            // Keep a failure visible until the user acts on it.
            if (_states.value[pack] !is PackState.Failed) set(pack, PackState.Missing)
            return
        }
        if (downloads.any { (_, s) -> s == null || s.status == DownloadManager.STATUS_FAILED }) {
            clearDownloads(pack)
            return set(pack, PackState.Failed(FailReason.Network))
        }
        val finished = downloads.filter { (_, s) -> s!!.status == DownloadManager.STATUS_SUCCESSFUL }
        if (finished.size == downloads.size) {
            set(pack, PackState.Verifying)
            for ((f, _) in finished) {
                install(pack, f)?.let { reason ->
                    clearDownloads(pack)
                    return set(pack, PackState.Failed(reason))
                }
                ids.edit { remove(key(pack, f)) }
            }
            return set(pack, if (isInstalled(pack)) PackState.Installed else PackState.Missing)
        }
        val total = pack.files.sumOf { it.size }
        val done = pack.files.filter { installed(pack, it) }.sumOf { it.size } +
            downloads.sumOf { (_, s) -> s!!.downloaded }
        set(pack, PackState.Downloading((done.toFloat() / total).coerceIn(0f, 1f)))
    }

    /**
     * Hashes the staged file while copying it into internal storage. Returns null on success,
     * or why the file cannot be used.
     */
    private fun install(pack: ModelPack, f: ModelFile): FailReason? {
        val source = staging(pack, f) ?: return FailReason.Storage
        val target = file(pack, f.name)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
        } catch (_: IOException) {
            // Usually a full disk. The caller removes the staged file.
            temp.delete()
            return FailReason.Storage
        }
        source.delete()
        val ok = digest.digest().toHex() == f.sha256 && temp.length() == f.size
        if (!ok || !temp.renameTo(target)) {
            temp.delete()
            return FailReason.Checksum
        }
        return null
    }

    private data class Status(val status: Int, val downloaded: Long)

    private fun query(id: Long): Status? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            Status(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            )
        }

    private fun clearDownloads(pack: ModelPack) {
        for (f in pack.files) {
            idOf(pack, f)?.let { dm.remove(it) }
            ids.edit { remove(key(pack, f)) }
            staging(pack, f)?.delete()
        }
    }

    private fun hasSpaceFor(bytes: Long): Boolean {
        root.mkdirs()
        return StatFs(root.path).availableBytes > bytes + MARGIN_BYTES
    }

    private fun installed(pack: ModelPack, f: ModelFile): Boolean = file(pack, f.name).length() == f.size

    private fun installedOrMissing(pack: ModelPack): PackState =
        if (isInstalled(pack)) PackState.Installed else PackState.Missing

    private fun idOf(pack: ModelPack, f: ModelFile): Long? =
        ids.getLong(key(pack, f), -1L).takeIf { it >= 0 }

    private fun staging(pack: ModelPack, f: ModelFile): File? =
        context.getExternalFilesDir(null)?.let { File(it, stagingPath(pack, f)) }

    private fun set(pack: ModelPack, state: PackState) = _states.update { it + (pack to state) }

    private companion object {
        const val MARGIN_BYTES = 64L shl 20
        fun key(pack: ModelPack, f: ModelFile) = "${pack.dir}/${f.name}"
        fun stagingPath(pack: ModelPack, f: ModelFile) = "downloads/${pack.dir}-${f.name}.part"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
