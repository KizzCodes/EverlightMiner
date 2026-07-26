# Everlight Porcelain Miner

A BotWithUs (RuneScape 3, classic client API) script that mines **Porcelain clay
rocks** in the Everlight Digsite cave, banks at the surface Bank chest when the
backpack fills, and returns to keep mining — hands-off.

## Features

- Mines Porcelain clay rocks and tracks porcelain gained + XP/hour.
- Automatic banking via the Bank chest's **Load Last Preset from** option (deposits
  and refills your mining loadout in one click).
- Full round-trip navigation: exit cave → cross the agility scaffold → bank → back.
- Self-recovery: if it ends up away from the dig site it teleports back via the
  **Archaeology journal → Guild → Dig sites map → Everlight** fast travel.
- Live in-client **Logs** tab and a **Help** tab; **Start/Stop** on the Play tab.

## Requirements

- The **Everlight Digsite** unlocked, and **Everlight** available on the Dig sites
  map fast travel.
- Your **last bank preset** set to your mining loadout (empty backpack / pickaxe
  equipped) — banking uses *Load Last Preset from*.
- An **Archaeology journal** in your backpack (used only for stranded-recovery).
- A pickaxe (equipped) and the Mining level for Porcelain clay.

## Build

**Gradle** (pulls the API from the BotWithUs nexus; needs a JDK 20):

```
./gradlew build
```

**or** the no-install PowerShell build (uses the client's bundled JDK 20):

```
pwsh -ExecutionPolicy Bypass -File build.ps1
```

Both compile with `--release 20 --enable-preview`, bundle the xapi.public API
(Bank/Backpack) into the jar, and deploy it to
`%USERPROFILE%\BotWithUs\scripts\local\EverlightMiner.jar`.

> The client does **not** hot-reload — fully restart it to load a rebuilt jar.

## Usage

1. Do the one-time setup in **Requirements** above (bank preset + journal).
2. Load the script in the client and open its window.
3. Stand at the Porcelain clay rocks in the Everlight cave.
4. **Play tab → Start.** Watch the **Logs** tab for the live status feed.
   **Stop** returns it to IDLE (safe for manual control).

## Notes

- Everything (rock/bank/scaffold/cave objects, tiles, region ids, fast-travel
  clicks) is hardcoded — there is nothing to configure.
- This client's pathfinder can't walk long distances, so start at the rocks; the
  journal recovery handles the rest.
- API signatures were verified with `javap` against the client jars.
