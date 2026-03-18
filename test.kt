import io.ktor.utils.io.ByteReadChannel

fun main() {
    val ch: ByteReadChannel? = null
    println(ch?.isClosedForRead)
    println(ch?.endOfInput)
}
