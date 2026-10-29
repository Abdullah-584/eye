package com.familyguard.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.familyguard.core.network.session.DataStoreSessionManager
import com.familyguard.core.network.session.DataStoreSessionStore
import com.familyguard.core.network.session.SessionCrypto
import com.familyguard.core.network.session.SessionStore
import com.familyguard.core.network.session.KeystoreSessionCrypto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.cio.CIO
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): SessionStore =
        DataStoreSessionStore(context.sessionDataStore)

    @Provides
    @Singleton
    fun provideSessionCrypto(): SessionCrypto = KeystoreSessionCrypto()

    @Provides
    @Singleton
    fun provideSessionManager(store: SessionStore, crypto: SessionCrypto): SessionManager =
        DataStoreSessionManager(store, crypto)

    /**
     * The only Supabase client in the app. It is configured with the PUBLIC
     * anon key: Row Level Security in Postgres is what actually restricts a
     * parent to their own family and a child device to its own rules.
     */
    @Provides
    @Singleton
    fun provideSupabaseClient(sessionManager: SessionManager): SupabaseClient {
        val url = BuildConfig.SUPABASE_URL.trim()
        val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
        require(url.isNotEmpty() && anonKey.isNotEmpty()) {
            "SUPABASE_URL and SUPABASE_ANON_KEY are missing. Copy local.properties.example " +
                "to local.properties and fill in your project's Settings -> API values."
        }

        return createSupabaseClient(url, anonKey) {
            httpEngine = CIO.create()

            install(Auth) {
                this.sessionManager = sessionManager
                autoLoadFromStorage = true
            }
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
    }
}
