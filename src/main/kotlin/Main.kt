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
import org.powernukkit.version.Version
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.math.abs

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

        val newReleasesAsync = async(Dispatchers.IO) { importNewReleases(client, knownData.releases + knownData.snapshots) }
        val snapshotsAsync = scanSnapshotsAsync(client, knownData.snapshots)
        val newReleases = newReleasesAsync.await()
        val allReleases = ArrayList<PublishedVersion>(knownData.releases.size + newReleases.size)
        allReleases += knownData.releases
        allReleases += newReleases
        allReleases.sortDescending()

        val testingNames = setOf("ALPHA", "BETA", "RC", "SNAPSHOT")
        fun removeTestingToken(version: Version): Version {
            val tokenIndex = version.parts.indexOfFirst { it is String && it.uppercase() in testingNames }
            val token = version.parts[tokenIndex].toString()
            val tokenPos = version.toString().indexOf(token, ignoreCase = true) - 1
            return Version(version.toString().substring(0, tokenPos))
        }

        val testingReleases = allReleases.filter { r->
            r.version.parts.any { it is String && it.uppercase() in testingNames }
        }

        allReleases -= testingReleases.toSet()

        val numeralizer = Regex("\\D")
        val snapshots = snapshotsAsync.await().toMutableList()
        testingReleases.forEach { release ->
            if (release !in snapshots) {
                val closestSnapshot = snapshots.minByOrNull { snapshot ->
                    val testingVersion = removeTestingToken(release.version)
                    val snapshotVersion = removeTestingToken(snapshot.version)
                    val snapshotVersionNumber = snapshotVersion.toString().replace(numeralizer, "").toInt()
                    val testingVersionNumber = testingVersion.toString().replace(numeralizer, "").toInt()
                    val versionComparison = abs(snapshotVersionNumber - testingVersionNumber)
                    val timeComparison = Duration.between(snapshot.releaseTime, release.releaseTime).abs()
                    TestingVersionComparison(versionComparison, timeComparison)
                }
                when {
                    closestSnapshot == null -> snapshots += release
                    closestSnapshot.releaseTime > release.releaseTime -> snapshots.add(snapshots.indexOf(closestSnapshot) + 1, release)
                    else -> snapshots.add(snapshots.indexOf(closestSnapshot), release)
                }
            }
        }

        val updatedData = PowerNukkitVersions(allReleases, snapshots)
        file.outputStream().buffered().use { output ->
            @OptIn(ExperimentalSerializationApi::class)
            json.encodeToStream(updatedData, output)
        }
    }
    println("Success.")
}

data class TestingVersionComparison(
    val versionComparison: Int,
    val durationComparison: Duration,
): Comparable<TestingVersionComparison> {
    override fun compareTo(other: TestingVersionComparison): Int {
        return compareBy(TestingVersionComparison::versionComparison, TestingVersionComparison::durationComparison)
            .compare(this, other)
    }
}

@Serializable
data class PowerNukkitVersions(
    val releases: List<PublishedVersion>,
    val snapshots: List<PublishedVersion>,
)
