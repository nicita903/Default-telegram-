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
    @Provides @Singleton fun chats(): ChatRepository = DemoChatRepository()
    @Provides @Singleton fun ghost(): GhostRepository = DemoGhostRepository()
}
