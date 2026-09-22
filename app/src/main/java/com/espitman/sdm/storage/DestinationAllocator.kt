package com.espitman.sdm.storage

import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.network.DownloadFilenameResolver
import java.io.File
import java.io.IOException

data class AllocatedDownloadDestination(
    val fileName: String,
    val destinationPath: String,
    val destinationTreeUri: String? = null,
    val destinationDisplayLabel: String? = null,
    val partFile: File,
)

fun interface DestinationAllocator {
    fun allocate(candidateFileName: String): AllocatedDownloadDestination
}

class AppPrivateDestinationAllocator(
    private val directory: () -> File,
    private val displayLabel: String? = null,
    private val treeUri: String? = null,
) : DestinationAllocator {
    override fun allocate(candidateFileName: String): AllocatedDownloadDestination {
        val baseDir = usableDirectory(directory())
        return reserveInDirectory(
            baseDir = baseDir,
            candidateFileName = candidateFileName,
            occupiedNames = existingNames(baseDir),
            destinationTreeUri = treeUri,
            destinationDisplayLabel = displayLabel,
        )
    }

    companion object {
        fun reserveInDirectory(
            baseDir: File,
            candidateFileName: String,
            occupiedNames: Set<String>,
            destinationTreeUri: String? = null,
            destinationDisplayLabel: String? = null,
        ): AllocatedDownloadDestination {
            val names = occupiedNames.toMutableSet()
            var reservedPart: File? = null
            var attempts = 0
            val resolved = DownloadFilenameResolver.resolveReservation(candidateFileName) { candidate ->
                attempts += 1
                if (attempts > MAX_ATTEMPTS) {
                    throw IOException(
                        "Failed to reserve a unique .part file for $candidateFileName in ${baseDir.absolutePath} after $MAX_ATTEMPTS attempts",
                    )
                }
                if (candidate in names) return@resolveReservation false
                val finalFile = File(baseDir, candidate)
                if (finalFile.exists()) return@resolveReservation false
                val partFile = DownloadPartFile.forResolvedFilename(baseDir, candidate)
                try {
                    if (partFile.createNewFile()) {
                        reservedPart = partFile
                        names.add(candidate)
                        true
                    } else {
                        false
                    }
                } catch (error: IOException) {
                    throw IOException(
                        "Failed to reserve .part file in ${baseDir.absolutePath}: ${error.message}",
                        error,
                    )
                }
            }
            val part = reservedPart
                ?: throw IOException("Failed to reserve a unique .part file for $candidateFileName in ${baseDir.absolutePath}")
            return AllocatedDownloadDestination(
                fileName = resolved,
                destinationPath = File(baseDir, resolved).absolutePath,
                destinationTreeUri = destinationTreeUri,
                destinationDisplayLabel = destinationDisplayLabel,
                partFile = part,
            )
        }

        private fun usableDirectory(directory: File): File {
            if (!directory.exists()) {
                val created = directory.mkdirs()
                if (!created && !directory.exists()) {
                    throw IOException("Failed to create download directory: ${directory.absolutePath}")
                }
            }
            if (!directory.isDirectory) {
                throw IOException("Download target path is not a directory: ${directory.absolutePath}")
            }
            return directory
        }

        private fun existingNames(directory: File): Set<String> =
            directory.list()?.toSet().orEmpty()

        private const val MAX_ATTEMPTS = 1000
    }
}

class SaveLocationDestinationAllocator(
    private val coordinator: SaveLocationCoordinator,
    private val appSpecificDirectory: () -> File,
    private val trees: UserTreeAccess,
) : DestinationAllocator {
    override fun allocate(candidateFileName: String): AllocatedDownloadDestination {
        val resolved = coordinator.resolveForNewDownload()
        return when (val location = resolved.location) {
            is ActiveSaveLocation.AppSpecific -> AppPrivateDestinationAllocator(appSpecificDirectory).allocate(
                candidateFileName,
            )
            is ActiveSaveLocation.UserTree -> allocateUserTree(location, candidateFileName)
        }
    }

    private fun allocateUserTree(
        location: ActiveSaveLocation.UserTree,
        candidateFileName: String,
    ): AllocatedDownloadDestination {
        val inspection = trees.inspect(location.treeUri)
        if (inspection.state != UserTreeState.Writable) {
            coordinator.validatePersisted()
            return AppPrivateDestinationAllocator(appSpecificDirectory).allocate(candidateFileName)
        }
        val stagingDir = stagingDirectory(appSpecificDirectory())
        return AppPrivateDestinationAllocator.reserveInDirectory(
            baseDir = stagingDir,
            candidateFileName = candidateFileName,
            occupiedNames = inspection.childDisplayNames + existingNames(stagingDir),
            destinationTreeUri = location.treeUri,
            destinationDisplayLabel = location.displayLabel,
        )
    }

    companion object {
        const val STAGING_DIRECTORY_NAME = ".sdm-saf-staging"

        fun stagingDirectory(appSpecificDirectory: File): File {
            val staging = File(appSpecificDirectory, STAGING_DIRECTORY_NAME)
            if (!staging.exists()) staging.mkdirs()
            if (!staging.exists() || !staging.isDirectory) {
                throw IOException("Failed to create download staging directory: ${staging.absolutePath}")
            }
            return staging
        }

        private fun existingNames(directory: File): Set<String> =
            directory.list()?.toSet().orEmpty()
    }
}
