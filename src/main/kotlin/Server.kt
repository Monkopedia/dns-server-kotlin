import DNSHeader.RCode

fun handlePacket(hosts: Map<String, ByteArray>, parsed: DNSPacket): DNSPacket {
    val rcode = if (parsed.header.opcode == 0.toByte()) {
        RCode.NO_ERROR
    } else {
        RCode.NOT_IMPLEMENTED
    }
    val answers = parsed.questions.flatMap { handleQuestion(hosts, it) }
    return DNSPacket(
        header = parsed.header.copy(qr = true, rcode = rcode, ancount = answers.size.toShort()),
        questions = parsed.questions,
        answers = answers,
        authorities = listOf(),
        additionals = listOf()
    )
}

fun handleQuestion(hosts: Map<String, ByteArray>, question: DNSQuestion): List<DNSRecord> {
    val query = when (question.name.size) {
        1 -> listOf(question.name.single(), "local")
        2 -> question.name.takeIf { it[1] == "local" } ?: return emptyList()
        else -> return emptyList()
    }
    println("Checking local query ${query.joinToString(".")}")
    val ip = hosts[query.first().lowercase()] ?: return emptyList()
    println(
        "Returning ${query.joinToString(".")} -> ${
            ip.joinToString(".") { it.toUByte().toString() }
        }"
    )
    return listOf(
        DNSRecord(
            name = query,
            type = DNSType.A,
            klass = DNSClass.IN,
            ttl = 60,
            data = ip
        )
    )
}
