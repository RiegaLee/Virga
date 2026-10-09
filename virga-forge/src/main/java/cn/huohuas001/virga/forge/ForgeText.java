package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.server.game.GameText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Converts Virga's platform-neutral chat lines into vanilla components. */
final class ForgeText {
    private ForgeText() {}

    static Component toComponent(GameText text) {
        MutableComponent root = Component.empty();
        for (GameText.Span span : text.getSpans()) {
            Style style = Style.EMPTY;
            Character color = span.getColor();
            if (color != null) {
                ChatFormatting formatting = ChatFormatting.getByCode(color);
                if (formatting != null) style = style.withColor(formatting);
            }
            if (span.getBold()) style = style.withBold(true);
            if (span.getItalic()) style = style.withItalic(true);
            if (span.getUnderlined()) style = style.withUnderlined(true);
            if (span.getStrikethrough()) style = style.withStrikethrough(true);
            if (span.getObfuscated()) style = style.withObfuscated(true);
            GameText.Click click = span.getClick();
            if (click instanceof GameText.Click.RunCommand run) {
                style = style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, run.getCommand()));
            } else if (click instanceof GameText.Click.CopyToClipboard copy) {
                style = style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copy.getValue()));
            }
            if (span.getHover() != null) {
                style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(span.getHover()).withStyle(ChatFormatting.YELLOW)));
            }
            root.append(Component.literal(span.getText()).setStyle(style));
        }
        return root;
    }
}
