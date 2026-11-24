package collector.runtime

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import java.io.File
import kotlin.test.assertTrue

class AndroidApiCompatibilityTest {
    @Test fun coreClassesDoNotLinkToJavaApisMissingOnAndroid26() {
        val forbiddenMethods = mapOf(
            "java/nio/file/Files" to setOf("readString", "writeString"),
            "java/nio/file/Path" to setOf("of"),
            "java/lang/Process" to setOf("descendants"),
            "java/lang/String" to setOf("repeat", "isBlank", "strip", "lines"),
            "java/util/List" to setOf("of"),
            "java/util/Map" to setOf("of"),
            "java/util/Set" to setOf("of"),
            "java/util/Optional" to setOf("isEmpty"),
            "java/time/LocalDate" to setOf("ofInstant")
        )
        val directory = File(CollectorRuntime::class.java.protectionDomain.codeSource.location.toURI())
        val classes = directory.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classes.isNotEmpty(), "No core class files found in $directory")
        val violations = buildSet {
            for (file in classes) {
                val reader = ClassReader(file.readBytes())
                val buffer = CharArray(reader.maxStringLength)
                // JVM constant-pool tags for class, method and interface-method references.
                for (index in 1 until reader.itemCount) {
                    val offset = reader.getItem(index)
                    if (offset == 0) continue
                    when (reader.readByte(offset - 1)) {
                        7 -> {
                            val name = reader.readUTF8(offset, buffer)
                            if (name.contains("java/util/HexFormat") || name.contains("java/net/http/") ||
                                name.contains("java/lang/ProcessHandle")) {
                                add("${reader.className}: $name")
                            }
                        }
                        10, 11 -> {
                            val owner = reader.readClass(offset, buffer)
                            val nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2))
                            val name = reader.readUTF8(nameAndType, buffer)
                            val descriptor = reader.readUTF8(nameAndType + 2, buffer)
                            if (name in forbiddenMethods[owner].orEmpty() ||
                                owner == "java/util/Optional" && name == "orElseThrow" && descriptor.startsWith("()") ||
                                owner == "java/lang/Math" && name == "floorMod" && descriptor == "(JI)I" ||
                                owner == "java/net/URLDecoder" && name == "decode" && descriptor.contains("Ljava/nio/charset/Charset;")
                            ) add("${reader.className}: $owner.$name$descriptor")
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.sorted().joinToString("\n"))
    }
}
