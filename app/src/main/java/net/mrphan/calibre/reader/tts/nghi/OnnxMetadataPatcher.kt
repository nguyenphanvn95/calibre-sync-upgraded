package net.mrphan.calibre.reader.tts.nghi

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Adds ONNX `ModelProto.metadata_props` entries (sherpa-onnx reads `model_type`, `comment`,
 * `has_espeak`, `sample_rate`, `n_speakers`, `voice`, `language` from here to know how to run a
 * Piper/VITS model) to a raw `.onnx` file that doesn't have them yet -- which is the case for
 * every model distributed by nghitts (nghitts runs models through ONNX Runtime Web directly and
 * never needed this metadata).
 *
 * Why simple byte-append works: an `.onnx` file IS a raw serialized Protocol Buffers
 * `ModelProto` message with no extra file-level wrapper/checksum. Per the protobuf wire format
 * spec, a message is just a sequence of independent (field-number, value) entries; for a
 * REPEATED field like `metadata_props` (field 14), a conformant parser accumulates every
 * occurrence of that field number regardless of where it appears in the byte stream -- entries
 * do not need to be contiguous or in any particular position. So appending a few dozen
 * additional, well-formed `metadata_props` entries to the END of an existing valid `.onnx` file
 * produces another valid `.onnx` file whose parsed result is "the original model + these extra
 * metadata entries" -- identical in effect to (but far cheaper than) loading the whole ~60MB
 * graph with a full ONNX library, mutating it, and re-serializing it.
 *
 * This is the same net effect as the official Python conversion script's `add_meta_data()`
 * (see k2-fsa.github.io/sherpa/onnx/tts/piper.html), just without needing `pip install onnx`
 * or moving the file off-device.
 */
object OnnxMetadataPatcher {

    fun appendMetadata(onnxFile: File, metadata: Map<String, String>) {
        val extraBytes = ByteArrayOutputStream()
        for ((key, value) in metadata) {
            extraBytes.write(encodeMetadataEntry(key, value))
        }
        FileOutputStream(onnxFile, /* append = */ true).use { out ->
            out.write(extraBytes.toByteArray())
        }
    }

    /** One `ModelProto.metadata_props` (field 14, embedded message) entry wrapping one
     * `StringStringEntryProto { key = 1 (string); value = 2 (string) }`. */
    private fun encodeMetadataEntry(key: String, value: String): ByteArray {
        val inner = ByteArrayOutputStream()
        writeTag(inner, fieldNumber = 1, wireType = WIRE_TYPE_LENGTH_DELIMITED)
        writeLengthDelimited(inner, key.toByteArray(Charsets.UTF_8))
        writeTag(inner, fieldNumber = 2, wireType = WIRE_TYPE_LENGTH_DELIMITED)
        writeLengthDelimited(inner, value.toByteArray(Charsets.UTF_8))
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        writeTag(outer, fieldNumber = MODEL_PROTO_METADATA_PROPS_FIELD, wireType = WIRE_TYPE_LENGTH_DELIMITED)
        writeLengthDelimited(outer, innerBytes)
        return outer.toByteArray()
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, (fieldNumber shl 3) or wireType)
    }

    private fun writeLengthDelimited(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, valueIn: Int) {
        var value = valueIn
        while (true) {
            if ((value and 0x7F.inv()) == 0) {
                out.write(value)
                return
            }
            out.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
    }

    private const val WIRE_TYPE_LENGTH_DELIMITED = 2
    private const val MODEL_PROTO_METADATA_PROPS_FIELD = 14
}
