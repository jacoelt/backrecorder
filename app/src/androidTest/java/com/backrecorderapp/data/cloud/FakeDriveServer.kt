package com.backrecorderapp.data.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * An in-process stand-in for the slice of the Drive v3 REST API this app uses.
 *
 * It exists so the **real** Drive client can be exercised: the test swaps the endpoint, not the
 * implementation, so request shapes, the resumable-upload protocol and the reflective JSON
 * parsing all run for real against recorded, assertable state.
 *
 * What it models, and why each piece is here:
 * - `files.list` with Drive's `q` language, because [DriveCloudStorage] resolves folders by query
 *   and a wrong parent clause is exactly the bug class this fake is for;
 * - `files.create`, both the resumable upload protocol the Drive client uses for media and the
 *   plain metadata POST used for folders;
 * - `files.get` / `files.update` with `addParents` / `removeParents`, which is how a recovery move
 *   happens, so the resulting parentage can be asserted;
 * - `files.delete`, so buffer cleanup can be observed — and so a recovery folder misplaced inside
 *   `staging` would actually be destroyed here, rather than merely looking wrong;
 * - `about.get`, which is how [DriveAboutIdentityResolver] decides whose Drive a token writes to.
 *
 * Tokens are checked on every request. [revokeToken] makes the server answer 401 to a token it
 * previously accepted, which is how the force-refresh path is driven. [switchAccount] models
 * connecting a *different* Google account: a fresh, empty Drive whose file ids the previous
 * account's cached ids can never match.
 *
 * Bound to the loopback interface only, on an ephemeral port.
 */
@Singleton
class FakeDriveServer @Inject constructor() {

    /** A file or folder in the fake Drive. */
    class Entry(
        val id: String,
        var name: String,
        val mimeType: String,
        val parents: MutableList<String>,
        val createdTime: Long,
        var content: ByteArray? = null,
        var trashed: Boolean = false
    )

    /** One request as the server saw it, for assertions about retries and bearer tokens. */
    data class Call(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val bearerToken: String?,
        val status: Int
    )

    private var serverSocket: ServerSocket? = null
    private var workers = Executors.newCachedThreadPool()
    private val ids = AtomicInteger(0)

    private val entries = Collections.synchronizedMap(LinkedHashMap<String, Entry>())
    private val calls = Collections.synchronizedList(mutableListOf<Call>())
    private val revoked = Collections.synchronizedSet(mutableSetOf<String>())

    /** Open resumable upload sessions: upload id to the metadata the initiation carried. */
    private val uploadSessions = Collections.synchronizedMap(mutableMapOf<String, JSONObject>())

    @Volatile
    private var accountId: String = DEFAULT_ACCOUNT_ID

    @Volatile
    private var accountEmail: String = DEFAULT_ACCOUNT_EMAIL

    @Volatile
    private var port: Int = 0

    /** Where the Drive client should point. Only valid once [start] has returned. */
    val rootUrl: String get() = "http://127.0.0.1:$port/"

    fun start() {
        if (serverSocket != null) return
        workers = Executors.newCachedThreadPool()
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        thread(name = "FakeDriveServer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    return@thread
                }
                workers.execute { serve(client) }
            }
        }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        workers.shutdownNow()
    }

    /** Forgets every file, call and revocation, keeping the current account. */
    fun reset() {
        entries.clear()
        calls.clear()
        revoked.clear()
        uploadSessions.clear()
        accountId = DEFAULT_ACCOUNT_ID
        accountEmail = DEFAULT_ACCOUNT_EMAIL
    }

    /**
     * Models connecting a different Google account: a Drive of its own, with none of the previous
     * account's files in it. Anything that reused a folder id across the switch would be writing
     * to an id this Drive has never heard of.
     */
    fun switchAccount(accountId: String, email: String) {
        entries.clear()
        uploadSessions.clear()
        this.accountId = accountId
        this.accountEmail = email
    }

    /** Makes the server answer 401 to [token], as Drive does once a token is no longer good. */
    fun revokeToken(token: String) {
        revoked += token
    }

    fun recordedCalls(): List<Call> = synchronized(calls) { calls.toList() }

    fun entries(): List<Entry> = synchronized(entries) { entries.values.toList() }

    fun entry(id: String): Entry? = entries[id]

    fun childrenOf(parentId: String): List<Entry> =
        entries().filter { !it.trashed && parentId in it.parents }

    /** The single entry named [name] whose parent is [parentId], or null. */
    fun child(parentId: String, name: String): Entry? =
        childrenOf(parentId).singleOrNull { it.name == name }

    /** Seeds a file directly, bypassing the API — for state a test needs to already exist. */
    fun seedFile(
        name: String,
        parentId: String,
        createdTime: Long,
        mimeType: String = "audio/mp4",
        content: ByteArray = ByteArray(0)
    ): Entry = newEntry(name, mimeType, mutableListOf(parentId), createdTime, content)

    fun seedFolder(name: String, parentId: String?): Entry = newEntry(
        name,
        FOLDER_MIME_TYPE,
        parentId?.let { mutableListOf(it) } ?: mutableListOf(),
        System.currentTimeMillis(),
        null
    )

    private fun newEntry(
        name: String,
        mimeType: String,
        parents: MutableList<String>,
        createdTime: Long,
        content: ByteArray?
    ): Entry {
        val entry = Entry(
            id = "file-" + ids.incrementAndGet(),
            name = name,
            mimeType = mimeType,
            parents = parents,
            createdTime = createdTime,
            content = content
        )
        entries[entry.id] = entry
        return entry
    }

    // ---------------------------------------------------------------- serving

    /**
     * Nothing may escape here. An uncaught exception on any thread kills the whole process on
     * Android, which would turn a dropped socket into an unexplained "Process crashed" instead of
     * a test failure.
     */
    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                val input = socket.getInputStream().buffered()
                val request = readRequest(input) ?: return
                val response = try {
                    handle(request)
                } catch (e: Exception) {
                    Response(500, error(500, "fake server failed: " + e.message))
                }
                calls += Call(
                    request.method,
                    request.path,
                    request.query,
                    request.bearerToken(),
                    response.status
                )
                write(socket.getOutputStream(), response)
            }
        } catch (e: Throwable) {
            android.util.Log.w("FakeDriveServer", "Dropped a request", e)
        }
    }

    private fun handle(request: Request): Response {
        val token = request.bearerToken()
        if (token == null || token in revoked) {
            // Exactly how Drive reports a token it will not accept, which is what drives
            // DriveAuthManager's force-refresh-and-retry handler.
            return Response(401, error(401, "Invalid Credentials"))
        }

        val path = request.path.trimEnd('/')
        val isUpload = "upload/" in path || request.query.containsKey("uploadType")
        val method = request.method

        return when {
            path.endsWith("/about") && method == "GET" -> about()

            method == "PUT" && isUpload -> finishResumableUpload(request)

            method == "POST" && isUpload -> startUpload(request)

            path.endsWith("/files") && method == "GET" -> list(request)

            path.endsWith("/files") && method == "POST" ->
                created(createFromMetadata(JSONObject(request.bodyText()), null))

            method == "GET" -> get(fileIdOf(path))

            method == "PATCH" -> update(request, fileIdOf(path))

            method == "DELETE" -> delete(fileIdOf(path))

            else -> Response(404, error(404, "unsupported: " + method + " " + path))
        }
    }

    private fun fileIdOf(path: String): String = path.substringAfterLast('/')

    private fun about(): Response = Response(
        200,
        JSONObject().put(
            "user",
            JSONObject()
                .put("emailAddress", accountEmail)
                .put("permissionId", accountId)
        ).toString()
    )

    /**
     * The Drive client uploads media resumably: a POST carrying only the metadata, answered with
     * a `Location` to PUT the bytes to.
     */
    private fun startUpload(request: Request): Response {
        val metadata = JSONObject(request.bodyText().ifBlank { "{}" })
        if (request.query["uploadType"] == "multipart") {
            // The other shape the client can take, kept so a future switch to direct upload does
            // not silently stop exercising this server.
            val parts = multipartBodies(request)
            val meta = parts.firstOrNull()?.let { JSONObject(String(it, Charsets.UTF_8)) }
                ?: metadata
            return created(createFromMetadata(meta, parts.getOrNull(1)))
        }

        val uploadId = "upload-" + ids.incrementAndGet()
        uploadSessions[uploadId] = metadata
        return Response(
            status = 200,
            body = "",
            headers = mapOf(
                "Location" to (rootUrl + "upload/drive/v3/files?upload_id=" + uploadId)
            )
        )
    }

    private fun finishResumableUpload(request: Request): Response {
        val uploadId = request.query["upload_id"]
            ?: return Response(400, error(400, "no upload_id"))
        val metadata = uploadSessions.remove(uploadId)
            ?: return Response(404, error(404, "unknown upload session"))
        return created(createFromMetadata(metadata, request.body))
    }

    private fun createFromMetadata(metadata: JSONObject, content: ByteArray?): Entry {
        val parents = metadata.optJSONArray("parents")?.let { array ->
            MutableList(array.length()) { array.getString(it) }
        } ?: mutableListOf()
        return newEntry(
            name = metadata.optString("name", "unnamed"),
            mimeType = metadata.optString("mimeType", "application/octet-stream"),
            parents = parents,
            createdTime = System.currentTimeMillis(),
            content = content
        )
    }

    private fun created(entry: Entry) = Response(200, entryJson(entry).toString())

    private fun get(id: String): Response {
        val entry = entries[id] ?: return Response(404, error(404, "File not found: " + id))
        return Response(200, entryJson(entry).toString())
    }

    private fun update(request: Request, id: String): Response {
        val entry = entries[id] ?: return Response(404, error(404, "File not found: " + id))
        request.query["removeParents"]?.split(",")?.filter { it.isNotBlank() }?.forEach {
            entry.parents.remove(it)
        }
        request.query["addParents"]?.split(",")?.filter { it.isNotBlank() }?.forEach {
            if (it !in entry.parents) entry.parents += it
        }
        val body = request.bodyText()
        if (body.isNotBlank()) {
            JSONObject(body).optString("name").takeIf { it.isNotEmpty() }?.let { entry.name = it }
        }
        return Response(200, entryJson(entry).toString())
    }

    private fun delete(id: String): Response {
        val removed = entries.remove(id) ?: return Response(404, error(404, "File not found: " + id))
        // Drive deletes a folder's contents with it. Modelling that is the whole point of this
        // fake for the recovery flow: a recovery folder left inside `staging` really does take
        // the recordings in it when cleanup deletes it.
        entries().filter { removed.id in it.parents }.forEach { delete(it.id) }
        return Response(200, "{}")
    }

    private fun list(request: Request): Response {
        val matcher = QueryMatcher(request.query["q"].orEmpty())
        val matched = entries()
            .filter(matcher::matches)
            .sortedBy { it.createdTime }
        val files = JSONArray()
        matched.forEach { files.put(entryJson(it)) }
        return Response(200, JSONObject().put("files", files).toString())
    }

    private fun entryJson(entry: Entry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("name", entry.name)
        .put("mimeType", entry.mimeType)
        .put("trashed", entry.trashed)
        .put("createdTime", rfc3339().format(Date(entry.createdTime)))
        .put("parents", JSONArray().apply { entry.parents.forEach { put(it) } })

    private fun error(code: Int, message: String): String = JSONObject()
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put(
                    "errors",
                    JSONArray().put(JSONObject().put("message", message).put("reason", "fake"))
                )
        )
        .toString()

    // ------------------------------------------------------------ Drive's `q`

    /**
     * The handful of `q` clauses [DriveCloudStorage] actually sends. Unknown clauses fail loudly
     * rather than matching everything: a silently-ignored parent clause would turn a misplaced
     * folder into a passing test.
     */
    private class QueryMatcher(query: String) {
        private val clauses: List<(Entry) -> Boolean> =
            query.split(" and ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(::compile)

        fun matches(entry: Entry): Boolean = clauses.all { it(entry) }

        private companion object {
            val NAME = Regex("""name\s*=\s*'(.*)'""")
            val MIME_IS = Regex("""mimeType\s*=\s*'(.*)'""")
            val MIME_IS_NOT = Regex("""mimeType\s*!=\s*'(.*)'""")
            val IN_PARENTS = Regex("""'(.*)'\s+in\s+parents""")
            val TRASHED = Regex("""trashed\s*=\s*(true|false)""")

            fun compile(clause: String): (Entry) -> Boolean {
                NAME.matchEntire(clause)?.let { m ->
                    val name = unescape(m.groupValues[1])
                    return { it.name == name }
                }
                MIME_IS_NOT.matchEntire(clause)?.let { m ->
                    val mime = unescape(m.groupValues[1])
                    return { it.mimeType != mime }
                }
                MIME_IS.matchEntire(clause)?.let { m ->
                    val mime = unescape(m.groupValues[1])
                    return { it.mimeType == mime }
                }
                IN_PARENTS.matchEntire(clause)?.let { m ->
                    val parent = unescape(m.groupValues[1])
                    return { parent in it.parents }
                }
                TRASHED.matchEntire(clause)?.let { m ->
                    val trashed = m.groupValues[1].toBoolean()
                    return { it.trashed == trashed }
                }
                error("FakeDriveServer cannot evaluate the Drive query clause: '" + clause + "'")
            }

            /** Undoes the escaping [DriveCloudStorage] applies to query literals. */
            fun unescape(value: String): String =
                value.replace("""\'""", "'").replace("""\\""", """\""")
        }
    }

    // -------------------------------------------------------------- HTTP bits

    private class Request(
        /**
         * The effective method. An `X-HTTP-Method-Override` header wins over the request line:
         * the Drive client tunnels PATCH through POST that way, because PATCH does not survive
         * every proxy — and a recovery move is a PATCH, so honouring it is not optional.
         */
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun bearerToken(): String? =
            headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    private class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.take(colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }

        // The Drive client gzips request bodies and sends them chunked (google-api-client enables
        // GZipEncoding unless a request disables it), so neither framing is optional here.
        val raw = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunked(input)
        } else {
            readFixedLength(input, headers["content-length"]?.toIntOrNull() ?: 0)
        }
        val body = if (headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }

        val target = parts[1]
        return Request(
            method = (headers["x-http-method-override"] ?: parts[0]).uppercase(Locale.US),
            path = target.substringBefore('?'),
            query = parseQuery(target.substringAfter('?', "")),
            headers = headers,
            body = body
        )
    }

    private fun readFixedLength(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return body
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            // The size line may carry chunk extensions after a ';'.
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(readFixedLength(input, size))
            readLine(input) // the CRLF that terminates the chunk
        }
        // Trailers, then the final empty line.
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        return out.toByteArray()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val key = it.substringBefore('=')
                val value = it.substringAfter('=', "")
                decode(key) to decode(value)
            }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (c == '\n'.code) return buffer.toString("UTF-8").removeSuffix("\r")
            buffer.write(c)
        }
    }

    private fun write(output: OutputStream, response: Response) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n")
            append("Content-Length: " + body.size + "\r\n")
            response.headers.forEach { (name, value) -> append(name + ": " + value + "\r\n") }
            // One request per connection keeps the parser honest: no keep-alive framing to get
            // wrong, and the client reconnects happily.
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Error"
    }

    /** Splits a `multipart/related` body into its parts' contents, in order. */
    private fun multipartBodies(request: Request): List<ByteArray> {
        val boundary = request.headers["content-type"]
            ?.substringAfter("boundary=", "")
            ?.trim('"', ' ')
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()

        val delimiter = ("--" + boundary).toByteArray(Charsets.UTF_8)
        val body = request.body
        val bodies = mutableListOf<ByteArray>()
        var cursor = indexOf(body, delimiter, 0)
        while (cursor >= 0) {
            val partStart = cursor + delimiter.size
            if (partStart < body.size && body[partStart] == '-'.code.toByte()) break
            val headerEnd = indexOf(body, HEADER_END, partStart)
            if (headerEnd < 0) break
            val contentStart = headerEnd + HEADER_END.size
            val next = indexOf(body, delimiter, contentStart)
            if (next < 0) break
            // The CRLF before the next delimiter belongs to the framing, not the content.
            bodies += body.copyOfRange(contentStart, (next - 2).coerceAtLeast(contentStart))
            cursor = next
        }
        return bodies
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun rfc3339(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val DEFAULT_ACCOUNT_ID = "fake-account-1"
        const val DEFAULT_ACCOUNT_EMAIL = "first@example.com"

        private const val BACKLOG = 16
        private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.UTF_8)
    }
}
