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

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Paths
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream

fun main() {
    println("""
            PowerNukkit Version Aggregator  Copyright (C) 2022  José Roberto de Araújo Júnior <joserobjr@powernukkit.org>
            This program comes with ABSOLUTELY NO WARRANTY.
            This is free software, and you are welcome to redistribute it under certain conditions.
            Check the LICENSE file for details, or visit https://www.gnu.org/licenses/gpl-3.0.html
    """.trimIndent())
    println()
    println("The generate json file (powernukkit-versions.json) can be used freely under MIT License: https://opensource.org/licenses/MIT")
    println()
    println("Processing, please wait...")
    val client = HttpClient(CIO) {
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
    }
    val json = Json {
        //prettyPrint = true
        encodeDefaults = false
    }

    runBlocking(Dispatchers.IO) {
        val file = Paths.get("powernukkit-versions.json")
        val knownData =
            if (!file.isRegularFile()) PowerNukkitVersions(emptyList(), emptyList())
            else file.inputStream().buffered().use { input ->
                @OptIn(ExperimentalSerializationApi::class)
                json.decodeFromStream(input)
            }

        val newReleasesAsync = async(Dispatchers.IO) { importNewReleases(client, knownData.releases) }
        val snapshotsAsync = scanSnapshotsAsync(client, knownData.snapshots)
        val newReleases = newReleasesAsync.await()
        val allReleases = ArrayList<PublishedVersion>(knownData.releases.size + newReleases.size)
        allReleases += knownData.releases
        allReleases += newReleases
        allReleases.sortDescending()

        val updatedData = PowerNukkitVersions(allReleases, snapshotsAsync.await())
        file.outputStream().buffered().use { output ->
            @OptIn(ExperimentalSerializationApi::class)
            json.encodeToStream(updatedData, output)
        }
    }
    println("Success.")
}

@Serializable
data class PowerNukkitVersions(
    val releases: List<PublishedVersion>,
    val snapshots: List<PublishedVersion>,
)
