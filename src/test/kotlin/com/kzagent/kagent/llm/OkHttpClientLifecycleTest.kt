package com.kzagent.kagent.llm

import kotlin.test.Test
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

class OkHttpClientLifecycleTest {
    @Test
    fun closingResourcesShutsDownAsyncDispatcher() {
        val client = OkHttpClient()

        client.closeResources()

        assertTrue(client.dispatcher.executorService.isShutdown)
    }

    @Test
    fun modelCatalogServiceOwnsAndClosesItsClient() {
        val client = OkHttpClient()
        val service = ModelCatalogService(client)

        service.close()

        assertTrue(client.dispatcher.executorService.isShutdown)
    }
}
