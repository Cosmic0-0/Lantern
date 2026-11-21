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
- **Left out from the reference:** the daily reading goal, favourites (a database change), justified text and page mode (both need paragraphs, and Lantern stores one sentence per row).
- **Play stays one tap away in the reader,** in the middle of the bottom bar. The reference hides it in its Listen sheet; Lantern is listened to more than read.

## Order

Each step is its own commit, so any one can be reverted.

1. Theme: colours, type and shapes.
2. Home and library: greeting, Continue card with cover and progress, library with sort, grid or list, and a Prepared filter.
3. Add sheet: the + button opens title search and Paste text.
4. Reader controls: bottom bar, chapter progress strip, chapters sheet.
5. Display and Listen sheets: font size, font and reader theme; voice, speed, sleep timer and Undo jump.
