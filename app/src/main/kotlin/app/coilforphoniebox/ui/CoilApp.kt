package app.coilforphoniebox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import app.coilforphoniebox.domain.model.FavoritesLayout
import app.coilforphoniebox.domain.model.FavoritesSort
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
import kotlinx.coroutines.flow.MutableSharedFlow

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
    // A tap on the library tab while already in the library is a request to search. An event
    // rather than state, so coming back to the tab later does not replay it.
    val librarySearchRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

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
    val compactHeight = isCompactHeight()

    val onNavigate: (Destination) -> Unit = { destination ->
        if (destination == selectedDestination && destination == Destination.LIBRARY) {
            librarySearchRequests.tryEmit(Unit)
        }
        navController.navigateSingleTop(destination.route)
    }

    val miniPlayer: @Composable () -> Unit = {
        if (state.showMiniPlayer && currentRoute != Destination.PLAYER.route) {
            MiniPlayer(
                status = state.status,
                coverUrl = state.coverUrl,
                coverName = state.coverName,
                coverPending = state.coverPending,
                onClick = { navController.navigateSingleTop(Destination.PLAYER.route) },
                onToggle = appViewModel::togglePlayback,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The display cutout as well as the system bars. The default is the system bars only,
        // which on a phone on its side put the rail's labels and the edge of the grid under
        // the front camera — the cutout is at one end of a landscape window, not at the top.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
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
                // Only the favourites tab has anything to put here: how its entries are
                // ordered, and what shape they take. The layout action shows what a tap would
                // switch *to*, so the icon and its label describe the same thing.
                actions = {
                    if (currentRoute == Destination.FAVOURITES.route) {
                        FavoritesSortAction(
                            current = state.settings.favoritesSort,
                            onSelect = appViewModel::setFavoritesSort,
                        )

                        val list = state.settings.favoritesLayout == FavoritesLayout.LIST
                        IconButton(onClick = appViewModel::toggleFavoritesLayout) {
                            Icon(
                                imageVector = if (list) {
                                    Icons.Rounded.GridView
                                } else {
                                    Icons.AutoMirrored.Rounded.ViewList
                                },
                                contentDescription = stringResource(
                                    if (list) R.string.action_favourites_show_grid
                                    else R.string.action_favourites_show_list,
                                ),
                            )
                        }
                    }
                },
                // A phone on its side has height to spare nowhere, and the bar is mostly empty
                // space around one pill — so it gives back a quarter of itself there.
                expandedHeight = if (compactHeight) {
                    COMPACT_TOP_BAR_HEIGHT
                } else {
                    TopAppBarDefaults.TopAppBarExpandedHeight
                },
            )
        },
        bottomBar = {
            if (!compactHeight) {
                Column {
                    miniPlayer()
                    BottomBar(selected = selectedDestination, onNavigate = onNavigate)
                }
            }
        },
    ) { padding ->
        // In a short window the navigation moves to a rail at the side. Stacked, the top bar,
        // the mini player and a bottom bar took two thirds of a landscape phone's height and
        // left the library without a single visible row; width is what such a window has, so
        // that is where the navigation goes. The mini player stays at the bottom, under the
        // screen rather than under the rail, so it lines up with what it belongs to.
        Row(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (compactHeight) {
                SideRail(selected = selectedDestination, onNavigate = onNavigate)
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
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
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    composable(Destination.PLAYER.route) {
                        val viewModel = hiltViewModel<PlayerViewModel>()
                        SnackbarMessages(viewModel.messages, snackbarHostState)
                        PlayerScreen(viewModel)
                    }

                    composable(Destination.LIBRARY.route) {
                        val viewModel = hiltViewModel<LibraryViewModel>()
                        SnackbarMessages(viewModel.messages, snackbarHostState)
                        LibraryScreen(viewModel, searchRequests = librarySearchRequests)
                    }

                    composable(Destination.FAVOURITES.route) {
                        val viewModel = hiltViewModel<FavoritesViewModel>()
                        SnackbarMessages(viewModel.messages, snackbarHostState)
                        FavoritesScreen(
                            viewModel = viewModel,
                            layout = state.settings.favoritesLayout,
                            sort = state.settings.favoritesSort,
                        )
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

                if (compactHeight) miniPlayer()
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

/**
 * The favourites tab's order, as a menu rather than a toggle.
 *
 * A toggle can only say what a tap switches *to*, which works for the layout — the shape on
 * screen answers "and what is it now?" by itself. Order cannot: a grid sorted A–Z and a grid
 * a user happens to have arranged that way look identical, so the state has to be written
 * down. Hence a menu, with the current order ticked.
 *
 * The three orders are two entries, not three: the alphabetical one reverses when it is
 * chosen again, which is the gesture a sort control has almost everywhere else. That would
 * normally be the thing this menu exists to avoid — a tap whose result is invisible — so the
 * entry is written to answer both questions at once: its label *is* the direction, so the
 * ticked row reads "Z–A" once reversed instead of leaving the user to infer it.
 */
@Composable
private fun FavoritesSortAction(current: FavoritesSort, onSelect: (FavoritesSort) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val descending = current == FavoritesSort.NAME_DESC
    val alphabetical = current == FavoritesSort.NAME || descending

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.action_favourites_sort),
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.favourites_sort_manual)) },
                // The tick is decorative; `selected` is what makes the choice audible,
                // and a menu whose state only exists as a picture is no state at all.
                leadingIcon = {
                    if (!alphabetical) {
                        Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                    }
                },
                onClick = {
                    menuOpen = false
                    onSelect(FavoritesSort.MANUAL)
                },
                modifier = Modifier.semantics { selected = !alphabetical },
            )

            // One entry for both directions, because choosing the alphabetical order again
            // is how a list gets reversed nearly everywhere else. The label carries the
            // direction rather than a fixed "A–Z" plus an arrow: it reads as the state it is
            // in — "Z–A", ticked — and as what a tap does from the other order, which is
            // what an order that looks identical on screen to a hand-made one needs. The
            // arrow is the affordance for tapping a row that is already ticked, and is
            // decorative for the same reason the tick is.
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (descending) R.string.favourites_sort_name_desc
                            else R.string.favourites_sort_name,
                        ),
                    )
                },
                leadingIcon = {
                    if (alphabetical) {
                        Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                    }
                },
                trailingIcon = {
                    if (alphabetical) {
                        Icon(
                            imageVector = if (descending) {
                                Icons.Rounded.ArrowDownward
                            } else {
                                Icons.Rounded.ArrowUpward
                            },
                            contentDescription = null,
                        )
                    }
                },
                onClick = {
                    menuOpen = false
                    // From the manual order, the first tap sorts A–Z; from A–Z it reverses.
                    onSelect(
                        if (current == FavoritesSort.NAME) FavoritesSort.NAME_DESC
                        else FavoritesSort.NAME,
                    )
                },
                modifier = Modifier.semantics { selected = alphabetical },
            )
        }
    }
}

@Composable
private fun BottomBar(selected: Destination?, onNavigate: (Destination) -> Unit) {
    NavigationBar {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onNavigate(destination) },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/**
 * The same four destinations as [BottomBar], down the side of a short window.
 *
 * Takes no insets of its own: it sits inside the scaffold's content, whose padding already
 * keeps it clear of the system bars and a side-mounted cutout.
 */
@Composable
private fun SideRail(selected: Destination?, onNavigate: (Destination) -> Unit) {
    NavigationRail(
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Spacer(Modifier.weight(1f))
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onNavigate(destination) },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Whether the window is short enough that the navigation belongs at the side: a phone in
 * landscape, or a split-screen half. The window, not the device — `screenHeightDp` follows
 * multi-window — and the same 480 dp line Material draws between compact and medium height.
 */
@Composable
internal fun isCompactHeight(): Boolean =
    LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

private const val COMPACT_HEIGHT_DP = 480
private val COMPACT_TOP_BAR_HEIGHT = 48.dp

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
