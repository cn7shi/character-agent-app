package com.character.app.network

import com.character.app.domain.BackendFailure
import com.character.app.domain.FailureKind
import okio.Buffer
import okio.BufferedSource

data class SseFrame(
    val id: String,
    val type: String,
    val data: String,
)

/**
 * WHATWG framing (LF/CRLF/CR, BOM, comments, multiline data), with explicit memory bounds.
 */
internal object SseReader {
    private const val MAX_FRAME_BYTES = 512 * 1024L

    fun read(source: BufferedSource, accept: (SseFrame) -> Unit) {
        var id = ""
        var type = ""
        var data = StringBuilder()
        var size = 0L
        var first = true
        var skipLf = false
        val line = Buffer()

        while (!source.exhausted()) {
            val byte = source.readByte().toInt() and 255
            size++
            if (size > MAX_FRAME_BYTES) {
                throw BackendFailure(FailureKind.PROTOCOL)
            }

            if (skipLf && byte == 10) {
                skipLf = false
                continue
            }
            skipLf = false

            if (byte != 10 && byte != 13) {
                line.writeByte(byte)
                continue
            }
            skipLf = byte == 13

            var text = line.readUtf8()
            if (first) {
                text = text.removePrefix("\uFEFF")
                first = false
            }

            if (text.isEmpty()) {
                if (data.isNotEmpty()) {
                    val frameType = if (type.isEmpty()) "message" else type
                    val frameData = data.dropLast(1).toString()
                    accept(SseFrame(id = id, type = frameType, data = frameData))
                }
                data = StringBuilder()
                type = ""
                size = 0L
            } else if (!text.startsWith(':')) {
                val field = text.substringBefore(':')
                val value = if (':' in text) {
                    text.substringAfter(':').removePrefix(" ")
                } else {
                    ""
                }

                when (field) {
                    "id" -> {
                        if ('\u0000' !in value) {
                            id = value
                        }
                    }
                    "event" -> {
                        type = value
                    }
                    "data" -> {
                        data.append(value).append('\n')
                    }
                }
            }
        }
        // An unfinished frame is discarded, never persisted as an acknowledged cursor.
    }
}
