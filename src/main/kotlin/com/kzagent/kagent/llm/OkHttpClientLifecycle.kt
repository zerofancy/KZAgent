package com.kzagent.kagent.llm

import okhttp3.OkHttpClient

internal fun OkHttpClient.closeResources() {
    dispatcher.cancelAll()
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
    cache?.close()
}
