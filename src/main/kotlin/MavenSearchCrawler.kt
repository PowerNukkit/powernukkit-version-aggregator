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

@file:UseSerializers(VersionSerializer::class, InstantSerializer::class)

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import org.powernukkit.version.Version
import java.nio.file.Files
import java.time.Instant
import java.util.*
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

private const val MAVEN_API = "https://search.maven.org"
private const val DOWNLOAD_URL = "https://search.maven.org/remotecontent?filepath="

suspend fun HttpClient.searchReleases(query: String): Response {
    val requestUrl = "$MAVEN_API/solrsearch/select?core=gav&wt=json&q=$query"
    return get(urlString = requestUrl)
}

suspend fun importNewReleases(client: HttpClient, existingReleases: List<PublishedVersion>): List<PublishedVersion> = coroutineScope {
    val result = client.searchReleases("g:org.powernukkit+AND+a:powernukkit+AND+p:jar")
    check(result.responseHeader.status == 0)
    importNewReleases(client, existingReleases, result.response).awaitAll()
}

private fun CoroutineScope.importNewReleases(client: HttpClient, existingReleases: List<PublishedVersion>, result: ResponseBody): List<Deferred<PublishedVersion>> {
    val skippedVersions = existingReleases.map { it.version }.toSet()
    val releases = result.releases.asSequence()
        .filter { it.version !in skippedVersions }
        //.take(1)
        .mapNotNull { release ->
            val protocolInfoJarUrl = release[Artefact.REDUCED_JAR]
                ?: release[Artefact.SHADED_JAR]
                ?: return@mapNotNull null

            async(Dispatchers.IO) {
                val response = client.get<ByteReadChannel>(protocolInfoJarUrl)
                val tempFile = Files.createTempFile("powernukkit_${release.version}_", ".jar")
                try {
                    tempFile.outputStream().buffered().use { output ->
                        response.copyTo(output)
                    }

                    val jarContents = scanJar(tempFile)
                    PublishedVersion(
                        version = release.version,
                        releaseTime = release.releaseTime,
                        minecraftVersion = jarContents.minecraftVersion,
                        artefacts = release.downloadUrls.keys,
                        commitId = jarContents.commitId,
                    )
                } finally {
                    tempFile.deleteIfExists()
                }
            }
        }
    return releases.toList()
}

@Serializable
data class Response(
    val responseHeader: ResponseHeader,
    val response: ResponseBody,
)

@Serializable
data class ResponseHeader(
    val status: Int,
    @SerialName("QTime")
    val queryTime: Int,
    val params: Map<String, String>,
)

@Serializable
data class ResponseBody(
    @SerialName("numFound")
    val found: Int,
    val start: Int,
    @SerialName("docs")
    val releases: List<MavenRelease>
)

@Serializable
data class MavenRelease(
    val id: String,
    @SerialName("g")
    val group: String,
    @SerialName("a")
    val artefact: String,
    @SerialName("v")
    val version: Version,
    @SerialName("p")
    val packaging: String,
    @SerialName("timestamp")
    val releaseTime: Instant,
    @SerialName("ec")
    val extensions: List<String>,
    val tags: List<String>,
) {
    @Transient
    val downloadUrls: Map<Artefact, Url> = EnumMap(
        "${DOWNLOAD_URL}${group.replace('.', '/')}/$artefact/$version/$artefact-$version".let { artefactUrl ->
            extensions.asSequence()
                .mapNotNull(Artefact.byExtension::get)
                .associateWith { Url(artefactUrl + it.extension) }
        }
    )

    operator fun get(artefact: Artefact) = downloadUrls[artefact]
}

enum class Artefact(val extension: String) {
    REDUCED_JAR(".jar"),
    REDUCED_SOURCES_JAR("-sources.jar"),
    SHADED_JAR("-shaded.jar"),
    SHADED_SOURCES_JAR("-shaded-sources.jar"),
    JAVADOC_JAR("-javadoc.jar"),
    ;
    companion object {
        val byExtension = values().associateBy { it.extension }
    }
}
