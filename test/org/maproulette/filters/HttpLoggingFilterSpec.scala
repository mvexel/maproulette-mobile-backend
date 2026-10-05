package org.maproulette.filters

import org.scalatestplus.play.PlaySpec
import play.api.test.FakeRequest

class HttpLoggingFilterSpec extends PlaySpec {
  "HTTP request logging" should {
    "omit mobile authorization query strings and sensitive headers" in {
      val request = FakeRequest("GET", "/oauth/mobile/callback?code=secret-code&state=secret-state")
        .withHeaders(
          "Authorization" -> "Bearer secret-access",
          "apiKey"        -> "secret-key",
          "Cookie"        -> "secret-cookie",
          "Referer"       -> "https://example.org/?code=secret-code",
          "Accept"        -> "application/json"
        )
      SafeRequestLog.request(request) mustBe "GET /oauth/mobile/callback"
      val headers = SafeRequestLog.headers(request)
      headers must not include "secret-"
      headers must include("Accept=application/json")
    }
    "preserve ordinary route diagnostics" in {
      val request = FakeRequest("GET", "/api/v2/task/123")
      SafeRequestLog.request(request) mustBe request.toString()
    }
  }
}
