package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSecurityManifestTest {
    @Test
    fun manifestPermissionsStayMinimalAndComponentsStayPrivate() {
        val manifest = readProjectFile("src/main/AndroidManifest.xml")
        val declared = Regex("""android:name="(android\.permission\.[A-Z0-9_]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(NetworkPermissionPolicy.ALLOWED_MANIFEST_PERMISSIONS, declared)
        NetworkPermissionPolicy.DISALLOWED_BROAD_PERMISSIONS.forEach { permission ->
            assertFalse(manifest.contains(permission))
        }
        assertTrue(manifest.contains("""android:name=".download.DownloadTransferService""""))
        assertTrue(manifest.contains("""android:exported="false""""))
        assertTrue(manifest.contains("android.support.FILE_PROVIDER_PATHS"))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/sdm_backup_rules""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/sdm_data_extraction_rules""""))
    }

    @Test
    fun backupRulesExcludeTheDownloadDatabase() {
        val dbName = "sdm-downloads.db"
        val backup = readProjectFile("src/main/res/xml/sdm_backup_rules.xml")
        val extraction = readProjectFile("src/main/res/xml/sdm_data_extraction_rules.xml")
        assertTrue(backup.contains("""domain="database""""))
        assertTrue(backup.contains(dbName))
        assertTrue(extraction.contains("<cloud-backup>"))
        assertTrue(extraction.contains("<device-transfer>"))
        assertTrue(extraction.contains(dbName))
        val providerPaths = readProjectFile("src/main/res/xml/sdm_file_paths.xml")
        assertFalse(providerPaths.contains("path=\".\""))
        assertFalse(providerPaths.contains("path=\"/\""))
        assertTrue(providerPaths.contains("path=\"Download/\""))
        assertTrue(providerPaths.contains("path=\"Downloads/\""))
    }

    @Test
    fun productionSourcesDoNotCallLogOrPrintSensitiveTraces() {
        val roots = listOf(
            File("src/main/java"),
            File("../app/src/main/java"),
        )
        val sourceRoot = roots.first { it.isDirectory }
        val leaked = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                FORBIDDEN_LOG_PATTERNS.mapNotNull { pattern ->
                    if (pattern.containsMatchIn(text)) {
                        "${file.name}: ${pattern.pattern}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), leaked)
    }

    private fun readProjectFile(relativeFromApp: String): String {
        val candidates = listOf(
            File(relativeFromApp),
            File("app/$relativeFromApp"),
            File("../app/$relativeFromApp"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Missing $relativeFromApp under ${File(".").canonicalPath}")
        return file.readText()
    }

    companion object {
        private val FORBIDDEN_LOG_PATTERNS = listOf(
            Regex("""\bandroid\.util\.Log\b"""),
            Regex("""\bLog\.[deviw]\s*\("""),
            Regex("""\bprintln\s*\("""),
            Regex("""\bprintStackTrace\s*\("""),
            Regex("""\bHttpLoggingInterceptor\b"""),
            Regex("""\bSystem\.err\b"""),
        )
    }
}
