package dk.azp.jellybook.data.chapters

import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ChapterParserTest {

    @Test
    fun readsNeroChaptersFromUdta() = runTest {
        val file = ftyp() + moov(mvhd(timescale = 1000, duration = 600_000), udta(chpl(listOf(0L to "Intro", 120_000L to "Part One", 400_000L to "Part Two")))) + mdat(ByteArray(64))

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertNotNull(result)
        assertEquals(600_000L, result!!.durationMs)
        assertEquals(listOf("Intro", "Part One", "Part Two"), result.chapters.map { it.title })
        assertEquals(listOf(0L, 120_000L, 400_000L), result.chapters.map { it.startMs })
        assertEquals(listOf(120_000L, 400_000L, 600_000L), result.chapters.map { it.endMs })
    }

    @Test
    fun readsQuickTimeChapterTrackWithMoovAfterMdat() = runTest {
        val titles = listOf("Chapter 1", "Chapter 2", "Chapter 3")
        val samples = titles.map { textSample(it) }
        val mdatHeader = 8
        val sampleData = samples.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val ftyp = ftyp()
        val firstSampleOffset = (ftyp.size + mdatHeader + 100).toLong()
        val mdatPayload = ByteArray(100) + sampleData
        val chapterTrack = trak(
            tkhd(trackId = 2),
            mdia(
                mdhd(timescale = 600),
                hdlr("text"),
                minf(stbl(
                    stsd("text"),
                    stts(listOf(1 to 90_000, 1 to 60_000, 1 to 30_000)),
                    stsc(listOf(Triple(1, 3, 1))),
                    stsz(samples.map { it.size }),
                    stco(listOf(firstSampleOffset)),
                )),
            ),
        )
        val audioTrack = trak(tkhd(trackId = 1), tref(chap(listOf(2))), mdia(mdhd(timescale = 44_100), hdlr("soun"), minf(stbl())))
        val file = ftyp + mdat(mdatPayload) + moov(mvhd(timescale = 600, duration = 180_000), audioTrack, chapterTrack)

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertNotNull(result)
        assertEquals(300_000L, result!!.durationMs)
        assertEquals(titles, result.chapters.map { it.title })
        assertEquals(listOf(0L, 150_000L, 250_000L), result.chapters.map { it.startMs })
        assertEquals(listOf(150_000L, 250_000L, 300_000L), result.chapters.map { it.endMs })
    }

    @Test
    fun prefersChapterTrackWhenNeroListIsAtItsLimit() = runTest {
        val neroChapters = (0 until 255).map { (it * 1000L) to "Nero $it" }
        val titles = (0 until 300).map { "Track $it" }
        val samples = titles.map { textSample(it) }
        val ftyp = ftyp()
        val sampleData = samples.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val chapterTrack = trak(
            tkhd(trackId = 2),
            mdia(
                mdhd(timescale = 1000),
                hdlr("text"),
                minf(stbl(
                    stsd("text"),
                    stts(listOf(300 to 1000)),
                    stsc(listOf(Triple(1, 300, 1))),
                    stsz(samples.map { it.size }),
                    stco(listOf((ftyp.size + 8).toLong())),
                )),
            ),
        )
        val audioTrack = trak(tkhd(trackId = 1), tref(chap(listOf(2))), mdia(mdhd(timescale = 44_100), hdlr("soun"), minf(stbl())))
        val file = ftyp + mdat(sampleData) + moov(mvhd(1000, 400_000), udta(chpl(neroChapters)), audioTrack, chapterTrack)

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertEquals(300, result!!.chapters.size)
        assertEquals("Track 299", result.chapters.last().title)
    }

    @Test
    fun returnsEmptyChapterListWhenFileHasNone() = runTest {
        val file = ftyp() + moov(mvhd(timescale = 1000, duration = 5_000)) + mdat(ByteArray(10))

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertNotNull(result)
        assertTrue(result!!.chapters.isEmpty())
        assertEquals(5_000L, result.durationMs)
    }

    @Test
    fun readsOnlyTheChapterDataFromAFileWithAHugeMoov() = runTest {
        // A real audiobook's moov runs to megabytes, nearly all of it the audio track's sample tables.
        val padding = ByteArray(6 * 1024 * 1024)
        val audioTrack = trak(
            tkhd(trackId = 1),
            mdia(mdhd(timescale = 44_100), hdlr("soun"), minf(stbl(stsd("mp4a"), box("stsz", padding)))),
        )
        val file = ftyp() +
            moov(mvhd(timescale = 1000, duration = 600_000), audioTrack, udta(chpl(listOf(0L to "One", 300_000L to "Two")))) +
            mdat(ByteArray(64))
        val source = CountingSource(file)

        val result = Mp4ChapterParser(source).parse()

        assertEquals(listOf("One", "Two"), result!!.chapters.map { it.title })
        assertTrue("read ${source.bytesRead} bytes from a ${file.size} byte file", source.bytesRead < 64 * 1024)
    }

    @Test
    fun readsChapterTitlesScatteredAcrossALargeMdat() = runTest {
        // A long audiobook stores its chapter titles in separate chunks far apart, so they cannot be read in one go.
        val titles = (1..16).map { "Chapter $it" }
        val samples = titles.map { textSample(it) }
        val gap = 300_000
        val ftyp = ftyp()
        val mdatStart = ftyp.size + 8
        val payload = ByteArray(gap * samples.size + 64)
        val offsets = samples.mapIndexed { index, sample ->
            val at = index * gap
            sample.copyInto(payload, at)
            (mdatStart + at).toLong()
        }
        val chapterTrack = trak(
            tkhd(trackId = 7),
            mdia(
                mdhd(timescale = 1000),
                hdlr("text"),
                minf(stbl(
                    stsd("text"),
                    stts(listOf(samples.size to 60_000)),
                    stsc(listOf(Triple(1, 1, 1))),
                    stsz(samples.map { it.size }),
                    stco(offsets),
                )),
            ),
        )
        val audioTrack = trak(tkhd(trackId = 1), tref(chap(listOf(7))), mdia(mdhd(timescale = 44_100), hdlr("soun"), minf(stbl())))
        val file = ftyp + mdat(payload) + moov(mvhd(timescale = 1000, duration = 16 * 60_000), audioTrack, chapterTrack)

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertEquals(titles, result!!.chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L, 120_000L), result.chapters.take(3).map { it.startMs })
    }

    @Test
    fun aChildDeclaringSizeZeroDoesNotHideTheChaptersInsideIt() = runTest {
        // Size zero means "runs to the end of the container". Treating it as the end of the tree loses the atom itself.
        val chapters = chpl(listOf(0L to "One", 200_000L to "Two"))
        val openEndedUdta = u32(0) + "udta".toByteArray(Charsets.ISO_8859_1) + chapters
        val file = ftyp() + moov(mvhd(timescale = 1000, duration = 600_000), openEndedUdta) + mdat(ByteArray(32))

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertEquals(listOf("One", "Two"), result!!.chapters.map { it.title })
    }

    @Test
    fun aChildOverrunningItsParentDoesNotHideTheChapterTrack() = runTest {
        val titles = listOf("Chapter 1", "Chapter 2")
        val samples = titles.map { textSample(it) }
        val ftyp = ftyp()
        val firstSampleOffset = (ftyp.size + 8).toLong()
        val chapterTrack = trak(
            tkhd(trackId = 2),
            mdia(
                mdhd(timescale = 1000),
                hdlr("text"),
                minf(stbl(
                    stsd("text"),
                    stts(listOf(2 to 120_000)),
                    stsc(listOf(Triple(1, 2, 1))),
                    stsz(samples.map { it.size }),
                    stco(listOf(firstSampleOffset)),
                )),
            ),
        )
        val audioTrack = trak(tkhd(trackId = 1), tref(chap(listOf(2))), mdia(mdhd(timescale = 44_100), hdlr("soun"), minf(stbl())))
        // The chapter track's declared size runs eight bytes past the end of moov, as a sloppy writer can leave it.
        val overrunning = chapterTrack.copyOf()
        val declared = ByteBuffer.wrap(overrunning, 0, 4).int + 8
        ByteBuffer.wrap(overrunning, 0, 4).putInt(declared)
        val file = ftyp + mdat(samples.fold(ByteArray(0)) { acc, bytes -> acc + bytes }) +
            moov(mvhd(timescale = 1000, duration = 240_000), audioTrack, overrunning)

        val result = Mp4ChapterParser(ByteArraySource(file)).parse()

        assertEquals(titles, result!!.chapters.map { it.title })
    }

    @Test
    fun rejectsNonMp4Input() = runTest {
        val mp3Header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0) + ByteArray(64)

        assertNull(Mp4ChapterParser(ByteArraySource(mp3Header)).parse())
    }

    /** Records how much of the file the parser actually pulls. */
    private class CountingSource(private val bytes: ByteArray) : RandomAccessSource {
        var bytesRead = 0L
            private set

        override suspend fun read(offset: Long, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end).also { bytesRead += it.size }
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
        override suspend fun read(offset: Long, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return u32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun fullBox(type: String, version: Int, vararg payloads: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0), *payloads)

    private fun ftyp() = box("ftyp", "M4A ".toByteArray(), u32(0), "M4A mp42isom".toByteArray())

    private fun mdat(payload: ByteArray) = box("mdat", payload)

    private fun moov(vararg children: ByteArray) = box("moov", *children)

    private fun mvhd(timescale: Int, duration: Long) = fullBox("mvhd", 0, u32(0), u32(0), u32(timescale), u32(duration.toInt()), ByteArray(80))

    private fun udta(vararg children: ByteArray) = box("udta", *children)

    private fun chpl(chapters: List<Pair<Long, String>>): ByteArray {
        val entries = chapters.fold(ByteArray(0)) { acc, (startMs, title) ->
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            acc + u64(startMs * 10_000) + byteArrayOf(titleBytes.size.toByte()) + titleBytes
        }
        return fullBox("chpl", 1, u32(0), byteArrayOf(chapters.size.toByte()), entries)
    }

    private fun trak(vararg children: ByteArray) = box("trak", *children)

    private fun tkhd(trackId: Int) = fullBox("tkhd", 0, u32(0), u32(0), u32(trackId), u32(0), u32(0), ByteArray(60))

    private fun tref(vararg children: ByteArray) = box("tref", *children)

    private fun chap(trackIds: List<Int>) = box("chap", *trackIds.map { u32(it) }.toTypedArray())

    private fun mdia(vararg children: ByteArray) = box("mdia", *children)

    private fun mdhd(timescale: Int) = fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0), u32(0))

    private fun hdlr(handler: String) = fullBox("hdlr", 0, u32(0), handler.toByteArray(Charsets.ISO_8859_1), ByteArray(13))

    private fun minf(vararg children: ByteArray) = box("minf", *children)

    private fun stbl(vararg children: ByteArray) = box("stbl", *children)

    private fun stsd(format: String) = fullBox("stsd", 0, u32(1), box(format, ByteArray(8)))

    private fun stts(entries: List<Pair<Int, Int>>) =
        fullBox("stts", 0, u32(entries.size), *entries.map { (count, delta) -> u32(count) + u32(delta) }.toTypedArray())

    private fun stsc(entries: List<Triple<Int, Int, Int>>) =
        fullBox("stsc", 0, u32(entries.size), *entries.map { (first, perChunk, index) -> u32(first) + u32(perChunk) + u32(index) }.toTypedArray())

    private fun stsz(sizes: List<Int>) = fullBox("stsz", 0, u32(0), u32(sizes.size), *sizes.map { u32(it) }.toTypedArray())

    private fun stco(offsets: List<Long>) = fullBox("stco", 0, u32(offsets.size), *offsets.map { u32(it.toInt()) }.toTypedArray())

    private fun textSample(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun u64(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
}
