package com.vector.messenger.telegram

import android.content.Context
import com.vector.core.auth.*
import com.vector.core.profile.*
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
    // Both repositories share one process-owned manager, receive loop and serialized reducer scope.
    @Provides @Singleton fun telegramScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Provides @Singleton fun client(@ApplicationContext context: Context, scope: CoroutineScope) = TdlibClientManager(context, scope)
    @Provides @Singleton fun authentication(client: TdlibClientManager, scope: CoroutineScope): AuthenticationRepository =
        DefaultAuthenticationRepository(client, scope)
    @Provides @Singleton fun profile(authentication: AuthenticationRepository, client: TdlibClientManager, scope: CoroutineScope): ProfileRepository =
        DefaultProfileRepository(authentication, client, scope)
}
