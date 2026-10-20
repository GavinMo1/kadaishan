package dev.sxc.kadaishan.domain.session

import dev.sxc.kadaishan.model.MediaKey

/** Only deliberate keep decisions survive restart. Pending deletion marks never enter this store. */
interface ReviewHistory {
    suspend fun keptIds(): Set<MediaKey>
    suspend fun setKept(ids: Set<MediaKey>, kept: Boolean)
    suspend fun clear()
}
