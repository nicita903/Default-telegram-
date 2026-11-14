package com.vector.messenger.di

import com.vector.core.ChatRepository
import com.vector.core.GhostRepository
import com.vector.core.demo.DemoChatRepository
import com.vector.core.demo.DemoGhostRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides fun appearance(store: com.vector.messenger.feature.AppearanceStore): com.vector.messenger.feature.AppearancePreferences = store
    @Provides @Singleton fun chats(authentication: com.vector.core.auth.AuthenticationRepository,
        client: com.vector.messenger.telegram.TdlibClientManager, scope: kotlinx.coroutines.CoroutineScope): ChatRepository =
        com.vector.core.messaging.SessionChatRepository(authentication, com.vector.messenger.telegram.TdlibMessagingClient(client), DemoChatRepository(), scope)
    @Provides @Singleton fun ghost(authentication: com.vector.core.auth.AuthenticationRepository,
        client: com.vector.messenger.telegram.TdlibClientManager, @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope): GhostRepository = com.vector.core.ghost.SelectiveGhostRepository(authentication,
        com.vector.messenger.telegram.TdlibGhostClient(client), com.vector.messenger.feature.SecureGhostStore(context), DemoGhostRepository(), scope)
}
