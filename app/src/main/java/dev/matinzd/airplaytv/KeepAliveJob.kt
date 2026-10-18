package dev.matinzd.airplaytv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Safety net: every 15 minutes, make sure the receiver service is running, in
 * case the system stopped it and START_STICKY didn't bring it back. The app is
 * exempt from battery optimisation (setup-device.sh), which allows starting a
 * foreground service from here.
 */
class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        AirPlayService.start(this)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true) // survives reboots
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("KeepAliveJob", "scheduling failed")
        }
    }
}
