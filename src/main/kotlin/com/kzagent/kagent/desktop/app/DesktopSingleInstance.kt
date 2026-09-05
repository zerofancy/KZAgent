package com.kzagent.kagent.desktop.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal sealed interface DesktopLaunchRequest {
    data object Activate : DesktopLaunchRequest

    data class OpenWorkspace(val workspace: Path) : DesktopLaunchRequest
}

internal fun desktopLaunchRequest(
    initialWorkspace: Path,
    createStartupSession: Boolean,
): DesktopLaunchRequest = if (createStartupSession) {
    DesktopLaunchRequest.OpenWorkspace(initialWorkspace.toAbsolutePath().normalize())
} else {
    DesktopLaunchRequest.Activate
}

internal fun requireReadableWorkspace(workspace: Path): Path {
    val normalized = workspace.toAbsolutePath().normalize()
    check(Files.isDirectory(normalized) && Files.isReadable(normalized)) {
        "工作区目录不存在或不可读：$normalized"
    }
    return normalized
}

internal sealed interface DesktopInstanceStart {
    data class Primary(val coordinator: DesktopSingleInstanceCoordinator) : DesktopInstanceStart

    data object Forwarded : DesktopInstanceStart
}

/**
 * Owns the desktop process lock and a loopback-only IPC endpoint.
 *
 * The endpoint is written only after the server socket is bound. A contender that
 * observes the lock during that short setup window retries reading the endpoint,
 * so a second GUI is never used as a fallback for an IPC race or failure.
 */
internal class DesktopSingleInstanceCoordinator private constructor(
    private val randomAccessFile: RandomAccessFile,
    private val fileLock: FileLock,
    private val serverSocket: ServerSocket,
    private val token: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val requestChannel = Channel<DesktopLaunchRequest>(Channel.UNLIMITED)
    private val shutdownHook = Thread({ closeResources() }, "kzagent-instance-shutdown")
    private val serverThread = Thread(::acceptRequests, "kzagent-instance-server").apply {
        isDaemon = true
    }

    val requests: Flow<DesktopLaunchRequest> = requestChannel.receiveAsFlow()

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        serverThread.start()
    }

    private fun acceptRequests() {
        while (running.get()) {
            try {
                serverSocket.accept().use(::handleClient)
            } catch (error: Exception) {
                if (running.get()) {
                    desktopLog(
                        "single-instance server request failed: ${error.message ?: error}",
                        error
                    )
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        runCatching {
            check(input.readInt() == PROTOCOL_VERSION) { "unsupported protocol version" }
            check(readFrame(input) == token) { "invalid instance token" }
            val request = when (val type = input.readUnsignedByte()) {
                REQUEST_ACTIVATE -> DesktopLaunchRequest.Activate
                REQUEST_OPEN_WORKSPACE -> {
                    val rawPath = readFrame(input)
                    val path = Path.of(rawPath)
                    check(path.isAbsolute) { "workspace path must be absolute" }
                    DesktopLaunchRequest.OpenWorkspace(path.normalize())
                }
                else -> error("unsupported desktop request type: $type")
            }
            check(requestChannel.trySend(request).isSuccess) { "desktop request queue is closed" }
        }.onSuccess {
            output.writeByte(RESPONSE_ACK)
            output.flush()
        }.onFailure { error ->
            desktopLog("rejected single-instance request: ${error.message ?: error}")
            runCatching {
                output.writeByte(RESPONSE_NACK)
                output.flush()
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        closeResources()
    }

    private fun closeResources() {
        running.set(false)
        requestChannel.close()
        runCatching { serverSocket.close() }
        runCatching { fileLock.release() }
        runCatching { randomAccessFile.close() }
        // Keep the file itself in place. Deleting a locked file can create a
        // second inode on Unix and briefly allow two processes to own a lock.
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val REQUEST_ACTIVATE = 1
        private const val REQUEST_OPEN_WORKSPACE = 2
        private const val RESPONSE_ACK = 1
        private const val RESPONSE_NACK = 0
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val SOCKET_TIMEOUT_MILLIS = 2_000
        private const val DEFAULT_FORWARD_TIMEOUT_MILLIS = 2_000L
        private const val FORWARD_RETRY_MILLIS = 25L

        fun startOrForward(
            lockFile: Path,
            request: DesktopLaunchRequest,
            forwardTimeoutMillis: Long = DEFAULT_FORWARD_TIMEOUT_MILLIS,
        ): DesktopInstanceStart {
            require(forwardTimeoutMillis > 0) { "forward timeout must be positive" }
            lockFile.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
            val accessFile = RandomAccessFile(lockFile.toFile(), "rw")
            val lock = try {
                // Windows prevents reads that overlap an exclusive lock. Lock
                // only byte zero and store the endpoint after it so contenders
                // can read the connection details from the same file.
                accessFile.channel.tryLock(0, 1, false)
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Exception) {
                accessFile.close()
                throw error
            }

            if (lock == null) {
                accessFile.close()
                forwardToPrimary(lockFile, request, forwardTimeoutMillis)
                return DesktopInstanceStart.Forwarded
            }

            try {
                val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                val token = UUID.randomUUID().toString()
                writeEndpoint(accessFile, socket.localPort, token)
                return DesktopInstanceStart.Primary(
                    DesktopSingleInstanceCoordinator(accessFile, lock, socket, token),
                )
            } catch (error: Exception) {
                runCatching { lock.release() }
                runCatching { accessFile.close() }
                throw error
            }
        }

        private fun writeEndpoint(file: RandomAccessFile, port: Int, token: String) {
            val content = buildString {
                append("version=").append(PROTOCOL_VERSION).append('\n')
                append("port=").append(port).append('\n')
                append("token=").append(token).append('\n')
            }.toByteArray(StandardCharsets.UTF_8)
            file.seek(1)
            file.write(content)
            file.setLength(content.size.toLong() + 1)
            file.fd.sync()
        }

        private fun forwardToPrimary(
            lockFile: Path,
            request: DesktopLaunchRequest,
            timeoutMillis: Long,
        ) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            var lastFailure: Throwable? = null
            while (System.nanoTime() < deadline) {
                try {
                    val endpoint = readEndpoint(lockFile)
                    Socket().use { socket ->
                        val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000)
                            .coerceIn(1, SOCKET_TIMEOUT_MILLIS.toLong())
                            .toInt()
                        socket.connect(InetSocketAddress("127.0.0.1", endpoint.port), remainingMillis)
                        socket.soTimeout = remainingMillis
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeInt(PROTOCOL_VERSION)
                        writeFrame(output, endpoint.token)
                        when (request) {
                            DesktopLaunchRequest.Activate -> output.writeByte(REQUEST_ACTIVATE)
                            is DesktopLaunchRequest.OpenWorkspace -> {
                                output.writeByte(REQUEST_OPEN_WORKSPACE)
                                writeFrame(output, request.workspace.toAbsolutePath().normalize().toString())
                            }
                        }
                        output.flush()
                        val response = DataInputStream(socket.getInputStream()).readUnsignedByte()
                        check(response == RESPONSE_ACK) { "primary desktop instance rejected the request" }
                    }
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    Thread.sleep(FORWARD_RETRY_MILLIS)
                }
            }
            throw IllegalStateException(
                "Another KZAgent GUI is running, but the launch request could not be delivered.",
                lastFailure,
            )
        }

        private fun readEndpoint(lockFile: Path): Endpoint {
            val properties = RandomAccessFile(lockFile.toFile(), "r").use { file ->
                val contentLength = file.length() - 1
                check(contentLength in 1..MAX_ENDPOINT_BYTES.toLong()) {
                    "invalid instance endpoint length"
                }
                file.seek(1)
                val bytes = ByteArray(contentLength.toInt())
                file.readFully(bytes)
                Properties().apply {
                    bytes.inputStream().reader(StandardCharsets.UTF_8).use(::load)
                }
            }
            check(properties.getProperty("version")?.toIntOrNull() == PROTOCOL_VERSION) {
                "instance endpoint is not ready"
            }
            val port = properties.getProperty("port")?.toIntOrNull()
            check(port != null && port in 1..65535) { "invalid instance endpoint port" }
            val token = properties.getProperty("token").orEmpty()
            check(token.isNotBlank()) { "invalid instance endpoint token" }
            return Endpoint(port, token)
        }

        private fun writeFrame(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_FRAME_BYTES) { "desktop request frame is too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun readFrame(input: DataInputStream): String {
            val length = input.readInt()
            require(length in 0..MAX_FRAME_BYTES) { "invalid desktop request frame length: $length" }
            return String(input.readNBytes(length).also {
                check(it.size == length) { "incomplete desktop request frame" }
            }, StandardCharsets.UTF_8)
        }

        private data class Endpoint(val port: Int, val token: String)

        private const val MAX_ENDPOINT_BYTES = 4 * 1024
    }
}
