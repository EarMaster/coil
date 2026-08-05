package app.coilforphoniebox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.coilforphoniebox.R
import app.coilforphoniebox.ui.boxes.AddBoxScreen
import app.coilforphoniebox.ui.boxes.AddBoxViewModel
import app.coilforphoniebox.ui.boxes.BoxDetailScreen
import app.coilforphoniebox.ui.boxes.BoxesScreen
import app.coilforphoniebox.ui.boxes.BoxesViewModel
import app.coilforphoniebox.ui.components.BoxIndicator
import app.coilforphoniebox.ui.components.BoxSwitcherSheet
import app.coilforphoniebox.ui.components.MiniPlayer
import app.coilforphoniebox.ui.components.OfflineBanner
import app.coilforphoniebox.ui.favorites.FavoritesScreen
import app.coilforphoniebox.ui.favorites.FavoritesViewModel
import app.coilforphoniebox.ui.library.LibraryScreen
import app.coilforphoniebox.ui.library.LibraryViewModel
import app.coilforphoniebox.ui.player.PlayerScreen
import app.coilforphoniebox.ui.player.PlayerViewModel
import app.coilforphoniebox.ui.settings.SettingsScreen
import app.coilforphoniebox.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow

private enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    PLAYER("player", R.string.nav_player, Icons.Rounded.PlayCircle),
    LIBRARY("library", R.string.nav_library, Icons.Rounded.LibraryMusic),
    FAVOURITES("favourites", R.string.nav_favourites, Icons.Rounded.Star),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
}

private const val ROUTE_ADD_BOX = "add-box"
private const val ROUTE_BOXES = "boxes"
private const val ARG_BOX_ID = "boxId"
private const val ROUTE_BOX_DETAIL = "box/{$ARG_BOX_ID}"

private fun boxDetailRoute(boxId: String) = "box/$boxId"

/**
 * Which tab a route belongs under. Box management sits behind settings rather than in the
 * navigation bar, so the settings tab has to stay lit while the user is in there — an
 * unselected bar reads as "you left the app's structure behind".
 */
private fun owningDestination(route: String?): Destination? = when (route) {
    null -> null
    ROUTE_BOXES, ROUTE_ADD_BOX, ROUTE_BOX_DETAIL -> Destination.SETTINGS
    else -> Destination.entries.firstOrNull { it.route == route }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoilApp(appViewModel: AppViewModel) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var switcherOpen by remember { mutableStateOf(false) }

    // Nothing in the app is usable without a box, so a first launch is the add-box screen
    // and nothing else — no feature tour, no carousel (§11.2).
    if (state.needsOnboarding) {
        Scaffold { padding ->
            AddBoxScreen(
                viewModel = hiltViewModel<AddBoxViewModel>(),
                onSaved = { },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedDestination = owningDestination(currentRoute)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    BoxIndicator(
                        activeBox = state.activeBox,
                        connection = state.connection,
                        switchable = state.boxes.size > 1,
                        onClick = {
                            switcherOpen = true
                            appViewModel.probeBoxes()
                        },
                    )
                },
                // Only for the screens that are pushed on top of a tab. The tabs themselves
                // are not a stack, so a back arrow there would be a lie.
                navigationIcon = {
                    if (currentRoute != null && Destination.entries.none { it.route == currentRoute }) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (state.showMiniPlayer && currentRoute != Destination.PLAYER.route) {
                    MiniPlayer(
                        status = state.status,
                        coverUrl = state.coverUrl,
                        onClick = { navController.navigateSingleTop(Destination.PLAYER.route) },
                        onToggle = appViewModel::togglePlayback,
                    )
                }
                BottomBar(navController = navController, selected = selectedDestination)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.isOffline) {
                OfflineBanner(
                    text = stringResource(R.string.offline_banner),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            NavHost(
                navController = navController,
                startDestination = Destination.PLAYER.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.PLAYER.route) {
                    val viewModel = hiltViewModel<PlayerViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    PlayerScreen(viewModel)
                }

                composable(Destination.LIBRARY.route) {
                    val viewModel = hiltViewModel<LibraryViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    LibraryScreen(viewModel)
                }

                composable(Destination.FAVOURITES.route) {
                    val viewModel = hiltViewModel<FavoritesViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    FavoritesScreen(viewModel)
                }

                composable(Destination.SETTINGS.route) {
                    val viewModel = hiltViewModel<SettingsViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    SettingsScreen(
                        viewModel = viewModel,
                        onAddBox = { navController.navigate(ROUTE_ADD_BOX) },
                        onManageBoxes = { navController.navigate(ROUTE_BOXES) },
                    )
                }

                composable(ROUTE_BOXES) {
                    val viewModel = hiltViewModel<BoxesViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    BoxesScreen(
                        viewModel = viewModel,
                        onOpenBox = { boxId -> navController.navigate(boxDetailRoute(boxId)) },
                        onAddBox = { navController.navigate(ROUTE_ADD_BOX) },
                    )
                }

                composable(ROUTE_BOX_DETAIL) { entry ->
                    val viewModel = hiltViewModel<BoxesViewModel>()
                    SnackbarMessages(viewModel.messages, snackbarHostState)
                    BoxDetailScreen(
                        viewModel = viewModel,
                        boxId = entry.arguments?.getString(ARG_BOX_ID).orEmpty(),
                        onRemoved = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_ADD_BOX) {
                    AddBoxScreen(
                        viewModel = hiltViewModel<AddBoxViewModel>(),
                        onSaved = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    if (switcherOpen) {
        val reachability by appViewModel.reachability.collectAsStateWithLifecycle()
        BoxSwitcherSheet(
            boxes = state.boxes,
            activeBoxId = state.activeBox?.id,
            reachability = reachability,
            onSelect = { boxId ->
                appViewModel.selectBox(boxId)
                switcherOpen = false
            },
            onAddBox = {
                switcherOpen = false
                navController.navigate(ROUTE_ADD_BOX)
            },
            onDismiss = { switcherOpen = false },
        )
    }
}

@Composable
private fun BottomBar(navController: NavHostController, selected: Destination?) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { navController.navigateSingleTop(destination.route) },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/** Shows one-off view model messages, which arrive as string resources rather than text. */
@Composable
private fun SnackbarMessages(messages: Flow<UiMessage>, host: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { message ->
            val text = message.formatArg
                ?.let { context.getString(message.text, it) }
                ?: context.getString(message.text)
            host.showSnackbar(text)
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
