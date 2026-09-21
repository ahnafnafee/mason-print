package dev.ahnafnafee.masonprint.data.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** The flat IPP attributes requested by the job monitor (RFC 8010 / RFC 8011). */
internal object IppProtocol {
    const val CANCEL_JOB = 0x0008
    const val GET_JOB = 0x0009
    const val GET_JOBS = 0x000a
    const val GET_PRINTER = 0x000b
    const val MAX_RESPONSE = 1024 * 1024

    data class Attribute(val name: String, val tag: Int, val bytes: ByteArray) {
        fun text(): String? {
            return when (tag) {
                0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49 -> bytes.toString(Charsets.UTF_8)
                0x35, 0x36 -> {
                    // text/nameWithLanguage contains two independently length-prefixed strings.
                    if (bytes.size < 4) return null
                    val value = ByteBuffer.wrap(bytes)
                    val languageLength = value.short.toInt() and 0xffff
                    if (languageLength > value.remaining() - 2) return null
                    value.position(value.position() + languageLength)
                    val textLength = value.short.toInt() and 0xffff
                    if (textLength != value.remaining()) return null
                    ByteArray(textLength).also(value::get).toString(Charsets.UTF_8)
                }
                else -> null
            }
        }
        fun number(): Int? = if (tag in setOf(0x21, 0x23) && bytes.size == 4) ByteBuffer.wrap(bytes).int else null
        companion object {
            fun text(name: String, value: String, tag: Int = 0x44) = Attribute(name, tag, value.toByteArray(Charsets.UTF_8))
            fun number(name: String, value: Int) = Attribute(name, 0x21, ByteBuffer.allocate(4).putInt(value).array())
            fun bool(name: String, value: Boolean) = Attribute(name, 0x22, byteArrayOf(if (value) 1 else 0))
        }
    }
    data class Group(val tag: Int, val attributes: List<Attribute>) {
        fun text(name: String) = attributes.firstOrNull { it.name == name }?.text()
        fun texts(name: String) = attributes.filter { it.name == name }.mapNotNull { it.text() }
        fun number(name: String) = attributes.firstOrNull { it.name == name }?.number()
        fun numbers(name: String) = attributes.filter { it.name == name }.mapNotNull { it.number() }.toSet()
    }
    data class Message(val status: Int, val requestId: Int, val groups: List<Group>)

    fun request(operation: Int, requestId: Int, attributes: List<Attribute>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            // These operations are all available in IPP 1.1, including on older printers.
            out.writeByte(1); out.writeByte(1)
            out.writeShort(operation); out.writeInt(requestId); out.writeByte(1)
            val all = listOf(Attribute.text("attributes-charset", "utf-8", 0x47),
                Attribute.text("attributes-natural-language", "en", 0x48)) + attributes
            all.forEach { attribute ->
                val name = attribute.name.toByteArray(Charsets.UTF_8)
                require(name.size <= 65535 && attribute.bytes.size <= 65535)
                out.writeByte(attribute.tag); out.writeShort(name.size); out.write(name)
                out.writeShort(attribute.bytes.size); out.write(attribute.bytes)
            }
            out.writeByte(3)
        }
        return bytes.toByteArray()
    }

    fun response(bytes: ByteArray, expectedRequestId: Int): Message {
        if (bytes.size !in 9..MAX_RESPONSE) throw IOException("Invalid printer response size")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val major = input.readUnsignedByte(); input.readUnsignedByte()
        if (major !in 1..2) throw IOException("Unsupported printer protocol")
        val status = input.readUnsignedShort()
        val requestId = input.readInt()
        if (requestId != expectedRequestId) throw IOException("Printer answered a different request")
        val groups = mutableListOf<Group>()
        var tag = 0
        var attributes = mutableListOf<Attribute>()
        var previousName: String? = null
        while (input.available() > 0) {
            val valueTag = input.readUnsignedByte()
            if (valueTag == 3) {
                if (tag != 0) groups += Group(tag, attributes)
                return Message(status, requestId, groups)
            }
            if (valueTag in 1..15) {
                if (tag != 0) groups += Group(tag, attributes)
                tag = valueTag; attributes = mutableListOf(); previousName = null
                continue
            }
            if (tag == 0 || valueTag == 0x7f) throw IOException("Unsupported printer attribute")
            val nameLength = input.readUnsignedShort()
            if (nameLength > input.available()) throw IOException("Truncated printer attribute")
            val name = if (nameLength == 0) previousName ?: throw IOException("Unnamed printer attribute")
                else ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8)
            val length = input.readUnsignedShort()
            if (length > input.available()) throw IOException("Truncated printer value")
            attributes += Attribute(name, valueTag, ByteArray(length).also(input::readFully))
            previousName = name
        }
        throw IOException("Incomplete printer response")
    }
}
