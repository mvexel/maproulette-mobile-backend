#!/usr/bin/env python3
"""Offline pure probe using pre-fetched pinned jars; never starts services."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--jars", type=Path, required=True)
parser.add_argument("--fixture", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
jars = str(args.jars.resolve() / "*")
fixture = str(args.fixture.resolve())
env = {k: v for k, v in os.environ.items() if not k.startswith("MR_")}
env.update({
    "POSTGRES_PASSWORD": "synthetic-not-a-secret",
    "APPLICATION_SECRET": "synthetic-not-a-secret",
    "MAPROULETTE_SECRET_KEY": "synthetic-not-a-secret",
    "MR_DEPLOYMENT": "stage",
    "MR_API_HOST": "mr-stage.osm.lol",
    "MR_ADMIN_HOST": "admin.mr-stage.osm.lol",
    "MR_OAUTH_CONSUMER_KEY": "synthetic",
    "MR_OAUTH_CONSUMER_SECRET": "synthetic",
    "MR_API_NETWORK_ALIAS": "synthetic-stage-api",
})
for selected, flag in [(False, None), (True, None), (True, "false"), (True, "true")]:
    current = env.copy()
    if flag is not None:
        current["MR_MOBILE_GUESTS_ENABLED"] = flag
    cmd = ["docker", "compose", "-f", "compose.field.yml"]
    if selected:
        cmd += ["-f", "compose.field-stage-pending.yml"]
    result = subprocess.run(cmd + ["config", "--format", "json"], cwd=root,
                            env=current, capture_output=True, text=True, check=True)
    api = json.loads(result.stdout)["services"]["api"]
    assert api["environment"]["MR_MOBILE_GUESTS_ENABLED"] == (flag or "false")
    assert api["environment"]["MR_GUEST_MAIL_PROVIDER"] == "none"
    assert api["environment"]["MR_POSTMARK_SERVER_TOKEN"] == ""
    config = "mobile-field-stage-pending.conf" if selected else "mobile-field.conf"
    assert api["command"][-1] == "-Dconfig.file=/MapRouletteAPI/conf/" + config
    print(f"PASS Compose selected_stage={selected} guest={flag or 'defaultfalse'} mail=none")

with tempfile.TemporaryDirectory(prefix="c001-stage-pending-") as directory:
    temp = Path(directory)
    classes = temp / "classes"
    classes.mkdir()
    choice = (root / "app/org/maproulette/provider/choice/ChoiceWork.scala").read_text()
    # Byte-for-byte source sections, omitting unrelated full-app exception wiring.
    (temp / "ChoiceWork.scala").write_text(choice[:choice.index("/** A choice payload that broke")])
    scopes = (root / "app/org/maproulette/auth/mobile/MobileOAuthSettings.scala").read_text()
    (temp / "MobileScopes.scala").write_text("package org.maproulette.auth.mobile\n" +
        scopes[scopes.index("object MobileScopes {"):scopes.index("/** The new provider")])
    subprocess.run(["java", "-cp", jars, "scala.tools.nsc.Main", "-usejavacp", "-d", str(classes),
                    str(temp / "ChoiceWork.scala"), str(temp / "MobileScopes.scala"),
                    str(root / "scripts/StagePendingProbe.scala")], cwd=root, env=env, check=True)
    subprocess.run(["java", "-cp", str(classes) + os.pathsep + jars, "StagePendingProbe", fixture],
                   cwd=root, env=env, check=True)
