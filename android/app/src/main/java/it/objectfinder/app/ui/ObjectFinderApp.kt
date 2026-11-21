package it.objectfinder.app.ui

import it.objectfinder.app.ui.common.rememberAppMessages
import it.objectfinder.app.ui.common.LocalAppMessages
import androidx.compose.runtime.CompositionLocalProvider
import it.objectfinder.app.ui.recognition.RecognizeFlow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import it.objectfinder.app.ui.auth.NewPasswordDialog
import it.objectfinder.app.data.EmailLinkFlow
import it.objectfinder.app.data.Pkce
import it.objectfinder.app.ui.objects.LinkCodeDialog
import it.objectfinder.app.data.AddIdentifierResult
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.nfc.NfcSession
import it.objectfinder.app.ui.settings.ExportSection
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.IosShare
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import it.objectfinder.app.qr.CodeScanner
import it.objectfinder.app.qr.ScanOutcome
import it.objectfinder.app.qr.ScanResolver
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.data.local.categoryLabel
import it.objectfinder.app.location.LocationProvider
import it.objectfinder.app.maps.ObjectMapScreen
import it.objectfinder.app.sync.SyncScheduler
import it.objectfinder.app.ui.auth.AuthScreen
import it.objectfinder.app.ui.objects.ObjectEditorScreen
import it.objectfinder.app.ui.objects.ObjectEditorViewModel
import it.objectfinder.app.ui.objects.ObjectListScreen
import it.objectfinder.app.ui.objects.ObjectListViewModel
import it.objectfinder.app.ui.projects.MembersScreen
import it.objectfinder.app.ui.projects.MembersViewModel
import it.objectfinder.app.ui.projects.ProjectsScreen
import it.objectfinder.app.ui.projects.ProjectsViewModel
import it.objectfinder.app.ui.settings.SettingsScreen

private object Routes {
    const val AUTH = "auth"
    const val OBJECTS = "objects"
    const val MAP = "map"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?id={id}&photo={photo}"
    const val PROJECTS = "projects"
    const val MEMBERS = "members"
    fun editor(id: String? = null) = if (id == null) "editor" else "editor?id=$id"
    /** Nuovo oggetto con una foto già scattata (es. dal riconoscimento senza risultati). */
    fun newObjectWithPhoto(photo: String) = "editor?photo=${android.net.Uri.encode(photo)}"
}

internal enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    OBJECTS(Routes.OBJECTS, "Oggetti", Icons.AutoMirrored.Filled.List),
    MAP(Routes.MAP, "Mappa", Icons.Default.Map),
    SETTINGS(Routes.SETTINGS, "Impostazioni", Icons.Default.Settings),
}

@Composable
fun ObjectFinderApp(
    container: AppContainer,
    pendingLink: String? = null,
    onLinkHandled: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messages = rememberAppMessages(snackbar)
    val resolver = remember(container) {
        ScanResolver(
            findObject = { container.objectRepository.get(it) },
            findObjectsByCode = { container.identifierRepository.findObjects(it) },
            currentProjectId = { container.projectRepository.currentProjectId },
            knownProjects = {
                // A freddo l'elenco può non essere ancora caricato: si aggiorna (best-effort).
                container.projectRepository.projects.value.ifEmpty {
                    if (container.authRepository.session.value == null) emptyList()
                    else runCatching { container.projectRepository.refreshProjects() }.getOrDefault(emptyList())
                }
            },
        )
    }

    var choosing by remember { mutableStateOf<ScanOutcome.Choose?>(null) }
    var unlinked by remember { mutableStateOf<ScanOutcome.Unlinked?>(null) }
    // Link di ripristino aperto: si è dentro, si sceglie subito la nuova password.
    var choosingPassword by remember { mutableStateOf(false) }

    /** Gestisce un contenuto scansionato, un deep link o un tag NFC: apre l'oggetto o propone di collegarlo. */
    fun handleScanned(raw: String, type: IdentifierType = IdentifierType.BARCODE) {
        scope.launch {
            when (val outcome = resolver.resolve(raw, type)) {
                is ScanOutcome.Unlinked -> { choosing = null; unlinked = outcome }
                is ScanOutcome.Open -> nav.openObject(outcome.objectId)
                is ScanOutcome.Message -> snackbar.showSnackbar(outcome.text, duration = SnackbarDuration.Long)
                is ScanOutcome.Choose -> choosing = outcome
                is ScanOutcome.SwitchProject -> {
                    val result = snackbar.showSnackbar(
                        message = "«${outcome.objectName}» è nel progetto «${outcome.project.name}»",
                        actionLabel = "Apri",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        container.projectRepository.selectProject(outcome.project)
                        nav.openObject(outcome.objectId)
                    }
                }
            }
        }
    }
    val startScan: () -> Unit = {
        val act = activity as? ComponentActivity
        if (act != null) {
            CodeScanner.scan(
                act,
                onResult = { handleScanned(it.raw, if (it.isQr) IdentifierType.QR else IdentifierType.BARCODE) },
                onError = { msg -> scope.launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Long) } },
            )
        }
    }
    val session by container.authRepository.session.collectAsStateWithLifecycle()
    val onboardingDone by container.settings.onboardingDone.collectAsStateWithLifecycle()
    val requestSync = remember(context) { { SyncScheduler.syncNow(context) } }

    // Primo avvio senza sessione → onboarding/login; poi l'app resta usabile anche offline.
    val start = remember { if (session == null && !onboardingDone) Routes.AUTH else Routes.OBJECTS }

    // Tag NFC avvicinati con l'app aperta: link Object Finder o UID del chip da collegare.
    LaunchedEffect(Unit) {
        NfcSession.reads.collect { read ->
            if (read.link != null) handleScanned(read.link)
            else if (read.uid.isNotEmpty()) handleScanned(read.uid, IdentifierType.NFC)
        }
    }

    // Deep link da etichetta QR (anche a freddo): si gestisce una volta pronta la navigazione.
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        onLinkHandled() // azzera pendingLink: questo effetto viene annullato, il lavoro va in `scope`
        scope.launch { when (val callback = Pkce.parseCallback(link)) {
            null -> handleScanned(link)
            // Link dell'email di conferma: account confermato e accesso fatto, senza password.
            is Pkce.Callback.Code -> {
              val expectedFlow = EmailLinkFlow.fromWire(container.settings.pkceFlow())
              runCatching { container.authRepository.completeEmailLink(callback.code) }
                .onSuccess { flow ->
                    container.settings.setOnboardingDone()
                    requestSync()
                    if (nav.currentDestination?.route == Routes.AUTH) nav.leaveAuth()
                    if (flow == EmailLinkFlow.RECOVERY) choosingPassword = true
                    else snackbar.showSnackbar("Email confermata: benvenuto in Object Finder")
                }
                .onFailure { e ->
                    snackbar.showSnackbar(
                        (e as? IllegalStateException)?.message
                            ?: if (expectedFlow == EmailLinkFlow.RECOVERY)
                                "Il link è scaduto o già usato: chiedine uno nuovo da «Password dimenticata?»."
                            else "Il link è scaduto o già usato. L'email risulta confermata: accedi con email e password.",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
            is Pkce.Callback.Error -> snackbar.showSnackbar(
                "Link di conferma non valido o scaduto (${callback.description}). Accedi e, se serve, registrati di nuovo.",
                duration = SnackbarDuration.Long,
            )
        } }
    }

    CompositionLocalProvider(LocalAppMessages provides messages) {
        Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.AUTH) {
                AuthScreen(
                    container = container,
                    onDone = {
                        container.settings.setOnboardingDone()
                        requestSync()
                        nav.leaveAuth()
                    },
                    onContinueOffline = {
                        container.settings.setOnboardingDone()
                        nav.leaveAuth()
                    },
                )
            }
            composable(Routes.OBJECTS) {
                val vm = viewModel { ObjectListViewModel(container.objectRepository, container.photoRepository, container.identifierRepository, container.placeRepository) }
                MainShell(container, nav, Tab.OBJECTS, requestSync, startScan) { modifier, startRecognition ->
                    val active by container.settings.activeProject.collectAsStateWithLifecycle()
                    ObjectListScreen(
                        viewModel = vm,
                        canCreate = active?.canEditObjects ?: true,
                        showSyncState = session != null,
                        onOpenObject = { nav.navigate(Routes.editor(it)) },
                        onCreateObject = { nav.navigate(Routes.editor()) },
                        onChanged = requestSync,
                        onScan = startScan,
                        onRecognize = startRecognition,
                        modifier = modifier,
                    )
                }
            }
            composable(Routes.MAP) {
                val objects by container.objectRepository.observeObjects().collectAsStateWithLifecycle(emptyList())
                val engine by container.settings.mapEngine.collectAsStateWithLifecycle()
                MainShell(container, nav, Tab.MAP, requestSync, startScan) { modifier, _ ->
                    ObjectMapScreen(
                        objects = objects.filter { it.statusEnum != ObjectStatus.ARCHIVED },
                        preferredEngine = engine,
                        onEngineChange = container.settings::setMapEngine,
                        onOpenObject = { nav.navigate(Routes.editor(it)) },
                        modifier = modifier,
                    )
                }
            }
            composable(Routes.SETTINGS) {
                MainShell(container, nav, Tab.SETTINGS, requestSync, startScan) { modifier, _ ->
                    SettingsScreen(
                        container = container,
                        onSignIn = { nav.navigate(Routes.AUTH) },
                        onSyncNow = requestSync,
                        onOpenProjects = { nav.navigate(Routes.PROJECTS) },
                        onOpenMembers = { nav.navigate(Routes.MEMBERS) },
                        modifier = modifier,
                    )
                }
            }
            composable(Routes.PROJECTS) {
                val vm = viewModel {
                    ProjectsViewModel(container.projectRepository, container.settings, onProjectChanged = requestSync)
                }
                ProjectsScreen(viewModel = vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.MEMBERS) {
                val project by container.settings.activeProject.collectAsStateWithLifecycle()
                val vm = viewModel { MembersViewModel(container.projectRepository, onProjectChanged = requestSync) }
                MembersScreen(viewModel = vm, project = project, onBack = { nav.popBackStack() })
            }
            composable(Routes.EDITOR) { entry ->
                val id = entry.arguments?.getString("id")
                val initialPhoto = entry.arguments?.getString("photo")?.let(android.net.Uri::parse)
                val vm = viewModel(key = "editor-$id-$initialPhoto") {
                    ObjectEditorViewModel(
                        objectId = id,
                        repository = container.objectRepository,
                        photoRepository = container.photoRepository,
                        identifierRepository = container.identifierRepository,
                        historyRepository = container.locationHistoryRepository,
                        refreshHistory = { objectId ->
                            if (container.authRepository.session.value != null &&
                                container.settings.activeProject.value?.role != null
                            ) container.locationHistoryRepository.refresh(objectId)
                        },
                        locationProvider = LocationProvider(context),
                        appScope = container.appScope,
                        onLocalChange = { requestSync(); container.indexPhotosInBackground() },
                        initialPhoto = initialPhoto,
                        mirage = container.mirageEngine,
                        places = container.placeRepository,
                        plants = container.plantIdentifier,
                        plantModel = container.plantEngine,
                    )
                }
                val active by container.settings.activeProject.collectAsStateWithLifecycle()
                val editorMapEngine by container.settings.mapEngine.collectAsStateWithLifecycle()
                ObjectEditorScreen(
                    viewModel = vm,
                    readOnly = active?.canEditObjects == false,
                    objectId = id,
                    canDelete = active?.canDeleteObjects ?: true,
                    mapEngine = editorMapEngine,
                    onClose = { nav.popBackStack() },
                    onRegistered = { newId, name ->
                        messages.show("«$name» registrato", actionLabel = "Apri") { nav.openObject(newId) }
                    },
                )
            }
        }
        choosing?.let { choice ->
            AlertDialog(
                onDismissRequest = { choosing = null },
                title = { Text("Più oggetti con questo codice") },
                text = {
                    Column {
                        choice.objects.forEach { item ->
                            ListItem(
                                supportingContent = { Text(categoryLabel(item.category)) },
                                modifier = Modifier.clickable { choosing = null; nav.openObject(item.id) },
                            ) { Text(item.name) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choosing = null }) { Text("Chiudi") } },
            )
        }

        unlinked?.let { code ->
            val objects by container.objectRepository.observeObjects().collectAsStateWithLifecycle(emptyList())
            val active by container.settings.activeProject.collectAsStateWithLifecycle()
            LinkCodeDialog(
                code = code.code,
                type = code.type,
                objects = objects,
                canLink = active?.canEditObjects ?: true,
                onPick = { item ->
                    unlinked = null
                    scope.launch {
                        val text = when (container.identifierRepository.add(item.id, code.type, code.code)) {
                            AddIdentifierResult.Added -> { requestSync(); "Collegato a «${item.name}»" }
                            AddIdentifierResult.Duplicate -> "Già collegato a «${item.name}»"
                            AddIdentifierResult.Invalid -> "Codice non valido"
                        }
                        nav.openObject(item.id)
                        snackbar.showSnackbar(text)
                    }
                },
                onDismiss = { unlinked = null },
            )
        }

        if (choosingPassword) {
            NewPasswordDialog(
                title = "Scegli una nuova password",
                message = "Sei dentro con il link dell'email. Imposta ora la nuova password per i prossimi accessi.",
                email = session?.email.orEmpty(),
                onSave = container.authRepository::changePassword,
                onDone = { text -> choosingPassword = false; scope.launch { snackbar.showSnackbar(text) } },
                onDismiss = { choosingPassword = false },
            )
        }

        // Snackbar globale (scansioni/deep link) sopra qualsiasi schermata, sopra la barra di navigazione.
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
        )
        }
    }
}

/** Apre l'editor dell'oggetto, senza duplicarlo se è già quello in primo piano. */
private fun NavHostController.openObject(objectId: String) {
    val top = currentBackStackEntry
    if (top?.destination?.route == Routes.EDITOR && top.arguments?.getString("id") == objectId) return
    navigate(Routes.editor(objectId))
}

private fun NavHostController.leaveAuth() {
    if (!popBackStack()) {
        navigate(Routes.OBJECTS) { popUpTo(Routes.AUTH) { inclusive = true } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    container: AppContainer,
    nav: NavHostController,
    current: Tab,
    onSync: () -> Unit,
    onScan: () -> Unit,
    content: @Composable (modifier: Modifier, startRecognition: () -> Unit) -> Unit,
) {
    val project by container.settings.activeProject.collectAsStateWithLifecycle()
    val session by container.authRepository.session.collectAsStateWithLifecycle()
    val pending by container.objectRepository.observePendingCount().collectAsStateWithLifecycle(0)
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    if (recognizing) {
        val objects by container.objectRepository.observeObjects().collectAsStateWithLifecycle(emptyList())
        val covers by container.photoRepository.observeCovers().collectAsStateWithLifecycle(emptyMap())
        RecognizeFlow(
            container = container,
            objects = objects,
            covers = covers,
            onOpenObject = { id -> recognizing = false; nav.navigate(Routes.editor(id)) },
            onRegisterNew = { photo -> recognizing = false; nav.navigate(Routes.newObjectWithPhoto(photo.toString())) },
            onClose = { recognizing = false },
        )
    }

    if (exportOpen) {
        ModalBottomSheet(onDismissRequest = { exportOpen = false }) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Esporta inventario", style = MaterialTheme.typography.titleLarge)
                ExportSection(container.inventoryExporter)
            }
        }
    }

    AppNavigationSuite(
        current = current,
        onSelect = { tab ->
            nav.navigate(tab.route) {
                // OBJECTS è sempre alla base dello stack (anche quando lo start è AUTH).
                popUpTo(Routes.OBJECTS) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == Tab.SETTINGS) "Impostazioni" else project?.name ?: "Object Finder") },
                actions = {
                    // Sugli Oggetti scansione e riconoscimento stanno nella barra di ricerca.
                    if (current == Tab.MAP) {
                        IconButton(onClick = onScan) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scansiona etichetta")
                        }
                    }
                    if (session != null) {
                        IconButton(onClick = onSync) {
                            BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sincronizza")
                            }
                        }
                    }
                    // Export vicino agli oggetti che esporta, in un menu per non affollare la barra.
                    if (current == Tab.OBJECTS) Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Altre azioni")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Esporta inventario") },
                                leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null) },
                                onClick = { menuOpen = false; exportOpen = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.padding(padding)) { recognizing = true }
    }
    }
}

/**
 * Navigazione principale adattiva: barra in basso su telefono, barra laterale da 600dp in su
 * (tablet, pieghevoli aperti, finestre libere). La scelta la fa la WindowSizeClass, mai l'orientamento.
 */
@Composable
internal fun AppNavigationSuite(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                item(
                    selected = tab == current,
                    onClick = { if (tab != current) onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        },
        modifier = modifier,
        content = content,
    )
}
