#!/usr/bin/env bash
# Regenerates the synthetic test photos used by the dev harnesses:
#   /tmp/photos/template2.{png,gray,cp}  printed alphabet sheet (the fastest path to a full font)
#   /tmp/photos/page1.{png,gray,txt}     ordinary notes page (OCR-only path)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/vision"
python3 - <<'PY'
import make_photo
from pathlib import Path
out = Path('/tmp/photos'); out.mkdir(parents=True, exist_ok=True)
t = make_photo.render_template(out / 'template2.png', 11, width=2000, height=2800, cell=148)
(out / 'template2.cp').write_text(','.join(str(ord(c)) for c in t))
make_photo.render_page(out / 'page1.png', 3)
print('photos written to', out)
PY
