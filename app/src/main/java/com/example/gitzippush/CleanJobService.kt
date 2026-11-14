package com.example.gitzippush

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Nettoyage GitHub automatique (une fois par semaine, réseau requis), avec les réglages de la tuile Nettoyage. */
class CleanJobService : JobService() {

    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters?): Boolean {
        stopped = false
        val app = applicationContext
        Thread {
            val s = Settings(app)
            var ok = 0
            var errors = 0
            try {
                val token = s.token()
                val repos = if (s.cleanAllRepos) s.repos().map { it.repo } else {
                    RepoRef.parse(s.repo)?.let { listOf(it.first + "/" + it.second) } ?: emptyList()
                }
                if (token != null && repos.isNotEmpty()) {
                    val api = GitHubApi(token).also { it.service = Service.CLEANER }
                    val o = GitHubCleaner.Options(
                        s.cleanFailedRuns, s.cleanOldRuns, s.cleanKeepRuns, s.cleanArtifacts, s.cleanCaches, s.cleanBranches
                    )
                    for (repo in repos) {
                        if (stopped) break
                        try {
                            val p = GitHubCleaner.plan(api, repo, o) { stopped }
                            if (p.total == 0) continue
                            val (a, b) = GitHubCleaner.execute(api, p, { _, _ -> }) { stopped }
                            ok += a
                            errors += b
                        } catch (e: Exception) {
                            errors++
                        }
                    }
                    if (ok > 0 || errors > 0) {
                        Notifier.simple(
                            app, 7301, "Nettoyage GitHub automatique",
                            "$ok élément(s) supprimé(s)" + (if (errors > 0) ", $errors erreur(s)" else "")
                        )
                    }
                }
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        stopped = true
        return true
    }

    companion object {
        private const val JOB_ID = 7300
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** Programme ou annule le nettoyage hebdomadaire. */
        fun schedule(ctx: Context, enabled: Boolean) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (!enabled) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CleanJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(WEEK_MS)
                .setPersisted(true)
                .build()
            try {
                js.schedule(job)
            } catch (e: Exception) {
                // programmation refusée
            }
        }
    }
}
