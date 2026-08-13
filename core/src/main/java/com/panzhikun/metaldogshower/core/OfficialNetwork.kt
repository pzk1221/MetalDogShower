package com.panzhikun.metaldogshower.core

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

fun interface TokenProvider {
    /** Must return the decrypted token from memory; this callback must not block. */
    fun token(): String?
}

object OfficialProtocol {
    const val BASE_URL = "https://api.sbooy.com/"
    const val TOKEN_HEADER = "token"
    const val MAX_IDENTIFIER_LENGTH = 128

    // The verified official client only attaches tokens longer than 100 characters.
    const val MIN_TOKEN_LENGTH_EXCLUSIVE = 100
}

private const val INTERNAL_AUTH_HEADER = "X-MetalDog-Requires-Token"
private const val INTERNAL_AUTH_VALUE = "1"
private const val AUTH_MARKER = "$INTERNAL_AUTH_HEADER: $INTERNAL_AUTH_VALUE"

internal interface OfficialApi {
    @GET("device/0/0/public/info")
    suspend fun deviceInfo(@Query("dev_id") alias: String): Response<DeviceInfoDto>

    @GET("user/0/0/public/validateCodeMark")
    suspend fun otpMark(@Query("phone") phone: String): Response<OtpMarkDto>

    @GET("user/0/0/public/validateCode")
    suspend fun sendOtp(
        @Query("phone") phone: String,
        @Query("app_id") appId: String = "null",
        @Query("key") key: String,
    ): Response<ResponseBody>

    @POST("user/0/0/public/yzmLogin")
    suspend fun otpLogin(@Body request: OtpLoginRequestDto): Response<OtpLoginResponseDto>

    @Headers(AUTH_MARKER)
    @GET("device/{brandId}/{stadiumId}/protect/shower/info")
    suspend fun showerStatus(
        @Path("brandId") brandId: Long,
        @Path("stadiumId") stadiumId: Long,
        @Query("_route_dev_id") deviceId: String,
    ): Response<ShowerStatusEnvelopeDto>

    @Headers(AUTH_MARKER)
    @POST("device/0/0/protect/shower/switch")
    suspend fun switchShower(@Body request: SwitchRequestDto): Response<ResponseBody>
}

internal data class DeviceInfoDto(
    @SerializedName("dev_id") val deviceId: String?,
    @SerializedName("dev_name") val deviceName: String?,
    @SerializedName("stadium_id") val stadiumId: Long?,
    @SerializedName("type") val type: String?,
)

internal data class OtpMarkDto(
    @SerializedName("mark") val mark: String?,
)

internal data class OtpLoginRequestDto(
    @SerializedName("app_id") val appId: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("phone") val phone: String,
    @SerializedName("validate_code") val validateCode: String,
    @SerializedName("nick_name") val nickName: String = "网页用户",
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("gender") val gender: Int? = null,
    @SerializedName("city") val city: String? = null,
)

internal data class OtpLoginResponseDto(
    @SerializedName("token") val token: String?,
)

internal data class ShowerStatusEnvelopeDto(
    @SerializedName("shower") val shower: ShowerStatusDto?,
)

internal data class ShowerStatusDto(
    @SerializedName("is_opened") val isOpened: Int?,
    @SerializedName("rest_time") val remainingSeconds: Int?,
)

internal data class SwitchRequestDto(
    @SerializedName("_route_dev_id") val deviceId: String,
    @SerializedName("switch") val switchValue: Int,
)

internal object OfficialNetworkFactory {
    fun production(tokenProvider: TokenProvider): OfficialApi = create(
        baseUrl = OfficialProtocol.BASE_URL.toHttpUrl(),
        tokenProvider = tokenProvider,
    )

    fun create(
        baseUrl: HttpUrl,
        tokenProvider: TokenProvider,
        connectTimeoutMillis: Long = 10_000L,
        readTimeoutMillis: Long = 10_000L,
        writeTimeoutMillis: Long = 10_000L,
        callTimeoutMillis: Long = 15_000L,
    ): OfficialApi {
        val client = OkHttpClient.Builder()
            // Control and OTP operations must never be replayed automatically.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .addInterceptor(ProtectedTokenInterceptor(tokenProvider))
            // Deliberately no HTTP logging interceptor: bodies can contain OTPs and tokens.
            .build()

        val gson = GsonBuilder()
            // The official OTP login body contains explicit JSON null fields.
            .serializeNulls()
            .create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OfficialApi::class.java)
    }
}

private class ProtectedTokenInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val requiresToken = original.header(INTERNAL_AUTH_HEADER) == INTERNAL_AUTH_VALUE
        val requestBuilder = original.newBuilder().removeHeader(INTERNAL_AUTH_HEADER)

        if (requiresToken) {
            val token = tokenProvider.token()
                ?.takeIf { it.length > OfficialProtocol.MIN_TOKEN_LENGTH_EXCLUSIVE }
                ?: throw AuthenticationRequiredException()
            requestBuilder.header(OfficialProtocol.TOKEN_HEADER, token)
        }

        return chain.proceed(requestBuilder.build())
    }
}
