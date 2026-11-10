# 华容道 Klotski Box: project guidance

A 1:1 wooden Klotski box on the desk for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.klotskibox`, v0.1.1.
Design contract: spatial-app-factory `apps/klotski-box/design-package.json` (A12: one main loop; `v2[]` not built — no
level picker, no free mode, no layout codes, no skins, no replay / redo / timer). Gate B default: 6 public-domain layouts.
Skeleton from the factory's royal-ur project (itself CloudAir lineage: two containers, theme, sound pool, level gate,
board-input template); the Klotski rules / solver / drag / scene are new.

## Surfaces
- **Box volume** (default container `KlotskiBox`, Volumetric 0.48 x 0.56 x 0.62 m, fixed scale, gravity aligned, uniform
  resize; 0.56 high so the 90° tilt stop fits). Walnut box (inner 0.28 x 0.35, 7 cm cells) on a wedge, tilt 0 / 30 / 45 /
  60 / 90 (default 30), pivot on the near-bottom edge. Ten chamfered tiles (GLB, Songti faces), the copper sill + light
  strip at the gap, a counter plate (Canvas texture) above the far wall. Cao Cao leaves through the gap and lands flat in
  front of it.
- **Control window** (`KlotskiControl`, Planar 560 x 640 dp, `Placement.right(KlotskiBox)`): native SpatialUI `TabBar`, three
  pages 玩 / 规矩 / 设置; no page scrolls. [重开控制窗] closes and reopens beside the box (`Placement.right`); [退出应用] closes both
  containers, removes the tasks, exits the process.

## Code
| Path | What |
|---|---|
| `domain/Klotski.kt` | board, shapes, free runs, slides, canonical key, the six layouts (`Puzzles.ALL`) |
| `domain/Solver.kt` | whole-component BFS + distance-to-gap map (one tile any distance = one step): minimum, hint, "always solvable" |
| `domain/Drag.kt` | `SlideDrag` (one-euro, axis lock 8 mm, one switch, soft stop, blocked after 1.5 cm / 300 ms past the end, exit pull 2 cm) |
| `domain/GameFlow.kt` | the single `PlayState`, phases S02..S09, step merging, undo, hint (L-shaped steps in two parts), exit, save, debug |
| `domain/Layout.kt` | metres for everything per tilt, eyes, occlusion helpers (unit-tested D35) |
| `domain/Game.kt` | settings, save codec (`files/game.txt`, atomic + backup in data/Stores.kt) |
| `ui/board/*` | `BoardScene` (entities, per-frame placement in `onFrame` via `BoardFrameSystem`), `BoardScreen` (board-input wiring), textures |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `build_models.py` (GLBs; `--test-f` = F↑ faces), `import_cc0_sounds.py`, `loudness.py`, `build_icon.py`, `contrast.py`, `blank.py`, `kb.sh`, `shots.sh` |

Volume input goes through the factory board-input template (`ui/board/input/BoardInput.kt`): holders `tile:<id>`
(GLB clone root, collider + Interactable + HoverEffect), `cell:<c>:<r>` (full-cell collider + a 5 mm marker child),
`box` (S10 only). A drag on a tile -> `GameFlow.dragStart/dragMove/dragEnd`; a tap on a tile shows arrows, a tap on an
arrow cell slides to the end. In S10 (panel closed) the first pinch only reopens the panel.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 46 JVM tests: solver (81 / all reachable / hint), steps, drag, flow, D35 rays, hit replay, plate widths
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; python3 tools/build_icon.py --icon512 icon.png
DEV=emulator-5562 tools/kb.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow klotski-box` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.klotskibox.DEBUG -p io.embodit.klotskibox --es cmd <cmd> --es a "<arg>"` (or `tools/kb.sh cmd <cmd> [arg]`). Log tag `Klotski`.
`tools/shots.sh OUTDIR zh|en S01 S02 ...` captures a list of states.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | one dark slab stand-in |
| S01 第一次 | `goto S01` | classic layout + rules page with [开始]; gap strip pulses twice |
| S02 待滑 | `goto S02` · `coach on|off` | coach: movable tiles glow for the first 3 steps |
| S03 拖动中 | `goto S03` | soldier 6 held half a cell down (`hold off` releases) |
| S04 顶住 | `goto S04` | soldier 8 pushed past its run, grey arrow, held |
| S05 落格 | `goto S05` | snap animation frozen at 60 ms |
| S06 提示中 | `goto S06` | ghost tile + arrow |
| S07 到门口 | `goto S07` | Cao Cao slid onto the gap (one real step) |
| S08 出盒 | `goto S08` | frozen at the top of the fall (470 ms) |
| S09 解开了 | `goto S09` · `best N` sets the best for record / non-record shots | |
| S10 面板已关 | `goto S10` (= closepanel) · `tap cell:1:4` reopens | plate says 捏一下牌 |
| S10b 木盒已关 | `goto S10b` (= closevolume) · [把木盒放回来] | |
| S11 / S12 | `page settings` / `page rules` | |
| S13 确认 | `goto S13` (reset) · `exitask` (quit) · `confirm` / `no` | |
| S14 恢复 | make a move, force-stop, relaunch | plate 接着上次的 |
Other: `drag "<tile> <dir> <cells>"`, `push "<tile> <dir> <cm> <ms>"`, `tap <holder>`, `arrow "<tile> <dir>"`, `undo`,
`hint on|off`, `reset`, `letout`, `solve [ms]`, `stop`, `puzzle N`, `setup "<rows>"`, `lang`, `tilt`, `sound`, `volume`,
`reduced`, `replay "<holder> [hand-ray|eye-hand|controller]"`, `hover`, `dump`, `evidence`. Tile ids: 0 曹操, 1 关羽,
2 张飞, 3 赵云, 4 马超, 5 黄忠, 6..9 卒 (row-major).

## Known
- 3D drags / pinches are not reachable by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through the
  same GameFlow calls; the three inputs (controller ray, hand ray, eye-hand) need the headset.
- Sounds are SoundPool (not spatial). Wood sounds are CC0 recordings (freesound 254343 + Kenney); the solved chime is synthesised.
- Hover: system HoverEffect on tiles and cell markers; the app's faint glow on `onHover` only if the system delivers hover.
