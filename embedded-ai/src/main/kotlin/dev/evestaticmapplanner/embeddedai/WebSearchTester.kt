package dev.evestaticmapplanner.embeddedai

import dev.evestaticmapplanner.shared.auth.SecretValue

enum class SearchTestCheckStatus { PASSED, FAILED, NOT_RUN }

data class SearchTestCheck(val status: SearchTestCheckStatus, val message: String)

data class WebSearchTestResult(
    val connection: SearchTestCheck,
    val authentication: SearchTestCheck,
    val searchResponse: SearchTestCheck,
    val sourceParsing: SearchTestCheck,
    val successful: Boolean,
    val errorCode: WebSearchErrorCode? = null,
)

class BraveSearchTester(private val client: WebSearchClient) {
    suspend fun test(secret: SecretValue): WebSearchTestResult = try {
        val response = client.search(
            WebSearchRequest(query = "EVE Online CCP Games", maxSources = 2),
            secret,
        )
        val parsed = response.results.isNotEmpty() && response.results.all { safeWebUrl(it.url) != null }
        WebSearchTestResult(
            connection = passed("Connection succeeded"),
            authentication = passed("Authentication succeeded"),
            searchResponse = passed("Search response received"),
            sourceParsing = if (parsed) passed("Source parsing succeeded") else failed("Source parsing failed"),
            successful = parsed,
            errorCode = WebSearchErrorCode.SEARCH_PROVIDER_ERROR.takeUnless { parsed },
        )
    } catch (failure: WebSearchException) {
        val connection = when (failure.code) {
            WebSearchErrorCode.INVALID_SEARCH_CREDENTIAL,
            WebSearchErrorCode.RATE_LIMITED,
            WebSearchErrorCode.SEARCH_EMPTY,
            WebSearchErrorCode.SEARCH_PROVIDER_ERROR,
            -> passed("Connection succeeded")
            else -> failed(failure.safeMessage)
        }
        val authentication = when (failure.code) {
            WebSearchErrorCode.INVALID_SEARCH_CREDENTIAL -> failed("Authentication failed")
            WebSearchErrorCode.SEARCH_NETWORK_ERROR,
            WebSearchErrorCode.SEARCH_TIMEOUT,
            WebSearchErrorCode.NO_SEARCH_CREDENTIAL,
            -> notRun("Authentication not tested")
            else -> passed("Authentication succeeded")
        }
        WebSearchTestResult(
            connection = connection,
            authentication = authentication,
            searchResponse = failed(failure.safeMessage).takeIf {
                connection.status == SearchTestCheckStatus.PASSED &&
                    authentication.status == SearchTestCheckStatus.PASSED
            } ?: notRun("Search response not tested"),
            sourceParsing = notRun("Source parsing not tested"),
            successful = false,
            errorCode = failure.code,
        )
    }
}

private fun passed(message: String) = SearchTestCheck(SearchTestCheckStatus.PASSED, message)
private fun failed(message: String) = SearchTestCheck(SearchTestCheckStatus.FAILED, message)
private fun notRun(message: String) = SearchTestCheck(SearchTestCheckStatus.NOT_RUN, message)
