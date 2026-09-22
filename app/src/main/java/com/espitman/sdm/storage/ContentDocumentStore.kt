package com.espitman.sdm.storage

interface ContentDocumentStore {
    fun presence(documentUri: String, treeUri: String?): CompletedDestinationPresence
    fun rename(documentUri: String, treeUri: String?, displayName: String): ContentDocumentMutation
    fun delete(documentUri: String, treeUri: String?): ContentDocumentMutation
}

sealed interface ContentDocumentMutation {
    data class Success(
        val documentUri: String,
        val fileName: String,
    ) : ContentDocumentMutation

    data class Failure(
        val reason: Reason,
        val cause: Throwable? = null,
    ) : ContentDocumentMutation {
        enum class Reason {
            Missing,
            AccessUnavailable,
            Collision,
            Refused,
            Generic,
        }
    }
}

fun ContentDocumentMutation.Failure.renameMessage(): String = when (reason) {
    ContentDocumentMutation.Failure.Reason.Missing -> CompletedFileUserMessages.MISSING
    ContentDocumentMutation.Failure.Reason.AccessUnavailable ->
        CompletedFileUserMessages.ACCESS_UNAVAILABLE
    ContentDocumentMutation.Failure.Reason.Collision -> CompletedFileUserMessages.COLLISION
    ContentDocumentMutation.Failure.Reason.Refused,
    ContentDocumentMutation.Failure.Reason.Generic,
    -> CompletedFileUserMessages.RENAME_FAILED
}

fun ContentDocumentMutation.Failure.deleteMessage(): String = when (reason) {
    ContentDocumentMutation.Failure.Reason.Missing -> CompletedFileUserMessages.ALREADY_DELETED
    ContentDocumentMutation.Failure.Reason.AccessUnavailable ->
        CompletedFileUserMessages.ACCESS_UNAVAILABLE
    ContentDocumentMutation.Failure.Reason.Collision,
    ContentDocumentMutation.Failure.Reason.Refused,
    ContentDocumentMutation.Failure.Reason.Generic,
    -> CompletedFileUserMessages.DELETE_FAILED
}
