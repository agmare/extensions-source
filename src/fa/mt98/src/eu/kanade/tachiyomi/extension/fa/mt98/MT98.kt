package eu.kanade.tachiyomi.extension.fa.mt98

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Request

@Source
class MT98(
    override val lang: String,
    override val id: Long,
) : KeiSource() {

    override val name = "Manhwa Tower"
    override val baseUrl = "https://mt98.ir"

    override val supportsLatest = true

    private val mt98Headers: Headers = Headers.Builder()
        .add("Referer", "https://mt98.ir")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .build()

    // Cache for all mangas loaded from Latest
    private val cacheMutex = Mutex()
    private val cachedMangas = LinkedHashMap<String, SManga>()
    private var lastSequentialPageLoaded = 0
    private var isAllLatestLoaded = false

    // --- Popular Manga ---
    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = GET(baseUrl, mt98Headers)
        val document = client.newCall(request).await().asJsoup()

        val mangas = document.select(".swiper-slide .manhwa-card").mapNotNull { element ->
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            SManga.create().apply {
                title = element.selectFirst(".title-overlay")?.text() ?: "Unknown"
                setUrlWithoutDomain(link)
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        return MangasPage(mangas, false)
    }

    // --- Latest Manga ---
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = GET("$baseUrl/page/$page", mt98Headers)
        val document = client.newCall(request).await().asJsoup()

        val mangas = document.select(".card").mapNotNull { element ->
            val link = element.selectFirst("a")?.attr("href")
                ?: element.parent()?.takeIf { it.tagName() == "a" }?.attr("href")
                ?: return@mapNotNull null

            SManga.create().apply {
                title = element.selectFirst(".card-title")?.text() ?: "Unknown"
                setUrlWithoutDomain(link)
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage = document.selectFirst(".pagination-wrapper .custom-pagination li a:contains(›)") != null

        synchronized(cachedMangas) {
            for (manga in mangas) {
                cachedMangas[manga.url] = manga
            }
            if (page == lastSequentialPageLoaded + 1) {
                lastSequentialPageLoaded = page
            }
            if (!hasNextPage || mangas.isEmpty()) {
                isAllLatestLoaded = true
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    // --- Search Manga (Fetches All Latest Pages Then Filters Locally) ---
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        cacheMutex.withLock {
            // Safety cap of 200 pages to prevent infinite loops if site HTML changes
            var safetyCounter = 0
            while (!isAllLatestLoaded && safetyCounter < 200) {
                val nextPage = lastSequentialPageLoaded + 1
                getLatestUpdates(nextPage)
                safetyCounter++
            }
        }

        val cleanQuery = query.trim()
        val filteredMangas = synchronized(cachedMangas) {
            if (cleanQuery.isEmpty()) {
                cachedMangas.values.toList()
            } else {
                cachedMangas.values.filter { manga ->
                    manga.title.contains(cleanQuery, ignoreCase = true)
                }
            }
        }

        return MangasPage(filteredMangas, false)
    }

    // --- Manga Details & Chapters ---
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var updatedManga = manga
        var updatedChapters = chapters

        if (fetchDetails || fetchChapters) {
            val request = GET(baseUrl + manga.url, mt98Headers)
            val document = client.newCall(request).await().asJsoup()

            if (fetchDetails) {
                updatedManga = SManga.create().apply {
                    url = manga.url
                    title = document.selectFirst("h1.display-6")?.text() ?: manga.title
                    thumbnail_url = document.selectFirst("img#poster")?.attr("src") ?: manga.thumbnail_url
                    description = document.selectFirst("p.kholase, p.lead")?.text()
                    genre = document.select("span.genre-tag").joinToString { it.text() }
                    status = SManga.UNKNOWN
                }
            }

            if (fetchChapters) {
                val mangaName = manga.url.split("/").last { it.isNotBlank() }

                updatedChapters = document.select(".chapter-item").map { element ->
                    SChapter.create().apply {
                        val link = element.selectFirst("a")
                        name = element.selectFirst("h6")?.text() ?: "Chapter"

                        val href = link?.attr("href") ?: ""
                        val chapNum = href.substringAfter("Chapter=").substringBefore(",")

                        setUrlWithoutDomain("/Manhwa/readerpage.php?manga=$mangaName&chap=$chapNum")
                    }
                }
            }
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // --- Page Links (Multi-Server Fallback & Dynamic Generation) ---
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = chapter.url

        if (!url.contains("manga=") || !url.contains("chap=")) {
            error("لطفاً یکبار لیست چپترها را به پایین بکشید تا رفرش شود (Please refresh chapters)")
        }

        val mangaName = url.substringAfter("manga=").substringBefore("&")
        val chapNum = url.substringAfter("chap=")

        val servers = listOf(
            "https://dozigame.ir",
            "https://dozigame.862720.ir.cdn.ir",
            "https://cdn.megaman-server.ir/users/564",
            "https://megamane.533644.ir.cdn.ir/users/564",
        )

        var activeServer = ""
        var qualityPath = ""
        var isServerFound = false

        for (server in servers) {
            val cleanServer = server.trimEnd('/')

            val hdUrl = "$cleanServer//$mangaName/$chapNum/HD/1.webp"
            val hdReq = Request.Builder().head().url(hdUrl).headers(mt98Headers).build()

            if (client.newCall(hdReq).await().isSuccessful) {
                activeServer = cleanServer
                qualityPath = "HD/"
                isServerFound = true
                break
            }

            val sdUrl = "$cleanServer//$mangaName/$chapNum/1.webp"
            val sdReq = Request.Builder().head().url(sdUrl).headers(mt98Headers).build()

            if (client.newCall(sdReq).await().isSuccessful) {
                activeServer = cleanServer
                qualityPath = ""
                isServerFound = true
                break
            }
        }

        if (!isServerFound) {
            error("هیچ تصویری برای این چپتر در هیچ سروری یافت نشد (Servers are down or chapter is empty).")
        }

        val pages = mutableListOf<Page>()

        coroutineScope {
            var currentPage = 1
            var hitEnd = false

            while (!hitEnd && currentPage <= 200) {
                val batch = (currentPage until currentPage + 10).toList()
                val deferreds = batch.map { p ->
                    async {
                        val imgUrl = "$activeServer//$mangaName/$chapNum/$qualityPath$p.webp"
                        val req = Request.Builder().head().url(imgUrl).headers(mt98Headers).build()
                        val isSuccess = client.newCall(req).await().isSuccessful
                        p to (if (isSuccess) imgUrl else null)
                    }
                }

                val results = deferreds.awaitAll().sortedBy { it.first }
                for ((_, imgUrl) in results) {
                    if (imgUrl != null) {
                        pages.add(Page(pages.size, imageUrl = imgUrl))
                    } else {
                        hitEnd = true
                        break
                    }
                }
                currentPage += 10
            }
        }

        return pages
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = mt98Headers.newBuilder()
            .set("Referer", "https://mt98.ir/")
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }
}