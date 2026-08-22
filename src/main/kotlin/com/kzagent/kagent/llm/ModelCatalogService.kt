package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ProviderId
import com.kzagent.kagent.config.SecretRedactor
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class ModelCatalogService(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AutoCloseable {
    suspend fun load(config: AppConfig): List<ModelDescriptor> = buildList {
        for (provider in config.configuredProviders) {
            addAll(loadProvider(config, provider))
        }
    }

    suspend fun loadProvider(config: AppConfig, provider: ProviderId): List<ModelDescriptor> {
        val providerConfig = config.provider(provider)
            ?: throw IllegalArgumentException("${provider.displayName} is not configured.")
        val suffix = when (provider) {
            ProviderId.DEEPSEEK -> "/models"
            ProviderId.OPENROUTER -> "/models?supported_parameters=tools"
        }
        val request = Request.Builder()
            .url("${providerConfig.baseUrl.trimEnd('/')}$suffix")
            .header("Authorization", "Bearer ${providerConfig.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val response = execute(request)
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ProviderApiException(
                    "${provider.displayName} Models API HTTP ${it.code}" +
                        body.takeIf(String::isNotBlank)?.let { value -> ": ${SecretRedactor.redact(value)}" }.orEmpty(),
                    statusCode = it.code,
                )
            }
            val catalog = runCatching { json.decodeFromString<ModelListResponse>(body) }
                .getOrElse { error ->
                    throw ProviderApiException("${provider.displayName} Models API returned invalid JSON.", cause = error)
                }
            return catalog.data.mapNotNull { model ->
                when (provider) {
                    ProviderId.DEEPSEEK -> ModelDescriptor(
                        provider = provider,
                        id = model.id,
                        displayName = model.name ?: model.id,
                        contextWindowSize = config.contextWindowSize,
                    )
                    ProviderId.OPENROUTER -> {
                        val supportsTools = "tools" in model.supportedParameters.orEmpty()
                        val textOutput = model.architecture?.outputModalities?.let { "text" in it } ?: true
                        if (!supportsTools || !textOutput) null else ModelDescriptor(
                            provider = provider,
                            id = model.id,
                            displayName = model.name ?: model.id,
                            contextWindowSize = model.contextLength?.takeIf { it > 0 },
                            supportsToolChoice = "tool_choice" in model.supportedParameters.orEmpty(),
                        )
                    }
                }
            }.sortedBy { it.displayName.lowercase() }
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }

    override fun close() {
        client.closeResources()
    }
}

@Serializable
private data class ModelListResponse(val data: List<CatalogModel> = emptyList())

@Serializable
private data class CatalogModel(
    val id: String,
    val name: String? = null,
    @kotlinx.serialization.SerialName("context_length")
    val contextLength: Int? = null,
    @kotlinx.serialization.SerialName("supported_parameters")
    val supportedParameters: List<String>? = null,
    val architecture: CatalogArchitecture? = null,
)

@Serializable
private data class CatalogArchitecture(
    @kotlinx.serialization.SerialName("output_modalities")
    val outputModalities: List<String>? = null,
)
