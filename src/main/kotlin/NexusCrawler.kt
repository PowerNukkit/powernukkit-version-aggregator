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
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.powernukkit.version.Version
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

private const val POWERNUKKIT_DIR =  "https://oss.sonatype.org/content/repositories/snapshots/org/powernukkit/powernukkit"
private val POWERNUKKIT_METADATA = Url("$POWERNUKKIT_DIR/maven-metadata.xml")

private val VALID_DOWNLOAD_LINK = Regex("""^${Regex.escape(POWERNUKKIT_DIR)}/(([^/]+?)-SNAPSHOT)/powernukkit-\2-(\d{8})\.(\d{6})-(\d+)(-(?:shaded|javadoc|(?:shaded-)?sources)?\.jar)$""")

private suspend fun loadVersions(httpClient: HttpClient): List<Version> {
    val xpath = XPathFactory.newInstance().newXPath()
    val inputSource = InputSource(StringReader(httpClient.get<String>(POWERNUKKIT_METADATA)))
    val versionNodes = xpath.evaluate("/metadata/versioning/versions/version", inputSource, XPathConstants.NODESET) as NodeList
    val versions = ArrayList<Version>(versionNodes.length)
    versionNodes.forEach { node ->
        versions += Version(node.textContent.trim())
    }
    return versions
}

fun CoroutineScope.scanSnapshotsAsync(client: HttpClient, snapshots: List<PublishedVersion>) = async(Dispatchers.IO) {
    val parsedSnapshotVersions = snapshots.associateBy { it.version }
    loadVersions(client)/*.take(1)*/.map { version ->
        async(Dispatchers.IO) {
            val listingHtml = client.get<String>(Url("$POWERNUKKIT_DIR/$version/"))
            val page = Jsoup.parse(listingHtml)
            val allArtefacts = sequence {
                val tags = page.getElementsByTag("a")
                tags.forEach { node ->
                    yield(node)
                }
            }.mapNotNull { it.attr("href") }
                .mapNotNull { VALID_DOWNLOAD_LINK.matchEntire(it) }
                .map {
                    val (_, v, _, date, time) = it.groupValues
                    val build = it.groupValues[5]
                    val extension = it.groupValues[6]
                    NexusSnapshotArtefact(
                        version = Version(v),
                        dateTime = LocalDateTime.of(
                            date.substring(0,4).toInt(), date.substring(4,6).toInt(), date.substring(6,8).toInt(),
                            time.substring(0,2).toInt(), time.substring(2,4).toInt(), time.substring(4,6).toInt()
                        ),
                        build = build.toInt(),
                        artefact = Artefact.byExtension[extension] ?: throw NoSuchElementException(extension)
                    )
                }
                .groupBy { it.fullVersion }
                .mapValues { (_, artefacts) -> artefacts.associateBy { it.artefact } }

            allArtefacts.entries.asSequence().mapNotNull { (version, artefacts) ->
                val jarArtefact = artefacts[Artefact.REDUCED_JAR]
                    ?: artefacts[Artefact.SHADED_JAR]
                    ?: return@mapNotNull null

                parsedSnapshotVersions[version]?.let {
                    return@mapNotNull CompletableDeferred(it)
                }

                async(Dispatchers.IO) {
                    val response = client.get<ByteReadChannel>(jarArtefact.downloadUrl)
                    val tempFile = Files.createTempFile("powernukkit_${version}_", ".jar")
                    try {
                        tempFile.outputStream().buffered().use { output ->
                            response.copyTo(output)
                        }
                        val jarContents = scanJar(tempFile)
                        PublishedVersion(
                            version = version,
                            releaseTime = jarArtefact.dateTime.toInstant(ZoneOffset.UTC),
                            minecraftVersion = jarContents.minecraftVersion,
                            commitId = jarContents.commitId,
                            snapshotBuild = jarArtefact.build,
                            artefacts = artefacts.keys
                        )
                    } finally {
                        tempFile.deleteIfExists()
                    }
                }
            }.toList().awaitAll()
        }
    }.toList().awaitAll().flatten().sortedDescending()
}

inline fun NodeList.forEach(action: (Node) -> Unit) {
    for (index in 0 until length) {
        action(item(index))
    }
}

data class NexusSnapshotArtefact(
    val version: Version,
    val dateTime: LocalDateTime,
    val build: Int,
    val artefact: Artefact,
) {
    val snapshotCode = Version(buildString {
        append(dateTime.year.toString().padStart(4, '0'))
            .append(dateTime.monthValue.toString().padStart(2, '0'))
            .append(dateTime.dayOfMonth.toString().padStart(2, '0'))
            .append('.')
            .append(dateTime.hour.toString().padStart(2, '0'))
            .append(dateTime.minute.toString().padStart(2, '0'))
            .append(dateTime.second.toString().padStart(2, '0'))
            .append('-')
            .append(build.toString())
    })

    val fullVersion = Version("$version-$snapshotCode")

    val downloadUrl get() = Url(buildString {
        append(POWERNUKKIT_DIR)
            .append('/')
            .append(version)
            .append('/')
            .append("powernukkit-")
            .append(version.toString().substringBeforeLast('-'))
            .append('-')
            .append(snapshotCode)
            .append(artefact.extension)
    })
}
