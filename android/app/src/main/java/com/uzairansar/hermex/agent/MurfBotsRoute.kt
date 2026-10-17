package com.uzairansar.hermex.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uzairansar.hermex.R
import com.uzairansar.hermex.ui.chat.MarkdownText
import com.uzairansar.hermex.ui.chat.SubtaskCard
import com.uzairansar.hermex.ui.chat.SubtaskDetailText
import com.uzairansar.hermex.ui.chat.TaskStatus
import com.uzairansar.hermex.ui.chat.TaskStatusPill
import com.uzairansar.hermex.ui.chat.rememberElapsedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotsTopBar(title: String, onBack: () -> Unit, leading: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leading?.invoke()
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_hermex_chevron_left), tr("Назад", "Back")) } },
        actions = { actions() },
    )
}

@Composable
private fun ErrorLine(text: String?) {
    text?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

// ───────────────────────────── список ботов ─────────────────────────────

@Composable
fun MurfBotsRoute(
    server: HttpUrl,
    api: MurfBotsApi,
    onOpenBot: (String) -> Unit,
    onOpenClis: () -> Unit,
    onOpenTasks: () -> Unit,
    onBack: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var bots by remember { mutableStateOf<List<BotDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<BotDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        error = null
        bots = runCatching { api.bots(server) }.onFailure { error = it.message }.getOrNull() ?: bots
    }
    Scaffold(
        topBar = {
            BotsTopBar(tr("Боты", "Bots"), onBack) {
                IconButton(onClick = onOpenTasks) { Icon(painterResource(R.drawable.ic_hermex_lightning_bolt), tr("Задачи", "Tasks")) }
                IconButton(onClick = onOpenClis) { Icon(painterResource(R.drawable.ic_hermex_tools_wrench), "CLI") }
                IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Refresh")) }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(painterResource(R.drawable.ic_hermex_plus), tr("Новый бот", "New bot")) }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    tr("Каждый бот — отдельный агент на сервере со своими инструкциями, папкой и бэкендом (Hermes или установленный CLI). " +
                        "Боты работают независимо и могут передавать задачи друг другу.",
                        "Each bot is a separate agent on the server with its own instructions, folder and backend (Hermes or an installed CLI). " +
                            "Bots run independently and can hand tasks to each other."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorLine(error)
            }
            item { BotProposalsSection(server, api, refreshKey = refresh, onDecided = { refresh++ }) }
            val list = bots
            if (list == null && error == null) item { CircularProgressIndicator() }
            if (list != null && list.isEmpty()) {
                item { Text(tr("Ботов пока нет. Нажмите «+», чтобы создать первого.", "No bots yet. Tap “+” to create one."), style = MaterialTheme.typography.bodyMedium) }
            }
            items(list.orEmpty(), key = { it.id }) { bot ->
                Card(Modifier.fillMaxWidth().clickable { onOpenBot(bot.id) }) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MurfBotAvatar(bot)
                        Column(Modifier.weight(1f)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(MurfBotsApi.backendNames[bot.backend] ?: bot.backend, bot.model?.takeIf { it.isNotBlank() },
                                    MurfBotsApi.accessLabel(bot.access)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(tr("Чатов: ${bot.chats}", "Chats: ${bot.chats}"), style = MaterialTheme.typography.labelSmall)
                            bot.createdByName?.let {
                                Text(tr("создан ботом «$it»", "created by “$it”"), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        if (bot.running > 0) TaskStatusPill(TaskStatus.Running)
                        TextButton(onClick = { editing = bot }) { Text(tr("Изм.", "Edit")) }
                    }
                }
            }
            item { Spacer(Modifier.heightIn(min = 72.dp)) }
        }
    }
    if (creating || editing != null) {
        BotEditorDialog(
            server = server, api = api, bot = editing,
            onDismiss = { creating = false; editing = null },
            onSaved = { creating = false; editing = null; refresh++ },
        )
    }
}

// ───────────────────────────── редактор бота ─────────────────────────────

@Composable
fun BotEditorDialog(server: HttpUrl, api: MurfBotsApi, bot: BotDto?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember(bot?.id) { mutableStateOf(bot?.let(BotDraft::of) ?: BotDraft()) }
    var clis by remember { mutableStateOf<ClisResponse?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmNotice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var backendMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { clis = runCatching { api.clis(server) }.onFailure { error = it.message }.getOrNull() }

    fun save(confirm: Boolean) {
        busy = true; error = null
        scope.launch {
            try {
                api.save(server, bot?.id, draft, confirm)
                onSaved()
            } catch (e: BotConfirmRequired) {
                confirmNotice = e.notice
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (bot == null) tr("Новый бот", "New bot") else tr("Бот «${bot.name}»", "Bot “${bot.name}”")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(60)) }, label = { Text(tr("Имя", "Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Персонаж", "Character"), style = MaterialTheme.typography.labelLarge)
                MurfAvatarPicker(
                    current = MurfAvatarSpec.resolve(draft.emoji, draft.backend, draft.name),
                    isDefault = MurfAvatarSpec.decode(draft.emoji) == null,
                    backend = draft.backend,
                    onPick = { spec -> draft = draft.copy(emoji = spec?.encode() ?: "🤖") },
                )
                OutlinedTextField(
                    draft.instructions, { draft = draft.copy(instructions = it.take(8000)) },
                    label = { Text(tr("Инструкции / характер", "Instructions / persona")) },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
                // бэкенд
                val installed = clis?.clis.orEmpty().filter { it.backend }
                Box {
                    OutlinedButton(onClick = { backendMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Бэкенд: ", "Backend: ") + (MurfBotsApi.backendNames[draft.backend] ?: draft.backend))
                    }
                    DropdownMenu(expanded = backendMenu, onDismissRequest = { backendMenu = false }) {
                        installed.forEach { c ->
                            DropdownMenuItem(
                                enabled = c.installed,
                                text = {
                                    Column {
                                        Text(c.name)
                                        Text(
                                            if (!c.installed) tr("не установлен", "not installed")
                                            else listOf(c.version, loginLabel(c.loggedIn), c.mode).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                onClick = { draft = draft.copy(backend = c.id); backendMenu = false },
                            )
                        }
                    }
                }
                if (draft.backend == "hermes") {
                    Box {
                        OutlinedButton(onClick = { profileMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Профиль Hermes: ", "Hermes profile: ") + draft.profile)
                        }
                        DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                            clis?.profiles.orEmpty().forEach { p ->
                                DropdownMenuItem(text = { Text(p) }, onClick = { draft = draft.copy(profile = p); profileMenu = false })
                            }
                        }
                    }
                }
                OutlinedTextField(draft.model, { draft = draft.copy(model = it.take(120)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Модель (необязательно)", "Model (optional)")) },
                    supportingText = { Text(tr("пусто — модель по умолчанию у бэкенда", "empty — backend default"), style = MaterialTheme.typography.labelSmall) })
                OutlinedTextField(draft.workdir, { draft = draft.copy(workdir = it.take(400)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Рабочая папка на сервере", "Working folder on the server")) },
                    supportingText = { Text(tr("пусто — ", "empty — ") + (clis?.workroot?.let { "$it/<имя>" } ?: "~/murf-bots/<name>"), style = MaterialTheme.typography.labelSmall) })
                Text(tr("Доступ", "Access"), style = MaterialTheme.typography.labelLarge)
                listOf(
                    "safe" to tr("Безопасный (по умолчанию): песочница папки, запрос разрешений", "Safe (default): folder sandbox, approval prompts"),
                    "readonly" to tr("Только чтение (план, без изменений)", "Read-only (plan, no changes)"),
                    "cli" to tr("Как в конфиге CLI — может быть полный доступ", "As in CLI config — may be full access"),
                ).forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(access = id) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = draft.access == id, onClick = { draft = draft.copy(access = id) })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (bot != null && bot.depth >= 1) {
                    Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(allowSpawn = !draft.allowSpawn) }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = draft.allowSpawn, onCheckedChange = { draft = draft.copy(allowSpawn = it) })
                        Text(
                            if (bot.depth >= 2) tr("Создан ботом второго уровня — создавать ботов не может", "Second-level bot — cannot create bots")
                            else tr("Разрешить этому боту предлагать новых ботов", "Allow this bot to propose new bots"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ErrorLine(error)
                if (bot != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text(tr("Удалить бота", "Delete bot"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(enabled = !busy && draft.name.isNotBlank(), onClick = { save(false) }) { Text(tr("Сохранить", "Save")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
    confirmNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { confirmNotice = null },
            title = { Text(tr("Подключить CLI к боту?", "Connect the CLI to this bot?")) },
            text = { Text(notice) },
            confirmButton = { Button(onClick = { confirmNotice = null; save(true) }) { Text(tr("Подключить", "Connect")) } },
            dismissButton = { TextButton(onClick = { confirmNotice = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
    if (confirmDelete && bot != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Удалить бота «${bot.name}»?", "Delete bot “${bot.name}”?")) },
            text = { Text(tr("Удалятся бот и его чаты в MURF. Рабочая папка на сервере останется.", "The bot and its MURF chats will be removed. The working folder on the server stays.")) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch { runCatching { api.delete(server, bot.id) }.onSuccess { onSaved() }.onFailure { error = it.message } }
                }) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

@Composable
private fun Modifier.horizontalScrollState(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

private fun loginLabel(v: Boolean?): String = when (v) {
    true -> tr("вход выполнен", "logged in")
    false -> tr("нет входа", "not logged in")
    null -> tr("вход неизвестен", "login unknown")
}

// ───────────────────────────── чаты бота ─────────────────────────────

@Composable
fun MurfBotChatsRoute(server: HttpUrl, api: MurfBotsApi, botId: String, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var bot by remember { mutableStateOf<BotDto?>(null) }
    var chats by remember { mutableStateOf<List<BotChatDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        error = null
        runCatching {
            bot = api.bots(server).firstOrNull { it.id == botId }
            chats = api.chats(server, botId)
        }.onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { while (true) { delay(5000); refresh++ } }
    Scaffold(
        topBar = {
            BotsTopBar(bot?.name ?: tr("Бот", "Bot"), onBack, leading = bot?.let { b -> { MurfBotAvatar(b, size = 32.dp) } }) {
                TextButton(onClick = { editing = true }, enabled = bot != null) { Text(tr("Настроить", "Settings")) }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scope.launch { runCatching { api.newChat(server, botId) }.onSuccess { onOpenChat(it.id) }.onFailure { error = it.message } }
            }) { Icon(painterResource(R.drawable.ic_hermex_square_and_pencil), tr("Новый чат", "New chat")) }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                bot?.let {
                    Text(listOfNotNull(MurfBotsApi.backendNames[it.backend], it.workdir).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ErrorLine(error)
            }
            if (chats?.isEmpty() == true) item { Text(tr("Чатов пока нет — начните новый.", "No chats yet — start a new one.")) }
            items(chats.orEmpty(), key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { tr("Новый чат", "New chat") }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            c.lastText?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (c.running) TaskStatusPill(TaskStatus.Running)
                    }
                }
            }
        }
    }
    if (editing) bot?.let { b ->
        BotEditorDialog(server, api, b, onDismiss = { editing = false }, onSaved = { editing = false; refresh++ })
    }
}

// ───────────────────────────── чат бота ─────────────────────────────

@Composable
fun MurfBotChatRoute(server: HttpUrl, api: MurfBotsApi, chatId: String, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val events = remember(chatId) { mutableStateListOf<BotEvent>() }
    var detail by remember { mutableStateOf<BotChatDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var handoffOpen by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    val transcript = remember(events.size) { BotTranscriptReducer.reduce(events.toList()) }
    val listState = rememberLazyListState()

    // загрузка + поток событий с переподключением
    LaunchedEffect(chatId) {
        var backoff = 1000L
        while (true) {
            try {
                val after = events.lastOrNull()?.seq ?: 0L
                val d = api.chat(server, chatId, after)
                detail = d
                d.events.map(::BotEvent).filter { e -> events.none { it.seq == e.seq } }.let { events.addAll(it) }
                error = null
                connected = true
                api.stream(server, chatId, events.lastOrNull()?.seq ?: 0L).collect { e ->
                    if (events.lastOrNull()?.seq?.let { e.seq > it } != false) events.add(e)
                }
                backoff = 1000L
                // поток закрыт сервером (простой) — ждём, если ничего не идёт
                if (!BotTranscriptReducer.reduce(events.toList()).running) delay(4000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected = false
                error = e.message
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        }
    }
    LaunchedEffect(transcript.items.size) {
        if (transcript.items.isNotEmpty()) listState.animateScrollToItem(transcript.items.lastIndex)
    }

    val bot = detail?.bot
    Scaffold(
        topBar = {
            BotsTopBar((detail?.chat?.title ?: "").ifBlank { bot?.name.orEmpty() }, onBack, leading = bot?.let { b ->
                {
                    MurfBotAvatar(b, size = 32.dp, status = when {
                        transcript.pendingApprovals > 0 -> MurfBotStatus.AwaitingConfirm
                        error != null -> MurfBotStatus.Error
                        transcript.running -> MurfBotStatus.Working
                        else -> MurfBotStatus.Idle
                    })
                }
            }) {
                TextButton(onClick = { handoffOpen = true }, enabled = bot != null) { Text(tr("Передать", "Hand off")) }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().imePadding().padding(8.dp)) {
                ErrorLine(error)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        input, { input = it }, modifier = Modifier.weight(1f), maxLines = 6,
                        placeholder = { Text(tr("Сообщение боту", "Message the bot")) },
                    )
                    if (transcript.running) {
                        IconButton(onClick = { scope.launch { runCatching { api.stop(server, chatId) }.onFailure { error = it.message } } }) {
                            Icon(painterResource(R.drawable.ic_hermex_stop_fill), tr("Остановить", "Stop"))
                        }
                    } else {
                        IconButton(enabled = input.isNotBlank() && !sending, onClick = {
                            val text = input.trim()
                            sending = true
                            scope.launch {
                                runCatching { api.send(server, chatId, text) }
                                    .onSuccess { input = "" }
                                    .onFailure { error = it.message }
                                sending = false
                            }
                        }) { Icon(painterResource(R.drawable.ic_hermex_arrow_up), tr("Отправить", "Send")) }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!connected && events.isEmpty()) item { CircularProgressIndicator() }
            items(transcript.items, key = { it.key }) { item ->
                BotItemView(item, onApprove = { id, opt ->
                    scope.launch { runCatching { api.approve(server, chatId, id, opt) }.onFailure { error = it.message } }
                }, onOpenChat = onOpenChat)
            }
            if (transcript.running) item("running") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(if (transcript.pendingApprovals > 0) tr("ждёт вашего ответа", "waiting for your answer") else tr("бот работает…", "bot is working…"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (handoffOpen && bot != null) {
        HandoffDialog(server, api, chatId, bot, onDismiss = { handoffOpen = false }, onDone = { handoffOpen = false; onOpenChat(it) })
    }
}

@Composable
private fun BotItemView(item: BotItem, onApprove: (String, String?) -> Unit, onOpenChat: (String) -> Unit, nested: Boolean = false) {
    when (item) {
        is BotItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp).widthInFraction(),
            ) {
                item.fromBot?.let { Text(tr("от бота «$it»", "from bot “$it”"), style = MaterialTheme.typography.labelSmall) }
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        is BotItem.Assistant -> MarkdownText(item.text, modifier = Modifier.fillMaxWidth())
        is BotItem.Thinking -> SubtaskCard(
            title = tr("Рассуждения", "Thinking"), status = TaskStatus.Done, stateKey = item.key,
            body = { SubtaskDetailText(item.text) },
        )
        is BotItem.Step -> {
            val elapsed = rememberElapsedText(item.status, item.startedAtMs, item.endedAtMs)
            var open by remember(item.key) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (nested) 0.5f else 0.35f))
                    .clickable(enabled = !item.detail.isNullOrBlank()) { open = !open }.padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\u2692", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                    elapsed?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    TaskStatusPill(item.status)
                }
                if (open) item.detail?.let {
                    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 6.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainer { SubtaskDetailText(it) } }
                    }
                }
            }
        }
        is BotItem.Subtask -> SubtaskCard(
            title = item.title, status = item.status, startedAtMs = item.startedAtMs, endedAtMs = item.endedAtMs,
            subtitle = tr("подзадача · шагов: ${item.children.count { it is BotItem.Step }}", "subtask · steps: ${item.children.count { it is BotItem.Step }}"),
            stateKey = item.key,
            body = if (item.children.isNotEmpty() || !item.detail.isNullOrBlank()) {
                {
                    item.children.forEach { BotItemView(it, onApprove, onOpenChat, nested = true) }
                    item.detail?.takeIf { it.isNotBlank() }?.let { SubtaskDetailText(it) }
                }
            } else null,
        )
        is BotItem.Approval -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Агент просит разрешение", "The agent asks for permission"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(item.title, style = MaterialTheme.typography.bodyMedium)
                item.detail?.let { SubtaskDetailText(it) }
                if (item.pending) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScrollState()) {
                        item.options.forEach { o ->
                            val allow = o.kind?.startsWith("allow") == true
                            if (allow) Button(onClick = { onApprove(item.id, o.id) }) { Text(o.name) }
                            else OutlinedButton(onClick = { onApprove(item.id, o.id) }) { Text(o.name) }
                        }
                        if (item.options.isEmpty()) OutlinedButton(onClick = { onApprove(item.id, null) }) { Text(tr("Отклонить", "Deny")) }
                    }
                } else {
                    val chosen = item.options.firstOrNull { it.id == item.chosen }?.name
                    Text(chosen?.let { tr("Ответ: $it", "Answer: $it") } ?: tr("Отклонено / истекло", "Denied / expired"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is BotItem.Plan -> SubtaskCard(
            title = tr("План: ${item.entries.count { it.status == "completed" }}/${item.entries.size}", "Plan: ${item.entries.count { it.status == "completed" }}/${item.entries.size}"),
            status = if (item.entries.all { it.status == "completed" }) TaskStatus.Done else TaskStatus.Running, stateKey = item.key,
            body = { item.entries.forEach { SubtaskDetailText((if (it.status == "completed") "☑ " else "☐ ") + it.text) } },
        )
        is BotItem.Notice -> Text(item.text, style = MaterialTheme.typography.bodySmall,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        is BotItem.Handoff -> Card(Modifier.fillMaxWidth().clickable(enabled = item.chatId != null) { item.chatId?.let(onOpenChat) }) {
            Column(Modifier.padding(10.dp)) {
                Text(if (item.outgoing) tr("Передано боту «${item.botName}» →", "Handed off to “${item.botName}” →")
                else tr("← Задача от бота «${item.botName}»", "← Task from “${item.botName}”"), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge)
                Text(item.task, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun Modifier.widthInFraction(): Modifier = this.then(Modifier.fillMaxWidth(0.86f))

@Composable
private fun HandoffDialog(server: HttpUrl, api: MurfBotsApi, chatId: String, bot: BotDto, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var bots by remember { mutableStateOf<List<BotDto>>(emptyList()) }
    var target by remember { mutableStateOf<BotDto?>(null) }
    var task by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { bots = runCatching { api.bots(server) }.getOrDefault(emptyList()).filter { it.id != bot.id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Передать задачу другому боту", "Hand the task to another bot")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (bots.isEmpty()) Text(tr("Других ботов нет.", "No other bots."))
                bots.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { target = b }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = target?.id == b.id, onClick = { target = b })
                        MurfBotAvatar(b, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(b.name)
                    }
                }
                OutlinedTextField(task, { task = it }, label = { Text(tr("Задача", "Task")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                ErrorLine(error)
            }
        },
        confirmButton = {
            Button(enabled = target != null && task.isNotBlank(), onClick = {
                val t = target ?: return@Button
                scope.launch { runCatching { api.handoff(server, chatId, t.id, task) }.onSuccess { onDone(it.id) }.onFailure { error = it.message } }
            }) { Text(tr("Передать", "Hand off")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

// ───────────────────────────── CLI ─────────────────────────────

@Composable
fun MurfClisRoute(server: HttpUrl, api: MurfBotsApi, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ClisResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(refresh) {
        loading = true; error = null
        data = runCatching { api.clis(server, refresh = refresh > 0) }.onFailure { error = it.message }.getOrNull() ?: data
        loading = false
    }
    Scaffold(topBar = {
        BotsTopBar(tr("CLI на сервере", "CLIs on the server"), onBack) {
            IconButton(onClick = { refresh++ }) { Icon(painterResource(R.drawable.ic_hermex_refresh), tr("Обновить", "Refresh")) }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(tr("MURF не ставит CLI и не трогает их входы: он находит уже установленные программы и запускает их от вашего пользователя. " +
                    "Показывается только, выполнен ли вход, — токены не читаются и не передаются.",
                    "MURF does not install CLIs or touch their logins: it finds installed programs and runs them as your user. " +
                        "Only whether you are logged in is shown — tokens are never read or sent."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                ErrorLine(error)
            }
            items(data?.clis.orEmpty(), key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val (label, ok) = when {
                                !c.installed -> tr("не установлен", "not installed") to null
                                c.loggedIn == true -> tr("вход выполнен", "logged in") to true
                                c.loggedIn == false -> tr("нет входа", "not logged in") to false
                                else -> tr("вход неизвестен", "unknown") to null
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.outline })
                        }
                        if (c.installed) {
                            Text(c.version.ifBlank { "?" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(c.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                tr("Режим: ${c.mode}", "Mode: ${c.mode}") + " · " +
                                    if (c.approvals) tr("разрешения спрашиваются в MURF", "approvals shown in MURF")
                                    else tr("без вопросов (песочница CLI)", "no prompts (CLI sandbox)"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (c.loginMethod.isNotBlank()) Text(tr("Способ: ", "Method: ") + c.loginMethod, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            data?.profiles?.takeIf { it.size > 1 }?.let { profiles ->
                item { Text(tr("Профили Hermes: ", "Hermes profiles: ") + profiles.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ───────────────────────────── задачи ─────────────────────────────

@Composable
fun MurfAgentTasksRoute(server: HttpUrl, api: MurfBotsApi, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    var runs by remember { mutableStateOf<List<BotRunDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { api.runs(server) }.onSuccess { runs = it; error = null }.onFailure { error = it.message }
            delay(if (runs.orEmpty().any { TaskStatus.from(it.status).isActive }) 2000 else 6000)
        }
    }
    Scaffold(topBar = { BotsTopBar(tr("Задачи ботов", "Bot tasks"), onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ErrorLine(error) }
            item { BotProposalsSection(server, api, refreshKey = (runs?.size ?: 0)) }
            val list = runs
            if (list == null && error == null) item { CircularProgressIndicator() }
            if (list?.isEmpty() == true) item { Text(tr("Запусков пока не было.", "No runs yet.")) }
            val (active, done) = list.orEmpty().partition { TaskStatus.from(it.status).isActive }
            if (active.isNotEmpty()) item { Text(tr("Идут сейчас", "Running now"), style = MaterialTheme.typography.titleSmall) }
            items(active, key = { "a-" + it.run }) { RunCard(it, onOpenChat) }
            if (done.isNotEmpty()) item { Text(tr("Завершённые", "Finished"), style = MaterialTheme.typography.titleSmall) }
            items(done, key = { "d-" + it.run }) { RunCard(it, onOpenChat) }
        }
    }
}

@Composable
private fun RunCard(r: BotRunDto, onOpenChat: (String) -> Unit) {
    val status = TaskStatus.from(r.status)
    val startedMs = r.started?.let { (it * 1000).toLong() }
    SubtaskCard(
        title = "${r.botName ?: r.bot}: ${r.chatTitle ?: ""}".trim(),
        leading = {
            MurfBotAvatar(
                MurfAvatarSpec.resolve(r.emoji, r.backend, r.botName ?: r.bot), r.backend, size = 28.dp,
                status = when {
                    status == TaskStatus.Waiting -> MurfBotStatus.AwaitingConfirm
                    status == TaskStatus.Running -> MurfBotStatus.Working
                    status == TaskStatus.Failed || r.error != null -> MurfBotStatus.Error
                    else -> MurfBotStatus.Idle
                },
            )
        },
        status = status,
        startedAtMs = startedMs,
        durationSec = r.duration.takeIf { !status.isActive },
        subtitle = listOfNotNull(MurfBotsApi.backendNames[r.backend] ?: r.backend, r.fromBot?.let { tr("от «$it»", "from “$it”") },
            (r.subtasks.size.takeIf { it > 0 } ?: r.subtasksCount.takeIf { it > 0 })?.let { tr("подзадач: $it", "subtasks: $it") },
            r.error).joinToString(" · "),
        stateKey = "run-" + r.run,
        modifier = Modifier.clickable { onOpenChat(r.chat) },
        body = if (r.subtasks.isNotEmpty()) {
            {
                r.subtasks.forEach { s ->
                    SubtaskCard(
                        title = s.title ?: tr("подзадача", "subtask"), status = TaskStatus.from(s.status),
                        startedAtMs = s.started?.let { (it * 1000).toLong() }, endedAtMs = s.ended?.let { (it * 1000).toLong() },
                        stateKey = "rs-" + s.id,
                    )
                }
            }
        } else null,
    )
}

// ───────────────────────────── предложения новых ботов ─────────────────────────────

/** Карточки «Бот предлагает создать бота»: создаётся только по кнопке «Создать». */
@Composable
fun BotProposalsSection(server: HttpUrl, api: MurfBotsApi, refreshKey: Int = 0, onDecided: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<BotProposalDto>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey, tick) {
        items = runCatching { api.proposals(server) }.getOrDefault(items).filter { it.status == "pending" }
    }
    LaunchedEffect(Unit) { while (true) { delay(8000); tick++ } }
    if (items.isEmpty() && error == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Предложения новых ботов", "Proposed bots"), style = MaterialTheme.typography.titleSmall)
        ErrorLine(error)
        items.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MurfBotAvatar(MurfAvatarSpec.resolve(p.emoji, p.backend, p.name), p.backend, size = 40.dp, status = MurfBotStatus.AwaitingConfirm)
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(tr("предлагает «${p.parentName}»", "proposed by “${p.parentName}”"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Text(listOf(MurfBotsApi.backendNames[p.backend] ?: p.backend, MurfBotsApi.accessLabel(p.access), p.workdir).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.reason.isNotBlank()) Text(tr("Зачем: ", "Why: ") + p.reason, style = MaterialTheme.typography.bodySmall)
                    if (p.instructions.isNotBlank()) Text(p.instructions, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    p.notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true, false).forEach { approve ->
                            val onClick = {
                                busy = p.id; error = null
                                scope.launch {
                                    runCatching { api.decideProposal(server, p.id, approve) }
                                        .onSuccess { items = items.filter { it.id != p.id }; onDecided() }
                                        .onFailure { error = it.message }
                                    busy = null
                                }
                                Unit
                            }
                            if (approve) Button(enabled = busy == null, onClick = onClick) { Text(tr("Создать", "Create")) }
                            else OutlinedButton(enabled = busy == null, onClick = onClick) { Text(tr("Отклонить", "Reject")) }
                        }
                    }
                }
            }
        }
    }
}
