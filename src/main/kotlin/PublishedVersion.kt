/*
 * Copyright (C) 2022 José Roberto de Araújo Júnior <joserobjr@powernukkit.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

@file:UseSerializers(InstantSerializer::class, VersionSerializer::class, UrlSerializer::class)

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.powernukkit.version.Version
import java.time.Instant

@Serializable
data class PublishedVersion (
    val version: Version,
    val releaseTime: Instant,
    val minecraftVersion: Version,
    val artefacts: Set<Artefact>,
    val commitId: String?,
    val snapshotBuild: Int? = null
): Comparable<PublishedVersion> {
    override fun compareTo(other: PublishedVersion): Int {
        return compareBy(PublishedVersion::version, PublishedVersion::releaseTime, PublishedVersion::minecraftVersion, PublishedVersion::commitId)
            .thenComparing { a, b -> a.artefacts.toString().compareTo(b.artefacts.toString()) }
            .compare(this, other)
    }
}
