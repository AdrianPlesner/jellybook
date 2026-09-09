package dk.azp.jellybook.data.chapters

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Parses files produced by ffmpeg (jellyfin-ffmpeg) rather than hand-built atoms. */
class Mp4ChapterParserSampleFileTest {

    @Test
    fun readsChapterTrackFromFfmpegM4bWithoutNeroAtom() = runTest {
        val result = Mp4ChapterParser(resource("sample-chapter-track.m4b")).parse()

        assertNotNull(result)
        assertEquals(listOf("Part 1", "Part 2"), result!!.chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L), result.chapters.map { it.startMs })
    }

    @Test
    fun readsChaptersFromFfmpegMp4WithNeroAtom() = runTest {
        val result = Mp4ChapterParser(resource("sample-chpl-only.mp4")).parse()

        assertNotNull(result)
        assertEquals(listOf("Part 1", "Part 2"), result!!.chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L), result.chapters.map { it.startMs })
    }

    private fun resource(name: String): RandomAccessSource {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }.use { it.readBytes() }
        return object : RandomAccessSource {
            override suspend fun read(offset: Long, length: Int): ByteArray {
                if (offset >= bytes.size) return ByteArray(0)
                return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
            }
        }
    }
}
