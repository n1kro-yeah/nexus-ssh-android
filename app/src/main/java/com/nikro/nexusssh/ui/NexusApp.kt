package com.nikro.nexusssh.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nikro.nexusssh.R
import com.nikro.nexusssh.data.prefs.AppSettings
import com.nikro.nexusssh.ui.forwarding.PortForwardsScreen
import com.nikro.nexusssh.ui.history.HistoryScreen
import com.nikro.nexusssh.ui.hosts.HostEditorScreen
import com.nikro.nexusssh.ui.hosts.HostsScreen
import com.nikro.nexusssh.ui.identities.IdentitiesScreen
import com.nikro.nexusssh.ui.keychain.KeyDetailScreen
import com.nikro.nexusssh.ui.keychain.KeychainScreen
import com.nikro.nexusssh.ui.knownhosts.KnownHostsScreen
import com.nikro.nexusssh.ui.lock.LockScreen
import com.nikro.nexusssh.ui.more.MoreScreen
import com.nikro.nexusssh.ui.navigation.Routes
import com.nikro.nexusssh.ui.navigation.TopLevelDestination
import com.nikro.nexusssh.ui.onboarding.OnboardingScreen
import com.nikro.nexusssh.ui.quickconnect.QuickConnectScreen
import com.nikro.nexusssh.ui.settings.AboutScreen
import com.nikro.nexusssh.ui.settings.BackupScreen
import com.nikro.nexusssh.ui.settings.SecuritySettingsScreen
import com.nikro.nexusssh.ui.settings.SettingsScreen
import com.nikro.nexusssh.ui.settings.SettingsViewModel
import com.nikro.nexusssh.ui.settings.TerminalSettingsScreen
import com.nikro.nexusssh.ui.sftp.SftpScreen
import com.nikro.nexusssh.ui.sftp.TransfersScreen
import com.nikro.nexusssh.ui.snippets.SnippetsScreen
import com.nikro.nexusssh.ui.terminal.TerminalScreen

private const val TRANSFERS_ROUTE = "transfers"

/**
 * The navigation graph and the shell around it.
 *
 * Three gates run before the graph: onboarding on first launch, the app lock when it is enabled,
 * and deep links. Keeping them here rather than inside individual screens means no screen can be
 * reached with the app still locked, and a deep link cannot skip the lock either.
 */
@Composable
fun NexusApp(
    settings: AppSettings,
    deepLink: String?,
    onDeepLinkHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    var unlocked by rememberSaveable { mutableStateOf(false) }

    if (!settings.onboardingComplete) {
        OnboardingScreen(
            onFinished = { settingsViewModel.setOnboardingComplete(true) },
            modifier = modifier,
        )
        return
    }

    if (settings.biometricLock && !unlocked) {
        LockScreen(onUnlocked = { unlocked = true }, modifier = modifier)
        return
    }

    val navController = rememberNavController()
    var quickConnectPrefill by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deepLink) {
        val target = deepLink ?: return@LaunchedEffect
        when {
            target.startsWith("nexusssh://host/new") -> {
                navController.navigate(Routes.hostEditor())
            }

            target.startsWith("nexusssh://quick-connect") -> {
                navController.navigate(Routes.QUICK_CONNECT)
            }

            target.startsWith("nexusssh://transfers") -> {
                navController.navigate(TRANSFERS_ROUTE)
            }

            target.startsWith("ssh://") ||
                target.startsWith("telnet://") ||
                target.startsWith("sftp://") -> {
                quickConnectPrefill = target
                navController.navigate(Routes.QUICK_CONNECT)
            }

            else -> Unit
        }
        onDeepLinkHandled()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevelDestination.entries.any { destination ->
        currentDestination?.hierarchy?.any { it.route?.startsWith(destination.route) == true } == true
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route?.startsWith(destination.route) == true
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(destination.route) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon(),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes())) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOSTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOSTS) {
                HostsScreen(
                    onOpenSession = { sessionId ->
                        navController.navigate(Routes.terminal(sessionId))
                    },
                    onEditHost = { hostId -> navController.navigate(Routes.hostEditor(hostId)) },
                    onQuickConnect = { navController.navigate(Routes.QUICK_CONNECT) },
                    onOpenSftp = { hostId -> navController.navigate(Routes.sftp(hostId)) },
                )
            }

            composable(
                route = "${Routes.TERMINAL}?${Routes.Args.SESSION_ID}={${Routes.Args.SESSION_ID}}",
                arguments = listOf(
                    navArgument(Routes.Args.SESSION_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                TerminalScreen(onOpenHosts = { navController.switchTab(Routes.HOSTS) })
            }

            composable(
                route = "${Routes.SFTP}?${Routes.Args.HOST_ID}={${Routes.Args.HOST_ID}}",
                arguments = listOf(
                    navArgument(Routes.Args.HOST_ID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                SftpScreen(
                    hostId = entry.arguments?.getLong(Routes.Args.HOST_ID) ?: 0L,
                    onBack = { navController.popBackStackOrHome() },
                )
            }

            composable(Routes.KEYCHAIN) {
                KeychainScreen(
                    onOpenKey = { keyId -> navController.navigate(Routes.keyDetail(keyId)) },
                )
            }

            composable(Routes.MORE) {
                MoreScreen(onNavigate = { route -> navController.navigate(route) })
            }

            composable(
                route = "${Routes.HOST_EDITOR}/{${Routes.Args.HOST_ID}}",
                arguments = listOf(
                    navArgument(Routes.Args.HOST_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                HostEditorScreen(
                    hostId = entry.arguments?.getLong(Routes.Args.HOST_ID) ?: 0L,
                    onBack = { navController.popBackStackOrHome() },
                )
            }

            composable(
                route = "${Routes.KEY_DETAIL}/{${Routes.Args.KEY_ID}}",
                arguments = listOf(
                    navArgument(Routes.Args.KEY_ID) { type = NavType.LongType },
                ),
            ) { entry ->
                KeyDetailScreen(
                    keyId = entry.arguments?.getLong(Routes.Args.KEY_ID) ?: 0L,
                    onBack = { navController.popBackStackOrHome() },
                )
            }

            composable(Routes.IDENTITY_LIST) {
                IdentitiesScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.KNOWN_HOSTS) {
                KnownHostsScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.SNIPPETS) {
                SnippetsScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.PORT_FORWARDS) {
                PortForwardsScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.HISTORY) {
                HistoryScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(TRANSFERS_ROUTE) {
                TransfersScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.QUICK_CONNECT) {
                QuickConnectScreen(
                    onConnected = { sessionId ->
                        quickConnectPrefill = null
                        navController.navigate(Routes.terminal(sessionId)) {
                            popUpTo(Routes.QUICK_CONNECT) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStackOrHome() },
                    prefill = quickConnectPrefill,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStackOrHome() },
                    onOpenTerminalSettings = {
                        navController.navigate(Routes.TERMINAL_SETTINGS)
                    },
                    onOpenSecuritySettings = {
                        navController.navigate(Routes.SECURITY_SETTINGS)
                    },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }

            composable(Routes.TERMINAL_SETTINGS) {
                TerminalSettingsScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.SECURITY_SETTINGS) {
                SecuritySettingsScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.BACKUP) {
                BackupScreen(onBack = { navController.popBackStackOrHome() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStackOrHome() })
            }
        }
    }
}

/** Switches bottom-bar tabs without stacking duplicates of the same tab. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pops the back stack, falling back to the host list when nothing is left to pop. */
private fun NavHostController.popBackStackOrHome() {
    if (!popBackStack()) switchTab(Routes.HOSTS)
}

private fun TopLevelDestination.icon(): ImageVector = when (this) {
    TopLevelDestination.HOSTS -> Icons.Rounded.List
    TopLevelDestination.TERMINAL -> Icons.Rounded.PlayArrow
    TopLevelDestination.SFTP -> Icons.Rounded.Folder
    TopLevelDestination.KEYCHAIN -> Icons.Rounded.Lock
    TopLevelDestination.MORE -> Icons.Rounded.Menu
}

private fun TopLevelDestination.labelRes(): Int = when (this) {
    TopLevelDestination.HOSTS -> R.string.nav_hosts
    TopLevelDestination.TERMINAL -> R.string.nav_terminal
    TopLevelDestination.SFTP -> R.string.nav_sftp
    TopLevelDestination.KEYCHAIN -> R.string.nav_keychain
    TopLevelDestination.MORE -> R.string.nav_more
}
