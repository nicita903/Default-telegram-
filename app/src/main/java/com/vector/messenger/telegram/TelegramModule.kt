package com.vector.messenger.telegram

import android.content.Context
import com.vector.core.auth.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.*

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {
    @Provides @Singleton fun authentication(@ApplicationContext context: Context): AuthenticationRepository {
        // Repository state transitions are serialized on Main. JNI receive and initialization run on IO.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return DefaultAuthenticationRepository(TdlibClientManager(context, scope), scope)
    }
}
