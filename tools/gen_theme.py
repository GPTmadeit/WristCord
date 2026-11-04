#!/usr/bin/env python3
"""Generate WristCord's Discord theme tables from colours extracted out of the Discord app.

Inputs (see docs/discord-protocol.md for where they come from):
    raw-colors.json        RawColors: name -> "#rrggbb" or "#rrggbbaa"
    semantic-colors.json   SemanticColors: token -> {ASH|LIGHT|ONYX|DARK: {raw, opacity}}
    gradient-presets.json  Nitro client-theme gradients

Outputs:
    wear/src/main/kotlin/com/wristcord/wear/ui/theme/DiscordTokens.kt
    docs/extracted/tokens-used.json   just the tokens WristCord uses, resolved to ARGB

    python3 tools/gen_theme.py ~/re/discord-android-351.11/out
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEMES = ["DARK", "ASH", "ONYX", "LIGHT"]

# Kotlin property name -> Discord semantic token. Only what the watch UI actually draws.
TOKENS = {
    "baseLowest": "BACKGROUND_BASE_LOWEST",
    "baseLower": "BACKGROUND_BASE_LOWER",
    "baseLow": "BACKGROUND_BASE_LOW",
    "surfaceHigh": "BACKGROUND_SURFACE_HIGH",
    "surfaceHighest": "BACKGROUND_SURFACE_HIGHEST",
    "modSubtle": "BACKGROUND_MOD_SUBTLE",
    "modNormal": "BACKGROUND_MOD_NORMAL",
    "modStrong": "BACKGROUND_MOD_STRONG",
    "backgroundBrand": "BACKGROUND_BRAND",
    "backgroundAccent": "BACKGROUND_ACCENT",
    "backgroundCode": "BACKGROUND_CODE",
    "backgroundScrim": "BACKGROUND_SCRIM",
    "backgroundVoiceMuted": "BACKGROUND_VOICE_MUTED",
    "backgroundFeedbackCritical": "BACKGROUND_FEEDBACK_CRITICAL",
    "cardBackground": "CARD_BACKGROUND_DEFAULT",
    "cardSecondary": "CARD_SECONDARY_BACKGROUND_DEFAULT",
    "embedBackground": "MOBILE_EMBED_BACKGROUND_DEFAULT",
    "chatInputBackground": "CHAT_INPUT_BACKGROUND",
    "voicePanelBackground": "MOBILE_VOICE_PANEL_BACKGROUND",
    "guildFolderBackground": "GUILD_FOLDER_BACKGROUND",
    "guildIconBackground": "MOBILE_GUILDBAR_ICON_BACKGROUND_DEFAULT",
    "textDefault": "TEXT_DEFAULT",
    "textStrong": "TEXT_STRONG",
    "textMuted": "TEXT_MUTED",
    "textSubtle": "TEXT_SUBTLE",
    "textLink": "TEXT_LINK",
    "textBrand": "TEXT_BRAND",
    "textCode": "TEXT_CODE",
    "textCritical": "TEXT_FEEDBACK_CRITICAL",
    "textPositive": "TEXT_FEEDBACK_POSITIVE",
    "textWarning": "TEXT_FEEDBACK_WARNING",
    "textVoiceConnected": "TEXT_VOICE_CONNECTED",
    "headingPrimary": "MOBILE_TEXT_HEADING_PRIMARY",
    "channelName": "REDESIGN_CHANNEL_NAME_TEXT",
    "channelNameMuted": "REDESIGN_CHANNEL_NAME_MUTED_TEXT",
    "channelIcon": "CHANNEL_ICON",
    "iconDefault": "ICON_DEFAULT",
    "iconStrong": "ICON_STRONG",
    "iconMuted": "ICON_MUTED",
    "iconSubtle": "ICON_SUBTLE",
    "iconBrand": "ICON_BRAND",
    "iconCritical": "ICON_FEEDBACK_CRITICAL",
    "iconVoiceMuted": "ICON_VOICE_MUTED",
    "iconVoiceConnected": "ICON_VOICE_CONNECTED",
    "iconVoiceSpeaking": "ICON_VOICE_SPEAKING",
    "interactiveText": "INTERACTIVE_TEXT_DEFAULT",
    "interactiveTextActive": "INTERACTIVE_TEXT_ACTIVE",
    "interactiveSelected": "INTERACTIVE_BACKGROUND_SELECTED",
    "interactiveMuted": "INTERACTIVE_MUTED",
    "mentionBackground": "MENTION_BACKGROUND",
    "mentionForeground": "MENTION_FOREGROUND",
    "messageMentioned": "MESSAGE_MENTIONED_BACKGROUND_DEFAULT",
    "reactionBackground": "REACTION_BACKGROUND_DEFAULT",
    "reactionBackgroundReacted": "REACTION_BACKGROUND_REACTED_DEFAULT",
    "reactionBorderReacted": "REACTION_BORDER_REACTED_DEFAULT",
    "reactionText": "REACTION_TEXT_DEFAULT",
    "reactionTextReacted": "REACTION_TEXT_REACTED_DEFAULT",
    "spoilerHidden": "SPOILER_HIDDEN_BACKGROUND",
    "spoilerRevealed": "SPOILER_REVEALED_BACKGROUND",
    "statusOnline": "STATUS_ONLINE",
    "statusDanger": "STATUS_DANGER",
    "statusWarning": "STATUS_WARNING",
    "statusPositive": "STATUS_POSITIVE",
    "statusSpeaking": "STATUS_SPEAKING",
    "iconStatusOnline": "ICON_STATUS_ONLINE",
    "iconStatusIdle": "ICON_STATUS_IDLE",
    "iconStatusDnd": "ICON_STATUS_DND",
    "iconStatusOffline": "ICON_STATUS_OFFLINE",
    "badgeNotification": "BADGE_NOTIFICATION_BACKGROUND",
    "borderSubtle": "BORDER_SUBTLE",
    "borderNormal": "BORDER_NORMAL",
    "borderStrong": "BORDER_STRONG",
    "controlPrimary": "CONTROL_PRIMARY_BACKGROUND_DEFAULT",
    "controlPrimaryText": "CONTROL_PRIMARY_TEXT_DEFAULT",
    "controlSecondary": "CONTROL_SECONDARY_BACKGROUND_DEFAULT",
    "controlSecondaryText": "CONTROL_SECONDARY_TEXT_DEFAULT",
    "controlCritical": "CONTROL_CRITICAL_PRIMARY_BACKGROUND_DEFAULT",
    "controlCriticalText": "CONTROL_CRITICAL_PRIMARY_TEXT_DEFAULT",
    "controlConnected": "CONTROL_CONNECTED_BACKGROUND_DEFAULT",
    "controlConnectedText": "CONTROL_CONNECTED_TEXT_DEFAULT",
    "toggleCriticalSelected": "TOGGLEBUTTON_CRITICAL_BACKGROUND_SELECTED",
    "toggleCriticalIconSelected": "TOGGLEBUTTON_CRITICAL_ICON_SELECTED",
    "switchSelected": "SWITCH_BACKGROUND_SELECTED_DEFAULT",
}


def argb(raw, entry):
    value = raw[entry["raw"]].lstrip("#")
    rgb = int(value[:6], 16)
    # Raw "#rrggbbaa" opacity colours carry the same alpha the token states, so the token's
    # opacity is the alpha - never multiplied on top of the raw one.
    alpha = round(float(entry.get("opacity", 1)) * 255)
    return (alpha << 24) | rgb


def main(src):
    raw = json.load(open(os.path.join(src, "raw-colors.json")))
    sem = json.load(open(os.path.join(src, "semantic-colors.json")))
    grads = json.load(open(os.path.join(src, "gradient-presets.json")))

    used = {}
    for prop, token in TOKENS.items():
        if token not in sem:
            sys.exit("token missing from extraction: " + token)
        used[prop] = {t: argb(raw, sem[token][t]) for t in THEMES}

    out = []
    w = out.append
    w("// GENERATED by tools/gen_theme.py from Discord's own design tokens - do not edit by hand.")
    w("// Source: com.discord 351.11, tokens/colors/generated/{raw-color-definitions,native/generated-definitions}.tsx")
    w("// and modules/client_themes/ClientThemesConstants.tsx. See docs/discord-protocol.md.")
    w("package com.wristcord.wear.ui.theme")
    w("")
    w("import androidx.compose.runtime.Immutable")
    w("import androidx.compose.ui.graphics.Color")
    w("")
    w("/** One of Discord's semantic colour sets, resolved for a single theme. */")
    w("@Immutable")
    w("class DiscordColors(")
    w("    val theme: DiscordTheme,")
    for prop in TOKENS:
        w("    val %s: Color," % prop)
    w(") {")
    w("    companion object {")
    for t in THEMES:
        w("        val %s = DiscordColors(" % t.capitalize())
        w("            theme = DiscordTheme.%s," % t)
        for prop in TOKENS:
            w("            %s = Color(0x%08X)," % (prop, used[prop][t]))
        w("        )")
    w("    }")
    w("}")
    w("")
    w("/** A Nitro client-theme gradient, exactly as Discord defines it. */")
    w("@Immutable")
    w("class GradientPreset(")
    w("    /** `ClientThemeSettings.background_gradient_preset_id` in the settings proto. */")
    w("    val protoId: Int,")
    w("    val key: String,")
    w("    /** Discord renders every gradient on top of this base theme. */")
    w("    val base: DiscordTheme,")
    w("    /** CSS-style angle in degrees, 0 = to top, clockwise. */")
    w("    val angle: Float,")
    w("    val stops: List<Pair<Float, Color>>,")
    w(")")
    w("")
    w("val GradientPresets: List<GradientPreset> = listOf(")
    for g in sorted(grads, key=lambda g: g["protoId"]):
        base = "LIGHT" if g["base"] == "LIGHT" else "DARK"
        stops = ", ".join(
            "%sf to Color(0xFF%s)" % (round(s["stop"] / 100.0, 4), s["hex"].lstrip("#").upper())
            for s in g["stops"]
        )
        w('    GradientPreset(%d, "%s", DiscordTheme.%s, %sf, listOf(%s)),' % (
            g["protoId"], g["name"], base, g["angle"], stops))
    w(")")
    w("")

    dst = os.path.join(ROOT, "wear/src/main/kotlin/com/wristcord/wear/ui/theme/DiscordTokens.kt")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "w").write("\n".join(out))

    ex = os.path.join(ROOT, "docs/extracted")
    os.makedirs(ex, exist_ok=True)
    json.dump(
        {"tokens": {TOKENS[p]: {t: "#%08x" % used[p][t] for t in THEMES} for p in TOKENS},
         "gradients": grads},
        open(os.path.join(ex, "tokens-used.json"), "w"), indent=1, sort_keys=True)
    print("wrote", dst, "with", len(TOKENS), "tokens x", len(THEMES), "themes and", len(grads), "gradients")


if __name__ == "__main__":
    main(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/re/discord-android-351.11/out"))
