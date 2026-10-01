package com.espitman.sdm.storage

/** Specific origin MIME types take precedence; generic MIME uses the sanitized metadata filename. */
enum class FileCategory(val label:String) {
    VIDEO("Video"), AUDIO("Audio"), DOCUMENTS("Documents"), ARCHIVES("Archives"), IMAGES("Images"), OTHER("Other");
    companion object {
        fun classify(fileName:String,mimeType:String?):FileCategory {
            val mime=mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
            if(!mime.isNullOrBlank() && mime !in setOf("application/octet-stream","binary/octet-stream","application/download","application/x-download")) {
                return when {
                    mime.startsWith("video/")->VIDEO
                    mime.startsWith("audio/")->AUDIO
                    mime.startsWith("image/")->IMAGES
                    mime.startsWith("text/") || mime in setOf("application/pdf","application/rtf","application/msword","application/vnd.ms-excel","application/vnd.ms-powerpoint","application/epub+zip") || mime.startsWith("application/vnd.openxmlformats-officedocument.") || mime.startsWith("application/vnd.oasis.opendocument.")->DOCUMENTS
                    mime in setOf("application/zip","application/x-zip-compressed","application/x-rar-compressed","application/vnd.rar","application/x-7z-compressed","application/gzip","application/x-gzip","application/x-tar","application/x-bzip2","application/x-xz")->ARCHIVES
                    else->OTHER
                }
            }
            return when(fileName.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)) {
                "mp4","mkv","avi","mov","webm","m4v","3gp","ts","mpeg","mpg"->VIDEO
                "mp3","m4a","aac","flac","wav","ogg","opus","wma"->AUDIO
                "pdf","txt","doc","docx","xls","xlsx","ppt","pptx","rtf","epub","csv","odt","ods"->DOCUMENTS
                "zip","rar","7z","tar","gz","bz2","xz","tgz"->ARCHIVES
                "jpg","jpeg","png","gif","webp","heic","heif","svg","bmp","tif","tiff","avif"->IMAGES
                else->OTHER
            }
        }
    }
}
data class CategoryFolderRule(val treeUri:String,val label:String)
data class CategoryFolderSettings(val enabled:Boolean=false,val rules:Map<FileCategory,CategoryFolderRule> = emptyMap())
