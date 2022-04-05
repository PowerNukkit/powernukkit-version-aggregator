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

import org.objectweb.asm.*
import org.powernukkit.version.Version
import java.nio.file.Path
import kotlin.io.path.inputStream

fun readProtocolInfoMinecraftVersion(protocolInfoPath: Path): Version {
    return protocolInfoPath.inputStream().buffered().use { inputStream ->
        var minecraftVersion: String? = null
        val reader = ClassReader(inputStream)
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            var lastString: String? = null
            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                if (name == "MINECRAFT_VERSION" && descriptor == "Ljava/lang/String;" && signature == null && value is String) {
                    minecraftVersion = value.removePrefix("v")
                    throw Found
                }
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<String>?
            ): MethodVisitor? {
                return if ("<clinit>" == name && "()V" == descriptor) {
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLdcInsn(value: Any?) {
                            if (value is String) {
                                lastString = value
                            }
                        }
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean
                        ) {
                            if (lastString == null
                                || opcode != Opcodes.INVOKESTATIC
                                || owner != "cn/nukkit/utils/Utils"
                                || name != "dynamic"
                                || descriptor != "(Ljava/lang/Object;)Ljava/lang/Object;"
                                || isInterface
                            ) {
                                lastString = null
                            }
                        }
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?
                        ) {
                            if (lastString != null
                                && opcode == Opcodes.PUTSTATIC
                                && owner == "cn/nukkit/network/protocol/ProtocolInfo"
                                && name == "MINECRAFT_VERSION"
                                && descriptor == "Ljava/lang/String;"
                            ) {
                                minecraftVersion = lastString!!.removePrefix("v")
                                throw Found
                            }
                        }
                    }
                } else null
            }
        }

        try {
            reader.accept(visitor, ClassReader.EXPAND_FRAMES)
        } catch (_: Found) {}
        Version(requireNotNull(minecraftVersion))
    }
}

private object Found: Throwable(null, null, false, false)

