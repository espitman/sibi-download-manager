package com.espitman.sdm.download

import java.io.File
import java.security.MessageDigest

/**
 * Deterministic `.part` path next to a reserved destination filename.
 * Fresh submission and resume must resolve the same file.
 */
object DownloadPartFile {

    fun forDestination(destinationFile: File): File {
        val parent = destinationFile.parentFile
            ?: throw IllegalArgumentException("Destination must have a parent directory: ${destinationFile.path}")
        return forResolvedFilename(parent, destinationFile.name)
    }

    fun forResolvedFilename(directory: File, resolvedFilename: String): File {
        require(resolvedFilename.isNotBlank()) { "Resolved filename cannot be blank" }
        require('/' !in resolvedFilename && '\\' !in resolvedFilename) {
            "Resolved filename cannot contain path separators"
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(resolvedFilename.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, ".sdm-$hash.part")
    }
}
