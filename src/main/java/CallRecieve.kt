import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException

/**
 *  REST APIのテストをするラッパー
 *
 *  @author HIYU
 *  @changeLog
 *    ➡ V1.0 - クラス作成
 */
class CallRecieve(val port: Int) {

    private var handle : (MockWebServer, RecordedRequest) -> MockResponse = { _, _ -> MockResponse() }
    var mockServer : MockWebServer? = null

    /**
     * サーバーを起動します
     */
    @Throws(IOException::class, InterruptedException::class)
    fun Start(function: (MockWebServer, RecordedRequest) -> MockResponse = { _, _ -> MockResponse().setBody("Hello World") }) : CallRecieve {
        Stop()
        handle = function
        val server = MockWebServer()

        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse
                = handle.invoke(server, request)
        }.also { server.dispatcher = it }

        server.start(port)

        mockServer = server

        return this
    }

    /**
     * リスポンス処理を変更します
     */
    fun ResponseHandle(function: (MockWebServer, RecordedRequest) -> MockResponse) : CallRecieve { handle = function ; return this }

    /**
     * サーバーを停止します
     */
    fun Stop() : CallRecieve { mockServer?.shutdown() ; return this }

}