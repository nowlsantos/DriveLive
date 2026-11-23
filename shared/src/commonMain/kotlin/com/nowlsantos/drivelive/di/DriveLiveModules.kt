package com.nowlsantos.drivelive.di

import com.nowlsantos.drivelive.config.BuildSecrets
import com.nowlsantos.drivelive.network.MichaelLiveClient
import com.nowlsantos.drivelive.presentation.DriveLiveViewModel
import com.nowlsantos.drivelive.state.MichaelStateMachine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

expect val platformModule: Module

fun commonModule(geminiApiKey: String): Module = module {
    single {
        HttpClient {
            install(WebSockets) {
                pingInterval = 20.seconds
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    }
                )
            }
        }
    }

    singleOf(::MichaelStateMachine)

    single {
        val resolvedKey = geminiApiKey
            .trim()
            .takeIf { it.isNotEmpty() && !it.startsWith("$(") }
            ?: BuildSecrets.DEFAULT_GEMINI_API_KEY
        MichaelLiveClient(
            httpClient = get(),
            apiKey = resolvedKey
        )
    }

    viewModelOf(::DriveLiveViewModel)
}

fun initDriveLiveKoin(
    geminiApiKey: String,
    appDeclaration: KoinAppDeclaration = {}
) {
    startKoin {
        appDeclaration()
        modules(
            platformModule,
            commonModule(geminiApiKey)
        )
    }
}
