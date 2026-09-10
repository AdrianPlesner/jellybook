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

data class Mp4Chapters(val durationMs: Long, val chapters: List<Chapter>, val trace: String = "")

/** Byte-range access to a file, local or remote. Returns fewer bytes than requested at end of file. */
interface RandomAccessSource {
    suspend fun read(offset: Long, length: Int): ByteArray
}

/**
 * Reads chapter markers from an MP4 container (m4b/m4a) without downloading the audio. Supports the two ways chapters are
 * stored in the wild: a QuickTime chapter text track referenced through `tref/chap`, and the Nero `udta/chpl` atom.
 *
 * The atom tree is walked by reading headers, and only the small atoms that carry chapter data are read in full. In a real
 * audiobook the `moov` atom runs to several megabytes, nearly all of it the audio track's sample tables, while the chapter
 * data is a few hundred bytes; reading the whole thing would mean a multi-megabyte download for every book opened.
 */
class Mp4ChapterParser(private val source: RandomAccessSource) {

    /** An atom located in the file, whose payload has not been read. */
    private class AtomRef(val type: String, val payloadStart: Long, val payloadEnd: Long) {
        val payloadSize: Long get() = payloadEnd - payloadStart
    }

    suspend fun parse(): Mp4Chapters? {
        val moov = findMoov() ?: return null
        val topLevel = childRefs(moov)
        val durationMs = topLevel.firstOrNull { it.type == "mvhd" }?.let { movieDurationMs(it) } ?: 0L
        val neroChapters = readNeroChapters(topLevel)
        val neroMayBeTruncated = neroChapters.isEmpty() || neroChapters.size >= NERO_MAX_CHAPTERS
        val trace = StringBuilder(
            "moov@${moov.payloadStart} children=${topLevel.count()} duration=${durationMs / 1000}s chpl=${neroChapters.size}",
        )
        val starts = if (neroMayBeTruncated) {
            readChapterTrack(topLevel, trace)?.takeIf { it.size > neroChapters.size } ?: neroChapters
        } else {
            neroChapters
        }
        val chapters = toChapters(starts, durationMs)
        trace.append(" markers=${starts.size} kept=${chapters.size}")
        return Mp4Chapters(durationMs, chapters, trace.toString())
    }

    /** Walks the top-level atoms until `moov` turns up, wherever the writer put it. */
    private suspend fun findMoov(): AtomRef? {
        var offset = 0L
        var index = 0
        var result: AtomRef? = null
        var scanning = true
        while (scanning && index < MAX_TOP_LEVEL_ATOMS) {
            val header = readHeader(offset) ?: break
            if (index == 0 && header.type !in KNOWN_TOP_LEVEL_TYPES) break
            if (header.type == "moov") {
                result = header.toRef()
                scanning = false
            } else {
                offset = header.end
                index++
            }
        }
        return result
    }

    private suspend fun readNeroChapters(moovChildren: List<AtomRef>): List<ChapterStart> {
        val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = childRefs(udta).firstOrNull { it.type == "chpl" } ?: return emptyList()
        if (chpl.payloadSize > MAX_SMALL_ATOM_BYTES) return emptyList()
        return parseNeroChapters(payloadOf(chpl))
    }

    /**
     * Reads the chapter text track: the one an audio track points at through `tref/chap`, or failing that the first text
     * track. Only that track's sample tables are read, never the audio track's.
     */
    private suspend fun readChapterTrack(moovChildren: List<AtomRef>, trace: StringBuilder): List<ChapterStart>? {
        val tracks = moovChildren.filter { it.type == "trak" }.map { describeTrack(it) }
        val referenced = tracks.flatMap { it.chapterRefs }.toSet()
        trace.append(" tracks=${tracks.size} refs=$referenced")
        val track = tracks.firstOrNull { it.id in referenced && it.stbl != null }
            ?: tracks.firstOrNull { it.stbl != null && it.handler in TEXT_HANDLERS }
        if (track == null) {
            trace.append(" chapterTrack=none")
            return null
        }
        val stbl = track.stbl
        trace.append(" chapterTrack=id${track.id} handler=${track.handler} timescale=${track.timescale} stbl=${stbl?.payloadSize}")
        if (stbl == null || track.timescale == 0L || stbl.payloadSize > MAX_SAMPLE_TABLE_BYTES) {
            trace.append(" rejected")
            return null
        }
        val samples = sampleTable(payloadOf(stbl, MAX_SAMPLE_TABLE_BYTES.toInt()))
        if (samples.isNullOrEmpty()) {
            trace.append(" samples=${samples?.size ?: -1}")
            return null
        }
        trace.append(" samples=${samples.size}")
        val texts = readSampleTexts(samples)
        return samples.mapIndexed { index, sample -> ChapterStart(toMs(sample.timeUnits, track.timescale), texts[index]) }
    }

    private suspend fun describeTrack(trak: AtomRef): Track {
        val children = childRefs(trak)
        val id = children.firstOrNull { it.type == "tkhd" }?.let { tkhd ->
            val buffer = ByteBuffer.wrap(payloadOf(tkhd))
            val version = buffer.get().toInt()
            buffer.position(buffer.position() + 3 + if (version == 1) 16 else 8)
            buffer.int
        } ?: 0
        val chapterRefs = children.firstOrNull { it.type == "tref" }
            ?.let { tref -> childRefs(tref).firstOrNull { it.type == "chap" } }
            ?.let { chap ->
                val buffer = ByteBuffer.wrap(payloadOf(chap))
                List((chap.payloadSize / 4).toInt()) { buffer.int }
            }
            ?: emptyList()
        val mdia = children.firstOrNull { it.type == "mdia" } ?: return Track(id, null, chapterRefs, 0, null)
        val mdiaChildren = childRefs(mdia)
        val timescale = mdiaChildren.firstOrNull { it.type == "mdhd" }?.let { mdhd ->
            val buffer = ByteBuffer.wrap(payloadOf(mdhd))
            val version = buffer.get().toInt()
            buffer.position(buffer.position() + 3 + if (version == 1) 16 else 8)
            buffer.int.toLong() and 0xFFFFFFFFL
        } ?: 0L
        val handler = mdiaChildren.firstOrNull { it.type == "hdlr" }?.let { hdlr ->
            val payload = payloadOf(hdlr, limit = 12)
            if (payload.size >= 12) String(payload, 8, 4, Charsets.ISO_8859_1) else null
        }
        val stbl = mdiaChildren.firstOrNull { it.type == "minf" }
            ?.let { minf -> childRefs(minf).firstOrNull { it.type == "stbl" } }
        return Track(id, handler, chapterRefs, timescale, stbl)
    }

    private suspend fun movieDurationMs(mvhd: AtomRef): Long {
        val buffer = ByteBuffer.wrap(payloadOf(mvhd, limit = MVHD_BYTES))
        val version = buffer.get().toInt()
        buffer.position(buffer.position() + 3)
        return if (version == 1) {
            buffer.position(buffer.position() + 16)
            val timescale = buffer.int.toLong() and 0xFFFFFFFFL
            toMs(buffer.long, timescale)
        } else {
            buffer.position(buffer.position() + 8)
            val timescale = buffer.int.toLong() and 0xFFFFFFFFL
            toMs(buffer.int.toLong() and 0xFFFFFFFFL, timescale)
        }
    }

    private fun parseNeroChapters(payload: ByteArray): List<ChapterStart> {
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.remaining() < 5) return emptyList()
        val version = buffer.get().toInt()
        buffer.position(buffer.position() + 3)
        if (version != 0) buffer.position(buffer.position() + 4)
        val count = buffer.get().toInt() and 0xFF
        val result = ArrayList<ChapterStart>(count)
        var reading = true
        for (index in 0 until count) {
            if (!reading) break
            if (buffer.remaining() < 9) {
                reading = false
            } else {
                val startTicks = buffer.long
                val titleLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < titleLength) {
                    reading = false
                } else {
                    val title = ByteArray(titleLength).also { buffer.get(it) }
                    result.add(ChapterStart(startTicks / TICKS_PER_MS, String(title, Charsets.UTF_8).trim()))
                }
            }
        }
        return result
    }

    private fun sampleTable(stbl: ByteArray): List<SampleRef>? {
        val atoms = parseAtoms(stbl, 0, stbl.size)
        val stts = atoms.firstOrNull { it.type == "stts" } ?: return null
        val stsz = atoms.firstOrNull { it.type == "stsz" } ?: return null
        val stsc = atoms.firstOrNull { it.type == "stsc" } ?: return null
        val chunkOffsets = atoms.firstOrNull { it.type == "stco" }?.let { readChunkOffsets(it, wide = false) }
            ?: atoms.firstOrNull { it.type == "co64" }?.let { readChunkOffsets(it, wide = true) }
            ?: return null

        val sttsBuffer = stts.buffer()
        sttsBuffer.position(sttsBuffer.position() + 4)
        val sttsEntryCount = sttsBuffer.int
        val times = ArrayList<Long>()
        var elapsed = 0L
        var withinLimits = true
        for (entry in 0 until sttsEntryCount) {
            if (!withinLimits) break
            val sampleCount = sttsBuffer.int
            val delta = sttsBuffer.int.toLong() and 0xFFFFFFFFL
            for (sample in 0 until sampleCount) {
                if (times.size >= MAX_CHAPTER_SAMPLES) {
                    withinLimits = false
                    break
                }
                times.add(elapsed)
                elapsed += delta
            }
        }
        if (!withinLimits) return null

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

    private fun readChunkOffsets(atom: BufferAtom, wide: Boolean): List<Long> {
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

    private fun toChapters(starts: List<ChapterStart>, durationMs: Long): List<Chapter> {
        val sorted = starts.filter { durationMs <= 0 || it.startMs < durationMs }.sortedBy { it.startMs }
        return sorted.mapIndexed { index, start ->
            val end = sorted.getOrNull(index + 1)?.startMs ?: maxOf(durationMs, start.startMs)
            Chapter(index, start.title.ifBlank { "Chapter ${index + 1}" }, start.startMs, end)
        }
    }

    /** Reads one atom header, which is all that is needed to walk past it or descend into it. */
    private suspend fun readHeader(offset: Long): Header? {
        val bytes = source.read(offset, HEADER_BYTES)
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes)
        var size = buffer.int.toLong() and 0xFFFFFFFFL
        val type = String(bytes, 4, 4, Charsets.ISO_8859_1)
        var headerSize = 8
        if (size == 1L) {
            if (bytes.size < 16) return null
            size = ByteBuffer.wrap(bytes, 8, 8).long
            headerSize = 16
        }
        if (size < headerSize) return null
        return Header(type, offset + headerSize, offset + size)
    }

    /** The direct children of a container atom, found by walking their headers. */
    private suspend fun childRefs(parent: AtomRef, skip: Int = 0): List<AtomRef> {
        val children = ArrayList<AtomRef>()
        var offset = parent.payloadStart + skip
        var walking = true
        while (walking && offset + 8 <= parent.payloadEnd && children.size < MAX_CHILDREN) {
            val header = readHeader(offset)
            if (header == null || header.end > parent.payloadEnd || header.end <= offset) {
                walking = false
            } else {
                children.add(header.toRef())
                offset = header.end
            }
        }
        return children
    }

    private suspend fun payloadOf(atom: AtomRef, limit: Int = MAX_SMALL_ATOM_BYTES.toInt()): ByteArray {
        val length = atom.payloadSize.coerceAtMost(limit.toLong()).toInt()
        return if (length <= 0) ByteArray(0) else source.read(atom.payloadStart, length)
    }

    private fun toMs(units: Long, timescale: Long): Long = if (timescale == 0L) 0 else units * 1000 / timescale

    private class Header(val type: String, val payloadStart: Long, val end: Long) {
        fun toRef() = AtomRef(type, payloadStart, end)
    }

    private class Track(
        val id: Int,
        val handler: String?,
        val chapterRefs: List<Int>,
        val timescale: Long,
        val stbl: AtomRef?,
    )

    private class SampleRef(val offset: Long, val size: Int, val timeUnits: Long)

    private class ChapterStart(val startMs: Long, val title: String)

    /** An atom inside an already-read buffer, used for the small sample tables. */
    private class BufferAtom(val type: String, val payloadStart: Int, val end: Int, val data: ByteArray) {
        fun buffer(): ByteBuffer = ByteBuffer.wrap(data, payloadStart, end - payloadStart)
    }

    private companion object {
        const val HEADER_BYTES = 16
        const val MAX_TOP_LEVEL_ATOMS = 64
        const val MAX_CHILDREN = 64
        const val MAX_CHAPTER_SAMPLES = 5000
        const val COALESCE_LIMIT_BYTES = 1L * 1024 * 1024
        const val MAX_SMALL_ATOM_BYTES = 1L * 1024 * 1024
        const val MAX_SAMPLE_TABLE_BYTES = 4L * 1024 * 1024
        const val MVHD_BYTES = 120
        const val NERO_MAX_CHAPTERS = 255
        const val TICKS_PER_MS = 10_000L
        val KNOWN_TOP_LEVEL_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "styp", "sidx", "meta")
        val TEXT_HANDLERS = setOf("text", "sbtl", "subt")

        fun parseAtoms(data: ByteArray, from: Int, to: Int): List<BufferAtom> {
            val result = mutableListOf<BufferAtom>()
            var offset = from
            var valid = true
            while (valid && offset + 8 <= to) {
                var size = ByteBuffer.wrap(data, offset, 4).int.toLong() and 0xFFFFFFFFL
                val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
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
                    result.add(BufferAtom(type, offset + headerSize, end, data))
                    offset = end
                }
            }
            return result
        }
    }
}
