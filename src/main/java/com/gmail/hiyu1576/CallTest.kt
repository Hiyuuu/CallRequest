package com.gmail.hiyu1576

import net.minidev.json.JSONArray
import okhttp3.mockwebserver.MockResponse
import java.io.File
import java.io.FileNotFoundException
fun main(args: Array<String>) {

    // Json例 取得
    val jsonExample =
        CallRequest::class.java
            .classLoader
            .getResource("Example.json")
            .also { File(it.toURI()) }
            ?.readText()
            ?: throw FileNotFoundException()

    // モックサーバー 開始
    CallRecieve(80)
        .ResponseHandle(MockResponse().setBody(jsonExample))
        .Start()

    // リクエストインスタンス
    val callRequest = CallRequest("http://localhost:80")

    // リクエスト1
    val result1 = callRequest.connectString()
    println("GET_RESULT: \"$result1\"")

    // リクエスト2(JSON解析あり)
    val result2 = callRequest.connectJsonPath<JSONArray>("$.store.book[*].price")
    println("PARSE_RESULT: $result2")
}