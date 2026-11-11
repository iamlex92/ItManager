package com.itmanager.app.core.network

import android.content.Context
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOfflineCache(@ApplicationContext context: Context): OfflineCache =
        OfflineCache(File(context.cacheDir, "offline"))

    @Provides
    @Singleton
    fun provideHttpClient(json: Json, cache: OfflineCache): HttpClient = HttpClient(OkHttp) {
        engine { addInterceptor(cache.interceptor) }
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) {
            // A LAN server answers in milliseconds; long waits usually mean the VPN is down.
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }
}
