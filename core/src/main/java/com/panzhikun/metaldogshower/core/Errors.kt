package com.panzhikun.metaldogshower.core

import java.io.IOException

/** The session is absent, too short to be an official token, or was rejected with HTTP 401. */
class AuthenticationRequiredException : IOException("Authentication required")

class InvalidInputException(message: String) : IllegalArgumentException(message)

/** A successful HTTP response did not contain the protocol fields required by the app. */
class ProtocolException(message: String) : IOException(message)

/** HTTP error details deliberately exclude the response body, which can contain private data. */
class ApiHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

