package com.espitman.sdm.storage

import com.espitman.sdm.domain.Download
import com.espitman.sdm.network.DownloadFilenameResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class PublishedDownloadDestination(
    val destinationPath: String,
    val destinationTreeUri: String?,
    val destinationDisplayLabel: String?,
    val fileName: String,
)

fun interface DownloadDestinationPublisher {
    fun afterLocalFinalize(download: Download, localFile: File): PublishedDownloadDestination

    object KeepLocal : DownloadDestinationPublisher {
        override fun afterLocalFinalize(
            download: Download,
            localFile: File,
        ) = PublishedDownloadDestination(
            destinationPath = download.destinationPath ?: localFile.absolutePath,
            destinationTreeUri = download.destinationTreeUri,
            destinationDisplayLabel = download.destinationDisplayLabel,
            fileName = download.fileName,
        )
    }
}

class SafDownloadDestinationPublisher(
    private val trees: UserTreeAccess,
    private val coordinator: SaveLocationCoordinator,
    private val appSpecificDirectory: () -> File,
) : DownloadDestinationPublisher {
    override fun afterLocalFinalize(
        download: Download,
        localFile: File,
    ): PublishedDownloadDestination {
        val treeUri = download.destinationTreeUri
        if (treeUri.isNullOrBlank()) {
            return DownloadDestinationPublisher.KeepLocal.afterLocalFinalize(download, localFile)
        }
        val inspection = trees.inspect(treeUri)
        if (inspection.state != UserTreeState.Writable) {
            return fallbackToAppPrivate(localFile, treeUri)
        }
        return try {
            val uniqueName = uniqueTreeName(download.fileName, inspection.childDisplayNames)
            val created = trees.createFile(
                treeUri = treeUri,
                mimeType = download.mimeType ?: "application/octet-stream",
                displayName = uniqueName,
            )
            try {
                trees.writeFrom(created.documentUri, localFile)
            } catch (error: IOException) {
                trees.deleteDocument(created.documentUri)
                throw error
            }
            if (!localFile.delete() && localFile.exists()) {
                localFile.deleteOnExit()
            }
            PublishedDownloadDestination(
                destinationPath = created.documentUri,
                destinationTreeUri = treeUri,
                destinationDisplayLabel = download.destinationDisplayLabel
                    ?: SaveLocationLabels.fromTree(treeUri, inspection.displayName),
                fileName = uniqueName,
            )
        } catch (_: IOException) {
            fallbackToAppPrivate(localFile, treeUri)
        }
    }

    private fun fallbackToAppPrivate(
        localFile: File,
        failedTreeUri: String,
    ): PublishedDownloadDestination {
        val current = coordinator.current().treeUri
        if (current.isNullOrBlank() || current == failedTreeUri) {
            coordinator.validatePersisted()
        }
        val promoted = promoteToAppSpecific(localFile)
        return PublishedDownloadDestination(
            destinationPath = promoted.absolutePath,
            destinationTreeUri = null,
            destinationDisplayLabel = null,
            fileName = promoted.name,
        )
    }

    private fun promoteToAppSpecific(localFile: File): File {
        val appDir = appSpecificDirectory()
        if (!appDir.exists()) appDir.mkdirs()
        val parent = localFile.parentFile
        if (parent != null && parent.canonicalFile == appDir.canonicalFile) {
            return localFile
        }
        val occupied = appDir.list()?.toSet().orEmpty()
        val uniqueName = uniqueOccupiedName(localFile.name, occupied)
        val target = File(appDir, uniqueName)
        if (target.exists()) {
            throw IOException("Destination already exists: ${target.path}")
        }
        try {
            Files.move(localFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(localFile.toPath(), target.toPath())
        }
        return target
    }

    private fun uniqueTreeName(fileName: String, occupied: Set<String>): String {
        if (fileName !in occupied) return fileName
        return uniqueOccupiedName(fileName, occupied)
    }

    private fun uniqueOccupiedName(fileName: String, occupied: Set<String>): String {
        val names = occupied.toMutableSet()
        return DownloadFilenameResolver.resolveReservation(fileName) { candidate ->
            if (candidate in names) {
                false
            } else {
                names.add(candidate)
                true
            }
        }
    }
}
