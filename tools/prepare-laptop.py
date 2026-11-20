"""Make a book's sentence audio on the laptop and put it in Lantern's cache on the phone, as if the phone made it.

A stopgap until phone preparing is proven (docs/decisions/prepared-audio.md). It needs a Lantern build that keeps
audio ahead of your position, or the app deletes what this pushes after one chapter.

    python -I tools/prepare-laptop.py [--book ID] [--workers 4] [--threads 4]

- Reads the library from the phone over adb (run-as, so a debug build) and the voice from the app's settings.
- Works from the last chapter backwards towards your position, so it meets the phone's own prepare job (which goes
  forwards) in the middle. Chapters whose text the phone hasn't fetched yet are waited for: it reads the library
  again every minute until every chapter from your position on has text and audio.
- Makes each missing sentence with ptts.exe v0.6.0 (same model, q8 and voice file as the phone), several at once.
- Pushes each finished chapter into cache/audio/<voice>/<text hash>/ on the phone, a file at a time through a
  temporary name, so the app never sees half a file. If the phone is unplugged, it keeps making audio and pushes
  when it's back. Running it again skips everything already made, here or on the phone.
- Keeps the laptop from sleeping while it runs, without changing power settings. Closing the lid may still sleep it.
- Refuses a book with skip rules or pronunciation fixes: those change what is said and where audio is stored.
"""

import argparse
import concurrent.futures
import ctypes
import os
import re
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / ".bench"
ADB = BENCH / "pt" / "platform-tools" / "adb.exe"
PTTS = BENCH / "ptts-win" / "ptts-0.6.0-x86_64-pc-windows-msvc" / "ptts.exe"
WORK = BENCH / "laptop-prepare"
PKG = "app.lantern"
VOICE_FILES = {"everly": "vjFyp2BcCyTlUmYb", "trevor": "0zLApsZfVLLNaEGl"}
STAGING = "/data/local/tmp/lantern-prepare"


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def local_properties():
    props = {}
    for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def adb(*args, binary=False, check=True):
    r = subprocess.run([str(ADB), *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def run_as(command, binary=False):
    return adb("exec-out", "run-as", PKG, "sh", "-c", command, binary=binary)


def phone_connected():
    r = subprocess.run([str(ADB), "get-state"], capture_output=True)
    return r.returncode == 0 and r.stdout.strip() == b"device"


def pull_library():
    """Copies the phone's database (with its write-ahead log) and returns an open connection to the copy."""
    # Overwritten in place: deleting the folder and making it again fails on Windows while the delete is pending.
    db_dir = WORK / "db"
    db_dir.mkdir(parents=True, exist_ok=True)
    (db_dir / "lantern.db-shm").unlink(missing_ok=True)
    for name in ("lantern.db", "lantern.db-wal"):
        data = run_as(f"cat databases/{name} 2>/dev/null", binary=True)
        (db_dir / name).write_bytes(data)
    return sqlite3.connect(db_dir / "lantern.db")


def phone_voice():
    prefs = run_as("cat shared_prefs/settings.xml 2>/dev/null")
    m = re.search(r'<string name="voice">([^<]+)</string>', prefs)
    return m.group(1) if m and m.group(1) in VOICE_FILES else "everly"


def phone_ready(voice, key):
    """The sentence indexes with audio on the phone for this chapter."""
    out = run_as(f"ls cache/audio/{voice}/{key} 2>/dev/null")
    return {int(n[:-4]) for n in out.split() if re.fullmatch(r"\d+\.wav", n)}


def chapters_ahead(db, book_id):
    """(id, number, textHash or None) from the book's position chapter (or its first) to its end."""
    row = db.execute(
        "SELECT ch.number FROM position p JOIN chapter ch ON ch.id = p.chapterId WHERE p.bookId = ?", (book_id,)
    ).fetchone()
    start = row[0] if row else -1
    return db.execute(
        "SELECT id, number, textHash FROM chapter WHERE bookId = ? AND number >= ? ORDER BY number", (book_id, start)
    ).fetchall()


def make_sentence(model, voice_file, threads, text, out):
    tmp = out.with_suffix(".tmp.wav")
    r = subprocess.run(
        [str(PTTS), "-m", model, "--quant", "q8", "--lang", "en", "--threads", str(threads),
         "--voice", voice_file, "-o", str(tmp), text],
        capture_output=True, env={**os.environ, "HF_HUB_OFFLINE": "1", "NO_COLOR": "1"},
    )
    if r.returncode != 0 or not tmp.is_file():
        raise RuntimeError(r.stderr.decode(errors="replace").strip().splitlines()[-1:])
    os.replace(tmp, out)


def push_chapter(voice, key, folder, indexes):
    """Copies the chapter's WAVs into the app's cache, each through a temporary name and a rename."""
    stage = f"{STAGING}/{key}"
    adb("shell", "rm", "-rf", stage)
    adb("shell", "mkdir", "-p", stage)
    adb("push", str(folder) + "/.", stage)
    adb("shell", "chmod", "-R", "a+rX", STAGING)
    names = " ".join(f"{i}.wav" for i in sorted(indexes))
    dest = f"cache/audio/{voice}/{key}"
    run_as(f"mkdir -p {dest} && for f in {names}; do [ -f {dest}/$f ] || "
           f"{{ cp {stage}/$f {dest}/$f.tmp && mv {dest}/$f.tmp {dest}/$f; }}; done")
    adb("shell", "rm", "-rf", stage)


def keep_awake():
    """Stops Windows sleeping while this process runs. Lifted when it exits."""
    ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
    ctypes.windll.kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--book", type=int, help="book id; defaults to the last one played")
    ap.add_argument("--workers", type=int, default=4, help="sentences made at once")
    ap.add_argument("--threads", type=int, default=4, help="CPU threads per sentence")
    args = ap.parse_args()

    if not PTTS.is_file():
        sys.exit(f"ptts.exe not found at {PTTS}")
    props = local_properties()
    model = props["lantern.modelDir"]
    keep_awake()

    db = pull_library()
    voice = phone_voice()
    voice_file = str(Path(props["lantern.voicesDir"]) / f"{VOICE_FILES[voice]}.safetensors")
    book_id = args.book or db.execute("SELECT bookId FROM position ORDER BY savedAt DESC LIMIT 1").fetchone()[0]
    title = db.execute("SELECT title FROM book WHERE id = ?", (book_id,)).fetchone()[0]
    rules = db.execute("SELECT COUNT(*) FROM rule WHERE bookId = ? OR bookId IS NULL", (book_id,)).fetchone()[0]
    if rules:
        sys.exit(f"{title} has {rules} skip rules or fixes; this script only handles books without any.")
    log(f"book {book_id} '{title}', voice {voice}, {args.workers} at once x {args.threads} threads")

    done = set()  # chapter ids finished and on the phone
    pool = concurrent.futures.ThreadPoolExecutor(args.workers)
    while True:
        try:
            db = step(args, model, voice, voice_file, book_id, db, done, pool)
        except Finished:
            return
        except Exception as e:
            # A dropped USB link or a busy phone shouldn't end a night's run: wait, read the library again, go on.
            log(f"error: {e!r}; trying again in a minute")
            time.sleep(60)
            try:
                db.close()
                db = pull_library()
            except Exception as e2:
                log(f"  library not readable yet: {e2!r}")


class Finished(Exception):
    pass


def step(args, model, voice, voice_file, book_id, db, done, pool):
    """One chapter, or one wait for text. Returns the library connection to use next."""
    chapters = chapters_ahead(db, book_id)
    todo = [c for c in reversed(chapters) if c[2] and c[0] not in done]
    waiting = [c for c in chapters if not c[2]]
    if not todo:
        if not waiting:
            log(f"all {len(chapters)} chapters from your position are done")
            raise Finished
        log(f"{len(waiting)} chapters have no text on the phone yet; looking again in a minute")
        time.sleep(60)
        db.close()  # the copy is overwritten, and Windows won't overwrite an open file
        return pull_library()

    chapter_id, number, key = todo[0]
    sentences = db.execute("SELECT idx, text FROM sentence WHERE chapterId = ? ORDER BY idx", (chapter_id,)).fetchall()
    folder = WORK / "audio" / voice / key
    folder.mkdir(parents=True, exist_ok=True)
    on_phone = phone_ready(voice, key) if phone_connected() else set()
    missing = [(i, t) for i, t in sentences if i not in on_phone and not (folder / f"{i}.wav").is_file()]
    start = time.time()
    jobs = {pool.submit(make_sentence, model, voice_file, args.threads, t, folder / f"{i}.wav"): i for i, t in missing}
    failed = 0
    for job in concurrent.futures.as_completed(jobs):
        try:
            job.result()
        except Exception as e:
            failed += 1
            log(f"chapter {number + 1} sentence {jobs[job]} failed: {e}")
    made_s = time.time() - start
    log(f"chapter {number + 1}: made {len(missing) - failed} of {len(sentences)} sentences in {made_s:.0f} s"
        f" ({len(on_phone)} already on the phone)")
    if failed:
        log("  failed sentences are left for the phone to make")

    local = {int(p.stem) for p in folder.glob("*.wav") if p.stem.isdigit()}
    to_push = local - on_phone
    while to_push and not phone_connected():
        log("phone not connected; waiting to push")
        time.sleep(60)
    if to_push:
        push_chapter(voice, key, folder, to_push)
        log(f"  pushed {len(to_push)} files")
    done.add(chapter_id)
    if len(done) % 5 == 0:
        db.close()
        return pull_library()  # pick up text the phone fetched meanwhile
    return db


if __name__ == "__main__":
    main()
