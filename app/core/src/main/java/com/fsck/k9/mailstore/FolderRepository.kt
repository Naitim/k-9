package com.fsck.k9.mailstore

import androidx.core.content.contentValuesOf
import com.fsck.k9.Account
import com.fsck.k9.Account.FolderMode
import com.fsck.k9.mail.FolderClass
import com.fsck.k9.mail.FolderType as RemoteFolderType

class FolderRepository(
    private val localStoreProvider: LocalStoreProvider,
    private val messageStoreManager: MessageStoreManager,
    private val account: Account
) {
    private val sortForDisplay =
        compareByDescending<DisplayFolder> { it.folder.type == FolderType.INBOX }
            .thenByDescending { it.folder.type == FolderType.OUTBOX }
            .thenByDescending { it.folder.type != FolderType.REGULAR }
            .thenByDescending { it.isInTopGroup }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.folder.name }

    fun getDisplayFolders(displayMode: FolderMode?): List<DisplayFolder> {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getDisplayFolders(
            displayMode = displayMode ?: account.folderDisplayMode,
            outboxFolderId = account.outboxFolderId
        ) { folder ->
            DisplayFolder(
                folder = Folder(
                    id = folder.id,
                    name = folder.name,
                    type = folderTypeOf(folder.id),
                    isLocalOnly = folder.isLocalOnly
                ),
                isInTopGroup = folder.isInTopGroup,
                unreadCount = folder.messageCount
            )
        }.sortedWith(sortForDisplay)
    }

    fun getFolder(folderId: Long): Folder? {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolder(folderId) { folder ->
            Folder(
                id = folder.id,
                name = folder.name,
                type = folderTypeOf(folder.id),
                isLocalOnly = folder.isLocalOnly
            )
        }
    }

    fun getFolderDetails(folderId: Long): FolderDetails? {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolder(folderId) { folder ->
            FolderDetails(
                folder = Folder(
                    id = folder.id,
                    name = folder.name,
                    type = folderTypeOf(folder.id),
                    isLocalOnly = folder.isLocalOnly
                ),
                isInTopGroup = folder.isInTopGroup,
                isIntegrate = folder.isIntegrate,
                syncClass = folder.syncClass,
                displayClass = folder.displayClass,
                notifyClass = folder.notifyClass,
                pushClass = folder.pushClass
            )
        }
    }

    fun getRemoteFolders(): List<RemoteFolder> {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolders(excludeLocalOnly = true) { folder ->
            RemoteFolder(
                id = folder.id,
                serverId = folder.serverId,
                name = folder.name,
                type = folder.type.toFolderType()
            )
        }
    }

    fun getRemoteFolderDetails(): List<RemoteFolderDetails> {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolders(excludeLocalOnly = true) { folder ->
            RemoteFolderDetails(
                folder = RemoteFolder(
                    id = folder.id,
                    serverId = folder.serverId,
                    name = folder.name,
                    type = folder.type.toFolderType()
                ),
                isInTopGroup = folder.isInTopGroup,
                isIntegrate = folder.isIntegrate,
                syncClass = folder.syncClass,
                displayClass = folder.displayClass,
                notifyClass = folder.notifyClass,
                pushClass = folder.pushClass
            )
        }
    }

    fun getFolderServerId(folderId: Long): String? {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolder(folderId) { folder ->
            folder.serverId
        }
    }

    fun getFolderId(folderServerId: String): Long? {
        val database = localStoreProvider.getInstance(account).database
        return database.execute(false) { db ->
            db.query(
                "folders",
                arrayOf("id"),
                "server_id = ?",
                arrayOf(folderServerId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }
    }

    fun isFolderPresent(folderId: Long): Boolean {
        val messageStore = messageStoreManager.getMessageStore(account)
        return messageStore.getFolder(folderId) { true } ?: false
    }

    fun updateFolderDetails(folderDetails: FolderDetails) {
        val database = localStoreProvider.getInstance(account).database
        database.execute(false) { db ->
            val contentValues = contentValuesOf(
                "top_group" to folderDetails.isInTopGroup,
                "integrate" to folderDetails.isIntegrate,
                "poll_class" to folderDetails.syncClass.name,
                "display_class" to folderDetails.displayClass.name,
                "notify_class" to folderDetails.notifyClass.name,
                "push_class" to folderDetails.pushClass.name
            )
            db.update("folders", contentValues, "id = ?", arrayOf(folderDetails.folder.id.toString()))
        }
    }

    private fun folderTypeOf(folderId: Long) = when (folderId) {
        account.inboxFolderId -> FolderType.INBOX
        account.outboxFolderId -> FolderType.OUTBOX
        account.sentFolderId -> FolderType.SENT
        account.trashFolderId -> FolderType.TRASH
        account.draftsFolderId -> FolderType.DRAFTS
        account.archiveFolderId -> FolderType.ARCHIVE
        account.spamFolderId -> FolderType.SPAM
        else -> FolderType.REGULAR
    }

    private fun RemoteFolderType.toFolderType(): FolderType = when (this) {
        RemoteFolderType.REGULAR -> FolderType.REGULAR
        RemoteFolderType.INBOX -> FolderType.INBOX
        RemoteFolderType.OUTBOX -> FolderType.REGULAR // We currently don't support remote Outbox folders
        RemoteFolderType.DRAFTS -> FolderType.DRAFTS
        RemoteFolderType.SENT -> FolderType.SENT
        RemoteFolderType.TRASH -> FolderType.TRASH
        RemoteFolderType.SPAM -> FolderType.SPAM
        RemoteFolderType.ARCHIVE -> FolderType.ARCHIVE
    }

    fun setIncludeInUnifiedInbox(folderId: Long, includeInUnifiedInbox: Boolean) {
        val localStore = localStoreProvider.getInstance(account)
        val folder = localStore.getFolder(folderId)
        folder.isIntegrate = includeInUnifiedInbox
    }

    fun setDisplayClass(folderId: Long, folderClass: FolderClass) {
        val localStore = localStoreProvider.getInstance(account)
        val folder = localStore.getFolder(folderId)
        folder.displayClass = folderClass
    }

    fun setSyncClass(folderId: Long, folderClass: FolderClass) {
        val localStore = localStoreProvider.getInstance(account)
        val folder = localStore.getFolder(folderId)
        folder.syncClass = folderClass
    }

    fun setNotificationClass(folderId: Long, folderClass: FolderClass) {
        val localStore = localStoreProvider.getInstance(account)
        val folder = localStore.getFolder(folderId)
        folder.notifyClass = folderClass
    }
}

data class Folder(val id: Long, val name: String, val type: FolderType, val isLocalOnly: Boolean)

data class RemoteFolder(val id: Long, val serverId: String, val name: String, val type: FolderType)

data class FolderDetails(
    val folder: Folder,
    val isInTopGroup: Boolean,
    val isIntegrate: Boolean,
    val syncClass: FolderClass,
    val displayClass: FolderClass,
    val notifyClass: FolderClass,
    val pushClass: FolderClass
)

data class RemoteFolderDetails(
    val folder: RemoteFolder,
    val isInTopGroup: Boolean,
    val isIntegrate: Boolean,
    val syncClass: FolderClass,
    val displayClass: FolderClass,
    val notifyClass: FolderClass,
    val pushClass: FolderClass
)

data class DisplayFolder(
    val folder: Folder,
    val isInTopGroup: Boolean,
    val unreadCount: Int
)

enum class FolderType {
    REGULAR,
    INBOX,
    OUTBOX,
    SENT,
    TRASH,
    DRAFTS,
    ARCHIVE,
    SPAM
}
