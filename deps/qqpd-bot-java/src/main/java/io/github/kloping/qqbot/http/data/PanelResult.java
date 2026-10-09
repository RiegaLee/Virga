package io.github.kloping.qqbot.http.data;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/** 创建 QQ 指令面板的响应。 */
@Data
public class PanelResult {
    @SerializedName("panel_id")
    private String panelId;
}
