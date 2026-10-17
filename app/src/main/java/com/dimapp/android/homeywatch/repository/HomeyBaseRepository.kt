package com.dimapp.android.homeywatch.repository

import android.content.Context
import com.dimapp.android.homeywatch.api.HomeyApiService
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.core.DependencyManager
import com.dimapp.android.homeywatch.storage.TokenStorage

/**
 * Base abstract class for domain-specific Homey repositories.
 *
 * @property context   Application context.
 * @property storage   Shared instance of [TokenStorage].
 * @property authRepo  Shared instance of [HomeyAuthRepository].
 */
abstract class HomeyBaseRepository(
    protected val context: Context,
    protected val storage: TokenStorage,
    protected val authRepo: HomeyAuthRepository
) {

    /**
     * Retrieves the [HomeyApiService] from [DependencyManager].
     *
     * @return Configured [HomeyApiService], or `null` if not configured.
     */
    protected fun _buildService(): HomeyApiService? {
        return DependencyManager.getApiService(context)
    }
}
