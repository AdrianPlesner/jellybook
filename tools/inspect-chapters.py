#!/usr/bin/env python3
"""Reports where an audiobook's chapter markers are, or why none were found.

Answers the two questions that matter when chapters do not show up in the app: did the *server* extract any, and does the
*file* actually contain markers. It reads only a few byte ranges of each file, looking for exactly what the app looks for:
a Nero `chpl` atom, or a QuickTime chapter text track.

    tools/inspect-chapters.py http://jellyfin.local:8096 USER PASSWORD
    tools/inspect-chapters.py http://jellyfin.local:8096 USER PASSWORD --title "Book name"
"""
import argparse
import json
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request

CLIENT = 'MediaBrowser Client="inspect-chapters", Device="script", DeviceId="inspect-chapters", Version="1.0"'
MP4_TOP_LEVEL = {"ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "styp", "sidx", "meta"}
CONTAINER_ATOMS = {"moov", "trak", "mdia", "minf", "stbl", "udta", "tref"}
TICKS_PER_MS = 10_000


class Server:
    def __init__(self, base: str, token: str | None = None) -> None:
        self.base = base.rstrip("/")
        self.token = token

    def _auth(self) -> str:
        return CLIENT if not self.token else f'{CLIENT}, Token="{self.token}"'

    def get_json(self, path: str) -> dict:
        request = urllib.request.Request(f"{self.base}/{path}", headers={"Authorization": self._auth()})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def login(self, user: str, password: str) -> dict:
        body = json.dumps({"Username": user, "Pw": password}).encode()
        request = urllib.request.Request(
            f"{self.base}/Users/AuthenticateByName",
            data=body,
            headers={"Authorization": self._auth(), "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
        self.token = result["AccessToken"]
        return result

    def read_range(self, item_id: str, start: int, length: int) -> tuple[bytes, int, int | None]:
        """Returns (bytes, status, total size). Status 206 means the server honoured the range request."""
        end = start + length - 1
        request = urllib.request.Request(
            f"{self.base}/Items/{item_id}/File",
            headers={"Authorization": self._auth(), "Range": f"bytes={start}-{end}"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
            content_range = response.headers.get("Content-Range")
            total = None
            if content_range and "/" in content_range:
                tail = content_range.rsplit("/", 1)[1]
                total = int(tail) if tail.isdigit() else None
            return data, response.status, total


class RangeReader:
    """Byte-range access with a note of whether the server actually supports ranges."""

    def __init__(self, server: Server, item_id: str) -> None:
        self.server = server
        self.item_id = item_id
        self.supports_ranges: bool | None = None
        self.total: int | None = None
        self.requests = 0

    def read(self, offset: int, length: int) -> bytes:
        self.requests += 1
        data, status, total = self.server.read_range(self.item_id, offset, length)
        if self.supports_ranges is None:
            self.supports_ranges = status == 206
        if total is not None:
            self.total = total
        if status != 206 and offset > 0:
            # The server ignored the range and restarted at zero; slice out the part we asked for.
            data = data[offset:offset + length]
        return data[:length]


def atoms(data: bytes, start: int, end: int) -> list[tuple[str, int, int, int]]:
    """Walks sibling atoms in a buffer. Yields (type, payload start, atom end, header size)."""
    found = []
    offset = start
    while offset + 8 <= end:
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8].decode("latin-1")
        header = 8
        if size == 1:
            if offset + 16 > end:
                break
            size = struct.unpack(">Q", data[offset + 8:offset + 16])[0]
            header = 16
        elif size == 0:
            size = end - offset
        if size < header:
            break
        found.append((kind, offset + header, min(end, offset + size), header))
        offset += size
    return found


def find_child(data: bytes, start: int, end: int, kind: str) -> tuple[int, int] | None:
    for found_kind, payload, stop, _ in atoms(data, start, end):
        if found_kind == kind:
            return payload, stop
    return None


def descend(data: bytes, start: int, end: int, path: list[str]) -> tuple[int, int] | None:
    current = (start, end)
    for step in path:
        found = find_child(data, current[0], current[1], step)
        if found is None:
            return None
        current = found
    return current


def locate_moov(reader: RangeReader) -> tuple[bytes, int, int] | None:
    """Finds the moov atom, wherever it sits, and returns its bytes plus the payload window inside them."""
    offset = 0
    for index in range(64):
        header = reader.read(offset, 16)
        if len(header) < 8:
            return None
        size = struct.unpack(">I", header[0:4])[0]
        kind = header[4:8].decode("latin-1")
        header_size = 8
        if size == 1:
            size = struct.unpack(">Q", header[8:16])[0]
            header_size = 16
        if index == 0 and kind not in MP4_TOP_LEVEL:
            print(f"    file does not start with an MP4 atom (first four bytes {header[4:8]!r}); not an MP4 container")
            return None
        if size == 0 or size < header_size:
            return None
        if kind == "moov":
            if size > 64 * 1024 * 1024:
                print(f"    moov is {size / 1e6:.1f} MB, larger than the app reads")
                return None
            blob = reader.read(offset, size)
            print(f"    moov found at byte {offset} ({size / 1024:.0f} KB), after {index} other top-level atoms")
            return blob, header_size, len(blob)
        offset += size
    return None


def read_chpl(blob: bytes, start: int, end: int) -> list[tuple[int, str]]:
    version = blob[start]
    cursor = start + 4
    if version != 0:
        cursor += 4
    count = blob[cursor]
    cursor += 1
    entries = []
    for _ in range(count):
        if cursor + 9 > end:
            break
        ticks = struct.unpack(">Q", blob[cursor:cursor + 8])[0]
        length = blob[cursor + 8]
        cursor += 9
        title = blob[cursor:cursor + length].decode("utf-8", "replace")
        cursor += length
        entries.append((ticks // TICKS_PER_MS, title))
    return entries


def describe_tracks(blob: bytes, payload: int, end: int) -> None:
    traks = [(p, s) for kind, p, s, _ in atoms(blob, payload, end) if kind == "trak"]
    print(f"    tracks: {len(traks)}")
    chapter_refs: list[int] = []
    for index, (start, stop) in enumerate(traks):
        mdia = find_child(blob, start, stop, "mdia")
        handler = None
        sample_format = None
        if mdia:
            hdlr = find_child(blob, mdia[0], mdia[1], "hdlr")
            if hdlr:
                handler = blob[hdlr[0] + 8:hdlr[0] + 12].decode("latin-1")
            stsd = descend(blob, mdia[0], mdia[1], ["minf", "stbl", "stsd"])
            if stsd:
                sample_format = blob[stsd[0] + 12:stsd[0] + 16].decode("latin-1")
        chap = descend(blob, start, stop, ["tref", "chap"])
        refs = []
        if chap:
            span = chap[1] - chap[0]
            refs = [struct.unpack(">I", blob[chap[0] + i * 4:chap[0] + i * 4 + 4])[0] for i in range(span // 4)]
            chapter_refs.extend(refs)
        stts = descend(blob, start, stop, ["mdia", "minf", "stbl", "stts"])
        samples = None
        if stts:
            count = struct.unpack(">I", blob[stts[0] + 4:stts[0] + 8])[0]
            samples = sum(
                struct.unpack(">I", blob[stts[0] + 8 + entry * 8:stts[0] + 12 + entry * 8])[0]
                for entry in range(count)
            )
        note = f" -> references chapter track(s) {refs}" if refs else ""
        print(f"      track {index + 1}: handler={handler!r} format={sample_format!r} samples={samples}{note}")
    if not chapter_refs:
        print("      no track references a chapter track (no tref/chap)")


def describe_itunes_tags(blob: bytes, payload: int, end: int) -> bool:
    """Looks for chapters hidden in an iTunes free-form tag, the scheme OverDrive rips use."""
    udta = find_child(blob, payload, end, "udta")
    if not udta:
        return False
    meta = find_child(blob, udta[0], udta[1], "meta")
    if not meta:
        return False
    # `meta` is a full box: its children start after the version and flags.
    ilst = find_child(blob, meta[0] + 4, meta[1], "ilst")
    if not ilst:
        return False
    found_markers = False
    labels = []
    for kind, entry_start, entry_end, _ in atoms(blob, ilst[0], ilst[1]):
        if kind != "----":
            labels.append(kind)
            continue
        name_box = find_child(blob, entry_start, entry_end, "name")
        data_box = find_child(blob, entry_start, entry_end, "data")
        tag_name = blob[name_box[0] + 4:name_box[1]].decode("utf-8", "replace") if name_box else "?"
        labels.append(f"----:{tag_name}")
        if data_box and "mediamarker" in tag_name.lower():
            payload_text = blob[data_box[0] + 8:data_box[1]].decode("utf-8", "replace")
            markers = payload_text.count("<Marker>")
            print(f"    iTunes free-form tag {tag_name!r}: {markers} marker(s)")
            print(f"      {payload_text[:160].replace(chr(10), ' ')!r}")
            found_markers = True
    if labels:
        print(f"    iTunes metadata tags: {', '.join(sorted(set(labels)))}")
    return found_markers


def inspect(server: Server, item: dict) -> None:
    name = item.get("Name")
    print(f"\n=== {name} ===")
    server_chapters = item.get("Chapters") or []
    sources = item.get("MediaSources") or []
    container = item.get("Container") or (sources[0].get("Container") if sources else None)
    size = sources[0].get("Size") if sources else None
    print(f"  server reports {len(server_chapters)} chapter(s); container={container!r}; "
          f"size={f'{size / 1e6:.1f} MB' if size else 'unknown'}")
    for chapter in server_chapters[:5]:
        print(f"    {chapter.get('StartPositionTicks', 0) // TICKS_PER_MS / 1000:8.1f}s  {chapter.get('Name')!r}")
    if len(server_chapters) > 5:
        print(f"    ... and {len(server_chapters) - 5} more")

    reader = RangeReader(server, item["Id"])
    try:
        located = locate_moov(reader)
    except urllib.error.HTTPError as error:
        print(f"    could not read the file: HTTP {error.code}")
        return
    print(f"    range requests honoured: {reader.supports_ranges}")
    if located is None:
        return
    blob, payload, end = located

    chpl = descend(blob, payload, end, ["udta", "chpl"])
    if chpl:
        entries = read_chpl(blob, chpl[0], chpl[1])
        print(f"    Nero chpl atom: {len(entries)} entry(ies)")
        for position, title in entries[:5]:
            print(f"      {position / 1000:8.1f}s  {title!r}")
        if len(entries) > 5:
            print(f"      ... and {len(entries) - 5} more")
    else:
        print("    Nero chpl atom: absent")

    describe_tracks(blob, payload, end)
    overdrive = describe_itunes_tags(blob, payload, end)
    print(f"    byte-range requests used: {reader.requests}")

    chapter_track = any(
        descend(blob, start, stop, ["tref", "chap"])
        for kind, start, stop, _ in atoms(blob, payload, end) if kind == "trak"
    )
    if server_chapters:
        print("    VERDICT: the server has chapters; the app should be showing these.")
    elif chpl or chapter_track:
        print("    VERDICT: the file carries markers the server did not extract; the app parses these itself.")
    elif overdrive:
        print("    VERDICT: chapters are in an OverDrive MediaMarkers tag, which the app does not read yet.")
    else:
        print("    VERDICT: no chapter markers in the file, and none extracted by the server.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("server")
    parser.add_argument("user")
    parser.add_argument("password")
    parser.add_argument("--title", help="only inspect books whose name contains this text")
    parser.add_argument("--limit", type=int, default=5, help="how many books to inspect (default 5)")
    args = parser.parse_args()

    server = Server(args.server)
    try:
        account = server.login(args.user, args.password)
    except urllib.error.HTTPError as error:
        print(f"sign-in failed: HTTP {error.code}", file=sys.stderr)
        return 1
    user_id = account["User"]["Id"]

    query = urllib.parse.urlencode({
        "userId": user_id,
        "IncludeItemTypes": "AudioBook,Audio",
        "Recursive": "true",
        "SortBy": "SortName",
        "Fields": "Chapters,MediaSources,Path,Container",
    })
    items = server.get_json(f"Items?{query}")["Items"]
    if args.title:
        items = [i for i in items if args.title.lower() in (i.get("Name") or "").lower()]
    print(f"{len(items)} matching audio item(s); inspecting up to {args.limit}")
    for item in items[:args.limit]:
        inspect(server, item)
    return 0


if __name__ == "__main__":
    sys.exit(main())
