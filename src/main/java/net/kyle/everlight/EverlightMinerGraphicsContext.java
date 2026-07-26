package net.kyle.everlight;

import net.botwithus.rs3.imgui.ImGui;
import net.botwithus.rs3.imgui.ImGuiWindowFlag;
import net.botwithus.rs3.script.ScriptConsole;
import net.botwithus.rs3.script.ScriptGraphicsContext;
import net.botwithus.rs3.game.skills.Skills;

/**
 * ImGui window for the Everlight Porcelain Miner: Play (Start/Stop + state) and
 * Stats (mining XP/hour, porcelain mined). Everything else is hardcoded, so there
 * is no Settings/Debug tab.
 */
public class EverlightMinerGraphicsContext extends ScriptGraphicsContext {

    private final EverlightMinerScript script;
    private final long startTime;
    private final int startingXp;
    private boolean autoScroll = true;

    public EverlightMinerGraphicsContext(ScriptConsole console, EverlightMinerScript script) {
        super(console);
        this.script = script;
        this.startTime = System.currentTimeMillis();
        int xp = 0;
        try { xp = Skills.MINING.getSkill().getExperience(); } catch (Throwable ignored) {}
        this.startingXp = xp;
    }

    @Override
    public void drawSettings() {
        long elapsedMs = System.currentTimeMillis() - startTime;
        long secs = elapsedMs / 1000;
        String runtime = String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);

        if (ImGui.Begin("Everlight Porcelain Miner", ImGuiWindowFlag.None.getValue())) {
            if (ImGui.BeginTabBar("elm_tabs", ImGuiWindowFlag.None.getValue())) {

                if (ImGui.BeginTabItem("Play", ImGuiWindowFlag.None.getValue())) {
                    ImGui.Text("Mine porcelain clay at the Everlight Digsite.");
                    ImGui.Text("State: " + script.getBotState());
                    if (!script.stopReason.isEmpty())
                        ImGui.Text("Stopped: " + script.stopReason.replace("%", "%%"));
                    if (ImGui.Button("Start")) script.setBotState(EverlightMinerScript.BotState.MINING);
                    ImGui.SameLine();
                    if (ImGui.Button("Stop")) script.setBotState(EverlightMinerScript.BotState.IDLE);
                    ImGui.SeparatorText("Stop conditions (0 = off)");
                    script.stopAtLevel = ImGui.InputInt("Stop at Mining level", script.stopAtLevel);
                    script.stopAfterMinutes = ImGui.InputInt("Stop after minutes", script.stopAfterMinutes);
                    ImGui.EndTabItem();
                }

                if (ImGui.BeginTabItem("Stats", ImGuiWindowFlag.None.getValue())) {
                    ImGui.SeparatorText("Time running  " + runtime);
                    ImGui.SeparatorText("Mining");
                    ImGui.Text("Mining level: " + safeLevel());
                    int gained = safeXp() - startingXp;
                    ImGui.Text("XP gained: " + gained);
                    double hours = elapsedMs / (1000.0 * 60 * 60);
                    long perHour = hours > 0 ? Math.round(gained / hours) : 0;
                    ImGui.Text("XP/hour: " + perHour);
                    int xpToNext = safeXpToNext();
                    ImGui.Text("XP to next level: " + xpToNext);
                    ImGui.Text("Time to level: " + (perHour > 0 && xpToNext > 0
                            ? fmtDuration((long) (xpToNext / (double) perHour * 3600)) : "—"));
                    ImGui.Separator();
                    ImGui.Text("Porcelain mined: " + script.oreMined);
                    long orePerHour = hours > 0 ? Math.round(script.oreMined / hours) : 0;
                    ImGui.Text("Porcelain/hour: " + orePerHour);
                    ImGui.EndTabItem();
                }

                if (ImGui.BeginTabItem("Help", ImGuiWindowFlag.None.getValue())) {
                    ImGui.SeparatorText("Setup (do this once)");
                    ImGui.Text("1. Set your LAST bank preset to your mining loadout");
                    ImGui.Text("   (empty backpack, pickaxe equipped). Banking uses the");
                    ImGui.Text("   Bank chest's 'Load Last Preset from' option, so the");
                    ImGui.Text("   preset is what refills your inventory each trip.");
                    ImGui.Text("2. Keep an 'Archaeology journal' in your backpack. If the");
                    ImGui.Text("   bot ends up away from the dig site it teleports to the");
                    ImGui.Text("   Guild, opens the Dig sites map and fast-travels back.");
                    ImGui.Text("3. Everlight must be unlocked on the Dig sites map.");
                    ImGui.SeparatorText("Running");
                    ImGui.Text("Stand at the Porcelain clay rocks in the Everlight cave,");
                    ImGui.Text("then press Start on the Play tab.");
                    ImGui.Text("Full backpack -> exit cave -> Skip over scaffold ->");
                    ImGui.Text("Bank chest (load preset) -> back across to the rocks.");
                    ImGui.SeparatorText("Notes");
                    ImGui.Text("Stop returns the bot to IDLE (safe for manual control).");
                    ImGui.Text("The Logs tab shows a live status feed.");
                    ImGui.Text("This client can't walk long distances, so always start");
                    ImGui.Text("at the rocks unless you have the journal for recovery.");
                    ImGui.EndTabItem();
                }

                if (ImGui.BeginTabItem("Logs", ImGuiWindowFlag.None.getValue())) {
                    autoScroll = ImGui.Checkbox("Auto-scroll", autoScroll);
                    ImGui.SameLine();
                    if (ImGui.Button("Clear")) script.clearLog();
                    ImGui.Separator();
                    // Scrolling log console. EndChild must always follow BeginChild.
                    ImGui.BeginChild("elm_log", 0f, 0f, true,
                            ImGuiWindowFlag.AlwaysVerticalScrollbar.getValue());
                    for (String line : script.recentLog()) {
                        ImGui.Text(line.replace("%", "%%"));   // ImGui.Text is printf-style
                    }
                    if (autoScroll) ImGui.SetScrollHereY(1.0f);
                    ImGui.EndChild();
                    ImGui.EndTabItem();
                }

                ImGui.EndTabBar();
            }
            ImGui.End();
        }
    }

    private int safeLevel() {
        try { return Skills.MINING.getSkill().getLevel(); } catch (Throwable t) { return 0; }
    }

    private int safeXp() {
        try { return Skills.MINING.getSkill().getExperience(); } catch (Throwable t) { return startingXp; }
    }

    private int safeXpToNext() {
        try { return Skills.MINING.getSkill().getExperienceToNextLevel(); } catch (Throwable t) { return 0; }
    }

    private static String fmtDuration(long secs) {
        if (secs < 0) secs = 0;
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    @Override
    public void drawOverlay() {
        super.drawOverlay();
    }
}
