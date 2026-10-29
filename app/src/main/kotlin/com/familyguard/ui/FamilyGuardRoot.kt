package com.familyguard.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.familyguard.R
import com.familyguard.core.auth.AuthPhase
import com.familyguard.core.auth.AuthViewModel
import com.familyguard.core.data.mode.AppMode
import com.familyguard.feature.childhub.ChildHomeScreen
import com.familyguard.feature.childhub.ChildRulesScreen
import com.familyguard.feature.childhub.ChildUiState
import com.familyguard.feature.childhub.ChildViewModel
import com.familyguard.feature.childhub.RequestTimeScreen
import com.familyguard.feature.childhub.WhatParentsSeeScreen
import com.familyguard.feature.family.AddChildScreen
import com.familyguard.feature.family.CreateFamilyScreen
import com.familyguard.feature.family.DevicesScreen
import com.familyguard.feature.family.FamilyDashboardScreen
import com.familyguard.feature.family.FamilyViewModel
import com.familyguard.feature.location.LocationScreen
import com.familyguard.feature.onboarding.ConfirmationScreen
import com.familyguard.feature.onboarding.SignInScreen
import com.familyguard.feature.onboarding.SignUpScreen
import com.familyguard.feature.pairing.JoinScreen
import com.familyguard.feature.pairing.PairingScreen
import com.familyguard.feature.reports.ReportsScreen
import com.familyguard.feature.requests.RequestsScreen
import com.familyguard.feature.rules.RuleEditorsScreen
import com.familyguard.feature.settings.ParentGate
import com.familyguard.feature.settings.SettingsScreen
import com.familyguard.service.enforcement.EnforcementService
import com.familyguard.service.location.LocationService

internal object Routes {
    const val SPLASH = "splash"
    const val SIGN_IN = "signin"
    const val SIGN_UP = "signup"
    const val CONFIRM = "confirm"

    // Mode chooser + child join (C1).
    const val MODE = "mode"
    const val JOIN = "join"

    // Parent shell (bottom-nav tabs first, then pushed screens).
    const val HOME = "home"
    const val REQUESTS = "requests"
    const val SETTINGS = "settings"
    const val CREATE_FAMILY = "create_family"
    const val ADD_CHILD = "add_child"
    const val DEVICES = "devices"
    const val RULES = "rules/{childId}"
    const val LOCATION = "location/{childId}"
    const val REPORTS = "reports/{childId}"
    const val PAIRING = "pairing/{childId}"

    // Child mode (C2–C5).
    const val CHILD_HOME = "child_home"
    const val CHILD_RULES = "child_rules"
    const val CHILD_PRIVACY = "child_privacy"
    const val CHILD_REQUEST = "child_request"

    fun rulesFor(childId: String) = "rules/$childId"
    fun locationFor(childId: String) = "location/$childId"
    fun reportsFor(childId: String) = "reports/$childId"
    fun pairingFor(childId: String) = "pairing/$childId"
}

private val TAB_ROUTES = setOf(Routes.HOME, Routes.REQUESTS, Routes.SETTINGS)
private val CHILD_TERRITORY = setOf(Routes.CHILD_HOME, Routes.CHILD_RULES, Routes.CHILD_PRIVACY, Routes.CHILD_REQUEST)

/**
 * Root of the app: navigation is driven by (auth phase × install mode),
 * exactly as PLAN §8 describes —
 *
 *  • no session → sign-in (a child device can go straight to join instead),
 *  • session + no mode → P1 chooser,
 *  • PARENT → dashboard/requests/settings shell behind the PIN gate (P18),
 *  • CHILD → the child hub, with the enforcement + location services running.
 *
 * A sign-out always clears the back stack so nobody navigates back into an
 * authenticated screen.
 */
@Composable
fun FamilyGuardRoot(
    onOpenChildRequest: () -> Boolean = { false },
    viewModel: AuthViewModel = hiltViewModel(),
    modeViewModel: ModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by modeViewModel.mode.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    // Child mode owns both foreground services; anything else tears them down.
    LaunchedEffect(mode) {
        if (mode == AppMode.CHILD) {
            EnforcementService.start(context)
            LocationService.start(context)
        } else {
            context.stopService(Intent(context, EnforcementService::class.java))
            context.stopService(Intent(context, LocationService::class.java))
        }
    }

    LaunchedEffect(state.phase, mode, currentRoute) {
        if (currentRoute == null) return@LaunchedEffect
        val target = when (state.phase) {
            AuthPhase.CHECKING_SESSION -> null

            AuthPhase.SIGNED_OUT ->
                // The join flow is reachable without an account (C1).
                if (currentRoute !in setOf(Routes.SIGN_IN, Routes.SIGN_UP, Routes.JOIN)) {
                    Routes.SIGN_IN
                } else {
                    null
                }

            AuthPhase.AWAITING_CONFIRMATION ->
                if (currentRoute != Routes.CONFIRM) Routes.CONFIRM else null

            AuthPhase.SIGNED_IN -> when (mode) {
                AppMode.NONE ->
                    if (currentRoute !in setOf(Routes.MODE, Routes.JOIN)) Routes.MODE else null

                AppMode.PARENT ->
                    if (currentRoute == Routes.SPLASH ||
                        currentRoute == Routes.MODE ||
                        currentRoute == Routes.JOIN ||
                        currentRoute in CHILD_TERRITORY
                    ) {
                        Routes.HOME
                    } else {
                        null
                    }

                AppMode.CHILD ->
                    if (currentRoute !in CHILD_TERRITORY) Routes.CHILD_HOME else null
            }
        }

        if (target != null) {
            navController.navigateToRoot(target)
        }
    }

    // BlockActivity ("Ask for more time") deep-links into the request screen.
    LaunchedEffect(mode, currentRoute) {
        if (mode == AppMode.CHILD && currentRoute == Routes.CHILD_HOME && onOpenChildRequest()) {
            navController.navigate(Routes.CHILD_REQUEST)
        }
    }

    val showTabs = mode == AppMode.PARENT && currentRoute in TAB_ROUTES

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            bottomBar = {
                if (showTabs) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.HOME,
                            onClick = { navController.selectTab(Routes.HOME) },
                            icon = {},
                            label = { Text(stringResource(R.string.nav_dashboard)) },
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.REQUESTS,
                            onClick = { navController.selectTab(Routes.REQUESTS) },
                            icon = {},
                            label = { Text(stringResource(R.string.nav_requests)) },
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.SETTINGS,
                            onClick = { navController.selectTab(Routes.SETTINGS) },
                            icon = {},
                            label = { Text(stringResource(R.string.nav_settings)) },
                        )
                    }
                }
            },
        ) { padding ->
            AppNavHost(
                navController = navController,
                modifier = Modifier.padding(padding),
                state = state,
                authViewModel = viewModel,
                onOpenChildRequest = onOpenChildRequest,
            )
        }
    }

    // P18: the gate only ever guards parent mode, and only while signed in.
    if (state.phase == AuthPhase.SIGNED_IN && mode == AppMode.PARENT) {
        ParentGate { scaffold() }
    } else {
        scaffold()
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier,
    state: com.familyguard.core.auth.AuthUiState,
    authViewModel: AuthViewModel,
    onOpenChildRequest: () -> Boolean,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier,
    ) {
        composable(Routes.SPLASH) { SplashScreen() }

        composable(Routes.SIGN_IN) {
            SignInScreen(
                state = state,
                onEmailChange = authViewModel::onEmailChange,
                onPasswordChange = authViewModel::onPasswordChange,
                onSignIn = authViewModel::signIn,
                onCreateAccountClick = { navController.navigate(Routes.SIGN_UP) },
                onDismissBanner = authViewModel::dismissBanner,
                onJoinDeviceClick = { navController.navigate(Routes.JOIN) },
            )
        }

        composable(Routes.SIGN_UP) {
            SignUpScreen(
                state = state,
                onDisplayNameChange = authViewModel::onDisplayNameChange,
                onEmailChange = authViewModel::onEmailChange,
                onPasswordChange = authViewModel::onPasswordChange,
                onSignUp = authViewModel::signUp,
                onSignInClick = { navController.popBackStack() },
                onDismissBanner = authViewModel::dismissBanner,
            )
        }

        composable(Routes.CONFIRM) {
            ConfirmationScreen(
                email = state.confirmationEmail,
                onBackToSignIn = { navController.navigateToRoot(Routes.SIGN_IN) },
            )
        }

        composable(Routes.MODE) {
            val modeViewModel: ModeViewModel = hiltViewModel()
            ModeChooserScreen(
                onParent = modeViewModel::becomeParent,
                onJoinDevice = { navController.navigate(Routes.JOIN) },
            )
        }

        composable(Routes.JOIN) {
            JoinScreen(
                onJoined = { /* mode flips to CHILD; the root effect routes on */ },
                onBack = { navController.popBackStack() },
            )
        }

        // ----- Parent shell -------------------------------------------------

        composable(Routes.HOME) {
            val familyViewModel: FamilyViewModel = hiltViewModel()
            val familyState by familyViewModel.state.collectAsStateWithLifecycle()

            // The family may have been created on the create screen since
            // this ViewModel instance last asked — re-check on every entry.
            LaunchedEffect(Unit) { familyViewModel.refreshFamily() }

            FamilyDashboardScreen(
                state = familyState,
                onSelectChild = familyViewModel::selectChild,
                onCreateFamilyClick = { navController.navigate(Routes.CREATE_FAMILY) },
                onAddChildClick = { navController.navigate(Routes.ADD_CHILD) },
                onOpenRequests = { navController.selectTab(Routes.REQUESTS) },
                onOpenDevices = { navController.navigate(Routes.DEVICES) },
                onDismissBanner = familyViewModel::dismissBanner,
                onOpenRules = {
                    familyState.selectedChildId?.let { navController.navigate(Routes.rulesFor(it)) }
                },
                onOpenLocation = {
                    familyState.selectedChildId?.let { navController.navigate(Routes.locationFor(it)) }
                },
                onOpenReports = {
                    familyState.selectedChildId?.let { navController.navigate(Routes.reportsFor(it)) }
                },
            )
        }

        composable(Routes.REQUESTS) {
            val familyViewModel: FamilyViewModel = hiltViewModel()
            val familyState by familyViewModel.state.collectAsStateWithLifecycle()
            val childId = familyState.selectedChildId ?: familyState.children.firstOrNull()?.id
            if (childId == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.requests_no_child),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                RequestsScreen(
                    childId = childId,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = null)
        }

        composable(Routes.CREATE_FAMILY) {
            val familyViewModel: FamilyViewModel = hiltViewModel()
            val familyState by familyViewModel.state.collectAsStateWithLifecycle()
            CreateFamilyScreen(
                state = familyState,
                onCreateFamily = { name, policyVersion ->
                    // Only leave the screen on success: on failure the
                    // banner explains what went wrong right here.
                    familyViewModel.createFamily(name, policyVersion) { ok ->
                        if (ok) navController.popBackStack()
                    }
                },
                onDismissBanner = familyViewModel::dismissBanner,
            )
        }

        composable(Routes.ADD_CHILD) {
            val familyViewModel: FamilyViewModel = hiltViewModel()
            val familyState by familyViewModel.state.collectAsStateWithLifecycle()
            AddChildScreen(
                state = familyState,
                onAddChild = { name, birthYear, timezone ->
                    familyViewModel.addChild(name, birthYear, timezone) { ok ->
                        if (ok) navController.popBackStack()
                    }
                },
                onDismissBanner = familyViewModel::dismissBanner,
            )
        }

        composable(Routes.DEVICES) {
            val familyViewModel: FamilyViewModel = hiltViewModel()
            val familyState by familyViewModel.state.collectAsStateWithLifecycle()
            DevicesScreen(
                state = familyState,
                onRevoke = familyViewModel::revokeDevice,
                onRefresh = familyViewModel::refreshDevices,
                onDismissBanner = familyViewModel::dismissBanner,
                onPair = {
                    val childId = familyState.selectedChildId
                        ?: familyState.children.firstOrNull()?.id
                    if (childId != null) {
                        navController.navigate(Routes.pairingFor(childId))
                    }
                },
            )
        }

        composable(Routes.RULES) { entry ->
            val childId = entry.arguments?.getString("childId") ?: return@composable
            RuleEditorsScreen(childId = childId, onBack = { navController.popBackStack() })
        }

        composable(Routes.LOCATION) { entry ->
            val childId = entry.arguments?.getString("childId") ?: return@composable
            LocationScreen(childId = childId, onBack = { navController.popBackStack() })
        }

        composable(Routes.REPORTS) { entry ->
            val childId = entry.arguments?.getString("childId") ?: return@composable
            ReportsScreen(childId = childId, onBack = { navController.popBackStack() })
        }

        composable(Routes.PAIRING) { entry ->
            val childId = entry.arguments?.getString("childId") ?: return@composable
            PairingScreen(childId = childId, onBack = { navController.popBackStack() })
        }

        // ----- Child mode ---------------------------------------------------

        composable(Routes.CHILD_HOME) {
            val childViewModel: ChildViewModel = hiltViewModel()
            val childState by childViewModel.state.collectAsStateWithLifecycle()
            ChildHomeScreen(
                state = childState,
                onRequestTime = { navController.navigate(Routes.CHILD_REQUEST) },
                onOpenRules = { navController.navigate(Routes.CHILD_RULES) },
                onOpenPrivacy = { navController.navigate(Routes.CHILD_PRIVACY) },
                onDismissBanner = childViewModel::dismissBanner,
            )
        }

        composable(Routes.CHILD_RULES) {
            val childViewModel: ChildViewModel = hiltViewModel()
            val childState by childViewModel.state.collectAsStateWithLifecycle()
            ChildRulesScreen(
                state = childState,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CHILD_PRIVACY) {
            WhatParentsSeeScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CHILD_REQUEST) {
            val childViewModel: ChildViewModel = hiltViewModel()
            val childState by childViewModel.state.collectAsStateWithLifecycle()
            RequestTimeScreen(
                state = childState,
                onSend = childViewModel::requestMoreTime,
                onAcknowledgeSent = childViewModel::acknowledgeRequestSent,
                onDismissBanner = childViewModel::dismissBanner,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.navigateToRoot(route: String) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** Tab switches keep the dashboard's state and never stack duplicates. */
private fun NavHostController.selectTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.checking_session),
            style = MaterialTheme.typography.bodyLarge,
        )
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 72.dp),
        )
    }
}
