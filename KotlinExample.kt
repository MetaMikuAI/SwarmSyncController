import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DEVICE_PORT_DEFAULT = 6668

// Message framing constants
private const val PREFIX_6699_VALUE: Int = 0x00006699
private val PREFIX_6699_BIN = byteArrayOf(0x00, 0x00, 0x66, 0x99.toByte())
private val SUFFIX_6699_BIN = byteArrayOf(0x00, 0x00, 0x99.toByte(), 0x66.toByte())

// Header format: > I H I I I  (big endian: u32 prefix, u16 unknown=0, u32 seqno, u32 cmd, u32 length)
private const val HEADER_LEN = 4 + 2 + 4 + 4 + 4 // 18 bytes

// AES-GCM specifics
private const val GCM_TAG_LEN_BITS = 128
private const val GCM_IV_LEN = 12

// Commands
private const val SESS_KEY_NEG_START = 3
private const val SESS_KEY_NEG_RESP = 4
private const val SESS_KEY_NEG_FINISH = 5
private const val DP_QUERY_NEW = 0x10
private const val CONTROL_NEW = 13

// Header version prefix used by control payload (matches Python header_version)
private val HEADER_VERSION: ByteArray = run {
	val prefix = "3.5".toByteArray(Charsets.UTF_8)
	val zeros = ByteArray(12) { 0 }
	ByteBuffer.allocate(prefix.size + zeros.size).apply {
		put(prefix)
		put(zeros)
	}.array()
}

data class DecodedMessage(
	val seqno: Int,
	val cmd: Int,
	val payload: ByteArray
)

class NeuroLamp(
	localKey: String,
	private val deviceIp: String = "",
	private val devicePort: Int = DEVICE_PORT_DEFAULT
) {
	private var seqno: Int = 1
	private val hmacKey: ByteArray = localKey.toByteArray(Charsets.UTF_8)

	private var socket: Socket? = null
	private var input: DataInputStream? = null
	private var output: BufferedOutputStream? = null
	private var sessionKey: ByteArray? = null

	fun connect() {
		println("[NeuroLamp] Connecting to $deviceIp:$devicePort ...")
		val s = Socket(deviceIp, devicePort)
		s.soTimeout = 5000 // read timeout
		socket = s
		input = DataInputStream(BufferedInputStream(s.getInputStream()))
		output = BufferedOutputStream(s.getOutputStream())
		sessionKey = negotiateSessionKey()
		println("[NeuroLamp] Session established")
	}

	fun close() {
		try { output?.flush() } catch (_: Throwable) {}
		try { input?.close() } catch (_: Throwable) {}
		try { output?.close() } catch (_: Throwable) {}
		try { socket?.close() } catch (_: Throwable) {}
		input = null
		output = null
		socket = null
		sessionKey = null
		println("[NeuroLamp] Connection closed")
	}

	// --- Low-level encode/decode ---

	private fun packHeader(seq: Int, cmd: Int, lengthField: Int): ByteArray {
		val buf = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
		buf.putInt(PREFIX_6699_VALUE)
		buf.putShort(0) // unknown
		buf.putInt(seq)
		buf.putInt(cmd)
		buf.putInt(lengthField)
		return buf.array()
	}

	private fun timeIv12(): ByteArray {
		// Mimic Python: iv = str(time.time() * 10)[:12].encode('utf8')
		val value = (System.currentTimeMillis().toDouble() / 100.0) // ms / 100 ~= seconds * 10
		val s = String.format(Locale.US, "%f", value) // e.g., 17312345678.123456
		val sub = if (s.length >= 12) s.substring(0, 12) else s.padEnd(12, '0')
		return sub.toByteArray(Charsets.UTF_8)
	}

	private fun tuyaEncode(cmd: Int, payload: ByteArray, key: ByteArray? = null): ByteArray {
		val encKey = key ?: hmacKey
		val msgLen = payload.size + /* (16sI)-4 + 12 */ 28
		val header = packHeader(seqno, cmd, msgLen)

		val iv = timeIv12()
		val aad = header.copyOfRange(4, HEADER_LEN)

		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		val spec = GCMParameterSpec(GCM_TAG_LEN_BITS, iv)
		val secret = SecretKeySpec(encKey, "AES")
		cipher.init(Cipher.ENCRYPT_MODE, secret, spec)
		cipher.updateAAD(aad)

		val encAll = cipher.doFinal(payload) // ciphertext + tag
		if (encAll.size < 16) error("AES-GCM output too short")
		val ciphertext = encAll.copyOf(encAll.size - 16)
		val tag = encAll.copyOfRange(encAll.size - 16, encAll.size)

		val out = ByteBuffer.allocate(HEADER_LEN + GCM_IV_LEN + ciphertext.size + tag.size + 4)
		out.put(header)
		out.put(iv)
		out.put(ciphertext)
		out.put(tag)
		out.put(SUFFIX_6699_BIN)
		return out.array()
	}

	private fun tuyaDecode(data: ByteArray, key: ByteArray? = null): DecodedMessage? {
		val decKey = key ?: hmacKey
		if (data.size < HEADER_LEN + 4) return null

		val hdr = ByteBuffer.wrap(data, 0, HEADER_LEN).order(ByteOrder.BIG_ENDIAN)
		val prefix = hdr.int
		val unknown = hdr.short // unused
		val seq = hdr.int
		val cmd = hdr.int
		val payloadLen = hdr.int

		if (prefix != PREFIX_6699_VALUE) {
			println("[Decode] Invalid prefix: $prefix")
			return null
		}

		val ivStart = HEADER_LEN
		val ivEnd = ivStart + GCM_IV_LEN
		val tagStart = HEADER_LEN + payloadLen - 16
		val suffixStart = tagStart + 16

		if (data.size < suffixStart + 4) {
			println("[Decode] Message too short: expected ${suffixStart + 4}, got ${data.size}")
			return null
		}

		val iv = data.copyOfRange(ivStart, ivEnd)
		val encrypted = data.copyOfRange(ivEnd, tagStart)
		val tag = data.copyOfRange(tagStart, suffixStart)
		val suffix = data.copyOfRange(suffixStart, suffixStart + 4)

		if (!suffix.contentEquals(SUFFIX_6699_BIN)) {
			println("[Decode] Invalid suffix: ${suffix.joinToString(",")}")
			return null
		}

		val aad = data.copyOfRange(4, HEADER_LEN)

		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		val spec = GCMParameterSpec(GCM_TAG_LEN_BITS, iv)
		val secret = SecretKeySpec(decKey, "AES")
		cipher.init(Cipher.DECRYPT_MODE, secret, spec)
		cipher.updateAAD(aad)

		// JCE expects tag appended to ciphertext for doFinal
		val encAll = ByteBuffer.allocate(encrypted.size + tag.size).apply {
			put(encrypted)
			put(tag)
		}.array()

		val plain = cipher.doFinal(encAll)
		return DecodedMessage(seq, cmd, plain)
	}

	// --- Socket helpers ---

	private fun sendMessage(bytes: ByteArray) {
		val out = output ?: error("Socket not connected")
		out.write(bytes)
		out.flush()
	}

	private fun readFully(buf: ByteArray, off: Int, len: Int) {
		var read = 0
		val inp = input ?: error("Socket not connected")
		while (read < len) {
			val r = inp.read(buf, off + read, len - read)
			if (r < 0) error("Stream closed while reading")
			read += r
		}
	}

	private fun readMessage(): ByteArray {
		val header = ByteArray(HEADER_LEN)
		readFully(header, 0, HEADER_LEN)

		val hdr = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
		val prefix = hdr.int
		val unknown = hdr.short
		val seq = hdr.int
		val cmd = hdr.int
		val payloadLen = hdr.int

		if (prefix != PREFIX_6699_VALUE) error("Invalid prefix in stream: $prefix")

		val restLen = payloadLen + 4 // payload (iv + ciphertext + tag) + suffix(4)
		val rest = ByteArray(restLen)
		readFully(rest, 0, restLen)

		// Concatenate for decoder convenience
		val all = ByteBuffer.allocate(HEADER_LEN + restLen)
		all.put(header)
		all.put(rest)
		return all.array()
	}

	// --- Session key negotiation ---

	private fun negotiateSessionKey(): ByteArray? {
		val localNonce = "0123456789abcdef".toByteArray(Charsets.UTF_8) // 16 bytes, should be random in theory
		println("=== 会话密钥协商开始 ===")

		// Step 1
		println("步骤 1: 发送 SESS_KEY_NEG_START")
		seqno += 1
		val step1 = tuyaEncode(SESS_KEY_NEG_START, localNonce, hmacKey)
		sendMessage(step1)

		val resp1Raw = readMessage()
		val resp1 = tuyaDecode(resp1Raw, hmacKey)
		if (resp1 == null || resp1.cmd != SESS_KEY_NEG_RESP) {
			println("步骤 1 失败：未收到正确的 SESS_KEY_NEG_RESP")
			return null
		}
		println("步骤 1 成功：收到 SESS_KEY_NEG_RESP")

		val payload = resp1.payload
		if (payload.size < 52) {
			println("步骤 2 失败：负载长度不足 ${payload.size}")
			return null
		}

		val remoteNonce = payload.copyOfRange(4, 20)
		val receivedHmac = payload.copyOfRange(20, 52)

		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
		val expectedHmac = mac.doFinal(localNonce)
		if (!receivedHmac.contentEquals(expectedHmac)) {
			println("步骤 2 失败：HMAC 验证失败")
			return null
		}
		println("步骤 2 成功：HMAC 验证通过")

		// Step 3
		println("步骤 3: 发送 SESS_KEY_NEG_FINISH")
		mac.reset()
		mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
		val finishHmac = mac.doFinal(remoteNonce)

		seqno += 1
		val step3 = tuyaEncode(SESS_KEY_NEG_FINISH, finishHmac, hmacKey)
		sendMessage(step3)
		println("步骤 3 成功")

		// Step 4
		println("步骤 4: 生成会话密钥")
		val xorKey = ByteArray(16) { i -> (localNonce[i].toInt() xor remoteNonce[i].toInt()).toByte() }

		// Python uses AES-GCM with nonce localNonce[:12], and takes only ciphertext bytes (no tag)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		val spec = GCMParameterSpec(GCM_TAG_LEN_BITS, localNonce.copyOfRange(0, 12))
		val secret = SecretKeySpec(hmacKey, "AES")
		cipher.init(Cipher.ENCRYPT_MODE, secret, spec)
		val onlyCiphertext = cipher.update(xorKey) ?: ByteArray(0)
		// finalize to compute tag but ignore it (to mimic Python's encrypt() behavior)
		try { cipher.doFinal() } catch (_: Throwable) {}

		println("会话密钥协商完成！最终密钥: ${onlyCiphertext.joinToString("") { String.format("%02x", it) }}")
		return onlyCiphertext
	}

	// --- High-level API ---

	private fun jsonEscape(s: String): String = buildString(s.length + 8) {
		s.forEach { ch ->
			when (ch) {
				'\\' -> append("\\\\")
				'\"' -> append("\\\"")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				'\t' -> append("\\t")
				else -> append(ch)
			}
		}
	}

	private fun jsonValue(v: Any): String = when (v) {
		is Boolean -> if (v) "true" else "false"
		is Number -> v.toString()
		is String -> "\"${jsonEscape(v)}\""
		else -> "\"${jsonEscape(v.toString())}\""
	}

	private fun sendDpWrite(`switch`: Int, value: Any, nowait: Boolean = false): String? {
		val key = sessionKey ?: run {
			println("未建立会话密钥")
			return null
		}

		val t = (System.currentTimeMillis() / 1000L)
		val payloadJson = "{" +
			"\"protocol\":5," +
			"\"t\":$t," +
			"\"data\":{\"dps\":{\"$`switch`\":${jsonValue(value)}}}" +
			"}"
		println("[DP Write] switch=$`switch`, json=$payloadJson")
		val payloadBytes = ByteBuffer.allocate(HEADER_VERSION.size + payloadJson.toByteArray(Charsets.UTF_8).size).apply {
			put(HEADER_VERSION)
			put(payloadJson.toByteArray(Charsets.UTF_8))
		}.array()

		seqno += 1
		val msg = tuyaEncode(CONTROL_NEW, payloadBytes, key)
		sendMessage(msg)

		if (!nowait) {
			val respRaw = readMessage()
			val resp = tuyaDecode(respRaw, key) ?: return null
			val start = 19 // payload[4 + 15:]
			val bytes = if (resp.payload.size > start) resp.payload.copyOfRange(start, resp.payload.size) else ByteArray(0)
			return String(bytes, Charsets.UTF_8)
		}
		return null
	}

	fun getStatus(): String? {
		println("=== 获取设备状态 ===")
		val key = sessionKey ?: run {
			println("未建立会话密钥")
			return null
		}

		val payload = "{}".toByteArray(Charsets.UTF_8)
		seqno += 1
		val msg = tuyaEncode(DP_QUERY_NEW, payload, key)
		sendMessage(msg)

		val respRaw = readMessage()
		val resp = tuyaDecode(respRaw, key) ?: run {
			println("获取状态失败：未收到有效响应")
			return null
		}
		if (resp.cmd != DP_QUERY_NEW) {
			println("获取状态失败：收到意外的命令 ${resp.cmd}")
			return null
		}
		val clean = resp.payload.dropLastWhile { it == 0.toByte() }.toByteArray()
		return try {
			String(clean, Charsets.UTF_8)
		} catch (e: Exception) {
			println("解析状态数据失败: ${e.message}")
			println("原始payload: ${resp.payload.joinToString(",")}")
			null
		}
	}

	fun setStatus(on: Any, `switch`: Int, nowait: Boolean = false): String? = sendDpWrite(`switch`, on, nowait)

	fun setPower(on: Boolean, nowait: Boolean = false): String? = sendDpWrite(20, on, nowait)

	fun setColor(color: String, nowait: Boolean = false): String? = sendDpWrite(24, color, nowait)

	fun setMode(mode: String, nowait: Boolean = false): String? {
		val m = mode.toLowerCase(Locale.ROOT)
		require(m == "colour" || m == "music") { "模式只支持 'colour' 或 'music'" }
		return sendDpWrite(21, m, nowait)
	}

	fun setScene(scene: String, nowait: Boolean = false): String? = sendDpWrite(25, scene, nowait)

	fun setAutoOff(seconds: Int, nowait: Boolean = false): String? {
		require(seconds in 0..86400) { "定时关机秒数需在 0..86400 范围内" }
		return sendDpWrite(26, seconds, nowait)
	}

	fun setDoNotDisturb(on: Boolean, nowait: Boolean = false): String? = sendDpWrite(34, on, nowait)
}

fun main() {
	val localKey = "****************"
	val lamp = NeuroLamp(localKey, "192.168.43.187", DEVICE_PORT_DEFAULT)
	try {
		lamp.connect()
		println(lamp.getStatus())
		println(lamp.setPower(true))
		println(lamp.setColor("011f03e803e8"))
		println(lamp.setMode("colour"))
		println(lamp.setScene("000e0d00002e03e802cc00000000"))
		println(lamp.setAutoOff(300))
		println(lamp.setDoNotDisturb(false))
	} catch (e: Exception) {
		e.printStackTrace()
	} finally {
		lamp.close()
	}
}

