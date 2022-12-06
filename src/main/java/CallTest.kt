fun main(args: Array<String>) {

    CallRecieve(80).Start()

    val result = CallRequest("http://localhost:80").connectString()
    println(result)


}