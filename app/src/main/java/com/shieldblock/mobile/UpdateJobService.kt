package com.shieldblock.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Runs every ~12 h; each list only downloads once its own uBlock `updateAfter` age is reached. */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            try {
                Updater.update(applicationContext, false)
                ShieldVpnService.instance?.reload()
            } catch (_: Throwable) {
            }
            jobFinished(params, false)
        }, "shield-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 1001

        fun schedule(ctx: Context, replace: Boolean = false) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!replace && js.getPendingJob(JOB_ID) != null) return
            val net = if (Prefs(ctx).wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                    .setRequiredNetworkType(net)
                    .setPeriodic(12L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
