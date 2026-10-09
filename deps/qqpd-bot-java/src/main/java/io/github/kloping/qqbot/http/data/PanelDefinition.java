package io.github.kloping.qqbot.http.data;

import lombok.Data;

import java.util.List;

/** QQ 指令面板的展示内容。 */
@Data
public class PanelDefinition {
    private String remark;
    private List<PanelItem> items;

    public PanelDefinition() {
    }

    public PanelDefinition(String remark, List<PanelItem> items) {
        this.remark = remark;
        this.items = items;
    }
}
