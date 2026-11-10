package fr.husi.shiftvps

import fr.husi.core.CoreClient
import fr.husi.database.DataStore
import fr.husi.proto.v1.HTTPFetchResponse
import fr.husi.proto.v1.hTTPFetchRequest
import fr.husi.proto.v1.hTTPFetchSocks5
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private const val SAMPLE_BYTES = 2_000_000

internal suspend fun measureDownloadKbps(core: CoreClient): Int? {
    val proxyPort = DataStore.mixedPort.get()
    val proxyUsername = DataStore.inboundUsername.get()
    val proxyPassword = DataStore.inboundPassword.get()
    val request = hTTPFetchRequest {
        url = "https://speed.cloudflare.com/__down?bytes=$SAMPLE_BYTES"
        headers["Accept-Encoding"] = "identity"
        socks5 = hTTPFetchSocks5 {
            port = proxyPort
            username = proxyUsername
            password = proxyPassword
        }
    }
    return withTimeoutOrNull(10_000L) {
        var receivedBytes = 0
        var responseAt: TimeSource.Monotonic.ValueTimeMark? = null
        core.httpFetch(request).collect { message ->
            when (message.payloadCase) {
                HTTPFetchResponse.PayloadCase.HEAD -> {
                    check(responseAt == null)
                    val length = message.head.contentLength
                    check(length == -1L || length == SAMPLE_BYTES.toLong())
                    responseAt = TimeSource.Monotonic.markNow()
                }
                HTTPFetchResponse.PayloadCase.CHUNK -> {
                    checkNotNull(responseAt)
                    receivedBytes += message.chunk.size()
                    check(receivedBytes <= SAMPLE_BYTES)
                }
                else -> Unit
            }
        }
        if (receivedBytes != SAMPLE_BYTES) return@withTimeoutOrNull null
        val milliseconds = checkNotNull(responseAt).elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        (receivedBytes * 8L / milliseconds).coerceIn(1, 10_000_000).toInt()
    }
}
