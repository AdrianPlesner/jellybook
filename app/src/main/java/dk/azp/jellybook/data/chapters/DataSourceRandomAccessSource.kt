package dk.azp.jellybook.data.chapters

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves byte ranges through the same Media3 data source the player uses, so chapter parsing reads from the offline cache
 * when a book is downloaded and issues HTTP range requests otherwise.
 */
class DataSourceRandomAccessSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val cacheKey: String,
) : RandomAccessSource {

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val dataSource = factory.createDataSource()
        try {
            val spec = DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length.toLong()).setKey(cacheKey).build()
            val opened = openOrEmpty(dataSource, spec)
            if (opened) readFully(dataSource, length) else ByteArray(0)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun openOrEmpty(dataSource: DataSource, spec: DataSpec): Boolean = try {
        dataSource.open(spec)
        true
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == HTTP_RANGE_NOT_SATISFIABLE) false else throw e
    } catch (e: DataSourceException) {
        if (e.reason == DataSourceException.POSITION_OUT_OF_RANGE) false else throw e
    }

    private fun readFully(dataSource: DataSource, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var filled = 0
        var endOfInput = false
        while (filled < length && !endOfInput) {
            val read = dataSource.read(buffer, filled, length - filled)
            if (read == C.RESULT_END_OF_INPUT) {
                endOfInput = true
            } else if (read < 0) {
                throw IOException("Unexpected read result $read")
            } else {
                filled += read
            }
        }
        return if (filled == length) buffer else buffer.copyOf(filled)
    }

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
