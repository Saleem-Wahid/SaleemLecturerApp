package dev.kwasi.echoservercomplete.network

import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import dev.kwasi.echoservercomplete.models.ContentModel
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Writer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.Exception
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.text.Charsets.UTF_8

/// The [Server] class has all the functionality that is responsible for the 'server' connection.
/// This is implemented using TCP. This Server class is intended to be run on the GO.

class Server(private val iFaceImpl:NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    private val clientMap: HashMap<String, Socket> = HashMap()
    private var hashedStudentID = hashStrSha256("816030569")
    private var aesKey = generateAESKey(hashedStudentID)
    private var aesIv = generateIV(hashedStudentID)
    private lateinit var clientConnectionSocket: Socket
    private lateinit var writer: BufferedWriter
    private lateinit var reader: BufferedReader

    init {
        thread {
            while (true) {
                try {
                    clientConnectionSocket = svrSocket.accept()
                    Log.e("SERVER", "The server has accepted a connection: ")
                    handleSocket(clientConnectionSocket)

                } catch (e: Exception) {
                    Log.e("SERVER", "An error has occurred in the server!")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.inetAddress.hostAddress?.let {
            clientMap[it] = socket
            Log.e("SERVER", "A new connection has been detected!")
            thread {
                reader = socket.inputStream.bufferedReader()
                writer = socket.outputStream.bufferedWriter()
                var receivedJson: String?
                var studentID = "816030374"

                authenticate(studentID, reader, writer)

                while (socket.isConnected) {
                    try {
                        receivedJson = reader.readLine()
                        if (receivedJson != null) {
                            Log.e("SERVER", "Received a message from client $it")
                            val clientContent = Gson().fromJson(receivedJson, ContentModel::class.java)
                            val message = decryptMessage(clientContent.message, aesKey, aesIv)
                            clientContent.message = message
                            iFaceImpl.onContent(clientContent)
                        }
                    } catch (e: Exception) {
                        Log.e("SERVER", "An error has occurred with the client $it")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun sendMessage(content: ContentModel) {
        thread {
            if(!clientConnectionSocket.isConnected) {
                throw Exception("We aren't currently connected to the server!")
            }
            val message = encryptMessage(content.message, aesKey, aesIv)
            content.message = message
            val contentAsStr:String = Gson().toJson(content)
            writer.write("$contentAsStr\n")
            writer.flush()
        }
    }

    fun close() {
        svrSocket.close()
        clientMap.clear()
    }

    private fun ByteArray.toHex() = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun hashStrSha256(str: String): String {
        val algorithm = "SHA-256"
        val hashedString = MessageDigest.getInstance(algorithm).digest(str.toByteArray(UTF_8))
        return hashedString.toHex();
    }

    private fun getFirstNChars(str: String, n: Int) = str.substring(0, n)

    private fun generateAESKey(seed: String): SecretKeySpec {
        val first32Chars = getFirstNChars(seed, 32)
        val secretKey = SecretKeySpec(first32Chars.toByteArray(), "AES")
        return secretKey
    }

    private fun generateIV(seed: String): IvParameterSpec {
        val first16Chars = getFirstNChars(seed, 16)
        return IvParameterSpec(first16Chars.toByteArray())
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encryptMessage(plaintext: String, aesKey:SecretKey, aesIv: IvParameterSpec):String{
        val plainTextByteArr = plaintext.toByteArray()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, aesIv)

        val encrypt = cipher.doFinal(plainTextByteArr)
        return Base64.Default.encode(encrypt)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decryptMessage(encryptedText: String, aesKey: SecretKey, aesIv: IvParameterSpec): String {
        val textToDecrypt = Base64.Default.decode(encryptedText)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")

        cipher.init(Cipher.DECRYPT_MODE, aesKey, aesIv)

        val decrypt = cipher.doFinal(textToDecrypt)
        return String(decrypt)
    }

    private fun authenticate(studentID: String, clientReader: BufferedReader, clientWriter: BufferedWriter) {
        val initialMessage = clientReader.readLine()

        if (initialMessage != null) {
            val clientContent = Gson().fromJson(initialMessage, ContentModel::class.java)
            if (clientContent.message == "I am here") {
                val challenge = (50000..999999).random().toString()
                clientWriter.write(Gson().toJson(ContentModel(challenge, "192.168.49.1")) + "\n")
                clientWriter.flush()
                val encryptedResponse = clientReader.readLine()
                if (encryptedResponse != null) {
                    val clientResponse = Gson().fromJson(encryptedResponse, ContentModel::class.java)
                    val decryptedResponse = decryptMessage(clientResponse.message, aesKey, aesIv)
                    if (decryptedResponse == challenge) {
                        Log.e("SERVER", "Authenticated")
                    } else {
                        Log.e("SERVER", "Authentication failed")
                    }
                }
            }
        }
    }
}
