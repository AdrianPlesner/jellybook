package dk.azp.jellybook.data.chapters

import java.nio.ByteBuffer
import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

data class Mp4Chapters(val durationMs: Long, val chapters: List<Chapter>)

/** Byte-range access to a file, local or remote. Returns fewer bytes than requested at end of file. */
interface RandomAccessSource {
    suspend fun read(offset: Long, length: Int): ByteArray
}

/**
 * Reads chapter markers from an MP4 container (m4b/m4a) without downloading the audio. Supports the two ways chapters are
 * stored in the wild: a QuickTime chapter text track referenced through `tref/chap`, and the Nero `udta/chpl` atom.
 */
class Mp4ChapterParser(private val source: RandomAccessSource) {

    suspend fun parse(): Mp4Chapters? {
        val moov = findMoov() ?: return null
        val durationMs = movieDurationMs(moov)
        val tracks = moov.children().filter { it.type == "trak" }.mapNotNull { parseTrack(it) }
        val neroChapters = moov.child("udta")?.child("chpl")?.let { parseNeroChapters(it) } ?: emptyList()
        val neroMayBeTruncated = neroChapters.isEmpty() || neroChapters.size >= NERO_MAX_CHAPTERS
        val starts = if (neroMayBeTruncated) {
            chapterTrackFor(tracks)?.let { readChapterTrack(it) }?.takeIf { it.size > neroChapters.size } ?: neroChapters
        } else {
            neroChapters
        }
        return Mp4Chapters(durationMs, toChapters(starts, durationMs))
    }

    private suspend fun findMoov(): Atom? {
        var offset = 0L
        var result: Atom? = null
        var index = 0
        while (result == null && index < MAX_TOP_LEVEL_ATOMS) {
            val header = source.read(offset, 16)
            if (header.size < 8) break
            val buffer = ByteBuffer.wrap(header)
            var size = buffer.int.toLong() and 0xFFFFFFFFL
            val type = fourCc(header, 4)
            var headerSize = 8
            if (index == 0 && type !in KNOWN_TOP_LEVEL_TYPES) break
            if (size == 1L) {
                if (header.size < 16) break
                size = ByteBuffer.wrap(header, 8, 8).long
                headerSize = 16
            }
            if (size == 0L || size < headerSize) break
            if (type == "moov") {
                if (size > MAX_MOOV_BYTES) break
                val bytes = source.read(offset, size.toInt())
                if (bytes.size.toLong() < size) break
                result = Atom("moov", headerSize, bytes.size, bytes)
            }
            offset += size
            index++
        }
        return result
    }

    private fun movieDurationMs(moov: Atom): Long {
        val mvhd = moov.child("mvhd") ?: return 0
        val buffer = mvhd.buffer()
        val version = buffer.get().toInt()
        buffer.position(buffer.position() + 3)
        return if (version == 1) {
            buffer.position(buffer.position() + 16)
            val timescale = buffer.int.toLong() and 0xFFFFFFFFL
            val duration = buffer.long
            toMs(duration, timescale)
        } else {
            buffer.position(buffer.position() + 8)
            val timescale = buffer.int.toLong() and 0xFFFFFFFFL
            val duration = buffer.int.toLong() and 0xFFFFFFFFL
            toMs(duration, timescale)
        }
    }

    private fun parseTrack(trak: Atom): Track? {
        val tkhd = trak.child("tkhd") ?: return null
        val tkhdBuffer = tkhd.buffer()
        val tkhdVersion = tkhdBuffer.get().toInt()
        tkhdBuffer.position(tkhdBuffer.position() + 3 + if (tkhdVersion == 1) 16 else 8)
        val trackId = tkhdBuffer.int
        val mdia = trak.child("mdia") ?: return null
        val mdhd = mdia.child("mdhd") ?: return null
        val mdhdBuffer = mdhd.buffer()
        val mdhdVersion = mdhdBuffer.get().toInt()
        mdhdBuffer.position(mdhdBuffer.position() + 3 + if (mdhdVersion == 1) 16 else 8)
        val timescale = mdhdBuffer.int.toLong() and 0xFFFFFFFFL
        val handler = mdia.child("hdlr")?.let { fourCc(it.data, it.payloadStart + 8) }
        val chapterRefs = trak.child("tref")?.child("chap")?.let { chap ->
            val buffer = chap.buffer()
            List(chap.payloadSize / 4) { buffer.int }
        } ?: emptyList()
        val stbl = mdia.child("minf")?.child("stbl")
        val sampleFormat = stbl?.child("stsd")?.takeIf { it.payloadSize >= 16 }?.let { fourCc(it.data, it.payloadStart + 12) }
        return Track(trackId, handler, chapterRefs, timescale, stbl, sampleFormat)
    }

    private fun chapterTrackFor(tracks: List<Track>): Track? {
        val referencedIds = tracks.flatMap { it.chapterRefs }.toSet()
        val referenced = tracks.firstOrNull { it.id in referencedIds && it.stbl != null }
        return referenced ?: tracks.firstOrNull { it.stbl != null && it.handler in TEXT_HANDLERS && it.sampleFormat in TEXT_FORMATS }
    }

    private suspend fun readChapterTrack(track: Track): List<ChapterStart>? {
        val stbl = track.stbl ?: return null
        val samples = sampleTable(stbl) ?: return null
        if (samples.isEmpty() || track.timescale == 0L) return null
        val texts = readSampleTexts(samples)
        return samples.mapIndexed { index, sample -> ChapterStart(toMs(sample.timeUnits, track.timescale), texts[index]) }
    }

    private fun sampleTable(stbl: Atom): List<SampleRef>? {
        val stts = stbl.child("stts") ?: return null
        val stsz = stbl.child("stsz") ?: return null
        val stsc = stbl.child("stsc") ?: return null
        val chunkOffsets = stbl.child("stco")?.let { readChunkOffsets(it, wide = false) }
            ?: stbl.child("co64")?.let { readChunkOffsets(it, wide = true) }
            ?: return null

        val sttsBuffer = stts.buffer()
        sttsBuffer.position(sttsBuffer.position() + 4)
        val sttsEntryCount = sttsBuffer.int
        val times = ArrayList<Long>()
        var elapsed = 0L
        for (entry in 0 until sttsEntryCount) {
            val sampleCount = sttsBuffer.int
            val delta = sttsBuffer.int.toLong() and 0xFFFFFFFFL
            for (sample in 0 until sampleCount) {
                if (times.size >= MAX_CHAPTER_SAMPLES) return null
                times.add(elapsed)
                elapsed += delta
            }
        }

        val stszBuffer = stsz.buffer()
        stszBuffer.position(stszBuffer.position() + 4)
        val fixedSize = stszBuffer.int
        val sampleCount = stszBuffer.int
        if (sampleCount > MAX_CHAPTER_SAMPLES) return null
        val sizes = if (fixedSize != 0) List(sampleCount) { fixedSize } else List(sampleCount) { stszBuffer.int }

        val stscBuffer = stsc.buffer()
        stscBuffer.position(stscBuffer.position() + 4)
        val stscEntryCount = stscBuffer.int
        val chunkRuns = List(stscEntryCount) {
            val firstChunk = stscBuffer.int
            val samplesPerChunk = stscBuffer.int
            stscBuffer.int
            firstChunk to samplesPerChunk
        }

        val samples = ArrayList<SampleRef>()
        var sampleIndex = 0
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val chunkNumber = chunkIndex + 1
            val samplesPerChunk = chunkRuns.lastOrNull { it.first <= chunkNumber }?.second ?: 0
            var runningOffset = chunkOffset
            for (sample in 0 until samplesPerChunk) {
                if (sampleIndex >= sizes.size) break
                val size = sizes[sampleIndex]
                val time = times.getOrElse(sampleIndex) { times.lastOrNull() ?: 0L }
                samples.add(SampleRef(runningOffset, size, time))
                runningOffset += size
                sampleIndex++
            }
        }
        return samples
    }

    private fun readChunkOffsets(atom: Atom, wide: Boolean): List<Long> {
        val buffer = atom.buffer()
        buffer.position(buffer.position() + 4)
        val count = buffer.int
        return List(count) { if (wide) buffer.long else buffer.int.toLong() and 0xFFFFFFFFL }
    }

    private suspend fun readSampleTexts(samples: List<SampleRef>): List<String> {
        val first = samples.minOf { it.offset }
        val last = samples.maxOf { it.offset + it.size }
        val span = last - first
        return if (span <= COALESCE_LIMIT_BYTES) {
            val block = source.read(first, span.toInt())
            samples.map { sample ->
                val start = (sample.offset - first).toInt()
                decodeSampleText(block.copyOfRange(start.coerceAtMost(block.size), (start + sample.size).coerceAtMost(block.size)))
            }
        } else {
            samples.map { sample -> decodeSampleText(source.read(sample.offset, sample.size)) }
        }
    }

    private fun decodeSampleText(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val length = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val end = (2 + length).coerceAtMost(bytes.size)
        val text = bytes.copyOfRange(2, end)
        val decoded = when {
            text.size >= 2 && text[0] == 0xFE.toByte() && text[1] == 0xFF.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16BE)
            text.size >= 2 && text[0] == 0xFF.toByte() && text[1] == 0xFE.toByte() -> String(text, 2, text.size - 2, Charsets.UTF_16LE)
            else -> String(text, Charsets.UTF_8)
        }
        return decoded.trim()
    }

    private fun parseNeroChapters(chpl: Atom): List<ChapterStart> {
        val buffer = chpl.buffer()
        if (buffer.remaining() < 5) return emptyList()
        val version = buffer.get().toInt()
        buffer.position(buffer.position() + 3)
        if (version != 0) buffer.position(buffer.position() + 4)
        val count = buffer.get().toInt() and 0xFF
        val result = ArrayList<ChapterStart>(count)
        for (index in 0 until count) {
            if (buffer.remaining() < 9) break
            val startTicks = buffer.long
            val titleLength = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < titleLength) break
            val title = ByteArray(titleLength).also { buffer.get(it) }
            result.add(ChapterStart(startTicks / TICKS_PER_MS, String(title, Charsets.UTF_8).trim()))
        }
        return result
    }

    private fun toChapters(starts: List<ChapterStart>, durationMs: Long): List<Chapter> {
        val sorted = starts.filter { durationMs <= 0 || it.startMs < durationMs }.sortedBy { it.startMs }
        return sorted.mapIndexed { index, start ->
            val end = sorted.getOrNull(index + 1)?.startMs ?: maxOf(durationMs, start.startMs)
            Chapter(index, start.title.ifBlank { "Chapter ${index + 1}" }, start.startMs, end)
        }
    }

    private fun toMs(units: Long, timescale: Long): Long = if (timescale == 0L) 0 else units * 1000 / timescale

    private class Track(
        val id: Int,
        val handler: String?,
        val chapterRefs: List<Int>,
        val timescale: Long,
        val stbl: Atom?,
        val sampleFormat: String?,
    )

    private class SampleRef(val offset: Long, val size: Int, val timeUnits: Long)

    private class ChapterStart(val startMs: Long, val title: String)

    private class Atom(val type: String, val payloadStart: Int, val end: Int, val data: ByteArray) {
        val payloadSize: Int get() = end - payloadStart

        fun buffer(): ByteBuffer = ByteBuffer.wrap(data, payloadStart, payloadSize)

        fun children(skip: Int = 0): List<Atom> = parseAtoms(data, payloadStart + skip, end)

        fun child(type: String, skip: Int = 0): Atom? = children(skip).firstOrNull { it.type == type }
    }

    private companion object {
        const val MAX_TOP_LEVEL_ATOMS = 64
        const val MAX_MOOV_BYTES = 64L * 1024 * 1024
        const val MAX_CHAPTER_SAMPLES = 5000
        const val COALESCE_LIMIT_BYTES = 1L * 1024 * 1024
        const val NERO_MAX_CHAPTERS = 255
        const val TICKS_PER_MS = 10_000L
        val KNOWN_TOP_LEVEL_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "styp", "sidx")
        val TEXT_HANDLERS = setOf("text", "sbtl", "subt")
        val TEXT_FORMATS = setOf("text", "tx3g")

        fun fourCc(data: ByteArray, offset: Int): String =
            if (offset + 4 <= data.size) String(data, offset, 4, Charsets.ISO_8859_1) else ""

        fun parseAtoms(data: ByteArray, from: Int, to: Int): List<Atom> {
            val result = mutableListOf<Atom>()
            var offset = from
            var valid = true
            while (valid && offset + 8 <= to) {
                var size = ByteBuffer.wrap(data, offset, 4).int.toLong() and 0xFFFFFFFFL
                val type = fourCc(data, offset + 4)
                var headerSize = 8
                if (size == 1L) {
                    if (offset + 16 > to) break
                    size = ByteBuffer.wrap(data, offset + 8, 8).long
                    headerSize = 16
                } else if (size == 0L) {
                    size = (to - offset).toLong()
                }
                if (size < headerSize) {
                    valid = false
                } else {
                    val end = minOf(to.toLong(), offset + size).toInt()
                    result.add(Atom(type, offset + headerSize, end, data))
                    offset = end
                }
            }
            return result
        }
    }
}
