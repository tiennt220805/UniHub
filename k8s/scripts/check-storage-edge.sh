#!/usr/bin/env bash
# The object-storage path through the gateway (Phase 8 task 9.2; M-88, D120-D122, checklist 3.5): a presigned PUT
# from "the browser" goes through Istio to MinIO, the cover is read back without credentials, and everything the edge
# must not open stays closed. Run it by hand on a deployed cluster; it changes nothing but one workshop (cancelled at
# the end) and one object in the bucket.
#
#   k8s/scripts/check-storage-edge.sh
#
# Checks (each prints `ok` or `FAIL`; the exit status is 1 when any failed):
#   1  the upload URL is minted for the origin this machine reaches the gateway at (https://unihub.local:8443)
#   2  a PUT with a Content-Type other than the signed one is refused, and so is one with another size, another key
#      (SigV4: the signature covers the headers and the path)
#   3  the real PUT is accepted, the confirmation answers with the cover's path, and the Web's detail carries it
#   4  an anonymous GET of that path is an image/png with `X-Content-Type-Options: nosniff` and the bytes uploaded
#   5  what must stay closed answers 405 and never HTML or an object: GET/DELETE on the bucket path, anything else
#      under /media/, a path that climbs out of /media/covers/ (sent as-is, not normalised by the client)
#
# Needs the local CA, the Secret `backend-api-secrets` and port 8443 free (the signed Host carries the port). Starts its
# own port-forward and stops it. No password or token is printed.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
# shellcheck source=k8s/scripts/lib.sh
. k8s/scripts/lib.sh
trap lib_cleanup EXIT

fail=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fail=1; }
expect() { # description actual expected
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: got '$2', wanted '$3'"; fi
}

gateway_up 8443
ORG="$(login organizer1 UNIHUB_DEV_SEED_ORGANIZER_PASSWORD)"
WID="$(new_workshop free)"
echo "[$(now)] workshop $WID, gateway $BASE"

work="$(mktemp -d)"
trap 'lib_cleanup; rm -rf "$work"' EXIT
# A 1x1 PNG, built here so that the object is one the backend's own check accepts as a real PNG.
python3 - "$work/cover.png" <<'EOF'
import struct, sys, zlib
def chunk(kind, data):
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
EOF
size="$(wc -c < "$work/cover.png" | tr -d ' ')"
head -c "$((size + 7))" /dev/zero > "$work/other-size.bin"

api() { c -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' "$@"; }
issued="$(api -X POST "$BASE/api/v1/workshops/$WID/cover-image-upload-url" -d "{\"contentType\":\"image/png\",\"contentLength\":$size}")"
url="$(echo "$issued" | json 'd["uploadUrl"]')"

# 1 -- the URL's origin. Printed without the query: the signature is a credential.
origin="$(python3 -c 'import sys,urllib.parse as u; p=u.urlsplit(sys.argv[1]); print(p.scheme+"://"+p.netloc)' "$url")"
expect "the upload URL is minted for the gateway's origin" "$origin" "$BASE"
echo "      path: $(python3 -c 'import sys,urllib.parse as u; print(u.urlsplit(sys.argv[1]).path)' "$url")"

put() { # contentType file url -> status
  c -o /dev/null -w '%{http_code}' -X PUT --data-binary "@$2" -H "Content-Type: $1" "$3"
}

# 2 -- what the signature must refuse
expect "PUT with another Content-Type is refused" "$(put image/jpeg "$work/cover.png" "$url")" 403
expect "PUT of another size is refused" "$(put image/png "$work/other-size.bin" "$url")" 403
tampered="$(python3 -c 'import sys,urllib.parse as u; p=u.urlsplit(sys.argv[1]); print(u.urlunsplit(p._replace(path=p.path[:-5]+"x"+p.path[-4:])))' "$url")"
expect "PUT to another key is refused" "$(put image/png "$work/cover.png" "$tampered")" 403

# 3 -- the real upload and the confirmation
expect "PUT of the signed file is accepted" "$(put image/png "$work/cover.png" "$url")" 200
confirmed="$(api -X POST "$BASE/api/v1/workshops/$WID/cover-image-upload-confirm")"
cover="$(echo "$confirmed" | json 'd["coverImageUrl"]')"
echo "      confirmed cover path: $cover"
expect "the cover path is on the application's origin" "$(case "$cover" in /media/covers/"$WID"/*.png) echo yes;; *) echo "$cover";; esac)" yes
expect "the public detail carries the cover path" "$(c "$BASE/api/v1/workshops/$WID" | json 'd["coverImageUrl"]')" "$cover"

# 4 -- the anonymous read
c -D "$work/headers.txt" -o "$work/read.png" "$BASE$cover"
status="$(head -1 "$work/headers.txt" | tr -d '\r' | awk '{print $2}')"
expect "anonymous GET of the cover" "$status" 200
header() { grep -i "^$1:" "$work/headers.txt" | head -1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'; }
expect "Content-Type is the image's" "$(header content-type)" image/png
expect "X-Content-Type-Options" "$(header x-content-type-options)" nosniff
if cmp -s "$work/cover.png" "$work/read.png"; then ok "the bytes read are the bytes uploaded"; else bad "the bytes differ"; fi
expect "HEAD of the cover" "$(c -o /dev/null -I -w '%{http_code}' "$BASE$cover")" 200

# 5 -- what stays closed
bucket_path="$(python3 -c 'import sys,urllib.parse as u; print(u.urlsplit(sys.argv[1]).path)' "$url")"
expect "anonymous GET of the bucket path (no signature)" "$(c -o /dev/null -w '%{http_code}' "$BASE$bucket_path")" 405
expect "DELETE on the bucket path" "$(c -o /dev/null -w '%{http_code}' -X DELETE "$BASE$bucket_path")" 405
expect "anonymous GET of the bucket root" "$(c -o /dev/null -w '%{http_code}' "$BASE/unihub-brochures/")" 405
expect "GET /media/ itself" "$(c -o /dev/null -w '%{http_code}' "$BASE/media/")" 405
expect "GET /media/brochures/…" "$(c -o /dev/null -w '%{http_code}' "$BASE/media/brochures/$WID/x.pdf")" 405
expect "a cover path with a climb out of covers/ (sent as-is)" \
  "$(c --path-as-is -o /dev/null -w '%{http_code}' "$BASE/media/covers/../brochures/$WID/x.pdf")" 405
expect "POST on a cover path" "$(c -o /dev/null -w '%{http_code}' -X POST "$BASE$cover")" 405
body_type="$(c -o /dev/null -w '%{content_type}' "$BASE/media/brochures/x")"
case "$body_type" in text/html*) bad "a closed path answered HTML";; *) ok "a closed path does not answer HTML ($body_type)";; esac

# cleanup: the workshop (the object stays in the bucket; it is a 70-byte PNG under covers/)
api -o /dev/null -X POST "$BASE/api/v1/workshops/$WID/cancel" -H "Idempotency-Key: $(uuidgen)" -d '{}' || true

[ "$fail" = 0 ] && echo "storage edge: all checks passed" || { echo "storage edge: FAILED" >&2; exit 1; }
