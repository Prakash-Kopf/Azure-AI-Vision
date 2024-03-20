//
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE.md file in the project root for full license information.
//

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.Exception
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.net.URL
import java.util.UUID
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.net.ssl.HttpsURLConnection

class PickImage : ActivityResultContracts.PickVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent {
        val intent = super.createIntent(context, input)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
        return intent
    }
}

object Utils {
    const val CRLF = "\r\n"

    // Function to get a session token for face liveness session
    fun GetFaceAPISessionToken(context: Context, verifyImageArray: ByteArray?) = runBlocking {
        withContext(Dispatchers.IO) {
            val sharedPref = context.getSharedPreferences("SettingValues", Context.MODE_PRIVATE)
            val faceApiEndpoint = sharedPref.getString("endpoint", "").toString()
            val faceApiKey = sharedPref.getString("key", "").toString()
            val sendResultsToClient = sharedPref.getBoolean("sendResultsToClient", false)

            val url: URL?
            var urlConnection: HttpsURLConnection? = null
            if (faceApiEndpoint.isNotBlank() && faceApiKey.isNotBlank()) {
                try {
                    url = if (verifyImageArray != null) {
                        URL("$faceApiEndpoint/face/v1.1-preview.1/detectLivenessWithVerify/singleModal/sessions")
                    } else {
                        URL("$faceApiEndpoint/face/v1.1-preview.1/detectLiveness/singleModal/sessions")
                    }

                    val tokenRequest = JSONObject(mapOf(
                        "livenessOperationMode" to "Passive",
                        "sendResultsToClient" to sendResultsToClient,
                        "deviceCorrelationId" to Settings.Secure.ANDROID_ID
                    )).toString()
                    val charset: Charset = Charset.forName("UTF-8")
                    urlConnection = url.openConnection() as HttpsURLConnection
                    urlConnection.doOutput = true
                    urlConnection.setChunkedStreamingMode(0)
                    urlConnection.useCaches = false
                    urlConnection.setRequestProperty("Ocp-Apim-Subscription-Key", faceApiKey)
                    if (verifyImageArray == null) {
                        urlConnection.setRequestProperty("Content-Type", "application/json; charset=$charset")
                        val out: OutputStream = BufferedOutputStream(urlConnection.outputStream)
                        out.write(tokenRequest.toByteArray(charset))
                        out.flush()
                    } else {
                        val boundary: String = UUID.randomUUID().toString()
                        urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        val outputStream = BufferedOutputStream(urlConnection.outputStream)

                        PrintWriter(OutputStreamWriter(outputStream, charset), true).use { writer ->
                            writer.append("--$boundary").append(CRLF)
                            writer.append("Content-Type: application/json; charset=$charset").append(CRLF)
                            writer.append("Content-Disposition: form-data; name=Parameters").append(CRLF)
                            writer.append(CRLF).append(tokenRequest).append(CRLF)

                            writer.append("--$boundary").append(CRLF)
                            writer.append("Content-Disposition: form-data; name=VerifyImage; filename=VerifyImage").append(CRLF)
                            writer.append("Content-Type: application/octet-stream").append(CRLF)
                            writer.append("Content-Transfer-Encoding: binary").append(CRLF)
                            writer.append(CRLF).flush()
                            outputStream.write(verifyImageArray, 0, verifyImageArray.size)
                            outputStream.flush()
                            writer.append(CRLF).flush()
                            writer.append(CRLF).flush()

                            writer.append("--$boundary--").append(CRLF).flush()
                            outputStream.flush()
                        }
                    }
                    val (reader, throwable) = try {
                        Pair(BufferedReader(InputStreamReader(urlConnection.inputStream)), null)
                    } catch (t: Throwable) {
                        Pair(BufferedReader(InputStreamReader(urlConnection.errorStream)), t)
                    }
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Parse the JSON response
                    val jsonResponse = response.toString()

                    if (throwable == null) {
                        val jsonObject = JSONObject(jsonResponse)
                        return@withContext jsonObject.getString("authToken")
                    } else {
                        Log.d("Face API Session Create", "Status: ${urlConnection.responseCode} ${urlConnection.responseMessage}")
                        Log.d("Face API Session Create", "Body: $jsonResponse")
                        throw throwable
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                } finally {
                    urlConnection!!.disconnect()
                }
            }
            return@withContext ""
        }
    }

    fun GetVerifyImage(context: Context, uri: Uri) : String {
        val filetype = context.applicationContext.getContentResolver().getType(uri)!!.split('/')[1]
        val file: File = File(
            context.applicationContext.getCacheDir(), UUID.randomUUID().toString() + "." + filetype)
        try {
            context.applicationContext.getContentResolver().openInputStream(uri).use { inputStream ->
                FileOutputStream(file).use { output ->
                    inputStream!!.copyTo(output)
                    output.flush()
                }
            }
        } catch (ex: IOException) {
            ex.printStackTrace()
        }

        return file.path
    }

    fun GetVerifyImageByteArray(context: Context, uri: Uri) : ByteArray {
        try {
            context.applicationContext.contentResolver.openInputStream(uri).use { inputStream ->
                ByteArrayOutputStream().use { output ->
                    inputStream!!.copyTo(output)
                    output.flush()
                    return output.toByteArray()
                }

            }
        } catch (ex: IOException) {
            ex.printStackTrace()
            throw ex
        }
    }
}