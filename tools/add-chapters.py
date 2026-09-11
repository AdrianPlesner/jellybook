#!/usr/bin/env python3
"""Writes chapter markers into an m4b or mp4 audiobook that has none.

Some audiobooks ship without markers, so neither Jellyfin nor a player has anything to navigate by. This rewrites the
container with a Nero `chpl` atom and a chapter track, copying the audio rather than re-encoding it, and then verifies the
result by reading the atoms back.

    tools/add-chapters.py book.m4b --list
    tools/add-chapters.py book.m4b --chapters chapters.txt
    tools/add-chapters.py book.m4b --every 15m --replace

A chapters file holds one marker per line, a timestamp then a title:

    00:00:00 Opening
    01:12:30 The Harbour
    2:05:00  Night Watch

ffmpeg is used from PATH when present; otherwise it runs in the jellyfin/jellyfin container image, which needs no install
beyond Docker.
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys

DOCKER_IMAGE = "jellyfin/jellyfin"
CONTAINER_FFMPEG = "/usr/lib/jellyfin-ffmpeg/ffmpeg"
CONTAINER_FFPROBE = "/usr/lib/jellyfin-ffmpeg/ffprobe"
CONTAINER_MOUNT = "/work"
TICKS_PER_MS = 10_000
MP4_TOP_LEVEL = {"ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "styp", "sidx", "meta"}


class Tools:
    """Runs ffmpeg and ffprobe, from PATH if available, otherwise inside a container."""

    def __init__(self, workdir: pathlib.Path) -> None:
        self.workdir = workdir.resolve()
        self.local = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None
        if not self.local and shutil.which("docker") is None:
            raise SystemExit("Needs either ffmpeg on PATH or Docker, and found neither.")

    def describe(self) -> str:
        return "ffmpeg from PATH" if self.local else f"ffmpeg from the {DOCKER_IMAGE} image"

    def _command(self, tool: str, args: list[str]) -> list[str]:
        if self.local:
            return [tool, *args]
        binary = CONTAINER_FFMPEG if tool == "ffmpeg" else CONTAINER_FFPROBE
        return [
            "docker", "run", "--rm",
            "-v", f"{self.workdir}:{CONTAINER_MOUNT}",
            "--entrypoint", binary,
            DOCKER_IMAGE,
            *args,
        ]

    def path(self, file: pathlib.Path) -> str:
        """The path to hand the tool: unchanged locally, mapped into the mount when containerised."""
        return str(file.resolve()) if self.local else f"{CONTAINER_MOUNT}/{file.resolve().name}"

    def run(self, tool: str, args: list[str]) -> subprocess.CompletedProcess:
        command = self._command(tool, args)
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            sys.stderr.write(result.stderr[-2000:] + "\n")
            raise SystemExit(f"{tool} failed with status {result.returncode}")
        return result

    def duration_ms(self, file: pathlib.Path) -> int:
        out = self.run("ffprobe", [
            "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", self.path(file),
        ]).stdout.strip()
        return int(float(out) * 1000)

    def chapters(self, file: pathlib.Path) -> list[tuple[int, str]]:
        out = self.run("ffprobe", ["-v", "error", "-print_format", "json", "-show_chapters", self.path(file)]).stdout
        found = []
        for chapter in json.loads(out or "{}").get("chapters", []):
            start = int(float(chapter.get("start_time", 0)) * 1000)
            found.append((start, (chapter.get("tags") or {}).get("title", "")))
        return found


def parse_timestamp(text: str) -> int:
    """`H:MM:SS(.mmm)`, `MM:SS` or plain seconds, in milliseconds."""
    parts = text.strip().split(":")
    if not all(re.fullmatch(r"\d+(\.\d+)?", part) for part in parts) or len(parts) > 3:
        raise ValueError(f"not a timestamp: {text!r}")
    seconds = 0.0
    for part in parts:
        seconds = seconds * 60 + float(part)
    return int(seconds * 1000)


def parse_duration(text: str) -> int:
    """`90s`, `15m`, `1h` or `MM:SS`, in milliseconds."""
    match = re.fullmatch(r"(\d+(?:\.\d+)?)([smh])", text.strip(), re.IGNORECASE)
    if match:
        scale = {"s": 1_000, "m": 60_000, "h": 3_600_000}[match.group(2).lower()]
        return int(float(match.group(1)) * scale)
    return parse_timestamp(text)


def read_chapters_file(file: pathlib.Path) -> list[tuple[int, str]]:
    markers = []
    for number, raw in enumerate(file.read_text().splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        stamp, _, title = line.partition(" ")
        try:
            start = parse_timestamp(stamp)
        except ValueError as error:
            raise SystemExit(f"{file}:{number}: {error}")
        markers.append((start, title.strip() or f"Chapter {len(markers) + 1}"))
    if not markers:
        raise SystemExit(f"{file}: no markers found")
    return sorted(markers)


def even_chapters(duration_ms: int, every_ms: int) -> list[tuple[int, str]]:
    if every_ms <= 0:
        raise SystemExit("--every must be positive")
    count = max(1, -(-duration_ms // every_ms))
    return [(index * every_ms, f"Chapter {index + 1}") for index in range(count)]


def write_metadata(markers: list[tuple[int, str]], duration_ms: int, target: pathlib.Path) -> None:
    lines = [";FFMETADATA1"]
    for index, (start, title) in enumerate(markers):
        end = markers[index + 1][0] if index + 1 < len(markers) else duration_ms
        lines += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={start}", f"END={max(end, start + 1)}", f"title={title}"]
    target.write_text("\n".join(lines) + "\n")


def atoms(data: bytes, start: int, end: int) -> list[tuple[str, int, int]]:
    found = []
    offset = start
    while offset + 8 <= end:
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8].decode("latin-1")
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[offset + 8:offset + 16])[0]
            header = 16
        elif size == 0:
            size = end - offset
        if size < header:
            break
        found.append((kind, offset + header, min(end, offset + size)))
        offset += size
    return found


def count_chpl_entries(file: pathlib.Path) -> int | None:
    """Reads back the Nero chapter list the app looks for. None means the atom is absent."""
    with file.open("rb") as handle:
        offset = 0
        for index in range(64):
            handle.seek(offset)
            header = handle.read(16)
            if len(header) < 8:
                return None
            size = struct.unpack(">I", header[0:4])[0]
            kind = header[4:8].decode("latin-1")
            header_size = 8
            if size == 1:
                size = struct.unpack(">Q", header[8:16])[0]
                header_size = 16
            if index == 0 and kind not in MP4_TOP_LEVEL:
                return None
            if size < header_size:
                return None
            if kind == "moov":
                handle.seek(offset)
                blob = handle.read(size)
                for name, start, stop in atoms(blob, header_size, len(blob)):
                    if name != "udta":
                        continue
                    for inner, inner_start, inner_stop in atoms(blob, start, stop):
                        if inner != "chpl":
                            continue
                        version = blob[inner_start]
                        cursor = inner_start + 4 + (4 if version != 0 else 0)
                        return blob[cursor]
                return None
            offset += size
    return None


def show(markers: list[tuple[int, str]], limit: int = 8) -> None:
    for start, title in markers[:limit]:
        minutes, seconds = divmod(start // 1000, 60)
        hours, minutes = divmod(minutes, 60)
        print(f"    {hours:d}:{minutes:02d}:{seconds:02d}  {title}")
    if len(markers) > limit:
        print(f"    ... and {len(markers) - limit} more")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("book", type=pathlib.Path)
    parser.add_argument("--chapters", type=pathlib.Path, help="file of timestamp and title lines")
    parser.add_argument("--every", help="instead, a marker every 15m, 1h, 90s and so on")
    parser.add_argument("--output", type=pathlib.Path, help="where to write (default: alongside, .chapters.m4b)")
    parser.add_argument("--replace", action="store_true", help="put the result in place of the original")
    parser.add_argument("--force", action="store_true", help="overwrite an existing output")
    parser.add_argument("--list", action="store_true", help="only show the chapters the file already has")
    args = parser.parse_args()

    book = args.book
    if not book.is_file():
        raise SystemExit(f"{book}: not a file")
    tools = Tools(book.parent)

    existing = tools.chapters(book)
    chpl = count_chpl_entries(book)
    print(f"{book.name}: {len(existing)} chapter(s) readable, "
          f"{'no chpl atom' if chpl is None else f'{chpl} chpl entries'}, using {tools.describe()}")
    if existing:
        show(existing)
    if args.list:
        return 0

    if bool(args.chapters) == bool(args.every):
        raise SystemExit("Give exactly one of --chapters or --every (or --list to only look).")

    duration = tools.duration_ms(book)
    markers = read_chapters_file(args.chapters) if args.chapters else even_chapters(duration, parse_duration(args.every))
    beyond = [start for start, _ in markers if start >= duration]
    if beyond:
        raise SystemExit(f"{len(beyond)} marker(s) start at or after the end of the book ({duration / 1000:.0f}s)")
    print(f"  writing {len(markers)} marker(s):")
    show(markers)

    output = args.output or book.with_suffix(f".chapters{book.suffix}")
    if output.exists() and not args.force:
        raise SystemExit(f"{output} exists; pass --force to overwrite")

    # The metadata file sits beside the book so one mounted directory covers every path the tool is given.
    metadata = book.parent / f".jellybook-chapters-{os.getpid()}.ffmeta"
    try:
        write_metadata(markers, duration, metadata)
        # Chapters come from the metadata file; every stream and the original tags come from the book, copied not re-encoded.
        tools.run("ffmpeg", [
            "-hide_banner", "-loglevel", "error", "-y",
            "-i", tools.path(book),
            "-i", tools.path(metadata),
            "-map", "0", "-map_metadata", "0", "-map_chapters", "1", "-c", "copy",
            tools.path(output),
        ])
    finally:
        metadata.unlink(missing_ok=True)

    written = tools.chapters(output)
    written_chpl = count_chpl_entries(output)
    print(f"  {output.name}: {len(written)} chapter(s) readable, "
          f"{'no chpl atom' if written_chpl is None else f'{written_chpl} chpl entries'}")
    if written_chpl is None or len(written) != len(markers):
        raise SystemExit("The result does not carry the markers that were asked for; the original is untouched.")

    if args.replace:
        backup = book.with_suffix(f"{book.suffix}.backup")
        os.replace(book, backup)
        os.replace(output, book)
        print(f"  replaced {book.name}; the original is {backup.name}")
    print("  done. Rescan the library in Jellyfin so it picks the markers up.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
