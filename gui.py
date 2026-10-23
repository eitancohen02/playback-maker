"""Simple window for Playback Maker: paste a YouTube link, pick options, click the button."""

import io
import logging
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext, ttk

import playback


class QueueWriter(io.TextIOBase):
    """File-like object that forwards text to the GUI log queue.

    A windowed app has no console (sys.stdout/stderr are None), and libraries such
    as tqdm write progress bars there, so both are redirected here.
    """

    def __init__(self, q):
        self.q = q

    def write(self, text):
        # tqdm redraws its bar with "\r"; keep only the latest state of the line.
        text = text.replace("\r", "\n")
        for line in text.splitlines():
            if line.strip():
                self.q.put(("log", line.rstrip()))
        return len(text)

    def flush(self):
        pass

    def isatty(self):
        return False


class QueueHandler(logging.Handler):
    def __init__(self, q):
        super().__init__()
        self.q = q

    def emit(self, record):
        self.q.put(("log", "      " + record.getMessage()))


def default_output_dir():
    music = Path.home() / "Music"
    return (music if music.is_dir() else Path.home()) / "Playback Maker"


def open_folder(path):
    if os.name == "nt":
        os.startfile(path)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


class App:
    def __init__(self, root):
        self.root = root
        self.q = queue.Queue()
        self.running = False

        root.title("Playback Maker")
        root.minsize(560, 420)
        frm = ttk.Frame(root, padding=12)
        frm.pack(fill="both", expand=True)
        frm.columnconfigure(1, weight=1)

        ttk.Label(frm, text="YouTube link:").grid(row=0, column=0, sticky="w")
        self.url = tk.StringVar()
        url_entry = ttk.Entry(frm, textvariable=self.url)
        url_entry.grid(row=0, column=1, columnspan=2, sticky="ew", pady=4)
        url_entry.focus()

        ttk.Label(frm, text="Quality:").grid(row=1, column=0, sticky="w")
        self.quality = tk.StringVar(value="balanced")
        ttk.Combobox(frm, textvariable=self.quality, values=list(playback.PRESETS),
                     state="readonly", width=12).grid(row=1, column=1, sticky="w", pady=4)

        ttk.Label(frm, text="Format:").grid(row=2, column=0, sticky="w")
        self.fmt = tk.StringVar(value="mp3")
        ttk.Combobox(frm, textvariable=self.fmt, values=["mp3", "flac", "wav"],
                     state="readonly", width=12).grid(row=2, column=1, sticky="w", pady=4)

        self.keep_vocals = tk.BooleanVar()
        ttk.Checkbutton(frm, text="Also save the vocals track", variable=self.keep_vocals).grid(
            row=3, column=1, sticky="w", pady=4)

        ttk.Label(frm, text="Save to:").grid(row=4, column=0, sticky="w")
        self.out_dir = tk.StringVar(value=str(default_output_dir()))
        ttk.Entry(frm, textvariable=self.out_dir).grid(row=4, column=1, sticky="ew", pady=4)
        ttk.Button(frm, text="Browse...", command=self.browse).grid(row=4, column=2, padx=(6, 0))

        buttons = ttk.Frame(frm)
        buttons.grid(row=5, column=0, columnspan=3, sticky="ew", pady=8)
        self.go = ttk.Button(buttons, text="Make playback", command=self.start)
        self.go.pack(side="left")
        ttk.Button(buttons, text="Open folder", command=self.open_output).pack(side="left", padx=6)
        self.progress = ttk.Progressbar(buttons, mode="indeterminate")
        self.progress.pack(side="left", fill="x", expand=True, padx=(6, 0))

        self.log = scrolledtext.ScrolledText(frm, height=14, state="disabled", wrap="word")
        self.log.grid(row=6, column=0, columnspan=3, sticky="nsew")
        frm.rowconfigure(6, weight=1)

        self.write_log("Paste a YouTube link and click \"Make playback\".")
        self.write_log("The first run downloads the separation model (one time only, ~600 MB).")

        root.bind("<Return>", lambda _e: self.start())
        root.after(100, self.poll)

    def browse(self):
        path = filedialog.askdirectory(initialdir=self.out_dir.get() or str(Path.home()))
        if path:
            self.out_dir.set(path)

    def open_output(self):
        path = Path(self.out_dir.get())
        path.mkdir(parents=True, exist_ok=True)
        open_folder(str(path))

    def write_log(self, line):
        self.log.configure(state="normal")
        self.log.insert("end", line + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def start(self):
        if self.running:
            return
        url = self.url.get().strip()
        if not url:
            messagebox.showwarning("Playback Maker", "Please paste a YouTube link first.")
            return
        self.running = True
        self.go.configure(state="disabled")
        self.progress.start(12)
        args = (url, self.out_dir.get(), self.fmt.get(), self.quality.get(), None, self.keep_vocals.get())
        threading.Thread(target=self.work, args=args, daemon=True).start()

    def work(self, *args):
        try:
            dest = playback.make_playback(*args, log=lambda m: self.q.put(("log", m)))
            self.q.put(("done", dest))
        except BaseException as e:  # SystemExit from sys.exit() in the pipeline included
            self.q.put(("error", str(e) or e.__class__.__name__))

    def poll(self):
        try:
            while True:
                kind, value = self.q.get_nowait()
                if kind == "log":
                    self.write_log(value)
                else:
                    self.running = False
                    self.go.configure(state="normal")
                    self.progress.stop()
                    if kind == "done":
                        self.write_log("Done!\n")
                        messagebox.showinfo("Playback Maker", f"Playback saved to:\n{value}")
                    else:
                        self.write_log(f"Error: {value}\n")
                        messagebox.showerror("Playback Maker", f"Something went wrong:\n{value}")
        except queue.Empty:
            pass
        self.root.after(100, self.poll)


def main():
    root = tk.Tk()
    app = App(root)

    writer = QueueWriter(app.q)
    sys.stdout = writer
    sys.stderr = writer
    handler = QueueHandler(app.q)
    for name in ("audio_separator", "yt_dlp"):
        logger = logging.getLogger(name)
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
        logger.propagate = False

    root.mainloop()


if __name__ == "__main__":
    main()
