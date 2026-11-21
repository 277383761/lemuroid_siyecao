package com.swordfish.lemuroid.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.startup.AppInitializer
import androidx.work.ListenableWorker
import com.google.android.material.color.DynamicColors
import com.swordfish.lemuroid.app.shared.startup.GameProcessInitializer
import com.swordfish.lemuroid.app.shared.startup.MainProcessInitializer
import com.swordfish.lemuroid.app.utils.android.isMainProcess
import com.swordfish.lemuroid.ext.feature.context.ContextHandler
import com.swordfish.lemuroid.lib.injection.HasWorkerInjector
import com.swordfish.lemuroid.lib.preferences.HarmonyStoreHealer
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.support.DaggerApplication
import javax.inject.Inject

class LemuroidApplication : DaggerApplication(), HasWorkerInjector {

    @Inject
    lateinit var workerInjector: DispatchingAndroidInjector<ListenableWorker>

    @SuppressLint("CheckResult")
    override fun onCreate() {
        super.onCreate()

        // Heal a corrupted Harmony preferences store BEFORE anything reads settings.
        // A half-written transaction log makes every read throw UTFDataFormatException,
        // which crashes game loads. Each game launch is a fresh process, so this runs
        // in time. See HarmonyStoreHealer for details.
        HarmonyStoreHealer.heal(this)

        val initializeComponent = if (isMainProcess()) {
            MainProcessInitializer::class.java
        } else {
            GameProcessInitializer::class.java
        }

        AppInitializer.getInstance(this).initializeComponent(initializeComponent)

        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ContextHandler.attachBaseContext(base)
    }

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> {
        return DaggerLemuroidApplicationComponent.builder().create(this)
    }

    override fun workerInjector(): AndroidInjector<ListenableWorker> = workerInjector
}
