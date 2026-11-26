package com.sleepanalysis.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sleepanalysis.ui.history.HistoryScreen
import com.sleepanalysis.ui.home.HomeScreen
import com.sleepanalysis.ui.report.ReportScreen
import com.sleepanalysis.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val REPORT = "report/{sessionId}"
    const val REPORT_LOG = "report/{sessionId}/log"
    const val REPORT_LABELING = "report/{sessionId}/label"
    const val HISTORY = "history"
    const val TRENDS = "trends"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"

    fun report(sessionId: Long) = "report/$sessionId"
    fun reportLog(sessionId: Long) = "report/$sessionId/log"
    fun reportLabeling(sessionId: Long) = "report/$sessionId/label"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenReport = { navController.navigate(Routes.report(it)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.REPORT,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
            ReportScreen(
                onBack = { navController.popBackStack() },
                onOpenSessionLog = { navController.navigate(Routes.reportLog(sessionId)) },
                onLabelClips = { navController.navigate(Routes.reportLabeling(sessionId)) },
            )
        }
        composable(
            Routes.REPORT_LABELING,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            com.sleepanalysis.ui.report.LabelingScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.REPORT_LOG,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) {
            com.sleepanalysis.ui.report.SessionLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenReport = { navController.navigate(Routes.report(it)) },
                onOpenTrends = { navController.navigate(Routes.TRENDS) },
                onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
            )
        }
        composable(Routes.TRENDS) {
            com.sleepanalysis.ui.trends.TrendsScreen(
                onBack = { navController.popBackStack() },
                onOpenReport = { navController.navigate(Routes.report(it)) },
            )
        }
        composable(Routes.INSIGHTS) {
            com.sleepanalysis.ui.insights.InsightsScreen(
                onBack = { navController.popBackStack() },
                onOpenReport = { navController.navigate(Routes.report(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
