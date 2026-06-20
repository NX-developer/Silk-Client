# Silk

Silk is a free Minecraft client created as an alternative to paid or low-quality clients available at the time, 

---

## Why It Was Made

Silk was developed to provide a **free, functional solution** for players wanting advanced client features. At the time, there were very few good free Minecraft clients, so we created Silk to fill that gap.

---

## Information

- **Version:** 1.21.4  
- **JDK Version:** JDK 21  

### Anti-Cheats Tested On

- GrimAC  
- Vulkan  
- Polar (Some modules may not bypass)  
- AGC  

---

## Module List

### Client
`ClickGUIModule`, `Client`, `ClientSettings`, `Debugger`, `KeybindsModule`, `NewClickGUI`, `Secret`

### Combat
`AimAssist`, `AntiMiss`, `AutoCart`, `AutoCrystal`, `AutoMace`, `AutoPot`, `BreachSwap`, `Criticals`, `CrystalOptimiser`, `ElytraHotSwap`, `LegitKillaura`, `MaceChain`, `StunCob`, `Wtap`, `Stap`, `Triggerbot`, `Velocity`, `TotemHit`, `ThrowPot`, `SwordSwap`, `SwordHotSwap`, `ShieldBreaker`, `KeyLava`, `KeyCrystal`, `KeyAnchor`

### Misc
`CartKey`, `FakePlayer`, `Friends`, `HoverTotem`, `MiddleClickFriend`, `PearlCatch`, `PearlKey`, `Teams`, `WindChargeKey`

### Movement
`AutoFireWork`, `AutoHeadHitter`, `KeepSprint`, `Sprint`

### Player
`AutoCrafter`, `AutoDoubleHand`, `AutoDrain`, `AutoExtinguish`, `AutoMLG`, `AutoRefill`, `AutoTool`, `AutoWeb`, `CoverUp`, `FastEXP`, `FastMine`, `FastPlace`, `PingSpoof`, `ReBuffNotifier`, `Scaffold`, `TrapSave`

### Render
`Arraylist`, `ArrowESP`, `ContainerSlots`, `ESP2D`, `FullBright`, `Notifications`, `OutlineESP`, `SwingSpeed`, `TargetHUD`, `Trajectories`, `WaterMark`

---

## Build Guide

1. Make sure you have **Gradle** and **JDK 21** installed.  
2. Clone the repository:

   ```bash
   git clone https://github.com/hitomdev/Silkcc.git
   ```

3. Cd into Silkcc:

   ```bash
   cd Silkcc
   ```

4. Build and run:

   ```bash
   ./gradlew build       # Builds the client
   ./gradlew runClient   # Runs the client
   ```

Or you can use the Gradle UI icon in IntelliJ/VSCode if preferred.

---

## Changelog

### New Modules

#### AutoMace (Advanced)
Completely rewritten with anti-cheat bypass capabilities:
- **Scenario 1 - Full Combo**: Pearl → WindCharge → ElytraGlide → ArmorSwap → MaceStrike chain
- **Scenario 2 - Fallback**: Passive fall control with reach-based auto-attack
- **Scenario 3 - Auto Only**: Simple mace auto-attack during falls
- **Anti-Cheat Bypass**: Latency-based delays, randomized timing, legit packet ordering
- **Inventory Management**: Silent swap support, armor swap optimization
- **Smart Targeting**: Player/mob filtering, team/friend checks, range-based selection

#### MaceChain
Advanced chain combo system for extended mace combat:
- **Chain Tracking**: Combo counter with configurable reset delay and max length
- **Elytra Chain**: Aerial mace strikes with automatic elytra equip and glide
- **Pearl Re-engage**: Auto-pearl for closing distance after kills
- **Wind Charge Momentum**: Wind charge usage for extended combo chains
- **Smart Target Selection**: Health/distance/height-based target scoring
- **Anti-Cheat Bypass**: Latency-aware timing, randomized delays, ping-based adjustments

#### Scaffold (New)
Advanced scaffold module with 3 modes:
- **Legit Mode**: Edge detection + SafeWalk/Eagle system
  - Auto-sneak at block edges to prevent falling
  - Legit rotation for block placement (75-85° down angle)
  - Smooth rotation with configurable speed
  - Human-like timing with randomized delays
- **Tower Mode**: Vertical scaffolding while jumping
- **Fast Mode**: Rapid block placement on ground
- **Anti-Cheat Bypass**: Latency-based delays, timing variance, ping-aware adjustments
- **Smart Features**: Auto-switch to blocks, silent switch, arm swing, turn off when no blocks

#### Legit Killaura (New)
Advanced human-like killaura with fluid-aware targeting:
- **Advanced Raytrace**: Ignores water blocks, blocks on solid opaque blocks
- **Lava Analysis**: Targets visible body parts above lava, scans head/chest/feet
- **Smooth Aiming**: Human-like rotation interpolation with easing functions
- **Legit Timing**: Randomized CPS (1-20), reaction time, random delays
- **Smart Targeting**: Distance/angle scoring, target stickiness, FOV filtering
- **Anti-Cheat Bypass**: Ping-aware timing, rotation randomness, natural movement

### Bug Fixes

#### Critical Fixes
- **fabric.mod.json**: Fixed mod name from "motionblurr" to "Silk", updated description and repository URLs (was leftover from motionblur fork)
- **Mixin crash fixes**: Added `isPresent()` checks before `.get()` on Optional in `HandledScreenMixin`, `LightmapTextureManagerMixin`, and `PlayerEntityMixin` — these were causing `NoSuchElementException` crashes
- **Thread safety**: Made `SilkClient.INSTANCE` and `SilkClient.mc` fields `volatile` to prevent stale reads from mixin threads
- **Null safety**: Added null checks for `SilkClient.INSTANCE` in all mixin injection points (`MinecraftClientMixin`, `HandledScreenMixin`, `LightmapTextureManagerMixin`, `PlayerEntityMixin`)

### High Priority Fixes
- **Version mismatch**: Window title now correctly shows "Silk 1.21.4" instead of "1.21.1"
- **Duplicate keybinds**: `NewClickGUI` module no longer conflicts with `ClickGui` module on `RIGHT_SHIFT`
- **Cross-platform support**: `User32` JNA load now checks for Windows OS before loading `user32.dll`, preventing `UnsatisfiedLinkError` on Linux/macOS
- **Dead mixins removed**: Removed empty `ExampleMixin` (injected into server-side `MinecraftServer`) and empty `WorldRendererMixin`

### Medium Priority Fixes
- **ChatUtil.colorFade**: Fixed division by zero when text length is 1 character
- **OutlineESP**: Added null checks for `mc.player` in `shouldRender()` and `isTeammate()`
- **RoundedRectRenderer**: Fixed GPU resource leak — EBO is now properly tracked and deleted in `cleanup()`
- **GuiUtils.drawButton**: `pressed` state now uses a distinct darker color instead of same color as `hovered`
- **NotificationManager**: Changed from `ArrayList` to `CopyOnWriteArrayList` for thread safety; added `cleanup()` method to remove expired notifications
- **FriendManager**: Changed from `HashSet` to `ConcurrentHashMap.newKeySet()` for thread safety
- **Notification.getLifetimeProgress**: Added guard against division by zero when `duration <= 0`
- **InventoryUtil**: Changed loop counters from `byte` to `int` to prevent overflow with large inventories
- **ProfileManager**: Removed unnecessary `Object` cast in `readProfileFromFile`

### Low Priority Fixes
- **ClickGui**: Removed dead `typedTitleElapsed` field; made `lastCursorBlink` mutable and functional for cursor blink
- **ClickGui search bar**: Cursor now shows even when search query is empty (indicates focus state)
- **ClickGui config cursor**: Cursor now shows when config name field is focused even if empty
- **ModMenuHider**: IOException now logs error instead of being silently swallowed
- **ClickSimulator**: Replaced per-click thread creation with a shared `ScheduledExecutorService` to prevent thread explosion
- **SilkClient**: Removed pointless event bus subscription on `NotificationManager` (no `@EventHandler` methods)
- **MinecraftClientMixin**: Added null check before posting `DoAttackEvent` and `DisconnectEvent`

---

## Disclaimer

Silk is no longer actively maintained. Only forks or clones may continue development. Critical bugs preventing use may not be fixed.

## License

MIT License Do whatever you want.

Formerly Known as OpenVolt
