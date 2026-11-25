package com.vector.messenger.feature

import android.content.Context
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.TdlibAvatarClient
import com.vector.messenger.telegram.request
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SettingsStorageUsage(val loading: Boolean = false, val cacheBytes: Long? = null,
    val telegramBytes: Long? = null, val databaseBytes: Long? = null, val error: String? = null)

/** Read-only storage accounting. Never traverses or deletes Telegram databases/drafts. */
@Singleton
class SettingsStorageRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val profile: ProfileRepository, private val client: TdlibAvatarClient, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SettingsStorageUsage())
    val state = mutable.asStateFlow()
    private var owner = profile.state.value.profile?.id?.takeIf { profile.state.value.authenticated } ?: 0L
    private var generation = 0L
    private var requested = false
    private var job: Job? = null
    init {
        scope.launch {
            profile.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } ?: 0L }.distinctUntilChanged().collect { account ->
                if (owner != account) { owner = account; generation++; job?.cancel(); job = null; mutable.value = SettingsStorageUsage(); if (requested) refresh() }
            }
        }
    }
    fun refresh() {
        requested = true
        if (job?.isActive == true) return
        val token = generation; val account = owner
        job = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            var error: String? = null
            val cache = try { withContext(Dispatchers.IO) {
                val scanContext = currentCoroutineContext()
                var bytes = 0L
                val roots = listOfNotNull(context.cacheDir, context.externalCacheDir).distinctBy { it.absolutePath }
                for (root in roots) if (root.exists()) Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { scanContext.ensureActive(); bytes += attrs.size(); return FileVisitResult.CONTINUE }
                })
                bytes
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Some app cache usage is unavailable."; null }
            val native = if (account == 0L) null else try { client.call(request("getStorageStatisticsFast"), account) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Telegram storage usage is unavailable. Retry when the account is ready."; null }
            if (token == generation && owner == account) mutable.value = SettingsStorageUsage(false, cache,
                native?.optLong("files_size"), native?.optLong("database_size"), error)
        }
    }
}
