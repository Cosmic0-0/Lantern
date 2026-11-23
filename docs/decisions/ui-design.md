# UI design

Decided with you in chat on 2026-11-21. Lantern's screens are redesigned after the Android app "Light Novel" by Not a simple studio, whose screenshots you shared. More reference apps may follow in later versions.

## What was decided

- **Look.** Near-black with one red, from your Instagram palette: Rush Red #E52620, Ink Black #20201E, Warm Ivory #F2EBDD. The app is always dark, whatever the phone's setting; only the reader gets light themes of its own (step 5).
- **Two reds.** The brand red reads at 4.1:1 as text on the dark ground, below the 4.5:1 that small text needs, and no red passes both as text there and under white text. So the brand red fills buttons and the + button, with white text on it (4.5:1), and a lighter red (#FF5A4E, 6.1:1) is used for red text, icons and selection.
- **Type.** Headings and titles use Schibsted Grotesk, a sturdy newspaper grotesk, bundled in the app (SIL Open Font License, copy in `third-party/fonts/`). Body text and labels stay in the phone's own Roboto.
- **No bottom navigation bar.** With search moving into the + sheet, only Library and Settings would be left, and Material asks for 3 to 5 tabs. Settings is an icon in the top bar.
- **Covers are shown,** downloaded once from the address each book already stores, into the app's cache, and decoded at tile size. About 100 lines of the phone's own HTTP and image code do this instead of the Coil library agreed in chat: a library of a few books needs no more, and it adds no dependency. Pasted texts, and covers that fail to download, get the book's first letter on a dark red tile.
- **Icons** are Material Symbols Rounded, copied in as Android vector drawables (Apache License 2.0), rather than an icon library.
- **The home screen's play button plays the book without opening the reader.** Tapping the rest of the card opens the reader without starting playback. Before, Continue did both.
- **The + button opens a sheet** with title search across the three sites and a Paste text row. Search results show each novel's cover with one button per site it is on. The separate Search screen is gone; Paste text keeps its own screen, since it needs room to type.
- **Reader controls.** A strip with the chapter number, how far through the chapter you are (the sentence being read aloud, or where you are reading) and buttons to the chapters either side; under it Chapters, a large Play or Pause, and Listen. The chapters sheet groups chapters in blocks of 100, ticks the ones before your place, and marks chapters with audio ready in the current voice. Listen holds the sleep timer and Undo jump, which used to sit in the bar; while the timer is set, the Listen button shows the minutes left.
- **Display and Listen sheets.** The Tt button in the reader's top bar opens Display: text size from 14 to 28, a font (Literata, a serif made for reading on screens; Roboto; or Atkinson Hyperlegible Next, made to be easy to tell apart; both bundled under the SIL Open Font License), and a theme for the reader only (Dark, Black, Sepia or White). Light themes colour the reader's bars and sheets too, and turn the status bar icons dark. Listen gains voice and speed. They also stay in Settings, which would otherwise be empty and is where you choose a voice before opening a book.
- **Left out from the reference:** the daily reading goal, favourites (a database change), justified text and page mode (both need paragraphs, and Lantern stores one sentence per row).
- **Play stays one tap away in the reader,** in the middle of the bottom bar. The reference hides it in its Listen sheet; Lantern is listened to more than read.

## Order

Each step is its own commit, so any one can be reverted.

1. Theme: colours, type and shapes.
2. Home and library: greeting, Continue card with cover and progress, library with sort, grid or list, and a Prepared filter.
3. Add sheet: the + button opens title search and Paste text.
4. Reader controls: bottom bar, chapter progress strip, chapters sheet.
5. Display and Listen sheets: font size, font and reader theme; voice, speed, sleep timer and Undo jump.

## App icon

Decided with you in chat on 2026-11-23, after several rounds of drawn options compared on a page and then on the phone.

- **The mark:** Rush Red sound bars rising off an ivory open book, like a flame: a voice reading the book aloud. Listening comes first in Lantern, so the light is a voice. The bars lean like a flame instead of peaking in the middle.
- **Colours:** the app's own dark theme. The background is Ink Black #20201E, the card colour, rather than a red tile, so tapping the icon leads straight into a matching dark app.
- **Runner-up:** a Rush Red flame with an ivory sound wave in it. You chose the book in the end, after seeing both on the A26.
- **Built as** an Android adaptive icon (`res/mipmap-anydpi-v26/ic_launcher.xml`): the colour background, a vector foreground and a single-colour layer for Android 13+ themed icons. No PNGs, since minSdk is 28. The shape is drawn at 90% and raised 3 units, because Samsung's launcher crops tighter than the 66dp safe zone and the tallest bar nearly touched the edge at full size.
- **Watch:** the icon sits close to the reference app Light Novel's white open book. The red bars and dark card colour keep it distinct; the notification icon is still Android's stock download arrow.
