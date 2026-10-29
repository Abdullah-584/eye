package com.familyguard.child.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.familyguard.feature.childhub.ChildHomeScreen
import com.familyguard.feature.childhub.ChildRulesScreen
import com.familyguard.feature.childhub.ChildViewModel
import com.familyguard.feature.childhub.RequestTimeScreen
import com.familyguard.feature.childhub.WhatParentsSeeScreen
import com.familyguard.feature.join.JoinScreen
import com.familyguard.service.enforcement.EnforcementService
import com.familyguard.service.location.LocationService

private object Routes {
    const val SPLASH = "splash"
    const val JOIN = "join"
    const val CHILD_HOME = "child_home"
    const val CHILD_RULES = "child_rules"
    const val CHILD_PRIVACY = "child_privacy"
    const val CHILD_REQUEST = "child_request"
}

private val CHILD_TERRITORY =
    setOf(Routes.CHILD_HOME, Routes.CHILD_RULES, Routes.CHILD_PRIVACY, Routes.CHILD_REQUEST)

/**
 * Root of the **child** app (PLAN-SPLIT §2/§6).
 *
 * Navigation is driven by pairing status alone — there is no sign-in (the
 * device session is minted by `pairing-verify`) and no mode switch (the role
 * is this module's `applicationId`). Both foreground services start exactly
 * while this install is paired, and the app keeps its real name and icon in
 * the launcher at all times.
 */
@Composable
fun ChildRoot(
    onOpenChildRequest: () -> Boolean = { false },
    viewModel: ChildShellViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    // Enforcement + location are owned by this app and run only while paired.
    LaunchedEffect(status) {
        when (status) {
            PairingStatus.PAIRED -> {
                EnforcementService.start(context)
                LocationService.start(context)
            }

            PairingStatus.NOT_PAIRED -> {
                context.stopService(Intent(context, EnforcementService::class.java))
                context.stopService(Intent(context, LocationService::class.java))
            }

            PairingStatus.CHECKING -> Unit
        }
    }

    LaunchedEffect(status, currentRoute) {
        if (currentRoute == null) return@LaunchedEffect
        when (status) {
            PairingStatus.CHECKING -> Unit
            PairingStatus.PAIRED ->
                if (currentRoute !in CHILD_TERRITORY) {
                    navController.navigateToRoot(Routes.CHILD_HOME)
                }

            PairingStatus.NOT_PAIRED ->
                if (currentRoute != Routes.JOIN) {
                    navController.navigateToRoot(Routes.JOIN)
                }
        }
    }

    // BlockActivity ("Ask for more time") deep-links into the request screen.
    LaunchedEffect(status, currentRoute) {
        if (status == PairingStatus.PAIRED &&
            currentRoute == Routes.CHILD_HOME &&
            onOpenChildRequest()
        ) {
            navController.navigate(Routes.CHILD_REQUEST)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
    ) {
        composable(Routes.SPLASH) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.padding(top = 72.dp))
            }
        }

        composable(Routes.JOIN) {
            // Pairing completes inside JoinViewModel; the status effect above
            // routes to the hub the moment the device row exists.
            JoinScreen(onJoined = {})
        }

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
