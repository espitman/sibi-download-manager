package com.espitman.sdm.torrent

import java.net.URI
import java.net.URLDecoder

/** A magnet is a content identifier, never an HTTP request or a filesystem path. */
object TorrentMagnet {
    fun identities(raw: String): Set<String> = runCatching {
        val uri=URI(raw.trim())
        val query=uri.rawQuery ?: uri.rawSchemeSpecificPart.removePrefix("?")
        query.split('&').mapNotNull { part ->
            val pair=part.split('=',limit=2)
            if (pair.size==2 && pair[0]=="xt") URLDecoder.decode(pair[1],"UTF-8").lowercase() else null
        }.toSet()
    }.getOrDefault(emptySet())
    fun isValid(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("magnet", true) || uri.rawFragment != null || uri.rawAuthority != null) return false
        val query = uri.rawQuery ?: uri.rawSchemeSpecificPart?.takeIf { it.startsWith('?') }?.substring(1) ?: return false
        val identities = query.split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "xt") URLDecoder.decode(pair[1], "UTF-8") else null
        }
        identities.any {
            when {
                it.startsWith("urn:btih:", true) -> it.substring(9).matches(Regex("(?i)([0-9a-f]{40}|[a-z2-7]{32})"))
                it.startsWith("urn:btmh:", true) -> it.substring(9).matches(Regex("(?i)1220[0-9a-f]{64}"))
                else -> false
            }
        }
    }.getOrDefault(false)
}

object TorrentPaths {
    fun validate(path: String): String {
        require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) { "Unsafe path in torrent" }
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none(Char::isISOControl) }) { "Unsafe path in torrent" }
        return path
    }
    fun resolve(root: java.io.File, path: String): java.io.File {
        val file = java.io.File(root, validate(path)).canonicalFile
        require(file.path.startsWith(root.canonicalPath + java.io.File.separator)) { "Torrent file escapes its folder" }
        return file
    }
}
