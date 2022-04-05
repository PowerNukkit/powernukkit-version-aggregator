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

import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.powernukkit.version.Version
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*
import kotlin.io.path.bufferedReader

suspend fun scanJar(jarPath: Path): JarContents = withContext(Dispatchers.IO) {
    FileSystems.newFileSystem(jarPath).use { fileSystem ->
        val minecraftVersion = async(Dispatchers.IO) {
            val protocolInfoPath =
                fileSystem.getPath("cn", "nukkit", "network", "protocol", "ProtocolInfo.class")
            readProtocolInfoMinecraftVersion(protocolInfoPath)
        }

        val properties = try {
            Properties(20).apply {
                fileSystem.getPath("git.properties").bufferedReader().use { reader ->
                    load(reader)
                }
            }
        } catch (e: Exception) {
            e.printStack()
            null
        }

        JarContents(
            minecraftVersion = minecraftVersion.await(),
            commitId = properties?.getProperty("git.commit.id"),
        )
    }
}

data class JarContents(
    val minecraftVersion: Version,
    val commitId: String?
)
