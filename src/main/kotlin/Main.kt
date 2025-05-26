import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>) {
    var hosts = mapOf<String, ByteArray>()
    thread {
        val json = Json {
            isLenient = true
            allowComments = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
        }
        runCatching {
            println("Waiting for hosts...")
            System.`in`.bufferedReader().lineSequence().map {
                json.decodeFromString<InputObject>(it)
            }.forEach { entries ->
                hosts = entries.hosts.associate { entry ->
                    entry.host.lowercase() to entry.ip.split(".").map { it.toInt().toByte() }
                        .toByteArray()
                }
                println(
                    "Updated hosts to [\n    ${
                        hosts.entries.joinToString("\n    ") {
                            "${it.key}=${it.value.joinToString(".") { it.toUByte().toString() }}"
                        }
                    }\n]"
                )
            }
        }.onFailure {
            it.printStackTrace()
        }
    }
    try {
        val serverSocket = DatagramSocket(8053)
        while (true) {
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            serverSocket.receive(packet)

            val parsed = packet.data.toDomain()
            val response = handlePacket(hosts, parsed)
            val responsePacket = response.toPacket()

            val packetResponse =
                DatagramPacket(responsePacket, responsePacket.size, packet.socketAddress)
            serverSocket.send(packetResponse)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        println("Exception: $e")
    }
}

@Serializable
data class InputObject(val hosts: List<HostEntry>)

@Serializable
data class HostEntry(val host: String, val ip: String)
