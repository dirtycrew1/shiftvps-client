package fr.husi.ktx

import fr.husi.fmt.AbstractBean
import fr.husi.fmt.Serializable
import fr.husi.fmt.anytls.parseAnyTLS
import fr.husi.fmt.http.parseHttp
import fr.husi.fmt.hysteria.parseHysteria1
import fr.husi.fmt.hysteria.parseHysteria2
import fr.husi.fmt.juicity.parseJuicity
import fr.husi.fmt.mieru.parseMieru
import fr.husi.fmt.naive.parseNaive
import fr.husi.fmt.parseUniversal
import fr.husi.fmt.shadowquic.parseShadowQUIC
import fr.husi.fmt.shadowsocks.parseShadowsocks
import fr.husi.fmt.socks.parseSOCKS
import fr.husi.fmt.trojan.parseTrojan
import fr.husi.fmt.trusttunnel.parseTrustTunnel
import fr.husi.fmt.tuic.parseTuic
import fr.husi.fmt.v2ray.parseV2Ray
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

fun String.b64EncodeUrlSafe(): String {
    return encodeUtf8().base64Url()
}

fun ByteArray.b64EncodeUrlSafe(): String {
    return toByteString().base64Url()
}

// v2rayN Style
fun ByteArray.b64EncodeOneLine(): String {
    return toByteString().base64()
}

fun String.b64EncodeOneLine(): String {
    return encodeUtf8().base64()
}

/**
 * Accepts both the standard and the URL-safe alphabet, optional padding,
 * and whitespace such as MIME line breaks.
 */
fun String.b64Decode(): ByteArray {
    val decoded = decodeBase64() ?: throw IllegalStateException("decode base64: invalid input")
    return decoded.toByteArray()
}

fun String.b64DecodeToString(): String {
    return b64Decode().decodeToString()
}

// zlib

fun ByteArray.zlibCompress(level: Int): ByteArray {
    // Compress the bytes
    // 1 to 4 bytes/char for UTF-8
    val output = ByteArray(size * 4)
    val compressor = Deflater(level).apply {
        setInput(this@zlibCompress)
        finish()
    }
    val compressedDataLength: Int = compressor.deflate(output)
    compressor.end()
    return output.copyOfRange(0, compressedDataLength)
}

fun ByteArray.zlibDecompress(): ByteArray {
    val inflater = Inflater()
    val outputStream = ByteArrayOutputStream()

    return outputStream.use {
        val buffer = ByteArray(1024)

        inflater.setInput(this)

        var count = -1
        while (count != 0) {
            count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }

        inflater.end()
        outputStream.toByteArray()
    }
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    // Imported links and parser exceptions can contain credentials.
    suspend fun String.parseLink(entities: MutableList<AbstractBean>) {
        if (startsWith("sing-box://import-remote-profile?") || startsWith("husi://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        val scheme = this.substringBefore("://")
        when (scheme) {
            "husi" -> {
                Logs.d("Try parse universal link")
                runCatching {
                    entities.add(parseUniversal(this))
                }.onFailure {
                    Logs.w("Universal link parse failed")
                }
            }

            "socks", "socks4", "socks4a", "socks5" -> {
                Logs.d("Try parse socks link")
                runCatching {
                    entities.add(parseSOCKS(this))
                }.onFailure {
                    Logs.w("SOCKS link parse failed")
                }
            }

            "http", "https" -> {
                Logs.d("Try parse http link")
                try {
                    entities.add(parseHttp(this))
                } catch (e: Exception) {
                    Logs.w("HTTP link parse failed")
                }
            }

            "vmess", "vless" -> {
                Logs.d("Try parse v2ray link")
                runCatching {
                    entities.add(parseV2Ray(this))
                }.onFailure {
                    Logs.w("V2Ray link parse failed")
                }
            }

            // Trojan-go was partially compatible
            "trojan", "trojan-go" -> {
                Logs.d("Try parse trojan link")
                runCatching {
                    entities.add(parseTrojan(this))
                }.onFailure {
                    Logs.w("Trojan link parse failed")
                }
            }

            "ss" -> {
                Logs.d("Try parse shadowsocks link")
                runCatching {
                    entities.add(parseShadowsocks(this))
                }.onFailure {
                    Logs.w("Shadowsocks link parse failed")
                }
            }

            "naive+https", "naive+quic" -> {
                Logs.d("Try parse naive link")
                runCatching {
                    entities.add(parseNaive(this))
                }.onFailure {
                    Logs.w("Naive link parse failed")
                }
            }

            "hysteria" -> {
                Logs.d("Try parse hysteria1 link")
                runCatching {
                    entities.add(parseHysteria1(this))
                }.onFailure {
                    Logs.w("Hysteria1 link parse failed")
                }
            }

            "hysteria2", "hy2" -> {
                Logs.d("Try parse hysteria2 link")
                runCatching {
                    entities.add(parseHysteria2(this))
                }.onFailure {
                    Logs.w("Hysteria2 link parse failed")
                }
            }

            "tuic" -> {
                Logs.d("Try parse TUIC link")
                runCatching {
                    entities.add(parseTuic(this))
                }.onFailure {
                    Logs.w("TUIC link parse failed")
                }
            }

            "juicity" -> {
                Logs.d("Try parse Juicity link")
                runCatching {
                    entities.add(parseJuicity(this))
                }.onFailure {
                    Logs.w("Juicity link parse failed")
                }
            }

            "sq", "shadowquic" -> {
                Logs.d("Try parse ShadowQUIC link")
                runCatching {
                    entities.add(parseShadowQUIC(this))
                }.onFailure {
                    Logs.w("ShadowQUIC link parse failed")
                }
            }

            "mierus" -> {
                Logs.d("Try parse Mieru link")
                runCatching {
                    entities.add(parseMieru(this))
                }.onFailure {
                    Logs.w("Mieru link parse failed")
                }
            }

            "anytls" -> {
                Logs.d("Try parse AnyTLS link")
                runCatching {
                    entities.add(parseAnyTLS(this))
                }.onFailure {
                    Logs.w("AnyTLS link parse failed")
                }
            }

            "tt" -> {
                Logs.d("Try parse TrustTunnel link")
                runCatching {
                    entities.add(parseTrustTunnel(this))
                }.onFailure {
                    Logs.w("TrustTunnel link parse failed")
                }
            }

        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
    var isBadLink = false
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
                isBadLink = true
                return@test
            }
        }
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}

fun String.substringBetween(after: String, before: String): String {
    return substringAfter(after).substringBefore(before)
}
