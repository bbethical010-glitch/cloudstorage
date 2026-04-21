package com.pratham.cloudstorage

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

data class ArchiveExtractionResult(
    val filesExtracted: Int,
    val directoriesCreated: Int,
    val archiveBytesRead: Long
)

private data class TarEntryHeader(
    val path: String,
    val size: Long,
    val typeFlag: Char
)

private class CountingInputStream(
    private val delegate: InputStream,
    private val onBytesRead: (Long) -> Unit
) : InputStream() {
    var totalRead = 0L
        private set

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            totalRead += 1
            onBytesRead(totalRead)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate.read(buffer, offset, length)
        if (bytesRead > 0) {
            totalRead += bytesRead
            onBytesRead(totalRead)
        }
        return bytesRead
    }

    override fun close() {
        delegate.close()
    }
}

class ArchiveStreamExtractor(
    private val contentResolver: ContentResolver,
    private val resolveOrCreateDirectory: (DocumentFile, String) -> DocumentFile,
    private val findFileReliable: (DocumentFile, String) -> DocumentFile?,
    private val sanitizeFilename: (String) -> String
) {
    fun extractTar(
        input: InputStream,
        baseDirectory: DocumentFile,
        onBytesRead: (Long) -> Unit = {}
    ): ArchiveExtractionResult {
        // Use BufferedInputStream for high-throughput reads
        val bufferedInput = input.buffered(128 * 1024)
        val countingInput = CountingInputStream(bufferedInput, onBytesRead)
        val directoryCache = mutableMapOf<String, DocumentFile>()
        directoryCache[""] = baseDirectory
        
        var filesExtracted = 0
        var directoriesCreated = 0

        while (true) {
            val header = readNextHeader(countingInput) ?: break
            val normalizedPath = normalizeEntryPath(header.path)

            when (header.typeFlag) {
                '5' -> {
                    ensureDirectory(baseDirectory, normalizedPath, directoryCache)
                    directoriesCreated += 1
                }
                '0', '\u0000' -> {
                    extractFileEntry(countingInput, baseDirectory, normalizedPath, header.size, directoryCache)
                    filesExtracted += 1
                }
                else -> {
                    discardEntryBytes(countingInput, header.size)
                    skipPadding(countingInput, header.size)
                }
            }
        }

        return ArchiveExtractionResult(
            filesExtracted = filesExtracted,
            directoriesCreated = directoriesCreated,
            archiveBytesRead = countingInput.totalRead
        )
    }

    private fun extractFileEntry(
        input: InputStream,
        baseDirectory: DocumentFile,
        path: String,
        size: Long,
        directoryCache: MutableMap<String, DocumentFile>
    ) {
        val segments = path.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "Archive entry path cannot be blank" }

        var targetDirectory = baseDirectory
        val parentPath = segments.dropLast(1).joinToString("/")
        
        if (parentPath.isNotEmpty()) {
            targetDirectory = getCachedDirectory(baseDirectory, parentPath, directoryCache)
        }

        val fileName = sanitizeFilename(segments.last())
        val existing = findFileReliable(targetDirectory, fileName)
        require(existing == null || !existing.isDirectory) {
            "Archive path conflict: '$fileName' already exists as a directory"
        }

        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        val fileUri = existing?.uri ?: (
            targetDirectory.createFile(mimeType, fileName)?.uri
                ?: android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    targetDirectory.uri,
                    mimeType,
                    fileName
                )
                ?: error("Failed to create archive file '$fileName'")
            )

        contentResolver.openFileDescriptor(fileUri, "rw")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.truncate(0)
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val readSize = minOf(buffer.size.toLong(), remaining).toInt()
                    val bytesRead = readFully(input, buffer, 0, readSize)
                    if (bytesRead <= 0) {
                        throw IllegalStateException("Unexpected end of archive stream")
                    }
                    channel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                    remaining -= bytesRead
                }
                channel.force(true)
            }
        } ?: throw IllegalStateException("Unable to open SAF file descriptor for '$fileName'")

        skipPadding(input, size)
    }

    private fun getCachedDirectory(
        baseDirectory: DocumentFile,
        path: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile {
        val segments = path.split('/').filter { it.isNotBlank() }
        var currentPath = ""
        var currentDir = baseDirectory
        
        for (segment in segments) {
            val nextPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            currentDir = cache.getOrPut(nextPath) {
                resolveOrCreateDirectory(currentDir, sanitizeFilename(segment))
            }
            currentPath = nextPath
        }
        return currentDir
    }

    private fun ensureDirectory(
        baseDirectory: DocumentFile, 
        path: String,
        directoryCache: MutableMap<String, DocumentFile>
    ) {
        getCachedDirectory(baseDirectory, path, directoryCache)
    }

    private fun normalizeEntryPath(path: String): String {
        val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trimStart('/')
        require(normalized.isNotBlank()) { "Archive entry path is blank" }

        val safeSegments = normalized.split('/').filter { it.isNotBlank() }.map { segment ->
            require(segment != "." && segment != "..") { "Archive path traversal detected: $path" }
            segment
        }

        require(safeSegments.isNotEmpty()) { "Archive entry path is empty after normalization" }
        return safeSegments.joinToString("/")
    }

    private fun readNextHeader(input: InputStream): TarEntryHeader? {
        val header = ByteArray(512)
        val bytesRead = readFully(input, header, 0, header.size)
        if (bytesRead == -1) return null
        if (bytesRead != header.size) {
            throw IllegalStateException("Truncated TAR header")
        }
        if (header.all { it.toInt() == 0 }) {
            return null
        }

        val name = parseString(header, 0, 100)
        val prefix = parseString(header, 345, 155)
        val path = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/")
        val size = parseOctal(header, 124, 12)
        val typeFlag = header[156].toInt().toChar()
        return TarEntryHeader(path = path, size = size, typeFlag = typeFlag)
    }

    private fun parseString(buffer: ByteArray, offset: Int, length: Int): String {
        val raw = buffer.copyOfRange(offset, offset + length)
        val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it == -1) raw.size else it }
        return raw.copyOf(end).toString(Charsets.UTF_8).trim()
    }

    private fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        val text = parseString(buffer, offset, length).trim()
        if (text.isBlank()) return 0L
        return text.toLong(8)
    }

    private fun discardEntryBytes(input: InputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val readSize = minOf(buffer.size.toLong(), remaining).toInt()
            val bytesRead = readFully(input, buffer, 0, readSize)
            if (bytesRead <= 0) {
                throw IllegalStateException("Unexpected end of archive stream")
            }
            remaining -= bytesRead
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((512 - (size % 512)) % 512).toInt()
        if (padding == 0) return

        var remaining = padding
        val buffer = ByteArray(512)
        while (remaining > 0) {
            val bytesRead = readFully(input, buffer, 0, minOf(buffer.size, remaining))
            if (bytesRead <= 0) {
                throw IllegalStateException("Unexpected end of TAR padding")
            }
            remaining -= bytesRead
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var totalRead = 0
        while (totalRead < length) {
            val bytesRead = input.read(buffer, offset + totalRead, length - totalRead)
            if (bytesRead == -1) {
                return if (totalRead == 0) -1 else totalRead
            }
            totalRead += bytesRead
        }
        return totalRead
    }
}
