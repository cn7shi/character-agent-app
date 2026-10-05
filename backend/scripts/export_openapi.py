"""Export only implemented operations; does not start the application lifespan."""
import json
from pathlib import Path
import sys

backend = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(backend / "src"))

from character_app.config import Settings
from character_app.main import create_app

target = backend.parent / "contracts" / "openapi-v1.json"
app = create_app(Settings(backend / ".runtime" / "app", dev_auth=True))
target.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Exported {len(app.openapi()['paths'])} paths to {target}")
