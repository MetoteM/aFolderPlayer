package dev.alex.afponline

/** Each lane owns a client and its mutable caches; only that lane serializes network work. */
internal class YandexClientLane(private val factory:(String)->YandexClient={ YandexClient(it) }) {
    private var token:String?=null
    private var client:YandexClient?=null
    private var verifiedAt=0L
    @Synchronized fun <T> use(value:String, action:(YandexClient)->T):T {
        if(token!=value) { token=value;client=null;verifiedAt=0L }
        val active=client ?: factory(value).also { client=it }
        try {
            if(System.currentTimeMillis()-verifiedAt>900_000) { active.checkSession();verifiedAt=System.currentTimeMillis() }
            return action(active)
        } catch(e:Exception) {
            if((e as? YandexClient.Problem)?.reason?.contains("HTTP 401")==true) { client=null;verifiedAt=0L }
            throw e
        }
    }
}
