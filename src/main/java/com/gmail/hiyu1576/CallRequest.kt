package com.gmail.hiyu1576

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.jayway.jsonpath.JsonPath
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.NullPointerException
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
 *    ➡ V1.4 - JsonPath解析に対応
 */
open class CallRequest(
    var URL: String? = null,
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
     * JsonPathを用いて、リスポンスの指定位置の値を取得します
     */
    fun <T> connectJsonPath(jsonPath: String, debug: Boolean = false) : T? {

        // 取得
        val json = connectString() ?: throw NullPointerException("リスポンスが空のため解析できません")

        // 解析
        return parseJsonPath(json, jsonPath, debug)
    }

    /**
     * JsonPathを用いてJsonの指定位置の値を取得します
     *
     * @sample (https://github.com/json-path/JsonPath より参照)
     * $.store.book[*].author  すべての書籍の著者
     * $..author すべての著者の名前
     * $.store.* 本も自転車も、ストアのすべてのもの
     * $.store.price すべてのものの価格
     * $..book[2] 3冊目の本
     * $..book[-2] 最後から2冊目の本
     * $..book[0,1] 最初の2冊の本
     * $..book[:2] インデックス0（を含む）からインデックス2（を含む）までのすべてのブック
     * $..book[1:2] インデックス1（を含む）からインデックス2（を含む）までのすべてのブック
     * $..book[-2:]最後の2冊
     * $..book[2:] インデックス2から最後までの全ての本 (含む)
     * $..book[?(@.isbn)] ISBN番号の存在する、すべての本
     * $.store.book[?(@.price < 10)] ストア内で10より安い本全て
     * $.store.book[?(@.price <= $['expensive'])] 店頭にある、10より安い本全て 店内にある "高価" でないすべての本
     * $..book[?(@.author =~ /.*REES/i)] 正規表現にマッチする全ての本 (大文字小文字は無視)
     * $..* すべてを取得
     * $..book.length() 本の数
     *
     * @operator (https://github.com/json-path/JsonPath より参照)
     * $ クエリーの対象となるルート要素。これは、すべてのパス表現を開始する
     * @ フィルタ述語で処理されている現在のノード
     * * ワイルドカード。名前または数値が必要な場所で利用可能
     * ..	ディープスキャン。名前が必要な場所ならどこでも利用可能です
     * .<name> ドットで表記された子
     * ['<name>' (, '<name>')] ブラケットで区切られた子または子供
     * [<number> (, <number>)] 配列のインデックスまたはインデックス
     * [start:end] 配列のスライス演算子
     * [?(<expression>)] フィルタ式。式はBoolean型で評価されなければならない
     *
     * @expression (https://github.com/json-path/JsonPath より参照)
     * == 左と右は等しい (ただし、1 は '1' と等しくない)
     * != 左と右は等しくない
     * < 左は右より小さい
     * <= 左は右より小さいか等しい
     * > 左は右より大きい
     * >= 左は右より大きいか等しい
     * =~ 左 は正規表現 [?(@.name =~ /foo.*?/i)] に一致する
     * in 左 は 右に存在する [?(@.size in ['S', 'M'])]
     * nin 左 は 右に存在しない
     * subsetof 左 は 右の部分集合 [?(@.sizes subsetof ['S', 'M', 'L'])] である
     * anyof 左 は 右と交差する [?(@.sizes anyof ['M', 'L']) ]
     * noneof 左 は 右と交差しない [?(@.sizes noneof ['M', 'L'])]
     * size 左(配列または文字列) のサイズが右と一致しなければならない
     * empty 左(配列または文字列) は空でなければなりません
     */
    fun <T> parseJsonPath(json: String, jsonPath: String, debug: Boolean = false) : T {

        // ロガー
        val logContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val log = logContext.getLogger("com.jayway.jsonpath")
        if (debug) log.level = Level.DEBUG else log.level = Level.WARN

        // JsonPath 解析
        return JsonPath.parse(json).read<T>(jsonPath)
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
        if (URL == null) return null

        // リクエストボディー
        val mediaType = mediaType.toMediaTypeOrNull() ?: throw NotExistMediaType(mediaType)
        val body = body.toRequestBody(mediaType)

        // リクエスト
        val request = Request.Builder().apply {

            // URL
            this.url(URL!!)

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
        return client.newCall(request).execute()
    }

}
