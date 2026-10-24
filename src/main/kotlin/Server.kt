import DNSHeader.RCode
import java.io.IOException
import java.io.EOFException
import java.io.DataOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.Socket
import jdk.internal.net.http.common.Cancelable
import kotlin.concurrent.getOrSet
import kotlin.coroutines.cancellation.CancellationException

suspend fun handlePacket(
    packet: DatagramPacket,
    hosts: Map<String, ByteArray>,
    parsed: DNSPacket
): ByteArray {
    val rcode = if (parsed.header.opcode == 0.toByte()) {
        RCode.NO_ERROR
    } else {
        return relayUpstream(packet)
    }
    val answers = parsed.questions.flatMap { handleQuestion(hosts, it) }
        .takeIf { it.isNotEmpty() }
        ?: return relayUpstream(packet)
    return DNSPacket(
        header = parsed.header.copy(qr = true, rcode = rcode, ancount = answers.size.toShort()),
        questions = parsed.questions,
        answers = answers,
        authorities = listOf(),
        additionals = listOf()
    ).toPacket()
}

val upstream = ThreadLocal<DatagramSocket>()

suspend fun relayUpstream(packet: DatagramPacket): ByteArray {
    try {
        val upstream = upstream.getOrSet { DatagramSocket() }
        upstream.soTimeout = 30_000
        upstream.send(
            DatagramPacket(packet.data, packet.length, InetSocketAddress("192.168.68.2", 8053))
        )
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        upstream.receive(packet)
        return packet.data.copyOf(packet.length)
    } catch (t: IOException) {
        runCatching { upstream.get()?.close() }
        upstream.set(null)
        throw CancellationException()
    }
}

fun handleQuestion(hosts: Map<String, ByteArray>, question: DNSQuestion): List<DNSRecord> {
    val query = when (question.name.size) {
        1 -> question.name.single()
        2 -> question.name.first().takeIf { question.name[1] != "local" }
            ?: return emptyList()

        else -> return emptyList<DNSRecord>()
    }
    println("Checking local query $query")
    val ip = hosts[query.lowercase()] ?: return emptyList()
    println("Returning $query -> ${ip.joinToString(".") { it.toUByte().toString() }}")
    return listOf(
        DNSRecord(
            name = question.name,
            type = DNSType.A,
            klass = DNSClass.IN,
            ttl = 60,
            data = ip
        )
    )
}


fun handleTcpClient(socket: Socket, hosts: Map<String, ByteArray>) {
    socket.use { sock ->
        val input = DataInputStream(sock.getInputStream())
        val output = DataOutputStream(sock.getOutputStream())
        while (true) {
            val len = try {
                input.readUnsignedShort()
            } catch (e: EOFException) {
                return
            }
            val query = ByteArray(len)
            input.readFully(query)
            val response = resolveTcp(hosts, query)
            output.writeShort(response.size)
            output.write(response)
            output.flush()
        }
    }
}

fun resolveTcp(hosts: Map<String, ByteArray>, query: ByteArray): ByteArray {
    val parsed = query.toDomain()
    if (parsed.header.opcode == 0.toByte()) {
        val answers = parsed.questions.flatMap { handleQuestion(hosts, it) }
        if (answers.isNotEmpty()) {
            return DNSPacket(
                header = parsed.header.copy(
                    qr = true,
                    rcode = RCode.NO_ERROR,
                    ancount = answers.size.toShort()
                ),
                questions = parsed.questions,
                answers = answers,
                authorities = listOf(),
                additionals = listOf()
            ).toPacket()
        }
    }
    return relayUpstreamTcp(query)
}

fun relayUpstreamTcp(query: ByteArray): ByteArray {
    Socket("192.168.68.2", 8053).use { sock ->
        sock.soTimeout = 30_000
        val output = DataOutputStream(sock.getOutputStream())
        val input = DataInputStream(sock.getInputStream())
        output.writeShort(query.size)
        output.write(query)
        output.flush()
        val len = input.readUnsignedShort()
        val response = ByteArray(len)
        input.readFully(response)
        return response
    }
}
