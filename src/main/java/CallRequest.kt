import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 *  REST APIにアクセスするラッパー
 *
 *  @author HIYU
 *  @changeLog
 *    ➡ V1.0 - クラス作成
 *    ➡ V1.1 - メンバー変数にGETTER・SETTERプロパティを追加
 *           - コード最適化
 *    ➡ V1.2 - BodyリクエストをCLOSEするようにした
 *    ➡ V1.3 - 認証付きProxyをサポート
 */
class CallRequest(
    var URL: String,
    var method: RequestMethod = RequestMethod.GET,
    var body: String = "",
    var mediaType: String = "text/plain"
) {

    // クラス
    enum class RequestMethod { GET, POST, PUT, DELETE, PATCH, HEAD, CONNECT, OPTIONS, TRACE }
    class HeaderSet(val key: String, val value: String)
    class DownloadProgress(val file: File, val current: Double, val max: Double)
    class NotExistMediaType(mediaTypeName: String) : Exception() {
        override val cause: Throwable = Throwable()
        override val message: String = "$mediaTypeName というメディアタイプが存在しません"
    }

    // 接続オプション
    var headers = arrayListOf<HeaderSet>()
    var connectOption : (OkHttpClient.Builder) -> Unit = {}
    var retryConnect : Boolean = true
    var redirect : Boolean = true
    var timeOut : Long = 5

    // プロキシ
    var proxy : Proxy? = null
    var proxyUserName : String? = null
    var proxyPassWord : String? = null

    /**
     *  ヘッダーを追加
     *  @Key   鍵
     *  @Value 値
     */
    fun addHeader(Key: String, Value: String) : Boolean = headers.add(HeaderSet(Key, Value))

    /**
     *  ヘッダー配列を一括追加
     */
    fun addHeaderAll(headerSet: List<HeaderSet>) { headers.addAll(headerSet) }

    /**
     *  URLへアクセス
     */
    fun connect(Fun: (Response) -> Unit = {}) : Response? {
        val response = getResponse() ?: return null
        Fun.invoke(response)

        // クローズ
        response.close()
        return response
    }

    /**
     *  STRING型でURLを取得
     */
    fun connectString() : String? {
        val response = getResponse() ?: return null
        val body = response.body
        val result = body?.string().toString()

        // クローズ
        response.close()
        body?.close()
        return result
    }

    /**
     *  STRING型でProxy経由でURLを取得
     */
    fun connectStringViaProxy(Host: String, Port: Int, ProxyType: Proxy.Type, UserName: String? = null, Password: String? = null) : String? {
        proxy = Proxy(ProxyType, InetSocketAddress(Host, Port))
        UserName?.let { proxyUserName = it }
        Password?.let { proxyPassWord = it }

        return connectString()
    }

    /**
　　　*  INPUTSTREAM型でURLを取得
　　　*/
    fun connectInputstream() : InputStream? {
        val response = getResponse() ?: return null
        val body = response.body
        val result = body?.byteStream()

        // クローズ
        response.close()
        body?.close()
        return result
    }

    /**
     *  FILE型でURLを取得
     */
    fun connectFile(file: File, function: (DownloadProgress) -> Unit = {}) : File? {

        val response = getResponse() ?: return null
        val body = response.body
        val inputStream = body?.byteStream() ?: return null

        var bytesRead : Int
        val byteArray = ByteArray(1024)
        val outFile = FileOutputStream(file)

        val contentLength = response.body?.contentLength() ?: 0
        var readBytes = 0
        while (true) {
            bytesRead = inputStream.read(byteArray)
            readBytes += bytesRead
            if (bytesRead == -1) break

            // 進捗
            val max = contentLength.toDouble()
            val current = readBytes / max
            val progress = DownloadProgress(file, current, max)
            function.invoke(progress)

            outFile.write(byteArray, 0, bytesRead)
        }

        // クローズ
        outFile.close()
        inputStream.close()
        body.close()

        return file
    }

    /**
     * リスポンスを取得
     */
    private fun getResponse() : Response? {

        // リクエストボディー
        val mediaType = mediaType.toMediaTypeOrNull() ?: throw NotExistMediaType(mediaType)
        val body = body.toRequestBody(mediaType)

        // リクエスト
        val request = Request.Builder().apply {

            // URL
            this.url(URL)

            // ヘッダー
            headers.forEach { addHeader(it.key, it.value) }

            // リクエストメソッド
            when(method) {
                RequestMethod.GET -> this.get()
                RequestMethod.HEAD -> this.head()
                RequestMethod.POST -> this.post(body)
                RequestMethod.PUT -> this.put(body)
                RequestMethod.DELETE -> this.delete(body)
                RequestMethod.PATCH -> this.patch(body)
                else -> this.method(method.name, body)
            }

        }.build()

        // プロキシ生成
        var proxyAuthenticator : Authenticator? = null
        if (proxyUserName != null && proxyPassWord != null) {
            proxyAuthenticator = Authenticator { route, response ->
                val credential = Credentials.basic(proxyUserName!!, proxyPassWord!!)
                response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
        }

        // クライアント生成
        val client = OkHttpClient().newBuilder().apply {
            this.connectTimeout(timeOut, TimeUnit.SECONDS)
            this.retryOnConnectionFailure(retryConnect)
            this.followRedirects(redirect)
            if (proxy != null) this.proxy(proxy)
            if (proxyAuthenticator != null) this.proxyAuthenticator(proxyAuthenticator)
            connectOption.invoke(this)
        }.build()

        // リスポンス 返す
        runCatching { client.newCall(request).execute() }.apply {
            this.exceptionOrNull()?.printStackTrace()
            return this.getOrNull()
        }
    }

}