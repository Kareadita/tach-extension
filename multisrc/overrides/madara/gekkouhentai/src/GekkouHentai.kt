package eu.kanade.tachiyomi.extension.pt.gekkouhentai

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class GekkouHentai : Madara(
    "Gekkou Hentai",
    "https://hentai.gekkouscans.com.br",
    "pt-BR",
    SimpleDateFormat("dd 'de' MMMMM 'de' YYYY", Locale("pt", "BR")),
) {

    // Theme changed from MMRCMS to Madara.
    override val versionId: Int = 2

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .rateLimit(1, 2, TimeUnit.SECONDS)
        .build()

    override val useNewChapterEndpoint: Boolean = true
}
