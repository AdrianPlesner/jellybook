#!/bin/sh
# Starts a throwaway Jellyfin server in Docker with two generated m4b audiobooks (chapters + covers) for manual testing.
# Sign in from the emulator with server http://10.0.2.2:8096, user "test", password "test".
# Usage: tools/dev-jellyfin.sh [workdir]      (defaults to ./build/dev-jellyfin)
set -e
WORK=$(cd "$(dirname "$0")/.." && pwd)/${1:-build/dev-jellyfin}
MEDIA=$WORK/media
CONFIG=$WORK/config
NAME=jellybook-dev
BASE=http://localhost:8096
FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
AUTH='Authorization: MediaBrowser Client="setup", Device="script", DeviceId="setup-script", Version="1.0"'

mkdir -p "$MEDIA/audiobooks/The Test Book" "$MEDIA/audiobooks/Short Story" "$MEDIA/work" "$CONFIG"

cat > "$MEDIA/work/test-book.ffmeta" <<'EOF'
;FFMETADATA1
title=The Test Book
artist=Test Author
album_artist=Test Author
album=The Test Book
[CHAPTER]
TIMEBASE=1/1000
START=0
END=180000
title=Chapter One: Arrival
[CHAPTER]
TIMEBASE=1/1000
START=180000
END=360000
title=Chapter Two: The Harbour
[CHAPTER]
TIMEBASE=1/1000
START=360000
END=540000
title=Chapter Three: Night Watch
[CHAPTER]
TIMEBASE=1/1000
START=540000
END=720000
title=Chapter Four: Departure
[CHAPTER]
TIMEBASE=1/1000
START=720000
END=900000
title=Epilogue
EOF

cat > "$MEDIA/work/short-story.ffmeta" <<'EOF'
;FFMETADATA1
title=Short Story
artist=Another Author
album_artist=Another Author
album=Short Story
[CHAPTER]
TIMEBASE=1/1000
START=0
END=60000
title=Part 1
[CHAPTER]
TIMEBASE=1/1000
START=60000
END=120000
title=Part 2
EOF

docker rm -f $NAME >/dev/null 2>&1 || true
docker run -d --name $NAME -p 8096:8096 -v "$CONFIG:/config" -v "$MEDIA:/media" jellyfin/jellyfin:latest >/dev/null

echo "generating audio"
docker exec $NAME $FF -hide_banner -loglevel error -y -f lavfi -i "color=c=0x5B3FA6:s=400x600:d=1" -frames:v 1 /media/work/cover1.png
docker exec $NAME $FF -hide_banner -loglevel error -y -f lavfi -i "color=c=0xB8860B:s=400x600:d=1" -frames:v 1 /media/work/cover2.png
docker exec $NAME $FF -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:duration=900" -i /media/work/test-book.ffmeta -i /media/work/cover1.png \
  -map 0:a -map 2:v -map_metadata 1 -c:a aac -b:a 32k -c:v png -disposition:v attached_pic \
  "/media/audiobooks/The Test Book/The Test Book.m4b"
docker exec $NAME $FF -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=660:duration=120" -i /media/work/short-story.ffmeta -i /media/work/cover2.png \
  -map 0:a -map 2:v -map_metadata 1 -c:a aac -b:a 32k -c:v png -disposition:v attached_pic \
  "/media/audiobooks/Short Story/Short Story.m4b"

echo "waiting for jellyfin"
i=0
until curl -sf $BASE/System/Info/Public >/dev/null || [ $i -ge 60 ]; do i=$((i + 1)); sleep 2; done

echo "startup wizard"
curl -sf -X POST $BASE/Startup/Configuration -H 'Content-Type: application/json' \
  -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
curl -sf $BASE/Startup/User >/dev/null
curl -sf -X POST $BASE/Startup/User -H 'Content-Type: application/json' -d '{"Name":"test","Password":"test"}'
curl -sf -X POST $BASE/Startup/RemoteAccess -H 'Content-Type: application/json' -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
curl -sf -X POST $BASE/Startup/Complete

TOKEN=$(curl -sf -X POST $BASE/Users/AuthenticateByName -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"Username":"test","Pw":"test"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["AccessToken"])')
TAUTH="Authorization: MediaBrowser Client=\"setup\", Device=\"script\", DeviceId=\"setup-script\", Version=\"1.0\", Token=\"$TOKEN\""

echo "creating library"
curl -sf -X POST "$BASE/Library/VirtualFolders?name=Audiobooks&collectionType=books&refreshLibrary=true" \
  -H "$TAUTH" -H 'Content-Type: application/json' \
  -d '{"LibraryOptions":{"PathInfos":[{"Path":"/media/audiobooks"}],"EnableRealtimeMonitor":false}}'

echo "waiting for scan"
i=0
COUNT=0
while [ "$COUNT" -lt 2 ] && [ $i -lt 60 ]; do
  COUNT=$(curl -sf "$BASE/Items?IncludeItemTypes=AudioBook&Recursive=true" -H "$TAUTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["TotalRecordCount"])' 2>/dev/null || echo 0)
  i=$((i + 1))
  sleep 3
done
echo "audiobooks found: $COUNT"
echo "Jellyfin is running at $BASE (user test / test). Stop it with: docker rm -f $NAME"
