package com.skillions.deskpilot.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A byte stream to the server. TCP over Wi-Fi, or RFCOMM over Bluetooth. */
interface Transport : Closeable {
    val input: InputStream
    val output: OutputStream
}

class TcpTransport(host: String, port: Int, timeoutMs: Int = 4000) : Transport {
    private val socket = Socket().apply {
        tcpNoDelay = true          // mouse movement must not be batched by Nagle
        keepAlive = true
        connect(InetSocketAddress(host, port), timeoutMs)
    }
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override fun close() = socket.close()
}

/**
 * Experimental Bluetooth transport. The Python server listens on a fixed RFCOMM channel
 * without an SDP record, so we connect by channel number via the hidden createRfcommSocket(int).
 */
class BluetoothTransport @SuppressLint("MissingPermission") constructor(
    context: Context, address: String, channel: Int,
) : Transport {
    private val socket: BluetoothSocket

    init {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: error("Bluetooth not available")
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        socket = m.invoke(device, channel) as BluetoothSocket
        socket.connect()
    }

    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() = socket.close()
}

/** 4-byte length | 1-byte flag | payload. Matches server.py. */
object Frames {
    const val PLAIN = 0
    const val SEALED = 1
    private const val MAX = 4 * 1024 * 1024

    fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val n = input.readInt()
        require(n in 1..MAX) { "bad frame length $n" }
        val flag = input.readUnsignedByte()
        val body = ByteArray(n - 1)
        input.readFully(body)
        return flag to body
    }

    fun write(output: DataOutputStream, flag: Int, payload: ByteArray) {
        output.writeInt(payload.size + 1)
        output.writeByte(flag)
        output.write(payload)
        output.flush()
    }
}
