package fit.bandwagon.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import fit.bandwagon.RootViewModel
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.feature.challenges.ChallengeDetailScreen
import fit.bandwagon.feature.challenges.ChallengeListTab
import fit.bandwagon.feature.challenges.ChallengeRoutes
import fit.bandwagon.feature.challenges.CreateChallengeScreen
import fit.bandwagon.feature.groups.CreateGroupScreen
import fit.bandwagon.feature.groups.GroupDetailScreen
import fit.bandwagon.feature.groups.GroupRoutes
import fit.bandwagon.feature.groups.GroupSettingsScreen
import fit.bandwagon.feature.groups.GroupsHomeScreen
import fit.bandwagon.feature.groups.InvitesScreen
import fit.bandwagon.feature.groups.JoinGroupScreen
import fit.bandwagon.feature.health.HealthRoutes
import fit.bandwagon.feature.health.HealthScreen
import fit.bandwagon.feature.health.MetricDetailScreen
import fit.bandwagon.feature.health.SleepDetailScreen
import fit.bandwagon.feature.health.WorkoutsScreen
import fit.bandwagon.feature.onboarding.ONBOARDING_ROUTE
import fit.bandwagon.feature.onboarding.OnboardingScreen
import fit.bandwagon.feature.home.HomeScreen
import fit.bandwagon.core.ui.rememberTactilePagerFlingBehavior
import fit.bandwagon.feature.you.EditAliasScreen
import fit.bandwagon.feature.you.EditUsernameScreen
import fit.bandwagon.feature.you.HealthDataScreen
import fit.bandwagon.feature.you.NotificationsScreen
import fit.bandwagon.feature.you.YouRoutes
import fit.bandwagon.feature.you.YouScreen
import kotlinx.coroutines.launch

private const val MAIN_ROUTE = "main"
private const val HOME_DEEP_LINK = "home"
private const val GROUPS_HOME_DEEP_LINK = "groups_home"

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Filled.Home),
    Tab("Health", Icons.Filled.Favorite),
    Tab("Groups", Icons.Filled.Groups),
    Tab("You", Icons.Filled.Person),
)

private val groupArg = navArgument("groupId") { type = NavType.StringType }

@Composable
fun BandwagonNavHost(navController: NavHostController) {
    val rootViewModel: RootViewModel = hiltViewModel()
    // Hoisted so the selected tab survives navigating into a detail screen and back.
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val sessionExpired by rootViewModel.sessionExpired.collectAsStateWithLifecycle()

    // A dead sign-in found anywhere sends the user back to sign in; onboarding explains why.
    LaunchedEffect(sessionExpired) {
        if (sessionExpired && navController.currentDestination?.route != ONBOARDING_ROUTE) {
            selectedTab = 0
            navController.navigate(ONBOARDING_ROUTE) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = ONBOARDING_ROUTE) {
        composable(ONBOARDING_ROUTE) {
            OnboardingScreen(onFinished = {
                navController.navigate(MAIN_ROUTE) { popUpTo(0) { inclusive = true } }
            })
        }

        composable(MAIN_ROUTE) {
            MainTabs(
                rootNav = navController,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onRefresh = rootViewModel::refresh,
            )
        }

        // Deep links that open a tab land on a redirect route that selects the tab and enters main.
        composable(HOME_DEEP_LINK, deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://home" })) {
            LaunchedEffect(Unit) {
                selectedTab = 0
                navController.navigate(MAIN_ROUTE) { popUpTo(HOME_DEEP_LINK) { inclusive = true }; launchSingleTop = true }
            }
        }
        composable(GROUPS_HOME_DEEP_LINK, deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://groups" })) {
            LaunchedEffect(Unit) {
                selectedTab = 2
                navController.navigate(MAIN_ROUTE) { popUpTo(GROUPS_HOME_DEEP_LINK) { inclusive = true }; launchSingleTop = true }
            }
        }

        // --- Health ----------------------------------------------------------------------------
        composable(
            HealthRoutes.METRIC,
            arguments = listOf(navArgument("metric") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://health/metric/{metric}" }),
        ) { entry ->
            if (entry.arguments?.getString("metric") == HealthMetric.SLEEP.name) {
                SleepDetailScreen(onBack = { navController.popBackStack() })
            } else {
                MetricDetailScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(HealthRoutes.SLEEP, deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://health/sleep" })) {
            SleepDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(HealthRoutes.WORKOUTS) { WorkoutsScreen(onBack = { navController.popBackStack() }) }

        // --- Groups ----------------------------------------------------------------------------
        composable(GroupRoutes.CREATE) {
            CreateGroupScreen(
                onBack = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(GroupRoutes.detail(id)) { popUpTo(GroupRoutes.CREATE) { inclusive = true } } },
            )
        }
        composable(GroupRoutes.PREVIEW, arguments = listOf(groupArg)) {
            JoinGroupScreen(
                onBack = { navController.popBackStack() },
                onJoined = { id -> navController.navigate(GroupRoutes.detail(id)) { popUpTo(MAIN_ROUTE) } },
            )
        }
        composable(
            GroupRoutes.JOIN,
            arguments = listOf(navArgument("code") { type = NavType.StringType }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://bandwagon.fit/i/{code}" },
                navDeepLink { uriPattern = "bandwagon://join/{code}" },
            ),
        ) {
            JoinGroupScreen(
                onBack = { if (!navController.popBackStack()) navController.navigate(MAIN_ROUTE) },
                onJoined = { id ->
                    selectedTab = 2
                    navController.navigate(GroupRoutes.detail(id)) { popUpTo(MAIN_ROUTE) }
                },
            )
        }
        composable(
            GroupRoutes.DETAIL,
            arguments = listOf(groupArg),
            deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://groups/{groupId}" }),
        ) {
            GroupDetailScreen(
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(GroupRoutes.settings(it)) },
                onInvites = { navController.navigate(GroupRoutes.invites(it)) },
                onNewChallenge = { navController.navigate(ChallengeRoutes.new(it)) },
                onLeft = { navController.popBackStack(MAIN_ROUTE, inclusive = false) },
                challengesTab = { groupId, membership, _ ->
                    ChallengeListTab(groupId, membership, onOpen = { navController.navigate(ChallengeRoutes.detail(groupId, it)) })
                },
            )
        }
        composable(GroupRoutes.SETTINGS, arguments = listOf(groupArg)) {
            GroupSettingsScreen(onBack = { navController.popBackStack() }, onDeleted = { navController.popBackStack(MAIN_ROUTE, inclusive = false) })
        }
        composable(GroupRoutes.INVITES, arguments = listOf(groupArg)) {
            InvitesScreen(onBack = { navController.popBackStack() })
        }

        // --- Challenges ------------------------------------------------------------------------
        composable(ChallengeRoutes.NEW, arguments = listOf(groupArg)) { entry ->
            val groupId = entry.arguments?.getString("groupId").orEmpty()
            CreateChallengeScreen(
                onBack = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(ChallengeRoutes.detail(groupId, id)) { popUpTo(ChallengeRoutes.NEW) { inclusive = true } } },
            )
        }
        composable(
            ChallengeRoutes.DETAIL,
            arguments = listOf(groupArg, navArgument("challengeId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "bandwagon://groups/{groupId}/challenges/{challengeId}" }),
        ) {
            ChallengeDetailScreen(onBack = { navController.popBackStack() })
        }

        // --- You -------------------------------------------------------------------------------
        composable(YouRoutes.ALIAS) { EditAliasScreen(onBack = { navController.popBackStack() }) }
        composable(YouRoutes.USERNAME) { EditUsernameScreen(onBack = { navController.popBackStack() }) }
        composable(YouRoutes.HEALTH_DATA) { HealthDataScreen(onBack = { navController.popBackStack() }) }
        composable(YouRoutes.NOTIFICATIONS) { NotificationsScreen(onBack = { navController.popBackStack() }) }
    }
}

/**
 * The four tab home screens, side by side in a swipeable pager. Each tab's detail screens remain
 * root routes above this pager, so swiping only ever moves between tabs (and a tab's own inner
 * tabs, like Groups' "My groups / Discover", consume the swipe first).
 */
@Composable
private fun MainTabs(
    rootNav: NavController,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size })

    LaunchedEffect(pagerState.currentPage) {
        onSelectTab(pagerState.currentPage)
        onRefresh()
    }

    // Back on a base tab returns to Home; back on Home quits. Detail screens (above this pager)
    // handle their own back, so this only fires while the pager is showing.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding), flingBehavior = rememberTactilePagerFlingBehavior(pagerState)) { page ->
            when (page) {
                0 -> HomeScreen(
                    onOpenChallenge = { g, c -> rootNav.navigate(ChallengeRoutes.detail(g, c)) },
                    onOpenMetric = { m ->
                        onSelectTab(1)
                        rootNav.navigate(HealthRoutes.metric(m))
                    },
                    onOpenSleep = {
                        onSelectTab(1)
                        rootNav.navigate(HealthRoutes.SLEEP)
                    },
                    onOpenGroups = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onDeepLink = { link -> runCatching { rootNav.navigate(android.net.Uri.parse(link)) } },
                )
                1 -> HealthScreen(
                    onMetric = { rootNav.navigate(HealthRoutes.metric(it)) },
                    onSleep = { rootNav.navigate(HealthRoutes.SLEEP) },
                    onWorkouts = { rootNav.navigate(HealthRoutes.WORKOUTS) },
                )
                2 -> GroupsHomeScreen(
                    onOpenGroup = { rootNav.navigate(GroupRoutes.detail(it)) },
                    onPreviewGroup = { rootNav.navigate(GroupRoutes.preview(it)) },
                    onCreate = { rootNav.navigate(GroupRoutes.CREATE) },
                )
                else -> YouScreen(
                    onSignedOut = { rootNav.navigate(ONBOARDING_ROUTE) { popUpTo(0) { inclusive = true } } },
                    onEditAlias = { rootNav.navigate(YouRoutes.ALIAS) },
                    onEditUsername = { rootNav.navigate(YouRoutes.USERNAME) },
                    onHealthData = { rootNav.navigate(YouRoutes.HEALTH_DATA) },
                    onNotifications = { rootNav.navigate(YouRoutes.NOTIFICATIONS) },
                )
            }
        }
    }
}
