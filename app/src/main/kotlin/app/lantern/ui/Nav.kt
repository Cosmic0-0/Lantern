package app.lantern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.text.style.TextOverflow
import app.lantern.library.Library
import kotlinx.coroutines.flow.first

/** Where you are in the app. [Reader]'s chapter is the one it opens on; the reader can move on from there. */
sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data class Book(val bookId: Long) : Screen
    data class Reader(val bookId: Long, val chapterId: Long) : Screen
    data object Settings : Screen
    data object Paste : Screen
    data class SwitchSource(val bookId: Long) : Screen
}

private fun Screen.encode(): String = when (this) {
    Screen.Library -> "library"
    Screen.Search -> "search"
    is Screen.Book -> "book:$bookId"
    is Screen.Reader -> "reader:$bookId:$chapterId"
    Screen.Settings -> "settings"
    Screen.Paste -> "paste"
    is Screen.SwitchSource -> "switch:$bookId"
}

private fun decodeScreen(text: String): Screen {
    val parts = text.split(':')
    return when (parts[0]) {
        "search" -> Screen.Search
        "book" -> Screen.Book(parts[1].toLong())
        "reader" -> Screen.Reader(parts[1].toLong(), parts[2].toLong())
        "settings" -> Screen.Settings
        "paste" -> Screen.Paste
        "switch" -> Screen.SwitchSource(parts[1].toLong())
        else -> Screen.Library
    }
}

/** A back stack of screens. The first is the library and is never popped. */
class Nav(private val stack: SnapshotStateList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        if (screen != current) stack.add(screen)
    }

    /** Swaps the current screen, for flows that shouldn't come back to the screen that started them. */
    fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }
}

/** Opens the reader where [bookId]'s saved position is, or at its first chapter. */
internal suspend fun Nav.openReader(library: Library, bookId: Long) {
    val chapterId = library.position(bookId).first()?.chapterId ?: library.firstChapter(bookId)?.id ?: return
    open(Screen.Reader(bookId, chapterId))
}

/** The screen host: the saved back stack, the system back button and the current screen. */
@Composable
fun LanternRoot() {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<Screen>, String>(
            save = { it.map(Screen::encode) },
            restore = { saved -> saved.map(::decodeScreen).toMutableStateList() },
        ),
    ) { mutableStateListOf<Screen>(Screen.Library) }
    val nav = remember(stack) { Nav(stack) }
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    key(nav.current) {
        when (val screen = nav.current) {
            Screen.Library -> LibraryScreen(nav)
            Screen.Search -> SearchScreen(nav)
            is Screen.Book -> BookScreen(nav, screen.bookId)
            is Screen.Reader -> ReaderScreen(nav, screen.bookId, screen.chapterId)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Paste -> PasteScreen(nav)
            is Screen.SwitchSource -> SwitchSourceScreen(nav, screen.bookId)
        }
    }
}

/** A screen with a top bar and, unless [bottomBar] says otherwise, the mini player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    nav: Nav,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = { MiniPlayer(nav) },
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (nav.canGoBack) TextButton(onClick = nav::back) { Text("Back") } },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/** What the source is called on screen. A book with no source is pasted text. */
fun sourceName(sourceId: String?): String = when (sourceId) {
    null -> "Pasted"
    "anf.net" -> "AllNovelFull"
    "readnovelfull" -> "ReadNovelFull"
    "novelbuddy" -> "NovelBuddy"
    else -> sourceId
}

fun chaptersText(count: Int): String = if (count == 1) "1 chapter" else "$count chapters"
