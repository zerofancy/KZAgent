package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import java.time.Duration
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

internal interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatCompletionRequest,
    ): Response<ChatCompletionResponse>
}

internal object DeepSeekApiFactory {
    fun create(
        config: AppConfig,
        json: Json = deepSeekJson(),
    ): DeepSeekApi {
        val baseUrl = "${config.baseUrl.trimEnd('/')}/".toHttpUrl()
        val client = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(300))
            .writeTimeout(Duration.ofSeconds(300))
            .callTimeout(Duration.ofSeconds(300))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekApi::class.java)
    }
}

internal fun deepSeekJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
