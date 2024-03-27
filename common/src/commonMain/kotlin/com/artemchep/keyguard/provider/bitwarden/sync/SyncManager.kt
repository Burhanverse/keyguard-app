package com.artemchep.keyguard.provider.bitwarden.sync

import com.artemchep.keyguard.common.util.millis
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import kotlinx.datetime.Instant
import kotlin.math.roundToLong

class SyncManager<Local : BitwardenService.Has<Local>, Remote : Any>(
    private val local: LensLocal<Local>,
    private val remote: Lens<Remote>,
) {
    data class Lens<T>(
        val getId: (T) -> String,
        val getRevisionDate: (T) -> Instant,
        val getDeletedDate: (T) -> Instant? = { null },
    )

    class LensLocal<T>(
        val getLocalId: (T) -> String,
        val getLocalRevisionDate: (T) -> Instant,
        val getLocalDeletedDate: (T) -> Instant? = { null },
    )

    data class Df<Local : Any, Remote : Any>(
        // Delete items
        val remoteDeletedCipherIds: List<Ite<Local, Remote>>,
        val localDeletedCipherIds: List<Ite<Local, Remote?>>,
        // Put items
        val remotePutCipher: List<Ite<Local, Remote?>>,
        val localPutCipher: List<Ite<Local?, Remote>>,
    ) {
        data class Ite<Local, Remote>(
            val local: Local,
            val remote: Remote,
        )
    }

    private fun getDate(model: Remote) = getDate(
        revisionDate = remote.getRevisionDate(model),
        deletedDate = remote.getDeletedDate(model),
    )

    private fun getDate(model: Local) = getDate(
        revisionDate = local.getLocalRevisionDate(model),
        deletedDate = local.getLocalDeletedDate(model),
    )

    private fun getDate(
        revisionDate: Instant,
        deletedDate: Instant?,
    ) = deletedDate?.let(revisionDate::coerceAtLeast)
        ?: revisionDate

    /**
     * Calculates the difference between local and remote
     * representations of a server, allowing you to easily sync
     * them with each other.
     */
    fun df(
        localItems: Collection<Local>,
        remoteItems: Collection<Remote>,
        shouldOverwrite: (Local, Remote) -> Boolean,
    ): Df<Local, Remote> {
        // Delete items
        val remoteDeletedCipherIds = mutableListOf<Df.Ite<Local, Remote>>()
        val localDeletedCipherIds = mutableListOf<Df.Ite<Local, Remote?>>()
        // Put items
        val remotePutCipher = mutableListOf<Df.Ite<Local, Remote?>>()
        val localPutCipher = mutableListOf<Df.Ite<Local?, Remote>>()

        val localItemsGrouped = localItems
            .groupBy {
                it.service.remote != null
            }
        val localItemsNew = localItemsGrouped
            .getOrDefault(false, emptyList()) // no remote
        val localItemsExistingByRemoteId = localItemsGrouped
            .getOrDefault(true, emptyList()) // remote
            .asSequence()
            .map { localItem ->
                val id = requireNotNull(localItem.service.remote?.id)
                id to localItem
            }
            .toMap(mutableMapOf())

        remoteItems.forEach { remoteItem ->
            val remoteItemId = remote.getId(remoteItem)
            val localItem = localItemsExistingByRemoteId
                .remove(remoteItemId)
            if (localItem != null) {
                // TODO: Replace it with a migration mechanism
                if (localItem.service.version < BitwardenService.VERSION) {
                    localPutCipher += Df.Ite(localItem, remoteItem)
                    return@forEach
                }

                // FIXME: After we create something, the date is
                //    --
                //    2022-09-21T14:04:33.1819975Z
                //    --
                //  but after we get the same entry using the sync
                //  API the revision date is some-why rounded to:
                //    --
                //    2022-09-21T14:04:33.1833333Z
                //    --
                //  for now we round the revision date to
                //    --
                //    2022-09-21T14:04:33.18
                //    --
                fun Instant.roundToMillis() = millis.toDouble().div(100.0).roundToLong()

                val localRemoteRevDate = kotlin.run {
                    val date = getDate(
                        revisionDate = localItem.service.remote?.revisionDate
                            ?: Instant.DISTANT_PAST,
                        deletedDate = localItem.service.remote?.deletedDate,
                    )
                    date.roundToMillis()
                }
                // If the local item has outdated remote revision, then it must
                // be updated and any of its changes are going to be discarded.
                //
                // Why:
                // This is needed to resolve a conflict where you edit one item
                // on multiple devices simultaneously.
                val remoteRevDate = getDate(remoteItem).roundToMillis()
                if (remoteRevDate != localRemoteRevDate) {
                    localPutCipher += Df.Ite(localItem, remoteItem)
                    return@forEach
                }

                val diff = kotlin.run {
                    val localRevDate = getDate(localItem).roundToMillis()
                    localRevDate.compareTo(remoteRevDate)
                }
                when {
                    diff < 0 -> localPutCipher += Df.Ite(localItem, remoteItem)
                    diff > 0 -> {
                        if (localItem.service.deleted) {
                            remoteDeletedCipherIds += Df.Ite(localItem, remoteItem)
                        } else {
                            val error = localItem.service.error
                            val revisionDate = getDate(localItem)
                            if (error?.canRetry(revisionDate) != false) {
                                remotePutCipher += Df.Ite(localItem, remoteItem)
                            }
                        }
                    }

                    else -> {
                        // Date rounding error can happen because Bitwarden rounds the
                        // revision date. We can not safely resolve this issue, so just
                        // fall back to the remote item.
                        val dateRoundingError = getDate(remoteItem) != getDate(localItem)
                        if (dateRoundingError || localItem.service.error != null) {
                            localPutCipher += Df.Ite(localItem, remoteItem)
                        } else {
                            val hasChanged = shouldOverwrite(localItem, remoteItem)
                            if (hasChanged) {
                                localPutCipher += Df.Ite(localItem, remoteItem)
                            } else {
                                // Up to date.
                            }
                        }
                    }
                }
            } else {
                localPutCipher += Df.Ite(null, remoteItem)
            }
        }

        // The item has remote id, but the remote items
        // do not have it -- therefore it has deleted on remote.
        localDeletedCipherIds += localItemsExistingByRemoteId
            .map { (_, localItem) ->
                Df.Ite(localItem, null)
            }

        //
        // Handle new items
        //

        localItemsNew.forEach { localItem ->
            if (localItem.service.deleted) {
                localDeletedCipherIds += Df.Ite(localItem, null)
            } else {
                val error = localItem.service.error
                val revisionDate = getDate(localItem)
                if (error?.canRetry(revisionDate) != false) {
                    remotePutCipher += Df.Ite(localItem, null)
                }
            }
        }

        return Df(
            remoteDeletedCipherIds = remoteDeletedCipherIds,
            localDeletedCipherIds = localDeletedCipherIds,
            remotePutCipher = remotePutCipher,
            localPutCipher = localPutCipher,
        )
    }
}
